package com.chitfund.service.recovery;

import com.chitfund.service.auction.AuctionModels.CoverRow;
import com.chitfund.service.auction.InternalPayments;
import com.chitfund.service.calc.AllocationEngine;
import com.chitfund.service.calc.AllocationEngine.DemandDue;
import com.chitfund.service.calc.DemandFigures;
import com.chitfund.service.calc.RecoveryWaterfall;
import com.chitfund.service.calc.RecoveryWaterfall.CoverBalance;
import com.chitfund.service.calc.RecoveryWaterfall.Plan;
import com.chitfund.service.calc.RecoveryWaterfall.WriteOffAmounts;
import com.chitfund.service.collection.CollectionModels.DemandRow;
import com.chitfund.service.collection.CollectionRepository;
import com.chitfund.service.common.Audit;
import com.chitfund.service.common.ChitException;
import com.chitfund.service.common.Hashing;
import com.chitfund.service.common.Json;
import com.chitfund.service.common.Money;
import com.chitfund.service.config.ChitSettings;
import com.chitfund.service.domain.ForemanGuaranteeMode;
import com.chitfund.service.domain.GroupStatus;
import com.chitfund.service.domain.TicketStatus;
import com.chitfund.service.domain.rules.ChitRules;
import com.chitfund.service.domain.state.Lifecycles;
import com.chitfund.service.enrolment.EnrolmentModels.TicketRow;
import com.chitfund.service.enrolment.EnrolmentRepository;
import com.chitfund.service.group.GroupModels.GroupRow;
import com.chitfund.service.group.GroupService;
import com.chitfund.service.idempotency.IdempotencyCheck.Replay;
import com.chitfund.service.idempotency.IdempotencyStore;
import com.chitfund.service.ledger.AccountRef;
import com.chitfund.service.ledger.AccountType;
import com.chitfund.service.ledger.JournalTemplates;
import com.chitfund.service.ledger.LedgerDtos.PostingContext;
import com.chitfund.service.ledger.LedgerRepository;
import com.chitfund.service.ledger.LedgerService;
import com.chitfund.service.recovery.RecoveryDtos.ActionView;
import com.chitfund.service.recovery.RecoveryDtos.ApplySecurityRequest;
import com.chitfund.service.recovery.RecoveryDtos.RejectWriteOffRequest;
import com.chitfund.service.recovery.RecoveryDtos.RepayCoverRequest;
import com.chitfund.service.recovery.RecoveryDtos.WriteOffRequest;
import com.chitfund.service.recovery.RecoveryDtos.WriteOffView;
import com.chitfund.service.recovery.RecoveryModels.CaseRow;
import com.chitfund.service.recovery.RecoveryModels.WriteOffRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What happens to a ticket that has stopped paying, after default detection has flagged it.
 *
 * <ol>
 *   <li><b>Apply the held deposit</b> (DEPOSIT security, including a prize hold): first to what the pool is owed, then to the foreman's
 *       covers on the ticket. A ticket whose overdue amount is cleared this way goes back to ACTIVE (or PRIZED).</li>
 *   <li><b>Repayment of covers</b>: money the holder pays the foreman against what he covered. It is not a payment into the pool.</li>
 *   <li><b>Write-off</b>, by a maker and a different checker, once the deposit has been applied. The foreman bears the loss: he pays the
 *       overdue instalments into the pool (a cover, so the pool is whole), and every cover on the ticket is then written off to
 *       GUARANTEE_LOSS_EXPENSE; overdue late fees, which were his income, are waived. The ticket stays DEFAULTED; it returns to good standing
 *       only through a replacement subscriber (see the transfer service).</li>
 * </ol>
 *
 * <p>Every step runs in one transaction under the group lock, posts its journal with a key built from the step's own id, and writes its event.
 */
@Service
public class RecoveryService {

