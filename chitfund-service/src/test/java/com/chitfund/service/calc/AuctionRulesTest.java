package com.chitfund.service.calc;

import com.chitfund.service.calc.LiquidityDecision.Action;
import com.chitfund.service.calc.QuorumGate.DemandPosition;
import com.chitfund.service.calc.WinnerSelector.Basis;
import com.chitfund.service.calc.WinnerSelector.Bid;
import com.chitfund.service.calendar.CycleCalendarGenerator;
import com.chitfund.service.calendar.ScheduledCycle;
import com.chitfund.service.common.ChitException;
import com.chitfund.service.domain.DividendBeneficiaries;
import com.chitfund.service.domain.PayoutLiquidityRule;
import com.chitfund.service.domain.QuorumRule;
import com.chitfund.service.domain.TestRules;
import com.chitfund.service.domain.TieBreakRule;
import com.chitfund.service.domain.rules.ChitRules;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure rules of Phase 5: bids, winner, gate, liquidity, dividend beneficiaries, roll-over calendar. */
class AuctionRulesTest {

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    private static Bid bid(int seq, int ticketNo, String discount, long millis) {
        return new Bid(seq, seq, ticketNo * 100L, ticketNo, bd(discount), Instant.ofEpochMilli(millis));
    }

    // ---- bids ------------------------------------------------------------------------------------------------------

    @Test
    void boundsComeFromThePercentagesOfTheChitValue() {
        BidRules.Bounds b = BidRules.bounds(bd("100000"), bd("5"), bd("40"));
        assertEquals(0, bd("5000").compareTo(b.min()));
        assertEquals(0, bd("40000").compareTo(b.max()));
    }

    @Test
    void aBidOutsideTheBoundsIsRefused() {
        BidRules.Bounds b = BidRules.bounds(bd("100000"), bd("5"), bd("40"));
        BigDecimal commission = bd("5000");
        assertEquals("BID_OUT_OF_BOUNDS", assertThrows(ChitException.class, () -> BidRules.validate(b, commission, bd("4999.99"), false, null)).code());
        assertEquals("BID_OUT_OF_BOUNDS", assertThrows(ChitException.class, () -> BidRules.validate(b, commission, bd("40000.01"), false, null)).code());
        BidRules.validate(b, commission, bd("5000"), false, null);
        BidRules.validate(b, commission, bd("40000"), false, null);
    }

    @Test
    void anAscendingBidMustBeatTheHighest() {
        BidRules.Bounds b = BidRules.bounds(bd("100000"), bd("5"), bd("40"));
        BigDecimal commission = bd("5000");
        assertEquals("BID_NOT_HIGHER", assertThrows(ChitException.class, () -> BidRules.validate(b, commission, bd("12000"), true, bd("12000"))).code());
        assertEquals("BID_NOT_HIGHER", assertThrows(ChitException.class, () -> BidRules.validate(b, commission, bd("11000"), true, bd("12000"))).code());
        BidRules.validate(b, commission, bd("12000.01"), true, bd("12000"));
        BidRules.validate(b, commission, bd("12000"), false, bd("12000")); // sealed bids may tie
    }

    @Test
    void aBidAmountMustBeWholePaise() {
        assertEquals("VALIDATION_FAILED", assertThrows(ChitException.class, () -> BidRules.clean(bd("100.001"))).code());
        assertEquals("VALIDATION_FAILED", assertThrows(ChitException.class, () -> BidRules.clean(bd("0"))).code());
        assertEquals("VALIDATION_FAILED", assertThrows(ChitException.class, () -> BidRules.clean(null)).code());
        assertEquals(0, bd("100.10").compareTo(BidRules.clean(bd("100.1"))));
        assertEquals(0, bd("100").compareTo(BidRules.clean(bd("100.000"))));
    }

    // ---- winner ----------------------------------------------------------------------------------------------------

    @Test
    void theHighestDiscountWins() {
        WinnerSelector.Selection s = WinnerSelector.select(List.of(bid(1, 3, "10000", 1), bid(2, 7, "12000", 2), bid(3, 5, "11000", 3)),
                TieBreakRule.EARLIEST_BID_TIME, 1L);
        assertEquals(7, s.winner().ticketNo());
        assertEquals(Basis.HIGHEST_BID, s.basis());
        assertNull(s.draw());
    }

    @Test
    void aTieGoesToTheEarliestBid() {
        WinnerSelector.Selection s = WinnerSelector.select(List.of(bid(1, 9, "12000", 50), bid(2, 4, "12000", 20), bid(3, 6, "9000", 1)),
                TieBreakRule.EARLIEST_BID_TIME, 1L);
        assertEquals(4, s.winner().ticketNo());
        assertEquals(Basis.TIE_BREAK_EARLIEST, s.basis());
        assertEquals(List.of(4, 9), s.tiedTicketNos());
    }

