package com.chitfund.service.calc;

import com.chitfund.service.common.ChitException;
import com.chitfund.service.common.Money;

import java.math.BigDecimal;

/**
 * What a bid must satisfy. Bounds come from the frozen rules: the lowest and highest discount, as amounts of the chit value.
 * An ascending auction also needs each bid to beat the highest one so far.
 */
public final class BidRules {

    /** The lowest and highest discount a bid may carry, in rupees. */
    public record Bounds(BigDecimal min, BigDecimal max) {
    }

    private BidRules() {
    }

    public static Bounds bounds(BigDecimal chitValue, BigDecimal minDiscountPct, BigDecimal maxDiscountPct) {
        BigDecimal p = Money.scale(chitValue);
        return new Bounds(Money.percentOf(p, minDiscountPct), Money.percentOf(p, maxDiscountPct));
    }

    /** A bid amount as the caller sent it: present, positive and in whole paise. */
    public static BigDecimal clean(BigDecimal raw) {
        if (raw == null) {
            throw ChitException.badRequest("VALIDATION_FAILED", "discountAmount is required");
        }
        if (raw.signum() <= 0) {
            throw ChitException.badRequest("VALIDATION_FAILED", "discountAmount must be more than zero");
        }
        if (raw.stripTrailingZeros().scale() > 2) {
            throw ChitException.badRequest("VALIDATION_FAILED", "discountAmount may have at most two decimal places");
        }
        return Money.scale(raw);
    }

    /**
     * @param ascending      true for OPEN_ASCENDING_DISCOUNT: the bid must also be higher than {@code currentHighest}
     * @param currentHighest the highest valid bid so far, or null if there is none
     * @throws ChitException BID_OUT_OF_BOUNDS, BID_NOT_HIGHER
     */
    public static void validate(Bounds bounds, BigDecimal commission, BigDecimal discount, boolean ascending, BigDecimal currentHighest) {
        if (discount.compareTo(bounds.min()) < 0 || discount.compareTo(bounds.max()) > 0) {
            throw new ChitException("BID_OUT_OF_BOUNDS", "A bid must be between " + bounds.min().toPlainString() + " and "
                    + bounds.max().toPlainString() + "; " + discount.toPlainString() + " is not");
        }
        if (discount.compareTo(commission) < 0) {
            // cannot happen with validated rules (min discount % >= commission %), but a negative dividend pool is never accepted
            throw new ChitException("BID_OUT_OF_BOUNDS", "A bid of " + discount.toPlainString() + " is below the commission "
                    + commission.toPlainString() + "; the dividend pool would be negative");
        }
        if (ascending && currentHighest != null && discount.compareTo(currentHighest) <= 0) {
            throw new ChitException("BID_NOT_HIGHER", "In an ascending auction a bid must beat the highest so far ("
                    + currentHighest.toPlainString() + "); " + discount.toPlainString() + " does not");
        }
    }
}
