package com.chitfund.service.auction;

import com.chitfund.service.auction.AuctionDtos.FloatRepaymentRequest;
import com.chitfund.service.auction.AuctionDtos.PayoutView;
import com.chitfund.service.auction.AuctionDtos.RejectPayoutRequest;
import com.chitfund.service.auction.AuctionModels.CycleInfo;
import com.chitfund.service.auction.AuctionModels.PayoutRow;
import com.chitfund.service.calc.LiquidityDecision;
import com.chitfund.service.calc.LiquidityDecision.Action;
import com.chitfund.service.calc.PoolPosition;
import com.chitfund.service.common.Audit;
import com.chitfund.service.common.ChitException;
import com.chitfund.service.common.Hashing;
import com.chitfund.service.common.Json;
import com.chitfund.service.common.Money;
import com.chitfund.service.config.ChitSettings;
import com.chitfund.service.domain.CycleStatus;
import com.chitfund.service.domain.GroupStatus;
import com.chitfund.service.domain.SecurityRule;
import com.chitfund.service.domain.TicketStatus;
import com.chitfund.service.domain.rules.ChitRules;
import com.chitfund.service.domain.state.Lifecycles;
import com.chitfund.service.enrolment.EnrolmentModels.SecurityRow;
import com.chitfund.service.enrolment.EnrolmentModels.TicketRow;
import com.chitfund.service.enrolment.EnrolmentRepository;
import com.chitfund.service.group.GroupModels.GroupRow;
import com.chitfund.service.group.GroupService;
import com.chitfund.service.idempotency.IdempotencyCheck.Replay;
import com.chitfund.service.idempotency.IdempotencyStore;
import com.chitfund.service.ledger.JournalTemplates;
import com.chitfund.service.ledger.LedgerDtos.PostingContext;
import com.chitfund.service.ledger.LedgerService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The prize payout of a closed cycle: requested by a maker, approved by a different checker, then disbursed.
 *
 * <p>Pre-conditions (spec 9.3.7), checked when requested, when approved and again before the first money leaves: the winner is
 * in good standing, the security is verified, nothing is overdue, and a payout account is on record. Disbursement then asks the
 * pool whether it can pay; if not, the group's liquidity rule decides (see {@link LiquidityDecision}). A hold
 * ({@code payout_hold_pct}) is moved from the pool to the security account as part of the same journal.
 */
@Service
public class PayoutService {

    private static final String OP_REQUEST = "PAYOUT_REQUEST";
    private static final String OP_APPROVE = "PAYOUT_APPROVE";
    private static final String OP_REJECT = "PAYOUT_REJECT";
    private static final String OP_DISBURSE = "PAYOUT_DISBURSE";
    private static final String OP_FLOAT_REPAY = "PAYOUT_FLOAT_REPAY";

    private final GroupService groups;
    private final AuctionRepository repo;
    private final EnrolmentRepository tickets;
    private final LedgerService ledger;
    private final IdempotencyStore idempotency;
    private final Audit audit;
    private final ChitSettings settings;
    private final AuctionReadService reads;
    private final CycleSettler settler;

    public PayoutService(GroupService groups, AuctionRepository repo, EnrolmentRepository tickets, LedgerService ledger, IdempotencyStore idempotency,
                         Audit audit, ChitSettings settings, AuctionReadService reads, CycleSettler settler) {
        this.groups = groups;
        this.repo = repo;
        this.tickets = tickets;
        this.ledger = ledger;
        this.idempotency = idempotency;
        this.audit = audit;
        this.settings = settings;
        this.reads = reads;
        this.settler = settler;
    }

    // ---- request (maker) ------------------------------------------------------------------------------------------