    @Test
    void aTieAtTheSameInstantGoesToTheFirstRecordedBid() {
        WinnerSelector.Selection s = WinnerSelector.select(List.of(bid(1, 9, "12000", 20), bid(2, 4, "12000", 20)), TieBreakRule.EARLIEST_BID_TIME, 1L);
        assertEquals(9, s.winner().ticketNo());
    }

    @Test
    void aTieCanGoToTheLowestTicketNumber() {
        WinnerSelector.Selection s = WinnerSelector.select(List.of(bid(1, 9, "12000", 1), bid(2, 4, "12000", 2), bid(3, 12, "12000", 3)),
                TieBreakRule.LOWEST_TICKET_NO, 1L);
        assertEquals(4, s.winner().ticketNo());
        assertEquals(Basis.TIE_BREAK_LOWEST_NO, s.basis());
    }

    @Test
    void aTieCanBeDrawnAndTheDrawReplays() {
        List<Bid> bids = List.of(bid(1, 9, "12000", 1), bid(2, 4, "12000", 2), bid(3, 12, "12000", 3), bid(4, 2, "3000", 4));
        WinnerSelector.Selection a = WinnerSelector.select(bids, TieBreakRule.LOTTERY, 777L);
        WinnerSelector.Selection b = WinnerSelector.select(bids, TieBreakRule.LOTTERY, 777L);
        assertEquals(a.winner().ticketNo(), b.winner().ticketNo());
        assertEquals(Basis.TIE_BREAK_DRAW, a.basis());
        assertEquals(List.of(4, 9, 12), a.draw().candidates());
        assertEquals(a.winner().ticketNo(), Draw.pick(a.draw().seed(), a.draw().candidates()).result().get(0));
        assertTrue(List.of(4, 9, 12).contains(a.winner().ticketNo()));
    }

    @Test
    void severalBidsOfOneTicketAreNotATie() {
        WinnerSelector.Selection s = WinnerSelector.select(List.of(bid(1, 4, "12000", 1), bid(2, 4, "12000", 2), bid(3, 6, "9000", 3)),
                TieBreakRule.LOTTERY, 5L);
        assertEquals(Basis.HIGHEST_BID, s.basis());
        assertEquals(1, s.winner().bidSeq());
    }

    @Test
    void noBidsIsAnError() {
        assertEquals("NO_BIDS", assertThrows(ChitException.class, () -> WinnerSelector.select(List.of(), TieBreakRule.LOTTERY, 1L)).code());
    }

    // ---- the gate --------------------------------------------------------------------------------------------------

    private static List<DemandPosition> demands(int cycle, int tickets, String due, int[] paidTickets, String paidEach) {
        List<DemandPosition> l = new ArrayList<>();
        for (int t = 1; t <= tickets; t++) {
            boolean paid = false;
            for (int p : paidTickets) {
                paid |= p == t;
            }
            l.add(new DemandPosition(cycle * 1000L + t, t, t, cycle, bd(due), paid ? bd(paidEach) : BigDecimal.ZERO));
        }
        return l;
    }

    private static int[] upTo(int n) {
        int[] a = new int[n];
        for (int i = 0; i < n; i++) {
            a[i] = i + 1;
        }
        return a;
    }

    @Test
    void allPaidOpensOnlyWhenEveryoneHasPaid() {
        QuorumGate.Evaluation ok = QuorumGate.evaluate(QuorumRule.ALL_PAID, null, 1, demands(1, 4, "5000", upTo(4), "5000"));
        assertTrue(ok.open());
        QuorumGate.Evaluation no = QuorumGate.evaluate(QuorumRule.ALL_PAID, null, 1, demands(1, 4, "5000", upTo(3), "5000"));
        assertFalse(no.open());
        assertEquals(1, no.unpaid().size());
        assertEquals(4, no.unpaid().get(0).ticketNo());
        assertEquals(0, bd("5000").compareTo(no.shortfall()));
    }

    @Test
    void allPaidAlsoLooksAtEarlierCycles() {
        List<DemandPosition> l = new ArrayList<>(demands(1, 2, "5000", new int[]{1}, "5000"));
        l.addAll(demands(2, 2, "5000", upTo(2), "5000"));
        QuorumGate.Evaluation e = QuorumGate.evaluate(QuorumRule.ALL_PAID, null, 2, l);
        assertFalse(e.open());
        assertEquals(2, e.unpaid().get(0).ticketNo());
        assertTrue(e.cover().isEmpty()); // cycle 2 itself is fully paid
    }

