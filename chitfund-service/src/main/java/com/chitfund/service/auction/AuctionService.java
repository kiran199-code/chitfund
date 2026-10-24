package com.chitfund.service.auction;

import com.chitfund.service.auction.AuctionDtos.AuctionView;
import com.chitfund.service.auction.AuctionDtos.BidView;
import com.chitfund.service.auction.AuctionDtos.PlaceBidRequest;
import com.chitfund.service.auction.AuctionDtos.WithdrawBidRequest;
import com.chitfund.service.auction.AuctionModels.AuctionRow;
import com.chitfund.service.auction.AuctionModels.BidRow;
import com.chitfund.service.auction.AuctionModels.CycleInfo;
import com.chitfund.service.auction.AuctionModels.DrawRow;
import com.chitfund.service.auction.AuctionModels.TicketFlag;
import com.chitfund.service.calc.AuctionSettlement;
import com.chitfund.service.calc.AuctionSettlementCalculator;
import com.chitfund.service.calc.BidRules;
import com.chitfund.service.calc.DemandFigures;
import com.chitfund.service.calc.Draw;
import com.chitfund.service.calc.DividendBeneficiaryPicker;
import com.chitfund.service.calc.QuorumGate;
import com.chitfund.service.calc.RollOver;
import com.chitfund.service.calc.WinnerSelector;
import com.chitfund.service.calendar.CycleCalendarGenerator;
import com.chitfund.service.calendar.ScheduledCycle;
import com.chitfund.service.calendar.WorkingDayCalendar;
import com.chitfund.service.collection.CollectionModels.DemandRow;
import com.chitfund.service.collection.CollectionRepository;
import com.chitfund.service.common.Audit;
import com.chitfund.service.common.ChitException;
import com.chitfund.service.common.Hashing;
import com.chitfund.service.common.Json;
import com.chitfund.service.common.Money;
import com.chitfund.service.domain.AuctionType;
import com.chitfund.service.domain.CycleStatus;
import com.chitfund.service.domain.DividendMode;
import com.chitfund.service.domain.GroupStatus;
import com.chitfund.service.domain.QuorumRule;
import com.chitfund.service.domain.TicketStatus;
import com.chitfund.service.domain.rules.ChitRules;
import com.chitfund.service.domain.state.Lifecycles;
import com.chitfund.service.group.GroupModels.GroupRow;
import com.chitfund.service.group.GroupService;
import com.chitfund.service.idempotency.IdempotencyCheck.Replay;
import com.chitfund.service.idempotency.IdempotencyStore;
import com.chitfund.service.ledger.JournalTemplates;
import com.chitfund.service.ledger.LedgerDtos.PostResult;
import com.chitfund.service.ledger.LedgerDtos.PostingContext;
import com.chitfund.service.ledger.LedgerService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Opens an auction, takes bids, and closes it: the winner, the discount, the commission and the dividends, in one
 * transaction under the group lock. Two simultaneous closes cannot both win: the second waits for the lock, finds the cycle
 * already closed and is refused (ILLEGAL_TRANSITION); the unique index on the cycle's winner is the last guard.
 *
 * <p>The sequence of a cycle: demands raised (COLLECTION_OPEN) -> auction opened if the gate allows (AUCTION_OPEN) -> closed
 * (AUCTION_CLOSED, the pot split and journalled) -> payout requested, approved and disbursed (see {@link PayoutService}).
 */
@Service
public class AuctionService {

    private static final String OP_OPEN = "AUCTION_OPEN";
    private static final String OP_BID = "AUCTION_BID";
    private static final String OP_WITHDRAW = "AUCTION_BID_WITHDRAW";
    private static final String OP_CLOSE = "AUCTION_CLOSE";

    private final GroupService groups;
    private final AuctionRepository repo;
    private final CollectionRepository collection;
    private final LedgerService ledger;
    private final IdempotencyStore idempotency;
    private final Audit audit;
    private final InternalPayments payments;
    private final DrawSeeds seeds;
    private final AuctionReadService reads;

