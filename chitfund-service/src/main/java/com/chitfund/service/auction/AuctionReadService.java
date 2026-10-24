package com.chitfund.service.auction;

import com.chitfund.service.auction.AuctionDtos.AuctionView;
import com.chitfund.service.auction.AuctionDtos.BidView;
import com.chitfund.service.auction.AuctionDtos.CoverView;
import com.chitfund.service.auction.AuctionDtos.DividendView;
import com.chitfund.service.auction.AuctionDtos.DrawView;
import com.chitfund.service.auction.AuctionDtos.GateView;
import com.chitfund.service.auction.AuctionDtos.LegView;
import com.chitfund.service.auction.AuctionDtos.LiquidityOutcome;
import com.chitfund.service.auction.AuctionDtos.PayoutView;
import com.chitfund.service.auction.AuctionDtos.PoolPositionView;
import com.chitfund.service.auction.AuctionDtos.SettlementView;
import com.chitfund.service.auction.AuctionDtos.UnpaidView;
import com.chitfund.service.auction.AuctionModels.AuctionRow;
import com.chitfund.service.auction.AuctionModels.BidRow;
import com.chitfund.service.auction.AuctionModels.CycleInfo;
import com.chitfund.service.auction.AuctionModels.DividendRow;
import com.chitfund.service.auction.AuctionModels.DrawRow;
import com.chitfund.service.auction.AuctionModels.PayoutRow;
import com.chitfund.service.auction.AuctionModels.TicketFlag;
import com.chitfund.service.calc.BidRules;
import com.chitfund.service.calc.LiquidityDecision;
import com.chitfund.service.calc.PoolPosition;
import com.chitfund.service.calc.QuorumGate;
import com.chitfund.service.common.ChitException;
import com.chitfund.service.common.Money;
import com.chitfund.service.domain.AuctionType;
import com.chitfund.service.domain.CycleStatus;
import com.chitfund.service.domain.GroupStatus;
import com.chitfund.service.domain.PayoutLiquidityRule;
import com.chitfund.service.domain.rules.ChitRules;
import com.chitfund.service.group.GroupModels.GroupRow;
import com.chitfund.service.group.GroupService;
import com.chitfund.service.ledger.AccountType;
import com.chitfund.service.ledger.LedgerDtos.AccountBalance;
import com.chitfund.service.ledger.LedgerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Read side of auctions, payouts, dividends and the pool's liquidity. Nothing here changes data. */
@Service
public class AuctionReadService {

    private final GroupService groups;
    private final AuctionRepository repo;
    private final LedgerRepository ledger;

    public AuctionReadService(GroupService groups, AuctionRepository repo, LedgerRepository ledger) {
        this.groups = groups;
        this.repo = repo;
        this.ledger = ledger;
    }

    // ---- the pool -------------------------------------------------------------------------------------------------

    /** The pool's cash and what it owes, summed from the group's ledger accounts. */
    public PoolPosition position(long groupId) {
        BigDecimal cash = Money.ZERO;
        BigDecimal commission = Money.ZERO;
        BigDecimal lateFee = Money.ZERO;
        BigDecimal dividend = Money.ZERO;
        BigDecimal prize = Money.ZERO;
        BigDecimal advance = Money.ZERO;
        for (AccountBalance a : ledger.balances(groupId)) {
            if (a.accountType() == AccountType.POOL_CASH) {
                cash = cash.add(a.balance());
            } else if (a.accountType() == AccountType.COMMISSION_INCOME) {
                commission = commission.add(a.balance());
            } else if (a.accountType() == AccountType.LATE_FEE_INCOME) {
                lateFee = lateFee.add(a.balance());
            } else if (a.accountType() == AccountType.DIVIDEND_PAYABLE) {
                dividend = dividend.add(a.balance());
            } else if (a.accountType() == AccountType.PRIZE_PAYABLE) {
                prize = prize.add(a.balance());
            } else if (a.accountType() == AccountType.SUBSCRIBER_RECEIVABLE && a.balance().signum() < 0) {
                advance = advance.add(a.balance().negate());
            }
        }
        return new PoolPosition(cash, commission, lateFee, dividend, prize, advance);
    }

    @Transactional(readOnly = true)
    public PoolPositionView liquidity(String code, Integer cycleNo) {
        GroupRow g = groups.require(code);
        ChitRules rules = groups.rulesOf(g);
        PoolPosition pos = position(g.id());
        BigDecimal remaining = Money.ZERO;
        if (cycleNo != null) {
            CycleInfo c = repo.cycle(g.id(), cycleNo).orElseThrow(() -> ChitException.notFound("Cycle", code + " #" + cycleNo));
            remaining = repo.payoutOfCycle(c.id()).map(PayoutRow::remainingGross)
                    .orElse(c.prize() != null && c.status() == CycleStatus.AUCTION_CLOSED ? c.prize() : Money.ZERO);
        }
        BigDecimal available = pos.availableFor(remaining);
        List<LiquidityOutcome> outcomes = new ArrayList<>();
        if (remaining.signum() > 0) {
            for (PayoutLiquidityRule r : PayoutLiquidityRule.values()) {
                LiquidityDecision.Decision d = LiquidityDecision.decide(r, remaining, available);
                outcomes.add(new LiquidityOutcome(r.name(), r == rules.payoutLiquidityRule(), d.action().name(), d.payNow(), d.floatAmount(),
                        d.balanceLater(), d.shortfall()));
            }
        }
        return new PoolPositionView(pos.poolCash(), pos.commissionIncome(), pos.lateFeeIncome(), pos.dividendPayable(), pos.prizePayable(),
                pos.advanceHeld(), available, pos.surplus(), remaining, outcomes);
    }

