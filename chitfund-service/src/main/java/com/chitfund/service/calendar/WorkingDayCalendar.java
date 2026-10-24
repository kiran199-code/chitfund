package com.chitfund.service.calendar;

import java.time.LocalDate;

/** Answers whether a date is a working day. In the service this is the weekend setting plus the holiday table; tests use a plain set. */
@FunctionalInterface
public interface WorkingDayCalendar {

    boolean isWorkingDay(LocalDate date);
}
