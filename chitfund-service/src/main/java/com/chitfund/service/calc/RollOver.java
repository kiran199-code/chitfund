package com.chitfund.service.calc;

import com.chitfund.service.calendar.ScheduledCycle;
import com.chitfund.service.common.ChitException;

import java.util.List;

/**
 * How the calendar stays consistent when an auction rolls over (ROLL_OVER_CYCLE, no bid).
 *
 * <p>The cycle keeps its number and its collected instalments, so the pot is not lost and nothing is demanded twice. Its
 * auction moves to the next period's auction date, and every later cycle moves one period back as well. Every ticket
 * still wins exactly once: the group holds as many auctions as before, the term is longer by one period per roll-over.
 *
 * <p>Concretely: generate the calendar for {@code cycleCount + e} cycles, where {@code e} is the number of roll-overs so far
 * including this one. Cycle {@code j} after the roll-over takes the dates of position {@code j + e} of that list (1-based);
 * the rolled cycle keeps its due date and takes the auction date of that position.
 */
public final class RollOver {

    private RollOver() {
    }

    /** The dates of cycle {@code cycleNo} after {@code rollovers} roll-overs, from the calendar generated for {@code cycleCount + rollovers} cycles. */
    public static ScheduledCycle datesOf(List<ScheduledCycle> generated, int cycleNo, int rollovers) {
        int index = cycleNo - 1 + rollovers;
        if (rollovers < 1 || index < 0 || index >= generated.size()) {
            throw new ChitException("CALENDAR_UNAVAILABLE", "No date for cycle " + cycleNo + " after " + rollovers + " roll-over(s)");
        }
        return generated.get(index);
    }
}