    private static final String OP_APPLY = "RECOVERY_APPLY_SECURITY";
    private static final String OP_REPAY = "RECOVERY_REPAY_COVER";
    private static final String OP_WRITE_OFF = "WRITE_OFF_REQUEST";
    private static final String OP_APPROVE = "WRITE_OFF_APPROVE";
    private static final String OP_REJECT = "WRITE_OFF_REJECT";
    private static final Set<String> CHANNELS = Set.of("UPI", "BANK_TRANSFER", "CASH_AT_FOREMAN", "AUTO_DEBIT");

    private final GroupService groups;
    private final RecoveryRepository repo;
    private final EnrolmentRepository tickets;
    private final CollectionRepository collection;
    private final LedgerService ledger;
    private final LedgerRepository ledgerRepo;
    private final InternalPayments payments;
    private final IdempotencyStore idempotency;
    private final Audit audit;
    private final ChitSettings settings;
    private final RecoveryReadService reads;

    public RecoveryService(GroupService groups, RecoveryRepository repo, EnrolmentRepository tickets, CollectionRepository collection, LedgerService ledger,
                           LedgerRepository ledgerRepo, InternalPayments payments, IdempotencyStore idempotency, Audit audit, ChitSettings settings,
                           RecoveryReadService reads) {
        this.groups = groups;
        this.repo = repo;
        this.tickets = tickets;
        this.collection = collection;
        this.ledger = ledger;
        this.ledgerRepo = ledgerRepo;
        this.payments = payments;
        this.idempotency = idempotency;
        this.audit = audit;
        this.settings = settings;
        this.reads = reads;
    }

    // ---- apply the held deposit -----------------------------------------------------------------------------------

    @Transactional
    public ActionView applySecurity(String code, int ticketNo, ApplySecurityRequest req, String idemKey, String actor, LocalDate today) {
        BigDecimal cap = req == null || req.amount() == null ? null : cleanAmount(req.amount());
        Replay replay = idempotency.begin(idemKey, OP_APPLY, Hashing.sha256(code + "|" + ticketNo + "|" + (cap == null ? "ALL" : cap.toPlainString())));
        if (replay != null) {
            return reads.action(code, ticketNo, Long.parseLong(replay.resultRef())).asReplay();
        }
        GroupRow g = lockRunning(code);
        ChitRules rules = groups.rulesOf(g);
        TicketRow t = liveTicket(g, ticketNo);
        CaseRow c = defaultedWithCase(t);

        BigDecimal held = Money.max(Money.ZERO, ledgerRepo.balanceOf(g.id(), AccountRef.of(AccountType.SECURITY_DEPOSIT_LIABILITY, t.id())));
        if (held.signum() <= 0) {
            throw new ChitException("NO_SECURITY_HELD", "No deposit is held for ticket " + ticketNo + "; a lien or a guarantor is claimed outside the books, "
                    + "and the money received is recorded as a payment on the ticket");
        }
        List<DemandRow> overdue = overdueDemands(t.id(), today);
        BigDecimal arrears = overdue.stream().map(d -> d.figures().totalOutstanding()).reduce(Money.ZERO, BigDecimal::add);
        List<CoverRow> covers = repo.openCovers(t.id());
        Plan plan = RecoveryWaterfall.applySecurity(held, cap, arrears, balances(covers));
        if (plan.total().signum() <= 0) {
            throw new ChitException("NOTHING_TO_RECOVER", "Ticket " + ticketNo + " owes nothing overdue and nothing on the foreman's covers, so the deposit is not applied");
        }

        long actionId = repo.insertAction(c.id(), t.id(), "SECURITY_APPLIED", plan.toArrears(), plan.toCoversTotal(), null, null, null, today, actor);
        String paymentRef = null;
        if (plan.toArrears().signum() > 0) {
            paymentRef = payments.applySecurity(g.id(), t.id(), actionId, plan.toArrears(), overdue, rules.allocationOrder(), today, actor);
            repo.setActionPaymentRef(actionId, paymentRef);
        }
        for (Map.Entry<Long, BigDecimal> e : plan.toCovers().entrySet()) {
            CoverRow cv = covers.stream().filter(x -> x.id() == e.getKey()).findFirst().orElseThrow();
            ledger.post(JournalTemplates.securityAppliedToGuarantee("SECGUAR:" + actionId + ":" + cv.id(), t.id(), e.getValue()),
                    new PostingContext(g.id(), today, cv.cycleId(), "RECOVERY", "RECOVERY-" + actionId, actor));
            repo.addCoverRepayment(cv.id(), e.getValue());
        }
        repo.addRecovery(c.id(), plan.total(), Money.ZERO);
        if (held.subtract(plan.total()).signum() == 0) {
            tickets.liveSecurityOfTicket(t.id()).filter(s -> "DEPOSIT".equals(s.kind())).ifPresent(s -> tickets.markSecurityApplied(s.id()));
        }
        boolean closed = closeIfRecovered(g, t, c.id(), today, actor);
        audit.log("RECOVERY", code + "#" + ticketNo + "/" + actionId, "SECURITY_APPLIED", actor, "to arrears " + plan.toArrears().toPlainString()
                + ", to foreman covers " + plan.toCoversTotal().toPlainString() + ", deposit left " + held.subtract(plan.total()).toPlainString());
        audit.event("SecurityApplied", "TICKET", code + "/" + ticketNo,
                Json.obj("groupCode", code, "ticketNo", ticketNo, "customerId", t.customerId(), "recoveryActionId", actionId, "toArrears", plan.toArrears(),
                        "toForemanCovers", plan.toCoversTotal(), "depositLeft", held.subtract(plan.total()), "paymentRef", paymentRef,
                        "defaultCaseClosed", closed ? Boolean.TRUE : null, "businessDate", today.toString(), "actor", actor));
        idempotency.complete(idemKey, String.valueOf(actionId));
        return reads.action(code, ticketNo, actionId);
    }

