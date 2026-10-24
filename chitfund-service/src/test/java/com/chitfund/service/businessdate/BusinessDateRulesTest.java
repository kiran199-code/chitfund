package com.chitfund.service.businessdate;

import com.chitfund.service.common.ChitException;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BusinessDateRulesTest {

    private static final LocalDate D = LocalDate.of(2026, 10, 12);

    @Test
    void closingTheCurrentDayMovesTheDate() {
        assertEquals(BusinessDateRules.Outcome.MOVE, BusinessDateRules.advance(D, D));
    }

    @Test
    void repeatingTheCallAfterItWorkedChangesNothing() {
        assertEquals(BusinessDateRules.Outcome.ALREADY_DONE, BusinessDateRules.advance(D.plusDays(1), D));
    }

    @Test
    void aStaleOrFutureDayIsRefused() {
        ChitException stale = assertThrows(ChitException.class, () -> BusinessDateRules.advance(D.plusDays(3), D));
        assertEquals("BUSINESS_DATE_MISMATCH", stale.code());
        assertEquals("BUSINESS_DATE_MISMATCH", assertThrows(ChitException.class, () -> BusinessDateRules.advance(D, D.plusDays(1))).code());
        assertEquals("VALIDATION_FAILED", assertThrows(ChitException.class, () -> BusinessDateRules.advance(D, null)).code());
    }

    @Test
    void endOfDayCannotStartTwice() {
        BusinessDateRules.requireOpenToStart(BusinessDateRules.OPEN, D);
        assertEquals("EOD_ALREADY_RUNNING",
                assertThrows(ChitException.class, () -> BusinessDateRules.requireOpenToStart(BusinessDateRules.EOD_RUNNING, D)).code());
    }

    @Test
    void theDateCanBeSetForwardOrSameButNotBack() {
        BusinessDateRules.requireNotBackwards(D, D);
        BusinessDateRules.requireNotBackwards(D, D.plusDays(40));
        assertEquals("BUSINESS_DATE_BACKWARDS", assertThrows(ChitException.class, () -> BusinessDateRules.requireNotBackwards(D, D.minusDays(1))).code());
        assertEquals("VALIDATION_FAILED", assertThrows(ChitException.class, () -> BusinessDateRules.requireNotBackwards(D, null)).code());
    }

    @Test
    void weekendAndHolidaysAreNotWorkingDays() {
        WorkingDays w = new WorkingDays(WorkingDays.parseWeekend("saturday, SUNDAY"), Set.of(LocalDate.of(2026, 10, 2)));
        assertTrue(w.isWorkingDay(LocalDate.of(2026, 10, 1)));      // Thursday
        assertFalse(w.isWorkingDay(LocalDate.of(2026, 10, 2)));     // holiday (Friday)
        assertFalse(w.isWorkingDay(LocalDate.of(2026, 10, 3)));     // Saturday
        assertFalse(w.isWorkingDay(LocalDate.of(2026, 10, 4)));     // Sunday
        assertTrue(w.isWorkingDay(LocalDate.of(2026, 10, 5)));      // Monday
    }

    @Test
    void theWeekendSettingIsParsedAndChecked() {
        assertEquals(Set.of(DayOfWeek.FRIDAY), WorkingDays.parseWeekend(" friday "));
        assertTrue(WorkingDays.parseWeekend("").isEmpty());
        assertTrue(WorkingDays.parseWeekend(null).isEmpty());
        assertEquals("CONFIG_INVALID", assertThrows(ChitException.class, () -> WorkingDays.parseWeekend("SATURDAY,FUNDAY")).code());
        assertEquals("CONFIG_INVALID", assertThrows(ChitException.class,
                () -> WorkingDays.parseWeekend("MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY,SATURDAY,SUNDAY")).code());
    }
}
