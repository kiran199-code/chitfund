package com.chitfund.service.calc;

import com.chitfund.service.common.Money;
import com.chitfund.service.domain.PayoutLiquidityRule;

import java.math.BigDecimal;

/**
 * What to do when a prize falls due. If the pool can pay it, it is paid whole. If not, the group's
 * {@code payout_liquidity_rule} decides: wait (BLOCK_UNTIL_FUNDED), the foreman puts in the difference
 * (FOREMAN_FLOATS), or what is available is paid now and the rest later (PAY_AVAILABLE_AND_BALANCE_LATER).
 */
public final class LiquidityDecision {

    public enum Action {
        /** The pool holds enough. */
        PAY_IN_FULL,
        /** Nothing is paid; the payout stays approved and is tried again when collections arrive. */
        BLOCKED,
        /** The foreman floats {@code floatAmount} into the pool and the whole prize is paid. */
        PAY_WITH_FLOAT,
        /** {@code payNow} is paid; {@code balanceLater} stays owed. */
        PAY_PART
    }

    /**
     * @param remaining   the unpaid part of the prize (gross)
     * @param available   cash free to pay it (may be negative)
     * @param shortfall   how much the pool is short; zero when it can pay
     * @param payNow      gross amount to pay in this step
     * @param floatAmount what the foreman must put in; zero unless PAY_WITH_FLOAT
     * @param balanceLater what stays owed after this step
     */
    public record Decision(Action action, BigDecimal remaining, BigDecimal available, BigDecimal shortfall, BigDecimal payNow,
                           BigDecimal floatAmount, BigDecimal balanceLater) {
    }

    private LiquidityDecision() {
    }

    public static Decision decide(PayoutLiquidityRule rule, BigDecimal remaining, BigDecimal available) {
        BigDecimal free = Money.max(available, Money.ZERO);
        if (available.compareTo(remaining) >= 0) {
            return new Decision(Action.PAY_IN_FULL, remaining, available, Money.ZERO, remaining, Money.ZERO, Money.ZERO);
        }
        BigDecimal shortfall = remaining.subtract(free);
        switch (rule) {
            case FOREMAN_FLOATS:
                return new Decision(Action.PAY_WITH_FLOAT, remaining, available, shortfall, remaining, shortfall, Money.ZERO);
            case PAY_AVAILABLE_AND_BALANCE_LATER:
                if (free.signum() == 0) {
                    return new Decision(Action.BLOCKED, remaining, available, shortfall, Money.ZERO, Money.ZERO, remaining);
                }
                return new Decision(Action.PAY_PART, remaining, available, shortfall, free, Money.ZERO, remaining.subtract(free));
            case BLOCK_UNTIL_FUNDED:
            default:
                return new Decision(Action.BLOCKED, remaining, available, shortfall, Money.ZERO, Money.ZERO, remaining);
        }
    }

    /**
     * The part of the hold taken with a payment leg. The hold is spread in proportion to the gross paid; the leg that
     * completes the prize takes whatever is left, so the legs always add up to the hold exactly.
     */
    public static BigDecimal holdForLeg(BigDecimal gross, BigDecimal hold, BigDecimal paidGross, BigDecimal paidHold, BigDecimal legGross) {
        BigDecimal holdLeft = hold.subtract(paidHold);
        if (paidGross.add(legGross).compareTo(gross) >= 0 || gross.signum() == 0) {
            return holdLeft;
        }
        BigDecimal share = hold.multiply(legGross).divide(gross, 2, Money.ROUNDING);
        return Money.min(share, holdLeft);
    }
}