    // ---- the holder repays the foreman ----------------------------------------------------------------------------

    @Transactional
    public ActionView repayCover(String code, int ticketNo, RepayCoverRequest req, String idemKey, String actor, LocalDate today) {
        if (req == null || req.amount() == null) {
            throw ChitException.badRequest("VALIDATION_FAILED", "amount is required");
        }
        BigDecimal amount = cleanAmount(req.amount());
        String channel = req.channel() == null ? "" : req.channel().trim().toUpperCase();
        if (!CHANNELS.contains(channel)) {
            throw ChitException.badRequest("VALIDATION_FAILED", "channel must be one of " + new java.util.TreeSet<>(CHANNELS));
        }
        String externalRef = req.externalRef() == null || req.externalRef().isBlank() ? null : req.externalRef().trim();
        if (externalRef != null && externalRef.length() > 60) {
            throw ChitException.badRequest("VALIDATION_FAILED", "externalRef is longer than 60 characters");
        }
        Replay replay = idempotency.begin(idemKey, OP_REPAY,
                Hashing.sha256(code + "|" + ticketNo + "|" + amount.toPlainString() + "|" + channel + "|" + (externalRef == null ? "" : externalRef)));
        if (replay != null) {
            return reads.action(code, ticketNo, Long.parseLong(replay.resultRef())).asReplay();
        }
        GroupRow g = lockRunning(code);
        TicketRow t = liveTicket(g, ticketNo);
        List<CoverRow> covers = repo.openCovers(t.id());
        Map<Long, BigDecimal> spread = RecoveryWaterfall.repayCovers(amount, balances(covers));
        CaseRow c = repo.openCaseForUpdate(t.id()).orElse(null);

        long actionId = repo.insertAction(c == null ? null : c.id(), t.id(), "COVER_REPAID", Money.ZERO, amount, channel, externalRef, null, today, actor);
        for (Map.Entry<Long, BigDecimal> e : spread.entrySet()) {
            CoverRow cv = covers.stream().filter(x -> x.id() == e.getKey()).findFirst().orElseThrow();
            ledger.post(JournalTemplates.guaranteeRepaid("GUARREPAY:" + actionId + ":" + cv.id(), t.id(), e.getValue()),
                    new PostingContext(g.id(), today, cv.cycleId(), "RECOVERY", "RECOVERY-" + actionId, actor));
            repo.addCoverRepayment(cv.id(), e.getValue());
        }
        boolean closed = false;
        if (c != null) {
            repo.addRecovery(c.id(), Money.ZERO, amount);
            closed = closeIfRecovered(g, t, c.id(), today, actor);
        }
        audit.log("RECOVERY", code + "#" + ticketNo + "/" + actionId, "COVER_REPAID", actor, amount.toPlainString() + " via " + channel);
        audit.event("GuaranteeRepaid", "TICKET", code + "/" + ticketNo,
                Json.obj("groupCode", code, "ticketNo", ticketNo, "customerId", t.customerId(), "recoveryActionId", actionId, "amount", amount, "channel", channel,
                        "externalRef", externalRef, "defaultCaseClosed", closed ? Boolean.TRUE : null, "businessDate", today.toString(), "actor", actor));
        idempotency.complete(idemKey, String.valueOf(actionId));
        return reads.action(code, ticketNo, actionId);
    }