    public AuctionService(GroupService groups, AuctionRepository repo, CollectionRepository collection, LedgerService ledger,
                          IdempotencyStore idempotency, Audit audit, InternalPayments payments, DrawSeeds seeds, AuctionReadService reads) {
        this.groups = groups;
        this.repo = repo;
        this.collection = collection;
        this.ledger = ledger;
        this.idempotency = idempotency;
        this.audit = audit;
        this.payments = payments;
        this.seeds = seeds;
        this.reads = reads;
    }

    // ---- open -----------------------------------------------------------------------------------------------------

    @Transactional
    public AuctionView open(String code, int cycleNo, String idemKey, String actor, LocalDate today) {
        Replay replay = idempotency.begin(idemKey, OP_OPEN, Hashing.sha256(code + "|" + cycleNo));
        if (replay != null) {
            return reads.auction(code, cycleNo).asReplay();
        }
        GroupRow g = lockActive(code);
        ChitRules rules = groups.rulesOf(g);
        CycleInfo c = cycleOf(g, cycleNo);
        if (c.status() == CycleStatus.SCHEDULED) {
            throw ChitException.conflict("AUCTION_GATE_CLOSED", "Collection for cycle " + cycleNo + " has not opened yet; the demand run raises its "
                    + "instalments on the due date, " + c.dueDate());
        }
        Lifecycles.CYCLE.require(c.status(), CycleStatus.AUCTION_OPEN);
        if (today.isBefore(c.auctionDate())) {
            throw ChitException.conflict("AUCTION_NOT_DUE", "The auction of cycle " + cycleNo + " is on " + c.auctionDate() + "; today is " + today);
        }
        if (repo.cyclesBeforeStillOpen(g.id(), cycleNo) > 0) {
            throw ChitException.conflict("PREVIOUS_CYCLE_OPEN", "An earlier cycle has not closed its auction yet; cycles are auctioned in order");
        }
        QuorumGate.Evaluation ev = QuorumGate.evaluate(rules.auctionQuorumRule(), rules.quorumMinCollectionPct(), cycleNo,
                repo.demandPositions(g.id(), cycleNo));
        if (!ev.open()) {
            throw ChitException.conflict("AUCTION_GATE_CLOSED", ev.reason() + " (rule " + ev.rule() + ")");
        }
        BigDecimal covered = Money.ZERO;
        if (rules.auctionQuorumRule() == QuorumRule.FOREMAN_COVERS_SHORTFALL) {
            for (QuorumGate.Cover cv : ev.cover()) {
                payments.coverInstalment(g.id(), c.id(), cycleNo, cv.ticketId(), cv.ticketNo(), cv.demandId(), cv.amount(), today, actor);
                covered = covered.add(cv.amount());
                audit.event("ForemanCovered", "TICKET", code + "/" + cv.ticketNo(),
                        Json.obj("groupCode", code, "cycleNo", cycleNo, "ticketNo", cv.ticketNo(), "amount", cv.amount(), "businessDate", today.toString()));
            }
        }
        int attempt = repo.auctionsOfCycleCount(c.id()) + 1;
        String note = ev.reason();
        long auctionId = repo.insertAuction(c.id(), rules.auctionType(), attempt, actor, note.length() > 300 ? note.substring(0, 300) : note);
        repo.setCycleStatus(c.id(), CycleStatus.AUCTION_OPEN);
        audit.log("CYCLE", code + "#" + cycleNo, "AUCTION_OPENED", actor, rules.auctionType() + ", attempt " + attempt + ", " + note);
        audit.event("AuctionOpened", "GROUP", code,
                Json.obj("groupCode", code, "cycleNo", cycleNo, "auctionType", rules.auctionType().name(), "attempt", attempt,
                        "auctionDate", c.auctionDate().toString(), "lastCycle", cycleNo == g.cycleCount() ? Boolean.TRUE : null,
                        "quorumRule", rules.auctionQuorumRule().name(), "collectedPct", ev.collectedPct(), "foremanCovered", covered.signum() > 0 ? covered : null,
                        "businessDate", today.toString(), "actor", actor));
        idempotency.complete(idemKey, String.valueOf(auctionId));
        return reads.auction(code, cycleNo);
    }