    @Test
    void aPartPaymentLeavesTheRestUnpaid() {
        List<DemandPosition> l = new ArrayList<>(demands(1, 2, "5000", upTo(2), "5000"));
        l.set(1, new DemandPosition(1002, 2, 2, 1, bd("5000"), bd("4000")));
        QuorumGate.Evaluation e = QuorumGate.evaluate(QuorumRule.ALL_PAID, null, 1, l);
        assertFalse(e.open());
        assertEquals(0, bd("1000").compareTo(e.unpaid().get(0).amount()));
    }

    @Test
    void minimumCollectionComparesThePercentage() {
        // 20 tickets x 4,250 = 85,000 due; 18 paid = 76,500 = 90%
        List<DemandPosition> l = demands(2, 20, "4250", upTo(18), "4250");
        assertTrue(QuorumGate.evaluate(QuorumRule.MIN_COLLECTION_PCT, bd("90"), 2, l).open());
        assertFalse(QuorumGate.evaluate(QuorumRule.MIN_COLLECTION_PCT, bd("90.01"), 2, l).open());
        assertEquals(0, bd("90.00").compareTo(QuorumGate.evaluate(QuorumRule.MIN_COLLECTION_PCT, bd("90"), 2, l).collectedPct()));
    }

    @Test
    void theForemanCoverRuleAlwaysOpensAndListsWhatToCover() {
        QuorumGate.Evaluation e = QuorumGate.evaluate(QuorumRule.FOREMAN_COVERS_SHORTFALL, null, 1, demands(1, 4, "5000", upTo(2), "5000"));
        assertTrue(e.open());
        assertEquals(2, e.cover().size());
        assertEquals(0, bd("10000").compareTo(e.shortfall()));
    }

    @Test
    void noDemandsMeansTheGateStaysShut() {
        for (QuorumRule r : QuorumRule.values()) {
            assertFalse(QuorumGate.evaluate(r, bd("50"), 1, List.of()).open(), r.name());
        }
    }

    // ---- pool position and liquidity -------------------------------------------------------------------------------

    @Test
    void whenEveryonePaidTheClaimsEqualTheCashAndThePrizeIsCovered() {
        // fixture A then C's discount, everyone paid: cycle 2, D = 10,000: W = 90,000, C = 5,000, DP = 5,000.
        // cash 20,000 left after cycle 1 + 85,000 collected = 105,000; commission 5,000 + 5,000; dividend payable 5,000; prize payable 90,000
        PoolPosition p = new PoolPosition(bd("105000"), bd("10000"), bd("0"), bd("5000"), bd("90000"), bd("0"));
        assertEquals(0, bd("90000").compareTo(p.availableFor(bd("90000"))));
        assertEquals(0, BigDecimal.ZERO.compareTo(p.surplus()));
        assertEquals(Action.PAY_IN_FULL, LiquidityDecision.decide(PayoutLiquidityRule.BLOCK_UNTIL_FUNDED, bd("90000"), p.availableFor(bd("90000"))).action());
    }

    @Test
    void fixtureCaseCollectionShortBy5000() {
        PoolPosition p = new PoolPosition(bd("100000"), bd("10000"), bd("0"), bd("5000"), bd("90000"), bd("0"));
        BigDecimal available = p.availableFor(bd("90000"));
        assertEquals(0, bd("85000").compareTo(available));

        LiquidityDecision.Decision block = LiquidityDecision.decide(PayoutLiquidityRule.BLOCK_UNTIL_FUNDED, bd("90000"), available);
        assertEquals(Action.BLOCKED, block.action());
        assertEquals(0, bd("5000").compareTo(block.shortfall()));
        assertEquals(0, BigDecimal.ZERO.compareTo(block.payNow()));

        LiquidityDecision.Decision fl = LiquidityDecision.decide(PayoutLiquidityRule.FOREMAN_FLOATS, bd("90000"), available);
        assertEquals(Action.PAY_WITH_FLOAT, fl.action());
        assertEquals(0, bd("5000").compareTo(fl.floatAmount()));
        assertEquals(0, bd("90000").compareTo(fl.payNow()));

        LiquidityDecision.Decision part = LiquidityDecision.decide(PayoutLiquidityRule.PAY_AVAILABLE_AND_BALANCE_LATER, bd("90000"), available);
        assertEquals(Action.PAY_PART, part.action());
        assertEquals(0, bd("85000").compareTo(part.payNow()));
        assertEquals(0, bd("5000").compareTo(part.balanceLater()));
    }

    @Test
    void nothingAvailableMeansNothingIsPaidUnderThePartRule() {
        LiquidityDecision.Decision d = LiquidityDecision.decide(PayoutLiquidityRule.PAY_AVAILABLE_AND_BALANCE_LATER, bd("90000"), bd("-3000"));
        assertEquals(Action.BLOCKED, d.action());
        LiquidityDecision.Decision f = LiquidityDecision.decide(PayoutLiquidityRule.FOREMAN_FLOATS, bd("90000"), bd("-3000"));
        assertEquals(0, bd("90000").compareTo(f.floatAmount())); // the foreman floats the whole prize, not more
    }