    // ---- write-off (maker) ----------------------------------------------------------------------------------------

    @Transactional
    public WriteOffView requestWriteOff(String code, int ticketNo, WriteOffRequest req, String idemKey, String actor, LocalDate today) {
        String reason = req == null || req.reason() == null ? "" : req.reason().trim();
        if (reason.isEmpty() || reason.length() > 200) {
            throw ChitException.badRequest("VALIDATION_FAILED", "reason is required and may be at most 200 characters");
        }
        Replay replay = idempotency.begin(idemKey, OP_WRITE_OFF, Hashing.sha256(code + "|" + ticketNo + "|" + reason));
        if (replay != null) {
            return reads.writeOff(code, Long.parseLong(replay.resultRef())).asReplay();
        }
        GroupRow g = lockRunning(code);
        ChitRules rules = groups.rulesOf(g);
        TicketRow t = liveTicket(g, ticketNo);
        CaseRow c = defaultedWithCase(t);
        requireWriteOffAllowed(rules, g, t);
        if (repo.pendingWriteOffExists(c.id())) {
            throw ChitException.conflict("WRITE_OFF_PENDING", "A write-off for ticket " + ticketNo + " is already waiting for a decision");
        }
        WriteOffAmounts a = currentAmounts(t, today);
        if (a.isEmpty()) {
            throw new ChitException("NOTHING_TO_WRITE_OFF", "Ticket " + ticketNo + " owes nothing overdue and nothing on the foreman's covers");
        }
        long id = repo.insertWriteOff(c.id(), t.id(), a.subscription(), a.covers(), a.lateFees(), reason, actor, today);
        audit.log("WRITE_OFF", code + "#" + ticketNo + "/" + id, "REQUESTED", actor, "total " + a.total().toPlainString() + ": " + reason);
        audit.event("WriteOffRequested", "TICKET", code + "/" + ticketNo,
                Json.obj("groupCode", code, "ticketNo", ticketNo, "writeOffId", id, "subscription", a.subscription(), "earlierCovers", a.covers(),
                        "lateFees", a.lateFees(), "total", a.total(), "reason", reason, "madeBy", actor, "businessDate", today.toString()));
        idempotency.complete(idemKey, String.valueOf(id));
        return reads.writeOff(code, id);
    }

    // ---- write-off (checker) --------------------------------------------------------------------------------------

