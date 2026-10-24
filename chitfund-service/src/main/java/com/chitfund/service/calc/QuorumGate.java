package com.chitfund.service.calc;

import com.chitfund.service.common.Money;
import com.chitfund.service.domain.QuorumRule;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The auction gate (spec 9.2.8): may the auction of this cycle open, given what has been collected?
 * <ul>
 *   <li>ALL_PAID: every instalment raised so far, this cycle's and earlier ones, is paid.</li>
 *   <li>MIN_COLLECTION_PCT: this cycle's collection has reached the configured percentage of what is due.</li>
 *   <li>FOREMAN_COVERS_SHORTFALL: always opens; the foreman pays the unpaid instalments of this cycle into the pool, and each
 *       becomes a receivable from that ticket.</li>
 * </ul>
 * Amounts are the subscription net of any dividend adjustment; late fees do not count.
 */
public final class QuorumGate {

    /** A raised instalment: {@code due} is the subscription less the dividend adjustment, {@code paid} what has been paid of it. */
    public record DemandPosition(long demandId, long ticketId, int ticketNo, int cycleNo, BigDecimal due, BigDecimal paid) {
        BigDecimal outstanding() {
            return Money.max(due.subtract(paid), Money.ZERO);
        }
    }

    /** What one ticket still owes of the instalments up to this cycle. */
    public record Unpaid(long ticketId, int ticketNo, BigDecimal amount) {
    }

    /** An unpaid instalment of this cycle the foreman would cover. */
    public record Cover(long demandId, long ticketId, int ticketNo, BigDecimal amount) {
    }

    public record Evaluation(QuorumRule rule, boolean open, String reason, BigDecimal due, BigDecimal collected, BigDecimal collectedPct,
                             BigDecimal shortfall, List<Unpaid> unpaid, List<Cover> cover) {
    }

    private QuorumGate() {
    }

    /**
     * @param demands every raised demand of the group up to and including {@code cycleNo}
     * @param minPct  the percentage for MIN_COLLECTION_PCT (5 for 5%); ignored otherwise
     */
    public static Evaluation evaluate(QuorumRule rule, BigDecimal minPct, int cycleNo, List<DemandPosition> demands) {
        BigDecimal due = Money.ZERO;
        BigDecimal collected = Money.ZERO;
        List<Cover> cover = new ArrayList<>();
        Map<Long, Unpaid> byTicket = new LinkedHashMap<>();
        for (DemandPosition d : demands) {
            if (d.cycleNo() > cycleNo) {
                continue;
            }
            BigDecimal out = d.outstanding();
            if (d.cycleNo() == cycleNo) {
                due = due.add(d.due());
                collected = collected.add(Money.min(d.paid(), d.due()));
                if (out.signum() > 0) {
                    cover.add(new Cover(d.demandId(), d.ticketId(), d.ticketNo(), out));
                }
            }
            if (out.signum() > 0) {
                byTicket.merge(d.ticketId(), new Unpaid(d.ticketId(), d.ticketNo(), out),
                        (a, b) -> new Unpaid(a.ticketId(), a.ticketNo(), a.amount().add(b.amount())));
            }
        }
        List<Unpaid> unpaid = new ArrayList<>(byTicket.values());
        unpaid.sort(java.util.Comparator.comparingInt(Unpaid::ticketNo));
        BigDecimal shortfall = due.subtract(collected);
        BigDecimal pct = due.signum() == 0 ? Money.ZERO : collected.multiply(Money.HUNDRED).divide(due, 2, Money.ROUNDING);
        BigDecimal unpaidTotal = unpaid.stream().map(Unpaid::amount).reduce(Money.ZERO, BigDecimal::add);

        if (due.signum() == 0) {
            return new Evaluation(rule, false, "No instalment has been raised for cycle " + cycleNo + " yet", due, collected, pct, shortfall, unpaid, cover);
        }
        switch (rule) {
            case ALL_PAID:
                return unpaid.isEmpty()
                        ? new Evaluation(rule, true, "Every instalment raised so far is paid", due, collected, pct, shortfall, unpaid, cover)
                        : new Evaluation(rule, false, unpaid.size() + " ticket(s) have not paid " + unpaidTotal.toPlainString()
                                + " of the instalments raised so far", due, collected, pct, shortfall, unpaid, cover);
            case MIN_COLLECTION_PCT: {
                boolean ok = collected.multiply(Money.HUNDRED).compareTo(due.multiply(minPct)) >= 0;
                return new Evaluation(rule, ok, ok ? "Collection is " + pct.toPlainString() + "% of what is due, at least the required " + minPct.toPlainString() + "%"
                        : "Collection is " + pct.toPlainString() + "% of what is due; the required minimum is " + minPct.toPlainString() + "%",
                        due, collected, pct, shortfall, unpaid, cover);
            }
            case FOREMAN_COVERS_SHORTFALL:
            default:
                return new Evaluation(rule, true, cover.isEmpty() ? "Every instalment of this cycle is paid"
                        : "The foreman covers " + shortfall.toPlainString() + " unpaid on " + cover.size() + " instalment(s)",
                        due, collected, pct, shortfall, unpaid, cover);
        }
    }
}