    // ---- bids -----------------------------------------------------------------------------------------------------

    @Transactional
    public BidView placeBid(String code, int cycleNo, PlaceBidRequest req, String idemKey, String actor) {
        if (req == null || req.ticketNo() == null) {
            throw ChitException.badRequest("VALIDATION_FAILED", "ticketNo is required");
        }
        BigDecimal discount = BidRules.clean(req.discountAmount());
        Replay replay = idempotency.begin(idemKey, OP_BID, Hashing.sha256(code + "|" + cycleNo + "|" + req.ticketNo() + "|" + discount.toPlainString()));
        if (replay != null) {
            return bidView(code, cycleNo, Integer.parseInt(replay.resultRef())).asReplay();
        }
        GroupRow g = lockActive(code);
        ChitRules rules = groups.rulesOf(g);
        CycleInfo c = cycleOf(g, cycleNo);
        AuctionRow a = openAuction(c);
        if (cycleNo == g.cycleCount()) {
            throw ChitException.conflict("LAST_CYCLE_NO_AUCTION", "Cycle " + cycleNo + " is the last: the one ticket left takes the whole pot, nobody bids");
        }
        if (a.type() == AuctionType.LOTTERY || a.type() == AuctionType.FIXED_ORDER) {
            throw ChitException.conflict("BIDS_NOT_ACCEPTED", "This group's auction type is " + a.type() + "; the winner is drawn, not bid for");
        }
        TicketFlag t = repo.ticketFlags(g.id()).stream().filter(f -> f.ticketNo() == req.ticketNo()).findFirst()
                .orElseThrow(() -> ChitException.notFound("Ticket", code + " #" + req.ticketNo()));
        requireEligible(t);
        BidRules.Bounds bounds = BidRules.bounds(g.chitValue(), rules.minDiscountPct(), rules.maxDiscountPct());
        BigDecimal commission = Money.percentOf(Money.scale(g.chitValue()), rules.commissionPct());
        List<BidRow> existing = repo.bids(a.id());
        BigDecimal highest = existing.stream().filter(b -> "VALID".equals(b.status())).map(BidRow::discount).max(BigDecimal::compareTo).orElse(null);
        boolean ascending = a.type() == AuctionType.OPEN_ASCENDING_DISCOUNT;
        BidRules.validate(bounds, commission, discount, ascending, highest);
        if (!ascending) { // a sealed bid replaces the ticket's earlier one
            for (BidRow b : existing) {
                if (b.ticketId() == t.ticketId() && "VALID".equals(b.status())) {
                    repo.withdrawBid(b.id(), "REPLACED", actor);
                }
            }
        }
        int seq = repo.nextBidSeq(a.id());
        repo.insertBid(a.id(), t.ticketId(), seq, discount, actor);
        audit.log("AUCTION", code + "#" + cycleNo, "BID_PLACED", actor, "ticket " + t.ticketNo() + " bid #" + seq + (ascending ? " " + discount.toPlainString() : " (sealed)"));
        audit.event("BidPlaced", "GROUP", code,
                Json.obj("groupCode", code, "cycleNo", cycleNo, "ticketNo", t.ticketNo(), "customerId", t.customerId(), "bidSeq", seq,
                        "discountAmount", ascending ? discount : null, "sealed", ascending ? null : Boolean.TRUE, "placedBy", actor));
        idempotency.complete(idemKey, String.valueOf(seq));
        return bidView(code, cycleNo, seq);
    }

