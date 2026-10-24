package com.chitfund.service.businessdate;

import java.time.LocalDate;
import java.util.List;

/** Requests and responses of the business date and holiday endpoints. */
public final class BusinessDateModels {
    private BusinessDateModels() {
    }

    /**
     * @param mode        MANAGED (the date is stored and moved by end of day) or SYSTEM (the wall-clock date in the business time zone)
     * @param status      OPEN or EOD_RUNNING (always OPEN in SYSTEM mode)
     * @param systemDate  the wall-clock date in the business time zone, for comparison
     */
    public record BusinessDateView(String businessDate, String previousBusinessDate, String nextBusinessDate, String status,
                                   boolean workingDay, String systemDate, String mode) {
    }

    public record AdvanceRequest(String expectedBusinessDate) {
    }

    public record SetDateRequest(String businessDate) {
    }

    public record HolidayRequest(String name) {
    }

    public record Holiday(LocalDate date, String name, String createdBy) {
    }

    public record CalendarView(List<String> weekend, List<Holiday> holidays) {
    }

    public record WorkingDayView(String date, boolean workingDay) {
    }
}