    @Transactional
    public PayoutView request(String code, int cycleNo, String idemKey, String actor, LocalDate today) {
        Replay replay = idempotency.begin(idemKey, OP_REQUEST, Hashing.sha256(code + "|" + cycleNo));
        if (replay != null) {
            return reads.payout(code, cycleNo).asReplay();
        }
        GroupRow g = lockOpen(code);
        ChitRules rules = groups.rulesOf(g);
        CycleInfo c = cycleOf(g, cycleNo);
        PayoutRow existing = repo.payoutOfCycleForUpdate(c.id()).orElse(null);
        if (existing != null && !"REJECTED".equals(existing.status())) {
            throw ChitException.conflict("PAYOUT_EXISTS", "Cycle " + cycleNo + " already has a payout request (" + existing.status() + ")");
        }
        if (existing == null) {
            Lifecycles.CYCLE.require(c.status(), CycleStatus.PAYOUT_PENDING);
        }
        TicketRow winner = tickets.findById(c.winnerTicketId()).orElseThrow(() -> ChitException.notFound("Ticket", String.valueOf(c.winnerTicketId())));
        checkPreconditions(rules, c, winner, today);
        BigDecimal gross = c.prize();
        BigDecimal hold = Money.percentOf(gross, rules.payoutHoldPct());
        BigDecimal net = gross.subtract(hold);
        if (existing == null) {
            repo.insertPayout(c.id(), winner.id(), gross, hold, net, actor, winner.payoutAccountRef());
            repo.setCycleStatus(c.id(), CycleStatus.PAYOUT_PENDING);
        } else {
            repo.resubmitPayout(existing.id(), gross, hold, net, actor);
        }
        audit.log("PAYOUT", code + "#" + cycleNo, existing == null ? "REQUESTED" : "RESUBMITTED", actor,
                "ticket " + winner.ticketNo() + " gross " + gross.toPlainString() + ", hold " + hold.toPlainString());
        audit.event("PrizePayoutRequested", "GROUP", code,
                Json.obj("groupCode", code, "cycleNo", cycleNo, "ticketNo", winner.ticketNo(), "customerId", winner.customerId(), "grossPrize", gross,
                        "holdAmount", hold, "netAmount", net, "madeBy", actor, "resubmitted", existing == null ? null : Boolean.TRUE));
        idempotency.complete(idemKey, code + "#" + cycleNo);
        return reads.payout(code, cycleNo);
    }

    // ---- approve / reject (checker) -------------------------------------------------------------------------------

    @Transactional
    public PayoutView approve(String code, int cycleNo, String idemKey, String actor, LocalDate today) {
        Replay replay = idempotency.begin(idemKey, OP_APPROVE, Hashing.sha256(code + "|" + cycleNo));
        if (replay != null) {
            return reads.payout(code, cycleNo).asReplay();
        }
        GroupRow g = lockOpen(code);
        ChitRules rules = groups.rulesOf(g);
        CycleInfo c = cycleOf(g, cycleNo);
        PayoutRow p = pending(c);
        requireChecker(p, actor);
        TicketRow winner = tickets.findById(p.ticketId()).orElseThrow(() -> ChitException.notFound("Ticket", String.valueOf(p.ticketId())));
        checkPreconditions(rules, c, winner, today);
        repo.decidePayout(p.id(), "APPROVED", actor, null);
        audit.log("PAYOUT", code + "#" + cycleNo, "APPROVED", actor, "made by " + p.madeBy());
        audit.event("PrizePayoutApproved", "GROUP", code,
                Json.obj("groupCode", code, "cycleNo", cycleNo, "ticketNo", p.ticketNo(), "grossPrize", p.gross(), "madeBy", p.madeBy(), "checkedBy", actor));
        idempotency.complete(idemKey, code + "#" + cycleNo);
        return reads.payout(code, cycleNo);
    }