    @Transactional
    public BidView withdrawBid(String code, int cycleNo, int bidSeq, WithdrawBidRequest req, String idemKey, String actor) {
        String reason = req == null || req.reason() == null ? "" : req.reason().trim();
        if (reason.isEmpty() || reason.length() > 120) {
            throw ChitException.badRequest("VALIDATION_FAILED", "reason is required and may be at most 120 characters");
        }
        Replay replay = idempotency.begin(idemKey, OP_WITHDRAW, Hashing.sha256(code + "|" + cycleNo + "|" + bidSeq + "|" + reason));
        if (replay != null) {
            return bidView(code, cycleNo, bidSeq).asReplay();
        }
        GroupRow g = lockActive(code);
        CycleInfo c = cycleOf(g, cycleNo);
        AuctionRow a = openAuction(c);
        BidRow b = repo.bids(a.id()).stream().filter(x -> x.bidSeq() == bidSeq).findFirst()
                .orElseThrow(() -> ChitException.notFound("Bid", code + " cycle " + cycleNo + " bid " + bidSeq));
        if (!"VALID".equals(b.status())) {
            throw ChitException.conflict("ILLEGAL_TRANSITION", "Bid " + bidSeq + " is already " + b.status());
        }
        repo.withdrawBid(b.id(), reason, actor);
        audit.log("AUCTION", code + "#" + cycleNo, "BID_WITHDRAWN", actor, "bid #" + bidSeq + " of ticket " + b.ticketNo() + ": " + reason);
        audit.event("BidWithdrawn", "GROUP", code, Json.obj("groupCode", code, "cycleNo", cycleNo, "ticketNo", b.ticketNo(), "bidSeq", bidSeq,
                "reason", reason, "withdrawnBy", actor));
        idempotency.complete(idemKey, String.valueOf(bidSeq));
        return bidView(code, cycleNo, bidSeq);
    }

    // ---- close ----------------------------------------------------------------------------------------------------

    /** The winner and how it was decided. */
    private record Pick(TicketFlag winner, BigDecimal discount, String basis, Long winningBidId, Long drawId) {
    }

