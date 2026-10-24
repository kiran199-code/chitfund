package com.chitfund.service.businessdate;

import com.chitfund.service.calendar.WorkingDayCalendar;
import com.chitfund.service.common.ChitException;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/** The chitfund working-day rule: a day is a working day unless it falls on a weekend day or on a listed holiday. */
public final class WorkingDays implements WorkingDayCalendar {

    private final Set<DayOfWeek> weekend;
    private final Set<LocalDate> holidays;

    public WorkingDays(Set<DayOfWeek> weekend, Set<LocalDate> holidays) {
        this.weekend = Set.copyOf(weekend);
        this.holidays = Set.copyOf(holidays);
    }

    @Override
    public boolean isWorkingDay(LocalDate date) {
        return !weekend.contains(date.getDayOfWeek()) && !holidays.contains(date);
    }

    /** "SATURDAY,SUNDAY" (any case, spaces allowed) to a set; blank means no weekend. */
    public static Set<DayOfWeek> parseWeekend(String text) {
        Set<DayOfWeek> out = EnumSet.noneOf(DayOfWeek.class);
        if (text == null || text.isBlank()) {
            return out;
        }
        for (String part : text.split(",")) {
            String p = part.trim().toUpperCase(Locale.ROOT);
            if (p.isEmpty()) {
                continue;
            }
            try {
                out.add(DayOfWeek.valueOf(p));
            } catch (IllegalArgumentException e) {
                throw new ChitException("CONFIG_INVALID", "chit.calendar.weekend has an unknown day: " + part.trim());
            }
        }
        if (out.size() >= 7) {
            throw new ChitException("CONFIG_INVALID", "chit.calendar.weekend cannot be every day of the week");
        }
        return out;
    }
}
