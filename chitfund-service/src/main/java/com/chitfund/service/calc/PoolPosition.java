package com.chitfund.service.calc;

import com.chitfund.service.common.Money;

import java.math.BigDecimal;

/**
 * The pool's cash against what it owes. Cash in the pool is not all free to pay a prize: commission and late fees are
 * the foreman's, declared dividends belong to members, advance money is held for later instalments, and prizes already
 * won but not yet paid are claims too. What is left after those is what a prize can be paid from.
 *
 * <p>When every member has paid, the claims add up to the cash exactly (the gross identity P = W + C + DP), so the
 * prize is always covered. A gap exists only where collection fell short, or cash left the pool in another way.
 *
 * @param poolCash        balance of POOL_CASH
 * @param commissionIncome balance of COMMISSION_INCOME (the foreman's, until swept at closure)
 * @param lateFeeIncome   balance of LATE_FEE_INCOME
 * @param dividendPayable all DIVIDEND_PAYABLE balances (declared, not yet applied or paid)
 * @param prizePayable    balance of PRIZE_PAYABLE: every prize won and not yet paid out
 * @param advanceHeld     advance money members have paid ahead (credit balances on SUBSCRIBER_RECEIVABLE)
 */
public record PoolPosition(BigDecimal poolCash, BigDecimal commissionIncome, BigDecimal lateFeeIncome, BigDecimal dividendPayable,
                           BigDecimal prizePayable, BigDecimal advanceHeld) {

    /** Claims on the cash other than the prize being paid: {@code remainingPrize} is the unpaid part of that prize, which is in {@code prizePayable}. */
    public BigDecimal otherClaims(BigDecimal remainingPrize) {
        BigDecimal otherPrizes = Money.max(prizePayable.subtract(remainingPrize), Money.ZERO);
        return commissionIncome.add(lateFeeIncome).add(dividendPayable).add(advanceHeld).add(otherPrizes);
    }

    /** What is free to pay the prize. May be negative: the pool owes more than it holds. */
    public BigDecimal availableFor(BigDecimal remainingPrize) {
        return poolCash.subtract(otherClaims(remainingPrize));
    }

    /** Cash less every claim including all prizes: not negative exactly when the pool can meet everything it owes. */
    public BigDecimal surplus() {
        return poolCash.subtract(commissionIncome).subtract(lateFeeIncome).subtract(dividendPayable).subtract(prizePayable).subtract(advanceHeld);
    }
}