    @Transactional
    public AuctionView close(String code, int cycleNo, String idemKey, String actor, LocalDate today, WorkingDayCalendar calendar) {
        Replay replay = idempotency.begin(idemKey, OP_CLOSE, Hashing.sha256(code + "|" + cycleNo));
        if (replay != null) {
            return closeReplay(code, cycleNo, replay.resultRef()).asReplay();
        }
        GroupRow g = lockActive(code);
        ChitRules rules = groups.rulesOf(g);
        CycleInfo c = cycleOf(g, cycleNo);
        Lifecycles.CYCLE.require(c.status(), CycleStatus.AUCTION_CLOSED);
        AuctionRow a = repo.liveAuctionForUpdate(c.id())
                .orElseThrow(() -> ChitException.conflict("AUCTION_NOT_OPEN", "Cycle " + cycleNo + " has no open auction"));
        if (!a.isOpen()) {
            throw ChitException.conflict("ILLEGAL_TRANSITION", "The auction of cycle " + cycleNo + " is already " + a.status());
        }
        List<TicketFlag> flags = repo.ticketFlags(g.id());
        boolean last = cycleNo == g.cycleCount();
        BigDecimal pot = Money.scale(g.chitValue());
        BidRules.Bounds bounds = BidRules.bounds(pot, rules.minDiscountPct(), rules.maxDiscountPct());

        Pick pick;
        if (last) {
            List<TicketFlag> left = flags.stream().filter(t -> !t.hasWon()).toList();
            if (left.size() != 1) {
                throw ChitException.conflict("LAST_CYCLE_CANDIDATES", "The last cycle needs exactly one ticket that has not won; there are " + left.size()
                        + (left.isEmpty() ? "" : " (tickets " + left.stream().map(t -> String.valueOf(t.ticketNo())).toList() + ")"));
            }
            pick = new Pick(left.get(0), Money.ZERO, "LAST_CYCLE", null, null);
        } else {
            List<TicketFlag> eligible = flags.stream().filter(t -> t.status() == TicketStatus.ACTIVE && !t.hasWon()).toList();
            if (eligible.isEmpty()) {
                throw ChitException.conflict("NO_ELIGIBLE_TICKETS", "No ticket can win cycle " + cycleNo + ": every live ticket has won or is not in good standing");
            }
            pick = switch (a.type()) {
                case OPEN_ASCENDING_DISCOUNT, SEALED_BID -> pickByBids(g, rules, c, a, eligible, bounds, actor);
                case LOTTERY -> drawWinner(g, c, eligible, "LOTTERY_AUCTION", bounds.min(), "LOTTERY", actor);
                case FIXED_ORDER -> fixedOrderWinner(g, flags, eligible, bounds.min(), actor);
            };
            if (pick == null) {
                rollOver(g, rules, c, a, actor, today, calendar, code);
                idempotency.complete(idemKey, String.valueOf(a.id()));
                return reads.auctionAttempt(code, cycleNo, a.attempt());
            }
        }

        // ---- settle the pot --------------------------------------------------------------------------------------
        TicketFlag winner = pick.winner();
        List<DividendBeneficiaryPicker.Candidate> cands = flags.stream()
                .map(t -> new DividendBeneficiaryPicker.Candidate(t.ticketNo(), false, t.status() == TicketStatus.DEFAULTED, t.hasWon())).toList();
        List<Integer> beneficiaries = last ? List.of() : DividendBeneficiaryPicker.pick(cands, rules.dividendBeneficiaries(),
                Boolean.TRUE.equals(rules.defaulterLosesDividend()), winner.ticketNo());
        AuctionSettlement s = last ? AuctionSettlementCalculator.settleLastCycle(pot, rules.commissionPct(), rules.lastCycleCommissionRule())
                : AuctionSettlementCalculator.settle(pot, pick.discount(), rules.commissionPct(), beneficiaries);
        if (!s.isBalanced()) {
            throw new ChitException("SETTLEMENT_UNBALANCED", "The settlement of cycle " + cycleNo + " does not add up to the chit value");
        }
        Map<Integer, Long> idByNo = new HashMap<>();
        flags.forEach(t -> idByNo.put(t.ticketNo(), t.ticketId()));
        SortedMap<Integer, BigDecimal> shares = new TreeMap<>();
        s.dividendShares().forEach((no, amount) -> {
            if (amount.signum() > 0) {
                shares.put(no, amount);
            }
        });
        PostResult posted = ledger.post(JournalTemplates.auctionSettled("AUCTION:" + a.id(), s, JournalTemplates.dividendsByTicketId(shares, idByNo)),
                new PostingContext(g.id(), today, c.id(), "AUCTION", code + "#" + cycleNo, actor));

        // ---- record the result -----------------------------------------------------------------------------------
        repo.closeAuction(a.id(), actor, pick.winningBidId(), pick.basis(), s.discount(), pick.drawId(), posted.journalId());
        repo.recordCycleResult(c.id(), winner.ticketId(), s.discount(), s.prize(), s.commission(), s.dividendPool(), today);
        declareDividends(g, rules, c, shares, idByNo, today, actor, code);

        audit.log("CYCLE", code + "#" + cycleNo, "AUCTION_CLOSED", actor, "ticket " + winner.ticketNo() + " wins " + s.prize().toPlainString()
                + " (" + pick.basis() + "), discount " + s.discount().toPlainString());
        audit.event("AuctionClosed", "GROUP", code,
                Json.obj("groupCode", code, "cycleNo", cycleNo, "auctionType", a.type().name(), "closeBasis", pick.basis(),
                        "winnerTicketNo", winner.ticketNo(), "winnerCustomerId", winner.customerId(), "discount", s.discount(), "prize", s.prize(),
                        "commission", s.commission(), "dividendPool", s.dividendPool(), "lastCycle", last ? Boolean.TRUE : null,
                        "drawId", pick.drawId(), "businessDate", today.toString(), "closedBy", actor));
        idempotency.complete(idemKey, String.valueOf(a.id()));
        return reads.auction(code, cycleNo);
    }

    /** What a repeated close returns: the attempt the first call closed (which may be a rolled-over one). */
    private AuctionView closeReplay(String code, int cycleNo, String auctionRef) {
        GroupRow g = groups.require(code);
        CycleInfo c = cycleOf(g, cycleNo);
        long id = Long.parseLong(auctionRef);
        int attempt = repo.auctionsOfCycle(c.id()).stream().filter(x -> x.id() == id).map(AuctionRow::attempt).findFirst().orElse(1);
        return reads.auctionAttempt(code, cycleNo, attempt);
    }

    // ---- winner determination -------------------------------------------------------------------------------------