    @Transactional
    public PayoutView reject(String code, int cycleNo, RejectPayoutRequest req, String idemKey, String actor) {
        String reason = req == null || req.reason() == null ? "" : req.reason().trim();
        if (reason.isEmpty() || reason.length() > 200) {
            throw ChitException.badRequest("VALIDATION_FAILED", "reason is required and may be at most 200 characters");
        }
        Replay replay = idempotency.begin(idemKey, OP_REJECT, Hashing.sha256(code + "|" + cycleNo + "|" + reason));
        if (replay != null) {
            return reads.payout(code, cycleNo).asReplay();
        }
        GroupRow g = lockOpen(code);
        CycleInfo c = cycleOf(g, cycleNo);
        PayoutRow p = pending(c);
        requireChecker(p, actor);
        repo.decidePayout(p.id(), "REJECTED", actor, reason);
        audit.log("PAYOUT", code + "#" + cycleNo, "REJECTED", actor, reason);
        audit.event("PrizePayoutRejected", "GROUP", code,
                Json.obj("groupCode", code, "cycleNo", cycleNo, "ticketNo", p.ticketNo(), "madeBy", p.madeBy(), "checkedBy", actor, "reason", reason));
        idempotency.complete(idemKey, code + "#" + cycleNo);
        return reads.payout(code, cycleNo);
    }

    // ---- disburse -------------------------------------------------------------------------------------------------

    @Transactional
    public PayoutView disburse(String code, int cycleNo, String idemKey, String actor, LocalDate today) {
        Replay replay = idempotency.begin(idemKey, OP_DISBURSE, Hashing.sha256(code + "|" + cycleNo));
        if (replay != null) {
            return reads.payout(code, cycleNo).asReplay();
        }
        GroupRow g = lockOpen(code);
        ChitRules rules = groups.rulesOf(g);
        CycleInfo c = cycleOf(g, cycleNo);
        PayoutRow p = repo.payoutOfCycleForUpdate(c.id()).orElseThrow(() -> ChitException.notFound("Payout", code + " cycle " + cycleNo));
        if (!"APPROVED".equals(p.status()) && !"PART_PAID".equals(p.status())) {
            throw ChitException.conflict("PAYOUT_NOT_APPROVED", "The payout of cycle " + cycleNo + " is " + p.status() + "; it can be disbursed once approved");
        }
        if (repo.payoutsBeforeStillOpen(g.id(), cycleNo) > 0) {
            throw ChitException.conflict("PREVIOUS_PAYOUT_PENDING", "An earlier cycle's prize has not been paid out yet; prizes are paid in cycle order");
        }
        TicketRow winner = tickets.findById(p.ticketId()).orElseThrow(() -> ChitException.notFound("Ticket", String.valueOf(p.ticketId())));
        boolean firstLeg = p.paidGross().signum() == 0;
        if (firstLeg) {
            checkPreconditions(rules, c, winner, today);
        }
        BigDecimal remaining = p.remainingGross();
        PoolPosition pos = reads.position(g.id());
        LiquidityDecision.Decision d = LiquidityDecision.decide(rules.payoutLiquidityRule(), remaining, pos.availableFor(remaining));
        if (d.action() == Action.BLOCKED) {
            throw new ChitException("PAYOUT_LIQUIDITY_SHORT", "The pool can free " + Money.max(d.available(), Money.ZERO).toPlainString() + " of the "
                    + remaining.toPlainString() + " owed on this prize; it is short by " + d.shortfall().toPlainString() + " (rule "
                    + rules.payoutLiquidityRule() + "). Collect the unpaid instalments, or change the rule.");
        }
        String ref = "PAYOUT-" + p.id();
        int leg = repo.nextLegNo(p.id());
        BigDecimal floatTotal = p.floatAmount();
        if (d.action() == Action.PAY_WITH_FLOAT) {
            ledger.post(JournalTemplates.foremanFloat("PAYFLOAT:" + p.id() + ":" + leg, d.floatAmount()),
                    new PostingContext(g.id(), today, c.id(), "PAYOUT", ref, actor));
            repo.insertLeg(p.id(), leg, "FLOAT", d.floatAmount(), Money.ZERO, null, today, actor);
            floatTotal = floatTotal.add(d.floatAmount());
            leg++;
        }
        BigDecimal legGross = d.payNow();
        BigDecimal legHold = LiquidityDecision.holdForLeg(p.gross(), p.hold(), p.paidGross(), p.paidHold(), legGross);
        ledger.post(JournalTemplates.prizeDisbursed("PRIZEPAY:" + p.id() + ":" + leg, p.ticketId(), legGross, legHold),
                new PostingContext(g.id(), today, c.id(), "PAYOUT", ref, actor));
        String instruction = ref + "-" + leg;
        repo.insertLeg(p.id(), leg, "DISBURSEMENT", legGross, legHold, instruction, today, actor);
        BigDecimal paidGross = p.paidGross().add(legGross);
        BigDecimal paidHold = p.paidHold().add(legHold);
        boolean complete = paidGross.compareTo(p.gross()) == 0;
        repo.recordDisbursement(p.id(), paidGross, paidHold, floatTotal, complete, ref, actor);
        if (firstLeg) {
            Lifecycles.TICKET.require(winner.status(), TicketStatus.PRIZED);
            tickets.setTicketStatus(winner.id(), TicketStatus.PRIZED, actor);
        }
        boolean settled = false;
        if (complete) {
            Lifecycles.CYCLE.require(CycleStatus.PAYOUT_PENDING, CycleStatus.PAYOUT_DONE);
            repo.setCycleStatus(c.id(), CycleStatus.PAYOUT_DONE);
            settled = settler.settleIfDone(code, c, actor);
        }
        audit.log("PAYOUT", code + "#" + cycleNo, complete ? "DISBURSED" : "PART_PAID", actor,
                "ticket " + winner.ticketNo() + " paid " + legGross.toPlainString() + " (hold " + legHold.toPlainString() + ") to " + p.accountRef()
                        + (d.action() == Action.PAY_WITH_FLOAT ? ", foreman floated " + d.floatAmount().toPlainString() : ""));
        audit.event("PrizeDisbursed", "GROUP", code,
                Json.obj("groupCode", code, "cycleNo", cycleNo, "ticketNo", winner.ticketNo(), "customerId", winner.customerId(), "legNo", leg,
                        "legGross", legGross, "legHold", legHold, "legNet", legGross.subtract(legHold), "grossPrize", p.gross(), "paidGross", paidGross,
                        "remainingGross", p.gross().subtract(paidGross), "complete", complete, "foremanFloat", d.floatAmount().signum() > 0 ? d.floatAmount() : null,
                        "payoutRef", ref, "instructionRef", instruction, "accountRef", p.accountRef(), "cycleSettled", settled ? Boolean.TRUE : null,
                        "businessDate", today.toString(), "disbursedBy", actor));
        idempotency.complete(idemKey, code + "#" + cycleNo);
        return reads.payout(code, cycleNo);
    }