    @Transactional
    public WriteOffView approveWriteOff(String code, long writeOffId, String idemKey, String actor, LocalDate today) {
        Replay replay = idempotency.begin(idemKey, OP_APPROVE, Hashing.sha256(code + "|" + writeOffId));
        if (replay != null) {
            return reads.writeOff(code, writeOffId).asReplay();
        }
        GroupRow g = lockRunning(code);
        ChitRules rules = groups.rulesOf(g);
        WriteOffRow w = pendingWriteOff(g, writeOffId);
        requireChecker(w, actor);
        TicketRow t = tickets.findById(w.ticketId()).orElseThrow(() -> ChitException.notFound("Ticket", String.valueOf(w.ticketId())));
        CaseRow c = defaultedWithCase(t);
        if (c.id() != w.caseId()) {
            throw ChitException.conflict("WRITE_OFF_STALE", "The default case changed since the write-off was requested; ask again");
        }
        requireWriteOffAllowed(rules, g, t);
        WriteOffAmounts now = currentAmounts(t, today);
        if (now.subscription().compareTo(w.subscription()) != 0 || now.covers().compareTo(w.covers()) != 0 || now.lateFees().compareTo(w.fees()) != 0) {
            throw ChitException.conflict("WRITE_OFF_STALE", "What the ticket owes has changed since the write-off was requested (then " + w.total().toPlainString()
                    + ", now " + now.total().toPlainString() + "); reject this request and ask again");
        }

        List<DemandRow> overdue = overdueDemands(t.id(), today);
        // 1. the foreman pays the overdue instalments into the pool (the pool is whole); each becomes a cover
        BigDecimal paidIntoPool = Money.ZERO;
        for (DemandRow d : overdue) {
            BigDecimal sub = d.figures().subscriptionOutstanding();
            if (sub.signum() > 0) {
                if (repo.coverExistsForDemand(d.id())) {
                    throw ChitException.conflict("WRITE_OFF_DEMAND_ALREADY_COVERED", "The instalment of cycle " + d.cycleNo() + " was covered by the foreman before and "
                            + "has been reopened since; it cannot be covered again here. Take the matter to the foreman.");
                }
                payments.coverInstalment(g.id(), d.cycleId(), d.cycleNo(), t.id(), t.ticketNo(), d.id(), sub, today, actor);
                paidIntoPool = paidIntoPool.add(sub);
            }
        }
        // 2. late fees on those demands are given up: the foreman's income is reduced and the demand ends as WRITTEN_OFF
        for (DemandRow d : overdue) {
            BigDecimal fee = d.figures().lateFeeOutstanding();
            if (fee.signum() > 0) {
                ledger.post(JournalTemplates.lateFeeWaived("LFWAIVE:" + d.id(), t.id(), fee),
                        new PostingContext(g.id(), today, d.cycleId(), "WRITE_OFF", "WRITEOFF-" + w.id(), actor));
                repo.waiveLateFee(d.id(), fee);
            }
        }
        // 3. every cover the foreman holds on this ticket (the new ones included) is written off to his expense
        BigDecimal guaranteeLoss = Money.ZERO;
        for (CoverRow cv : repo.openCovers(t.id())) {
            BigDecimal balance = cv.amount().subtract(cv.recovered());
            if (balance.signum() > 0) {
                ledger.post(JournalTemplates.guaranteeWrittenOff("GWO:" + cv.id(), t.id(), balance),
                        new PostingContext(g.id(), today, cv.cycleId(), "WRITE_OFF", "WRITEOFF-" + w.id(), actor));
                guaranteeLoss = guaranteeLoss.add(balance);
            }
            repo.markCoverWrittenOff(cv.id());
        }
        repo.approveWriteOff(w.id(), actor, today);
        repo.addWriteOff(c.id(), w.total(), paidIntoPool, w.madeBy(), actor);
        audit.log("WRITE_OFF", code + "#" + t.ticketNo() + "/" + w.id(), "APPROVED", actor, "total " + w.total().toPlainString() + ", foreman loss "
                + guaranteeLoss.toPlainString() + ", late fees waived " + w.fees().toPlainString() + ", made by " + w.madeBy());
        audit.event("WriteOffApproved", "TICKET", code + "/" + t.ticketNo(),
                Json.obj("groupCode", code, "ticketNo", t.ticketNo(), "customerId", t.customerId(), "writeOffId", w.id(), "paidIntoPoolByForeman", paidIntoPool,
                        "foremanLoss", guaranteeLoss, "lateFeesWaived", w.fees(), "total", w.total(), "madeBy", w.madeBy(), "checkedBy", actor,
                        "businessDate", today.toString()));
        idempotency.complete(idemKey, String.valueOf(w.id()));
        return reads.writeOff(code, w.id());
    }