    /** Picks by bids, applying the no-bid rule when there are none. Returns null when the cycle is to roll over. */
    private Pick pickByBids(GroupRow g, ChitRules rules, CycleInfo c, AuctionRow a, List<TicketFlag> eligible, BidRules.Bounds bounds, String actor) {
        Map<Long, TicketFlag> eligibleById = new HashMap<>();
        eligible.forEach(t -> eligibleById.put(t.ticketId(), t));
        List<BidRow> valid = repo.bids(a.id()).stream().filter(b -> "VALID".equals(b.status()) && eligibleById.containsKey(b.ticketId())).toList();
        if (valid.isEmpty()) {
            switch (rules.noBidRule()) {
                case LOTTERY:
                    return drawWinner(g, c, eligible, "NO_BID", bounds.min(), "NO_BID_LOTTERY", actor);
                case FOREMAN_DEFAULT_DISCOUNT:
                    return drawWinner(g, c, eligible, "NO_BID", Money.percentOf(Money.scale(g.chitValue()), rules.foremanDefaultDiscountPct()),
                            "NO_BID_FOREMAN_DEFAULT", actor);
                case ROLL_OVER_CYCLE:
                default:
                    return null;
            }
        }
        List<WinnerSelector.Bid> bids = valid.stream()
                .map(b -> new WinnerSelector.Bid(b.id(), b.bidSeq(), b.ticketId(), b.ticketNo(), b.discount(), b.bidTime().toInstant())).toList();
        WinnerSelector.Selection sel = WinnerSelector.select(bids, rules.tieBreakRule(), seeds.next());
        Long drawId = sel.draw() == null ? null : repo.insertDraw(g.id(), c.id(), "TIE_BREAK", sel.draw(), actor);
        TicketFlag winner = eligibleById.get(sel.winner().ticketId());
        return new Pick(winner, sel.winner().discount(), sel.basis().name(), sel.winner().bidId(), drawId);
    }

    /** A recorded, reproducible draw among the eligible tickets. */
    private Pick drawWinner(GroupRow g, CycleInfo c, List<TicketFlag> eligible, String purpose, BigDecimal discount, String basis, String actor) {
        List<Integer> nos = eligible.stream().map(TicketFlag::ticketNo).toList();
        Draw.Outcome out = Draw.pick(seeds.next(), nos);
        long drawId = repo.insertDraw(g.id(), c.id(), purpose, out, actor);
        int winnerNo = out.result().get(0);
        TicketFlag winner = eligible.stream().filter(t -> t.ticketNo() == winnerNo).findFirst().orElseThrow();
        return new Pick(winner, discount, basis, null, drawId);
    }

    /** FIXED_ORDER: the order of the whole group is drawn once and stored; each cycle takes the next eligible ticket in it. */
    private Pick fixedOrderWinner(GroupRow g, List<TicketFlag> all, List<TicketFlag> eligible, BigDecimal discount, String actor) {
        DrawRow stored = repo.fixedOrderDraw(g.id()).orElse(null);
        long drawId;
        List<Integer> order;
        if (stored == null) {
            Draw.Outcome out = Draw.order(seeds.next(), all.stream().map(TicketFlag::ticketNo).toList());
            drawId = repo.insertDraw(g.id(), null, "FIXED_ORDER", out, actor);
            order = out.result();
        } else {
            drawId = stored.id();
            order = AuctionReadService.drawView(stored).result();
        }
        TicketFlag winner = null;
        for (int no : order) {
            for (TicketFlag t : eligible) {
                if (winner == null && t.ticketNo() == no) {
                    winner = t;
                }
            }
        }
        if (winner == null) { // a ticket added after the order was drawn
            winner = eligible.stream().min(java.util.Comparator.comparingInt(TicketFlag::ticketNo)).orElseThrow();
        }
        return new Pick(winner, discount, "FIXED_ORDER", null, drawId);
    }

    // ---- roll-over ------------------------------------------------------------------------------------------------