    @Test
    void advanceMoneyAndOtherPrizesAreNotFreeCash() {
        PoolPosition p = new PoolPosition(bd("100000"), bd("0"), bd("0"), bd("0"), bd("90000"), bd("4000"));
        assertEquals(0, bd("96000").compareTo(p.availableFor(bd("90000"))));
        PoolPosition q = new PoolPosition(bd("100000"), bd("0"), bd("0"), bd("0"), bd("130000"), bd("0"));
        assertEquals(0, bd("60000").compareTo(q.availableFor(bd("90000")))); // 40,000 of another prize is ahead
    }

    @Test
    void holdIsSpreadOverTheLegsAndAddsUpExactly() {
        BigDecimal gross = bd("90000");
        BigDecimal hold = bd("4500");
        BigDecimal h1 = LiquidityDecision.holdForLeg(gross, hold, BigDecimal.ZERO, BigDecimal.ZERO, bd("30000"));
        assertEquals(0, bd("1500").compareTo(h1));
        BigDecimal h2 = LiquidityDecision.holdForLeg(gross, hold, bd("30000"), h1, bd("33333.33"));
        BigDecimal h3 = LiquidityDecision.holdForLeg(gross, hold, bd("63333.33"), h1.add(h2), bd("26666.67"));
        assertEquals(0, hold.compareTo(h1.add(h2).add(h3)));
        assertEquals(0, hold.compareTo(LiquidityDecision.holdForLeg(gross, hold, BigDecimal.ZERO, BigDecimal.ZERO, gross)));
    }

    // ---- dividend beneficiaries ------------------------------------------------------------------------------------

    private static List<DividendBeneficiaryPicker.Candidate> tickets() {
        return List.of(new DividendBeneficiaryPicker.Candidate(1, false, false, true),   // won earlier
                new DividendBeneficiaryPicker.Candidate(2, false, false, false),
                new DividendBeneficiaryPicker.Candidate(3, false, true, false),          // defaulted
                new DividendBeneficiaryPicker.Candidate(4, true, false, false),          // exited
                new DividendBeneficiaryPicker.Candidate(5, false, false, false));         // wins this cycle
    }

    @Test
    void allTicketsShareExceptExitedAndDefaulters() {
        assertEquals(List.of(1, 2, 5), DividendBeneficiaryPicker.pick(tickets(), DividendBeneficiaries.ALL_TICKETS, true, 5));
        assertEquals(List.of(1, 2, 3, 5), DividendBeneficiaryPicker.pick(tickets(), DividendBeneficiaries.ALL_TICKETS, false, 5));
    }

    @Test
    void nonPrizedOnlyLeavesOutEarlierWinnersAndThisCyclesWinner() {
        assertEquals(List.of(2), DividendBeneficiaryPicker.pick(tickets(), DividendBeneficiaries.NON_PRIZED_ONLY, true, 5));
        assertEquals(List.of(2, 3), DividendBeneficiaryPicker.pick(tickets(), DividendBeneficiaries.NON_PRIZED_ONLY, false, 5));
    }

    // ---- roll-over calendar ----------------------------------------------------------------------------------------

    @Test
    void aRollOverMovesTheAuctionAndEveryLaterCycleOnePeriod() {
        ChitRules rules = TestRules.baseline().ticketCount(4).cycleCount(4).chitValue(bd("40000")).subscription(bd("10000")).build();
        java.util.function.Predicate<java.time.LocalDate> weekday = d -> d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY;
        List<ScheduledCycle> before = CycleCalendarGenerator.generate(rules, weekday::test);
        List<ScheduledCycle> generated = CycleCalendarGenerator.generate(rules.toBuilder().cycleCount(5).build(), weekday::test);
        assertEquals(4, before.size());
        assertEquals(5, generated.size());
        // cycle 2 rolls over once: its auction becomes cycle 3's old auction date, cycle 3 and 4 take the dates of positions 4 and 5
        assertEquals(before.get(2).auctionDate(), RollOver.datesOf(generated, 2, 1).auctionDate());
        assertEquals(before.get(3).dueDate(), RollOver.datesOf(generated, 3, 1).dueDate());
        assertEquals(generated.get(4).dueDate(), RollOver.datesOf(generated, 4, 1).dueDate());
        // the rolled cycle's new auction is still before the next cycle's new due date
        assertTrue(RollOver.datesOf(generated, 2, 1).auctionDate().isBefore(RollOver.datesOf(generated, 3, 1).dueDate()));
        assertEquals("CALENDAR_UNAVAILABLE", assertThrows(ChitException.class, () -> RollOver.datesOf(generated, 5, 1)).code());
    }
}