    @Transactional
    public WriteOffView rejectWriteOff(String code, long writeOffId, RejectWriteOffRequest req, String idemKey, String actor, LocalDate today) {
        String reason = req == null || req.reason() == null ? "" : req.reason().trim();
        if (reason.isEmpty() || reason.length() > 200) {
            throw ChitException.badRequest("VALIDATION_FAILED", "reason is required and may be at most 200 characters");
        }
        Replay replay = idempotency.begin(idemKey, OP_REJECT, Hashing.sha256(code + "|" + writeOffId + "|" + reason));
        if (replay != null) {
            return reads.writeOff(code, writeOffId).asReplay();
        }
        GroupRow g = lockRunning(code);
        WriteOffRow w = pendingWriteOff(g, writeOffId);
        requireChecker(w, actor);
        repo.rejectWriteOff(w.id(), actor, reason, today);
        audit.log("WRITE_OFF", code + "#" + w.ticketNo() + "/" + w.id(), "REJECTED", actor, reason);
        audit.event("WriteOffRejected", "TICKET", code + "/" + w.ticketNo(),
                Json.obj("groupCode", code, "ticketNo", w.ticketNo(), "writeOffId", w.id(), "reason", reason, "madeBy", w.madeBy(), "checkedBy", actor));
        idempotency.complete(idemKey, String.valueOf(w.id()));
        return reads.writeOff(code, w.id());
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    /** A ticket whose overdue amount and covers are all cleared by recovery goes back to good standing, unless a write-off has been made. */
    private boolean closeIfRecovered(GroupRow g, TicketRow t, long caseId, LocalDate today, String actor) {
        CaseRow c = repo.findCase(caseId).orElseThrow();
        if (!"OPEN".equals(c.status()) || c.writtenOff().signum() > 0 || t.status() != TicketStatus.DEFAULTED) {
            return false;
        }
        if (!overdueDemands(t.id(), today).isEmpty() || !repo.openCovers(t.id()).isEmpty()) {
            return false;
        }
        TicketStatus back = Lifecycles.restoreAfterDefault(collection.hasWonPrize(t.id()));
        Lifecycles.TICKET.require(TicketStatus.DEFAULTED, back);
        tickets.setTicketStatus(t.id(), back, actor);
        collection.closeCase(caseId, "RECOVERED", today);
        audit.log("TICKET", g.groupCode() + "#" + t.ticketNo(), "DEFAULT_RECOVERED", actor, "overdue and covers cleared by recovery, ticket is " + back);
        audit.event("TicketCured", "TICKET", g.groupCode() + "/" + t.ticketNo(),
                Json.obj("groupCode", g.groupCode(), "ticketNo", t.ticketNo(), "customerId", t.customerId(), "status", back.name(), "curedOn", today.toString(),
                        "via", "RECOVERY"));
        return true;
    }

    private void requireWriteOffAllowed(ChitRules rules, GroupRow g, TicketRow t) {
        if (rules.foremanGuaranteeMode() == ForemanGuaranteeMode.NONE) {
            throw new ChitException("WRITE_OFF_NOT_PERMITTED", "Group " + g.groupCode() + " has no foreman guarantee (foremanGuaranteeMode NONE), so there is "
                    + "nobody to carry a written-off loss. For now the foreman bears write-off losses and only groups with a guarantee mode support it.");
        }
        BigDecimal held = Money.max(Money.ZERO, ledgerRepo.balanceOf(g.id(), AccountRef.of(AccountType.SECURITY_DEPOSIT_LIABILITY, t.id())));
        if (held.signum() > 0) {
            throw ChitException.conflict("SECURITY_STILL_HELD", "Ticket " + t.ticketNo() + " still has " + held.toPlainString()
                    + " held as deposit; apply the deposit before writing anything off");
        }
    }

    private WriteOffAmounts currentAmounts(TicketRow t, LocalDate today) {
        List<DemandFigures> figures = new ArrayList<>();
        for (DemandRow d : overdueDemands(t.id(), today)) {
            figures.add(d.figures());
        }
        return RecoveryWaterfall.writeOffAmounts(figures, balances(repo.openCovers(t.id())));
    }

    private List<DemandRow> overdueDemands(long ticketId, LocalDate today) {
        return collection.openDemandsOfTicket(ticketId).stream().filter(d -> d.dueDate().isBefore(today)).toList();
    }

    private static List<CoverBalance> balances(List<CoverRow> covers) {
        return covers.stream().map(c -> new CoverBalance(c.id(), c.amount().subtract(c.recovered()))).toList();
    }

    /** DEFAULTED ticket with an open case, as the recovery steps need. */
    private CaseRow defaultedWithCase(TicketRow t) {
        if (t.status() != TicketStatus.DEFAULTED) {
            throw ChitException.conflict("RECOVERY_TICKET_NOT_DEFAULTED", "Ticket " + t.ticketNo() + " is " + t.status() + "; recovery applies to a DEFAULTED ticket");
        }
        return repo.openCaseForUpdate(t.id()).orElseThrow(() -> ChitException.conflict("RECOVERY_NO_OPEN_CASE",
                "Ticket " + t.ticketNo() + " has no open default case"));
    }

    private WriteOffRow pendingWriteOff(GroupRow g, long id) {
        WriteOffRow w = repo.findWriteOffForUpdate(g.id(), id).orElseThrow(() -> ChitException.notFound("Write-off", g.groupCode() + "/" + id));
        if (!"PENDING".equals(w.status())) {
            throw ChitException.conflict("ILLEGAL_TRANSITION", "Write-off " + id + " is " + w.status() + ", not waiting for a decision");
        }
        return w;
    }

    private void requireChecker(WriteOffRow w, String actor) {
        if (settings.makerCheckerEnforce() && actor.equals(w.madeBy())) {
            throw ChitException.forbidden("MAKER_CHECKER_VIOLATION", "The write-off was requested by " + w.madeBy() + "; a different person must decide on it");
        }
    }

    private GroupRow lockRunning(String code) {
        GroupRow g = groups.lock(code);
        if (g.status() != GroupStatus.ACTIVE && g.status() != GroupStatus.CLOSING) {
            throw ChitException.conflict("GROUP_NOT_ACTIVE", "Group " + code + " is " + g.status() + "; recovery runs while it is ACTIVE or CLOSING");
        }
        return g;
    }

    private TicketRow liveTicket(GroupRow g, int ticketNo) {
        return tickets.findLive(g.id(), ticketNo).orElseThrow(() -> ChitException.notFound("Ticket", g.groupCode() + " #" + ticketNo));
    }

    private static BigDecimal cleanAmount(BigDecimal raw) {
        if (raw.signum() <= 0) {
            throw ChitException.badRequest("VALIDATION_FAILED", "amount must be greater than zero");
        }
        if (raw.stripTrailingZeros().scale() > 2) {
            throw ChitException.badRequest("VALIDATION_FAILED", "amount may have at most two decimal places");
        }
        if (raw.compareTo(new BigDecimal("999999999999.99")) > 0) {
            throw ChitException.badRequest("VALIDATION_FAILED", "amount is too large");
        }
        return Money.scale(raw);
    }
}
