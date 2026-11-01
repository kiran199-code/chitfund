package com.chitfund.e2e;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Money arithmetic for expectations. Compared to the paisa; the platform's own rounding at a half paisa is not assumed, so a 0.01 slack is allowed. */
public final class Money {
    public static final BigDecimal ONE_PAISA = new BigDecimal("0.01");

    private Money() {
    }

    public static BigDecimal pct(BigDecimal base, BigDecimal pct) {
        return base.multiply(pct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    public static boolean same(BigDecimal a, BigDecimal b) {
        return a != null && b != null && a.subtract(b).abs().compareTo(BigDecimal.ZERO) == 0;
    }

    /** Equal to within one paisa. */
    public static boolean near(BigDecimal a, BigDecimal b) {
        return a != null && b != null && a.subtract(b).abs().compareTo(ONE_PAISA) <= 0;
    }

    public static BigDecimal zeroIfNull(BigDecimal a) {
        return a == null ? BigDecimal.ZERO : a;
    }

    /**
     * Discounts for {@code n} ascending bids strictly between min and max: whole rupees, each higher than the last, none equal to a bound.
     * Fewer are returned when the band is too narrow for {@code n} whole rupees.
     */
    public static java.util.List<BigDecimal> ascendingBids(BigDecimal min, BigDecimal max, int n) {
        java.util.List<BigDecimal> out = new java.util.ArrayList<>();
        BigDecimal lo = min.setScale(0, RoundingMode.CEILING);
        if (lo.compareTo(min) == 0) lo = lo.add(BigDecimal.ONE);
        BigDecimal hi = max.setScale(0, RoundingMode.FLOOR);
        if (hi.compareTo(max) == 0) hi = hi.subtract(BigDecimal.ONE);
        if (hi.compareTo(lo) < 0) return out;
        BigDecimal span = hi.subtract(lo);
        BigDecimal step = n <= 1 ? BigDecimal.ZERO : span.divide(BigDecimal.valueOf(n - 1), 0, RoundingMode.FLOOR);
        if (n > 1 && step.signum() == 0) step = BigDecimal.ONE;
        BigDecimal v = lo;
        for (int i = 0; i < n && v.compareTo(hi) <= 0; i++) {
            out.add(v);
            v = v.add(step.signum() == 0 ? BigDecimal.ONE : step);
        }
        return out;
    }
}
