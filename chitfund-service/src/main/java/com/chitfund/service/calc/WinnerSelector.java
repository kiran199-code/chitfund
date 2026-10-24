package com.chitfund.service.calc;

import com.chitfund.service.common.ChitException;
import com.chitfund.service.domain.TieBreakRule;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

/**
 * Picks the winning bid: the highest discount, then the tie-break rule. A tie broken by LOTTERY uses a recorded draw
 * ({@link Draw}), so it can be replayed from the seed and the candidate list.
 */
public final class WinnerSelector {

    /** One valid bid. {@code bidTime} is the server time it was recorded; {@code bidSeq} orders bids made at the same instant. */
    public record Bid(long bidId, int bidSeq, long ticketId, int ticketNo, BigDecimal discount, Instant bidTime) {
    }

    public enum Basis {
        HIGHEST_BID, TIE_BREAK_EARLIEST, TIE_BREAK_LOWEST_NO, TIE_BREAK_DRAW
    }

    /** {@code tiedTicketNos} lists every ticket that bid the winning discount (one entry when there was no tie); {@code draw} is set only for a drawn tie. */
    public record Selection(Bid winner, Basis basis, List<Integer> tiedTicketNos, Draw.Outcome draw) {
    }

    private WinnerSelector() {
    }

    public static Selection select(List<Bid> validBids, TieBreakRule rule, long seed) {
        if (validBids == null || validBids.isEmpty()) {
            throw new ChitException("NO_BIDS", "There is no valid bid to choose from");
        }
        BigDecimal top = validBids.stream().map(Bid::discount).max(Comparator.naturalOrder()).orElseThrow();
        List<Bid> tied = validBids.stream().filter(b -> b.discount().compareTo(top) == 0)
                .sorted(Comparator.comparingInt(Bid::bidSeq)).toList();
        TreeSet<Integer> tiedNos = new TreeSet<>();
        tied.forEach(b -> tiedNos.add(b.ticketNo()));
        List<Integer> nos = List.copyOf(tiedNos);
        if (nos.size() == 1) {
            return new Selection(tied.get(0), Basis.HIGHEST_BID, nos, null);
        }
        switch (rule) {
            case EARLIEST_BID_TIME: {
                Bid w = tied.stream().min(Comparator.comparing(Bid::bidTime).thenComparingInt(Bid::bidSeq)).orElseThrow();
                return new Selection(w, Basis.TIE_BREAK_EARLIEST, nos, null);
            }
            case LOWEST_TICKET_NO: {
                Bid w = tied.stream().min(Comparator.comparingInt(Bid::ticketNo).thenComparingInt(Bid::bidSeq)).orElseThrow();
                return new Selection(w, Basis.TIE_BREAK_LOWEST_NO, nos, null);
            }
            case LOTTERY: {
                Draw.Outcome draw = Draw.pick(seed, nos);
                int winnerNo = draw.result().get(0);
                Bid w = tied.stream().filter(b -> b.ticketNo() == winnerNo).findFirst().orElseThrow();
                return new Selection(w, Basis.TIE_BREAK_DRAW, nos, draw);
            }
            default:
                throw new ChitException("UNSUPPORTED_COMBINATION", "Unknown tie-break rule " + rule);
        }
    }
}