    private void rollOver(GroupRow g, ChitRules rules, CycleInfo c, AuctionRow a, String actor, LocalDate today, WorkingDayCalendar calendar, String code) {
        if (repo.laterCycleStarted(g.id(), c.cycleNo())) {
            throw ChitException.conflict("ROLL_OVER_BLOCKED", "Cycle " + c.cycleNo() + " cannot roll over: a later cycle has already started collecting");
        }
        int e = repo.rollOversSoFar(g.id()) + 1;
        List<ScheduledCycle> generated = CycleCalendarGenerator.generate(rules.toBuilder().cycleCount(g.cycleCount() + e).build(), calendar);
        List<CycleInfo> cycles = repo.cycles(g.id());
        int shifted = 0;
        for (CycleInfo later : cycles) {
            if (later.cycleNo() > c.cycleNo()) {
                ScheduledCycle d = RollOver.datesOf(generated, later.cycleNo(), e);
                repo.setCycleDates(later.id(), d.dueDate(), d.auctionDate());
                shifted++;
            }
        }
        LocalDate newAuctionDate = RollOver.datesOf(generated, c.cycleNo(), e).auctionDate();
        repo.setCycleAuctionDate(c.id(), newAuctionDate);
        repo.rollOverAuction(a.id(), actor);
        Lifecycles.CYCLE.require(CycleStatus.AUCTION_OPEN, CycleStatus.COLLECTION_OPEN);
        repo.setCycleStatus(c.id(), CycleStatus.COLLECTION_OPEN);
        audit.log("CYCLE", code + "#" + c.cycleNo(), "AUCTION_ROLLED_OVER", actor, "no bid; auction moved to " + newAuctionDate + ", " + shifted + " later cycle(s) shifted");
        audit.event("AuctionRolledOver", "GROUP", code,
                Json.obj("groupCode", code, "cycleNo", c.cycleNo(), "attempt", a.attempt(), "rolloverNo", e, "newAuctionDate", newAuctionDate.toString(),
                        "laterCyclesShifted", shifted, "businessDate", today.toString(), "closedBy", actor));
    }

    // ---- dividends ------------------------------------------------------------------------------------------------

    private void declareDividends(GroupRow g, ChitRules rules, CycleInfo c, SortedMap<Integer, BigDecimal> shares, Map<Integer, Long> idByNo,
                                  LocalDate today, String actor, String code) {
        if (shares.isEmpty()) {
            return;
        }
        DividendMode mode = rules.dividendMode();
        BigDecimal total = Money.ZERO;
        BigDecimal toDues = Money.ZERO;
        List<Map<String, Object>> list = new ArrayList<>();
        NextCycle next = nextCycle(g, c);
        for (Map.Entry<Integer, BigDecimal> e : shares.entrySet()) {
            long ticketId = idByNo.get(e.getKey());
            BigDecimal amount = e.getValue();
            total = total.add(amount);
            boolean credit = mode == DividendMode.CREDIT_SUBSCRIBER_BALANCE;
            long id = repo.insertDividend(c.id(), ticketId, amount, mode, credit ? "APPLIED" : "DECLARED", today, credit ? today : null);
            if (credit) {
                toDues = toDues.add(payments.creditDividend(g.id(), c.id(), c.cycleNo(), id, ticketId, amount, rules.allocationOrder(), today, actor));
            } else if (mode == DividendMode.ADJUST_NEXT_INSTALMENT && next.started) {
                applyToStartedNext(g, c, next, id, ticketId, e.getKey(), amount, today, actor);
            }
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("ticketNo", e.getKey());
            row.put("amount", amount.toPlainString());
            list.add(row);
        }
        audit.event("DividendDeclared", "GROUP", code,
                Json.obj("groupCode", code, "cycleNo", c.cycleNo(), "dividendPool", total, "mode", mode.name(), "tickets", shares.size(),
                        "shares", list, "appliedToDues", mode == DividendMode.CREDIT_SUBSCRIBER_BALANCE ? toDues : null, "businessDate", today.toString()));
    }

    /** The cycle after {@code c}, and whether its instalments have already been raised. */
    private record NextCycle(CycleInfo cycle, boolean started) {
    }

    private NextCycle nextCycle(GroupRow g, CycleInfo c) {
        for (CycleInfo x : repo.cycles(g.id())) {
            if (x.cycleNo() == c.cycleNo() + 1) {
                return new NextCycle(x, x.status() != CycleStatus.SCHEDULED);
            }
        }
        return new NextCycle(null, false);
    }