    // ---- the auction ----------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public AuctionView auction(String code, int cycleNo) {
        GroupRow g = groups.require(code);
        return auctionView(g, groups.rulesOf(g), cycle(g, cycleNo));
    }

    /** One attempt of a cycle's auction, including a rolled-over one (what a close that found no bid returns). */
    @Transactional(readOnly = true)
    public AuctionView auctionAttempt(String code, int cycleNo, int attempt) {
        GroupRow g = groups.require(code);
        CycleInfo c = cycle(g, cycleNo);
        Optional<AuctionRow> row = repo.auctionsOfCycle(c.id()).stream().filter(a -> a.attempt() == attempt).findFirst();
        return auctionView(g, groups.rulesOf(g), c, row);
    }

    AuctionView auctionView(GroupRow g, ChitRules rules, CycleInfo cycle) {
        return auctionView(g, rules, cycle, repo.liveAuction(cycle.id()));
    }

    private AuctionView auctionView(GroupRow g, ChitRules rules, CycleInfo cycle, Optional<AuctionRow> live) {
        List<AuctionRow> all = repo.auctionsOfCycle(cycle.id());
        List<Integer> rolled = all.stream().filter(a -> "ROLLED_OVER".equals(a.status())).map(AuctionRow::attempt).toList();
        BidRules.Bounds bounds = BidRules.bounds(g.chitValue(), rules.minDiscountPct(), rules.maxDiscountPct());
        boolean lastCycle = cycle.cycleNo() == g.cycleCount();
        List<TicketFlag> tickets = repo.ticketFlags(g.id());
        Integer winnerNo = null;
        Long winnerCustomer = null;
        if (cycle.winnerTicketId() != null) {
            for (TicketFlag t : tickets) {
                if (t.ticketId() == cycle.winnerTicketId()) {
                    winnerNo = t.ticketNo();
                    winnerCustomer = t.customerId();
                }
            }
        }
        SettlementView settlement = null;
        if (cycle.prize() != null) {
            List<DividendView> dividends = repo.dividendsOfCycle(cycle.id()).stream().map(AuctionReadService::dividendView).toList();
            BigDecimal discount = Money.nz(cycle.discount());
            settlement = new SettlementView(g.chitValue(), discount, cycle.prize(), cycle.commission(), cycle.dividendPool(),
                    g.chitValue().compareTo(cycle.prize().add(cycle.commission()).add(cycle.dividendPool())) == 0, dividends);
        }
        if (live.isEmpty()) {
            return new AuctionView(g.groupCode(), cycle.cycleNo(), cycle.status().name(), cycle.dueDate(), cycle.auctionDate(),
                    rules.auctionType().name(), lastCycle, null, "NOT_OPENED", null, null, null, null, null, null, bounds.min(), bounds.max(),
                    null, 0, false, List.of(), winnerNo, winnerCustomer, settlement, null, rolled, false);
        }
        AuctionRow a = live.get();
        boolean hidden = a.type() == AuctionType.SEALED_BID && a.isOpen();
        List<BidRow> rows = repo.bids(a.id());
        List<BidView> bids = new ArrayList<>();
        BigDecimal highest = null;
        int valid = 0;
        for (BidRow b : rows) {
            boolean ok = "VALID".equals(b.status());
            if (ok) {
                valid++;
                if (highest == null || b.discount().compareTo(highest) > 0) {
                    highest = b.discount();
                }
            }
            bids.add(new BidView(b.bidSeq(), b.ticketNo(), hidden ? null : b.discount(), b.bidTime(), b.placedBy(), b.status(), b.withdrawnReason(),
                    a.winningBidId() != null && a.winningBidId() == b.id(), false));
        }
        DrawView draw = a.drawRecordId() == null ? null : repo.draw(a.drawRecordId()).map(AuctionReadService::drawView).orElse(null);
        return new AuctionView(g.groupCode(), cycle.cycleNo(), cycle.status().name(), cycle.dueDate(), cycle.auctionDate(), a.type().name(), lastCycle,
                a.attempt(), a.status(), a.openedAt(), a.openedBy(), a.closedAt(), a.closedBy(), a.closeBasis(), a.gateNote(), bounds.min(), bounds.max(),
                hidden ? null : highest, valid, hidden, bids, winnerNo, winnerCustomer, settlement, draw, rolled, false);
    }

    @Transactional(readOnly = true)
    public GateView gate(String code, int cycleNo, LocalDate today) {
        GroupRow g = groups.require(code);
        ChitRules rules = groups.rulesOf(g);
        CycleInfo c = cycle(g, cycleNo);
        QuorumGate.Evaluation ev = QuorumGate.evaluate(rules.auctionQuorumRule(), rules.quorumMinCollectionPct(), cycleNo,
                repo.demandPositions(g.id(), cycleNo));
        String blocker = null;
        if (g.status() != GroupStatus.ACTIVE) {
            blocker = "Group is " + g.status();
        } else if (c.status() != CycleStatus.COLLECTION_OPEN) {
            blocker = "Cycle " + cycleNo + " is " + c.status() + "; an auction opens from COLLECTION_OPEN";
        } else if (today.isBefore(c.auctionDate())) {
            blocker = "The auction date is " + c.auctionDate();
        } else if (repo.cyclesBeforeStillOpen(g.id(), cycleNo) > 0) {
            blocker = "An earlier cycle has not closed its auction";
        }
        List<UnpaidView> unpaid = ev.unpaid().stream().map(u -> new UnpaidView(u.ticketNo(), u.amount())).toList();
        return new GateView(code, cycleNo, c.status().name(), c.auctionDate(), ev.rule().name(), ev.open(), !today.isBefore(c.auctionDate()), ev.reason(),
                ev.due(), ev.collected(), ev.collectedPct(), ev.shortfall(), unpaid, cycleNo == g.cycleCount(), blocker);
    }

    // ---- payouts --------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PayoutView payout(String code, int cycleNo) {
        GroupRow g = groups.require(code);
        CycleInfo c = cycle(g, cycleNo);
        PayoutRow p = repo.payoutOfCycle(c.id()).orElseThrow(() -> ChitException.notFound("Payout", code + " cycle " + cycleNo));
        return payoutView(code, p);
    }

    @Transactional(readOnly = true)
    public List<PayoutView> payouts(String code) {
        GroupRow g = groups.require(code);
        return repo.payoutsOfGroup(g.id()).stream().map(p -> payoutView(code, p)).toList();
    }

    PayoutView payoutView(String code, PayoutRow p) {
        List<LegView> legs = repo.legs(p.id()).stream().map(l -> new LegView(l.legNo(), l.kind(), l.amount(), l.holdAmount(), l.instructionRef(),
                l.businessDate(), l.createdAt(), l.createdBy())).toList();
        return new PayoutView(code, p.cycleNo(), p.ticketNo(), p.customerId(), p.gross(), p.hold(), p.net(), p.status(), p.madeBy(), p.checkedBy(),
                p.decisionNote(), p.approvedAt(), p.payoutRef(), p.accountRef(), p.disbursedAt(), p.disbursedBy(), p.paidGross(), p.paidHold(),
                p.remainingGross(), p.floatAmount(), p.floatRepaid(), legs, false);
    }

    // ---- dividends, draws, cover ----------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<DividendView> dividends(String code, Integer ticketNo) {
        GroupRow g = groups.require(code);
        return repo.dividendsOfGroup(g.id(), ticketNo).stream().map(AuctionReadService::dividendView).toList();
    }

    @Transactional(readOnly = true)
    public List<DrawView> draws(String code) {
        GroupRow g = groups.require(code);
        return repo.drawsOfGroup(g.id()).stream().map(AuctionReadService::drawView).toList();
    }

    @Transactional(readOnly = true)
    public List<CoverView> covers(String code) {
        GroupRow g = groups.require(code);
        return repo.coversOfGroup(g.id()).stream().map(c -> new CoverView(c.cycleNo(), c.ticketNo(), c.amount(), c.recovered(), c.status(), c.coveredOn())).toList();
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    CycleInfo cycle(GroupRow g, int cycleNo) {
        return repo.cycle(g.id(), cycleNo).orElseThrow(() -> ChitException.notFound("Cycle", g.groupCode() + " #" + cycleNo));
    }

    static DividendView dividendView(DividendRow d) {
        return new DividendView(d.cycleNo(), d.ticketNo(), d.amount(), d.mode().name(), d.status(), d.declaredOn(), d.settledOn());
    }

    static DrawView drawView(DrawRow d) {
        return new DrawView(d.id(), d.purpose(), d.algorithm(), d.seed(), ints(d.candidates()), ints(d.result()), d.createdAt(), d.createdBy());
    }

    private static List<Integer> ints(String json) {
        List<Integer> out = new ArrayList<>();
        String s = json == null ? "" : json.replace("[", "").replace("]", "").trim();
        if (!s.isEmpty()) {
            for (String part : s.split(",")) {
                out.add(Integer.parseInt(part.trim()));
            }
        }
        return out;
    }
}
