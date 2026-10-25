package com.chitfund.service.calc;

import com.chitfund.service.common.ChitException;
import com.chitfund.service.common.Money;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where recovered money goes. Pure arithmetic, no database.
 *
 * <p>Order (decided with Kiran): a held deposit is applied first to what the pool is owed (overdue instalments and late
 * fees, in the group's allocation order), then to the balances of the foreman's covers on the same ticket, oldest cover
 * first. Money the ticket repays to the foreman on its own goes only to the covers. Nothing is ever applied beyond what is
 * owed, so a deposit that is larger than the claim keeps its remainder.
 */
public final class RecoveryWaterfall {

    private RecoveryWaterfall() {
    }

    /** What the foreman is still owed on one cover (amount covered less what has been repaid). */
    public record CoverBalance(long coverId, BigDecimal balance) {
    }

    /**
     * @param toArrears amount paid to the pool against overdue instalments and late fees
     * @param toCovers  amount paid to each cover, by cover id, oldest first (only covers that receive something)
     * @param unused    the part of the deposit that stays held
     */
    public record Plan(BigDecimal toArrears, Map<Long, BigDecimal> toCovers, BigDecimal unused) {
        public BigDecimal toCoversTotal() {
            return toCovers.values().stream().reduce(Money.ZERO, BigDecimal::add);
        }

        public BigDecimal total() {
            return toArrears.add(toCoversTotal());
        }
    }

    /**
     * Plans the use of a held deposit.
     *
     * @param available          the deposit balance held for the ticket
     * @param cap                an optional limit on how much to apply now (null = as much as is owed)
     * @param arrearsOutstanding overdue subscription plus overdue late fees on the ticket's open demands
     * @param covers             open foreman covers on the ticket, oldest first
     */
    public static Plan applySecurity(BigDecimal available, BigDecimal cap, BigDecimal arrearsOutstanding, List<CoverBalance> covers) {
        BigDecimal usable = Money.max(Money.ZERO, Money.nz(available));
        if (cap != null) {
            usable = Money.min(usable, Money.nz(cap));
        }
        BigDecimal toArrears = Money.min(usable, Money.max(Money.ZERO, Money.nz(arrearsOutstanding)));
        BigDecimal left = usable.subtract(toArrears);
        Map<Long, BigDecimal> toCovers = new LinkedHashMap<>();
        for (CoverBalance c : covers) {
            if (left.signum() <= 0) {
                break;
            }
            BigDecimal part = Money.min(left, Money.max(Money.ZERO, c.balance()));
            if (part.signum() > 0) {
                toCovers.put(c.coverId(), part);
                left = left.subtract(part);
            }
        }
        BigDecimal used = toArrears.add(toCovers.values().stream().reduce(Money.ZERO, BigDecimal::add));
        return new Plan(toArrears, toCovers, Money.nz(available).subtract(used));
    }

    /**
     * Spreads a repayment by the ticket over its open covers, oldest first.
     *
     * @throws ChitException COVER_REPAYMENT_TOO_LARGE if the amount is more than the covers still owe
     */
    public static Map<Long, BigDecimal> repayCovers(BigDecimal amount, List<CoverBalance> covers) {
        BigDecimal owed = covers.stream().map(c -> Money.max(Money.ZERO, c.balance())).reduce(Money.ZERO, BigDecimal::add);
        if (amount.signum() <= 0) {
            throw ChitException.badRequest("VALIDATION_FAILED", "amount must be greater than zero");
        }
        if (amount.compareTo(owed) > 0) {
            throw ChitException.conflict("COVER_REPAYMENT_TOO_LARGE", "The foreman is owed " + owed.toPlainString() + " on this ticket; "
                    + amount.toPlainString() + " was offered");
        }
        Map<Long, BigDecimal> out = new LinkedHashMap<>();
        BigDecimal left = amount;
        for (CoverBalance c : covers) {
            if (left.signum() <= 0) {
                break;
            }
            BigDecimal part = Money.min(left, Money.max(Money.ZERO, c.balance()));
            if (part.signum() > 0) {
                out.put(c.coverId(), part);
                left = left.subtract(part);
            }
        }
        return out;
    }

    /** The three parts of a write-off, in rupees. */
    public record WriteOffAmounts(BigDecimal subscription, BigDecimal lateFees, BigDecimal covers) {
        public BigDecimal total() {
            return subscription.add(lateFees).add(covers);
        }

        public boolean isEmpty() {
            return total().signum() == 0;
        }
    }

    /**
     * What a write-off would clear right now: the subscription and late fees still owed on the ticket's overdue demands, and
     * the balance of the foreman's open covers.
     */
    public static WriteOffAmounts writeOffAmounts(List<DemandFigures> overdueDemands, List<CoverBalance> covers) {
        BigDecimal sub = Money.ZERO;
        BigDecimal fees = Money.ZERO;
        for (DemandFigures f : overdueDemands) {
            sub = sub.add(f.subscriptionOutstanding());
            fees = fees.add(f.lateFeeOutstanding());
        }
        BigDecimal cov = covers.stream().map(c -> Money.max(Money.ZERO, c.balance())).reduce(Money.ZERO, BigDecimal::add);
        return new WriteOffAmounts(Money.scale(sub), Money.scale(fees), Money.scale(cov));
    }
}