    /**
     * The next cycle's instalments were raised before this auction closed, so the dividend cannot wait for the demand run:
     * it is set against that demand now if it fits (whole or not at all, as in the demand run).
     */
    private void applyToStartedNext(GroupRow g, CycleInfo c, NextCycle next, long dividendId, long ticketId, int ticketNo, BigDecimal amount,
                                    LocalDate today, String actor) {
        DemandRow d = collection.findDemandOf(next.cycle().id(), ticketId).orElse(null);
        if (d == null) {
            return;
        }
        DemandFigures f = d.figures();
        if (amount.compareTo(f.subscriptionOutstanding()) > 0) {
            return;
        }
        collection.saveFigures(d.id(), f.withDividendAdjustment(f.dividendAdjustment().add(amount)));
        collection.markDividendApplied(dividendId, d.id());
        ledger.post(JournalTemplates.dividendApplied("DIVAPPLY:" + dividendId, ticketId, amount),
                new PostingContext(g.id(), today, next.cycle().id(), "DIVIDEND", d.demandRef(), actor));
        audit.event("DividendApplied", "TICKET", g.groupCode() + "/" + ticketNo,
                Json.obj("groupCode", g.groupCode(), "ticketNo", ticketNo, "cycleNo", next.cycle().cycleNo(), "demandRef", d.demandRef(), "dividendCycleNo", c.cycleNo(), "amount", amount));
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    private GroupRow lockActive(String code) {
        GroupRow g = groups.lock(code);
        if (g.status() != GroupStatus.ACTIVE) {
            throw ChitException.conflict("GROUP_NOT_ACTIVE", "Group " + code + " is " + g.status() + "; auctions run while it is ACTIVE");
        }
        return g;
    }

    private CycleInfo cycleOf(GroupRow g, int cycleNo) {
        return repo.cycle(g.id(), cycleNo).orElseThrow(() -> ChitException.notFound("Cycle", g.groupCode() + " #" + cycleNo));
    }

    private AuctionRow openAuction(CycleInfo c) {
        if (c.status() != CycleStatus.AUCTION_OPEN) {
            throw ChitException.conflict("AUCTION_NOT_OPEN", "Cycle " + c.cycleNo() + " is " + c.status() + "; bids are taken while its auction is open");
        }
        AuctionRow a = repo.liveAuctionForUpdate(c.id()).orElseThrow(() -> ChitException.conflict("AUCTION_NOT_OPEN", "Cycle " + c.cycleNo() + " has no open auction"));
        if (!a.isOpen()) {
            throw ChitException.conflict("AUCTION_NOT_OPEN", "The auction of cycle " + c.cycleNo() + " is " + a.status());
        }
        return a;
    }

    private static void requireEligible(TicketFlag t) {
        if (t.hasWon()) {
            throw new ChitException("TICKET_NOT_ELIGIBLE", "Ticket " + t.ticketNo() + " has already won a cycle");
        }
        if (t.status() != TicketStatus.ACTIVE) {
            throw new ChitException("TICKET_NOT_ELIGIBLE", "Ticket " + t.ticketNo() + " is " + t.status() + "; only tickets in good standing can bid");
        }
    }

    private BidView bidView(String code, int cycleNo, int seq) {
        GroupRow g = groups.require(code);
        CycleInfo c = cycleOf(g, cycleNo);
        AuctionRow a = repo.liveAuction(c.id()).orElseThrow(() -> ChitException.notFound("Auction", code + " cycle " + cycleNo));
        BidRow b = repo.bids(a.id()).stream().filter(x -> x.bidSeq() == seq).findFirst()
                .orElseThrow(() -> ChitException.notFound("Bid", code + " cycle " + cycleNo + " bid " + seq));
        return new BidView(b.bidSeq(), b.ticketNo(), b.discount(), b.bidTime(), b.placedBy(), b.status(), b.withdrawnReason(),
                a.winningBidId() != null && a.winningBidId() == b.id(), false);
    }
}