    // ---- foreman float repayment ----------------------------------------------------------------------------------

    @Transactional
    public PayoutView repayFloat(String code, int cycleNo, FloatRepaymentRequest req, String idemKey, String actor, LocalDate today) {
        if (req == null || req.amount() == null || req.amount().signum() <= 0 || req.amount().stripTrailingZeros().scale() > 2) {
            throw ChitException.badRequest("VALIDATION_FAILED", "amount is required and must be a positive amount in whole paise");
        }
        BigDecimal amount = Money.scale(req.amount());
        Replay replay = idempotency.begin(idemKey, OP_FLOAT_REPAY, Hashing.sha256(code + "|" + cycleNo + "|" + amount.toPlainString()));
        if (replay != null) {
            return reads.payout(code, cycleNo).asReplay();
        }
        GroupRow g = lockOpen(code);
        CycleInfo c = cycleOf(g, cycleNo);
        PayoutRow p = repo.payoutOfCycleForUpdate(c.id()).orElseThrow(() -> ChitException.notFound("Payout", code + " cycle " + cycleNo));
        BigDecimal owed = p.floatAmount().subtract(p.floatRepaid());
        if (amount.compareTo(owed) > 0) {
            throw ChitException.conflict("FLOAT_REPAYMENT_TOO_LARGE", "Only " + owed.toPlainString() + " of the foreman's float is still owed on this payout");
        }
        PoolPosition pos = reads.position(g.id());
        if (pos.surplus().compareTo(amount) < 0) {
            throw new ChitException("PAYOUT_LIQUIDITY_SHORT", "The pool holds " + Money.max(pos.surplus(), Money.ZERO).toPlainString()
                    + " beyond what it owes; the float repayment of " + amount.toPlainString() + " must wait for collections");
        }
        int leg = repo.nextLegNo(p.id());
        ledger.post(JournalTemplates.floatRepaid("FLOATREPAY:" + p.id() + ":" + leg, amount), new PostingContext(g.id(), today, c.id(), "PAYOUT", "PAYOUT-" + p.id(), actor));
        repo.insertLeg(p.id(), leg, "FLOAT_REPAYMENT", amount, Money.ZERO, null, today, actor);
        repo.recordFloatRepaid(p.id(), p.floatRepaid().add(amount));
        audit.log("PAYOUT", code + "#" + cycleNo, "FLOAT_REPAID", actor, amount.toPlainString());
        audit.event("ForemanFloatRepaid", "GROUP", code, Json.obj("groupCode", code, "cycleNo", cycleNo, "amount", amount, "stillOwed", owed.subtract(amount),
                "businessDate", today.toString(), "repaidBy", actor));
        idempotency.complete(idemKey, code + "#" + cycleNo);
        return reads.payout(code, cycleNo);
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    /** Security verified, nothing overdue, winner in good standing, account on record. */
    private void checkPreconditions(ChitRules rules, CycleInfo c, TicketRow winner, LocalDate today) {
        if (winner.status() != TicketStatus.ACTIVE) {
            throw new ChitException("PAYOUT_TICKET_NOT_IN_GOOD_STANDING", "Ticket " + winner.ticketNo() + " is " + winner.status()
                    + "; a prize is paid to a ticket in good standing");
        }
        if (rules.securityRule() != SecurityRule.NONE) {
            SecurityRow s = tickets.liveSecurityOfTicket(winner.id()).orElse(null);
            if (s == null || !"VERIFIED".equals(s.status())) {
                throw new ChitException("PAYOUT_SECURITY_NOT_VERIFIED", "The " + rules.securityRule() + " security of ticket " + winner.ticketNo()
                        + " is " + (s == null ? "missing" : s.status()) + "; it must be verified before the prize is paid");
            }
        }
        BigDecimal arrears = repo.arrearsOf(winner.id(), c.cycleNo(), today);
        if (arrears.signum() > 0) {
            throw new ChitException("PAYOUT_ARREARS_UNCLEARED", "Ticket " + winner.ticketNo() + " still owes " + arrears.toPlainString()
                    + " (instalments and late fees); arrears are cleared before the prize is paid");
        }
        if (winner.payoutAccountRef() == null || winner.payoutAccountRef().isBlank()) {
            throw new ChitException("PAYOUT_ACCOUNT_MISSING", "Ticket " + winner.ticketNo() + " has no payout account on record");
        }
    }

    private void requireChecker(PayoutRow p, String actor) {
        if (settings.makerCheckerEnforce() && actor.equals(p.madeBy())) {
            throw ChitException.forbidden("MAKER_CHECKER_VIOLATION", "The payout was requested by " + p.madeBy() + "; a different person must decide on it");
        }
    }

    private PayoutRow pending(CycleInfo c) {
        PayoutRow p = repo.payoutOfCycleForUpdate(c.id()).orElseThrow(() -> ChitException.notFound("Payout", "cycle " + c.cycleNo()));
        if (!"PENDING_APPROVAL".equals(p.status())) {
            throw ChitException.conflict("ILLEGAL_TRANSITION", "The payout of cycle " + c.cycleNo() + " is " + p.status() + ", not waiting for a decision");
        }
        return p;
    }

    private GroupRow lockOpen(String code) {
        GroupRow g = groups.lock(code);
        if (g.status() != GroupStatus.ACTIVE && g.status() != GroupStatus.CLOSING) {
            throw ChitException.conflict("GROUP_NOT_ACTIVE", "Group " + code + " is " + g.status() + "; payouts run while it is ACTIVE or CLOSING");
        }
        return g;
    }

    private CycleInfo cycleOf(GroupRow g, int cycleNo) {
        return repo.cycle(g.id(), cycleNo).orElseThrow(() -> ChitException.notFound("Cycle", g.groupCode() + " #" + cycleNo));
    }
}
