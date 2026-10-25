package com.chitfund.service.calc;

import com.chitfund.service.calc.RecoveryWaterfall.CoverBalance;
import com.chitfund.service.calc.RecoveryWaterfall.Plan;
import com.chitfund.service.calc.RecoveryWaterfall.WriteOffAmounts;
import com.chitfund.service.common.ChitException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoveryWaterfallTest {

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private static void eq(String expected, BigDecimal actual) {
        assertEquals(0, d(expected).compareTo(actual), "expected " + expected + " but was " + actual.toPlainString());
    }

    private static final List<CoverBalance> NO_COVERS = List.of();

    @Test
    void depositLargerThanArrearsPaysArrearsAndKeepsTheRest() {
        Plan p = RecoveryWaterfall.applySecurity(d("10000"), null, d("6000"), NO_COVERS);
        eq("6000", p.toArrears());
        assertTrue(p.toCovers().isEmpty());
        eq("4000", p.unused());
    }

    @Test
    void depositSmallerThanArrearsIsUsedUpAndNothingGoesToTheForeman() {
        Plan p = RecoveryWaterfall.applySecurity(d("4000"), null, d("6000"), List.of(new CoverBalance(1, d("5000"))));
        eq("4000", p.toArrears());
        assertTrue(p.toCovers().isEmpty(), "the pool is paid before the foreman");
        eq("0", p.unused());
    }

    @Test
    void whatIsLeftAfterArrearsGoesToTheCoversOldestFirst() {
        Plan p = RecoveryWaterfall.applySecurity(d("20000"), null, d("6000"),
                List.of(new CoverBalance(11, d("5000")), new CoverBalance(12, d("5000")), new CoverBalance(13, d("5000"))));
        eq("6000", p.toArrears());
        assertEquals(List.of(11L, 12L, 13L), List.copyOf(p.toCovers().keySet()));
        eq("5000", p.toCovers().get(11L));
        eq("5000", p.toCovers().get(12L));
        eq("4000", p.toCovers().get(13L));
        eq("0", p.unused());
    }

    @Test
    void aCapLimitsHowMuchIsApplied() {
        Plan p = RecoveryWaterfall.applySecurity(d("20000"), d("2500"), d("6000"), List.of(new CoverBalance(1, d("5000"))));
        eq("2500", p.toArrears());
        eq("17500", p.unused());
    }

    @Test
    void noArrearsMeansTheCoversAreServedDirectly() {
        Plan p = RecoveryWaterfall.applySecurity(d("3000"), null, d("0"), List.of(new CoverBalance(1, d("5000"))));
        eq("0", p.toArrears());
        eq("3000", p.toCovers().get(1L));
    }

    @Test
    void nothingOwedMeansNothingIsApplied() {
        Plan p = RecoveryWaterfall.applySecurity(d("3000"), null, d("0"), NO_COVERS);
        eq("0", p.total());
        eq("3000", p.unused());
    }

    @Test
    void appliedPlusUnusedAlwaysEqualsTheDepositAndNeverExceedsWhatIsOwed() {
        Random rnd = new Random(20261025L); // fixed seed so a failure replays
        for (int i = 0; i < 500; i++) {
            BigDecimal held = BigDecimal.valueOf(rnd.nextInt(2_000_000), 2);
            BigDecimal arrears = BigDecimal.valueOf(rnd.nextInt(1_000_000), 2);
            int n = rnd.nextInt(4);
            java.util.ArrayList<CoverBalance> covers = new java.util.ArrayList<>();
            BigDecimal coverTotal = BigDecimal.ZERO;
            for (int k = 0; k < n; k++) {
                BigDecimal b = BigDecimal.valueOf(rnd.nextInt(600_000), 2);
                covers.add(new CoverBalance(k + 1, b));
                coverTotal = coverTotal.add(b);
            }
            BigDecimal cap = rnd.nextBoolean() ? null : BigDecimal.valueOf(rnd.nextInt(2_000_000), 2);
            Plan p = RecoveryWaterfall.applySecurity(held, cap, arrears, covers);
            assertEquals(0, held.compareTo(p.total().add(p.unused())), "identity, seed iteration " + i);
            assertTrue(p.toArrears().compareTo(arrears) <= 0, "never more than the arrears");
            assertTrue(p.total().compareTo(arrears.add(coverTotal)) <= 0, "never more than owed in total");
            if (cap != null) {
                assertTrue(p.total().compareTo(cap) <= 0, "never more than the cap");
            }
            assertTrue(p.unused().signum() >= 0);
            for (Map.Entry<Long, BigDecimal> e : p.toCovers().entrySet()) {
                BigDecimal bal = covers.stream().filter(c -> c.coverId() == e.getKey()).findFirst().orElseThrow().balance();
                assertTrue(e.getValue().compareTo(bal) <= 0, "a cover never gets more than its balance");
            }
        }
    }

    @Test
    void repaymentIsSpreadOverCoversOldestFirst() {
        Map<Long, BigDecimal> out = RecoveryWaterfall.repayCovers(d("7500"),
                List.of(new CoverBalance(1, d("5000")), new CoverBalance(2, d("5000"))));
        eq("5000", out.get(1L));
        eq("2500", out.get(2L));
    }

    @Test
    void repayingMoreThanIsOwedIsRefused() {
        ChitException e = assertThrows(ChitException.class,
                () -> RecoveryWaterfall.repayCovers(d("5000.01"), List.of(new CoverBalance(1, d("5000")))));
        assertEquals("COVER_REPAYMENT_TOO_LARGE", e.code());
    }

    @Test
    void repayingWhenNothingIsOwedIsRefused() {
        ChitException e = assertThrows(ChitException.class, () -> RecoveryWaterfall.repayCovers(d("1"), NO_COVERS));
        assertEquals("COVER_REPAYMENT_TOO_LARGE", e.code());
    }

    @Test
    void repaymentMustBePositive() {
        assertThrows(ChitException.class, () -> RecoveryWaterfall.repayCovers(d("0"), List.of(new CoverBalance(1, d("5000")))));
    }

    @Test
    void writeOffAmountsAddUpSubscriptionFeesAndCovers() {
        DemandFigures a = new DemandFigures(d("5000"), d("0"), d("0"), d("150"), d("0"));        // owes 5000 + 150 fee
        DemandFigures b = new DemandFigures(d("5000"), d("300"), d("1000"), d("0"), d("0"));     // owes 3700 net of dividend
        WriteOffAmounts w = RecoveryWaterfall.writeOffAmounts(List.of(a, b),
                List.of(new CoverBalance(1, d("5000")), new CoverBalance(2, d("1200.50"))));
        eq("8700", w.subscription());
        eq("150", w.lateFees());
        eq("6200.50", w.covers());
        eq("15050.50", w.total());
        assertTrue(!w.isEmpty());
    }

    @Test
    void writeOffOfNothingIsEmpty() {
        assertTrue(RecoveryWaterfall.writeOffAmounts(List.of(), NO_COVERS).isEmpty());
    }
}
