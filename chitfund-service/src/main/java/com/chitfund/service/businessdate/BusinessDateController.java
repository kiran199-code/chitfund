package com.chitfund.service.businessdate;

import com.chitfund.service.businessdate.BusinessDateModels.AdvanceRequest;
import com.chitfund.service.businessdate.BusinessDateModels.BusinessDateView;
import com.chitfund.service.businessdate.BusinessDateModels.CalendarView;
import com.chitfund.service.businessdate.BusinessDateModels.Holiday;
import com.chitfund.service.businessdate.BusinessDateModels.HolidayRequest;
import com.chitfund.service.businessdate.BusinessDateModels.SetDateRequest;
import com.chitfund.service.businessdate.BusinessDateModels.WorkingDayView;
import com.chitfund.service.common.Actor;
import com.chitfund.service.common.ChitException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/** The chitfund business date, end-of-day hand-over and holiday list. */
@RestController
@RequestMapping("/api/v1/chit")
@Tag(name = "Business date", description = "The date every chit rule uses, and the working-day calendar")
public class BusinessDateController {

    private final BusinessDateService dates;

    public BusinessDateController(BusinessDateService dates) {
        this.dates = dates;
    }

    @GetMapping("/business-date")
    @Operation(summary = "Current business date, status (OPEN or EOD_RUNNING), and whether it is a working day (chit.calendar.read)")
    public BusinessDateView current() {
        return dates.view();
    }

    @PostMapping("/business-date/eod/start")
    @Operation(summary = "Mark end of day as started: OPEN to EOD_RUNNING; 409 EOD_ALREADY_RUNNING if it already is (chit.businessdate.advance)")
    public BusinessDateView startEndOfDay(@RequestHeader(value = "X-Actor", required = false) String actor) {
        return dates.startEndOfDay(Actor.orService(actor));
    }

    @PostMapping("/business-date/advance")
    @Operation(summary = "Close the given day and open the next one; repeating the call changes nothing (chit.businessdate.advance)",
            description = "Body {\"expectedBusinessDate\":\"yyyy-MM-dd\"}: the day being closed. 409 BUSINESS_DATE_MISMATCH if the date is not that day or the day after it.")
    public BusinessDateView advance(@RequestBody(required = false) AdvanceRequest request,
                                    @RequestHeader(value = "X-Actor", required = false) String actor) {
        return dates.advance(day(request == null ? null : request.expectedBusinessDate(), "expectedBusinessDate"), Actor.orService(actor));
    }

    @PutMapping("/business-date")
    @Operation(summary = "Set the business date forward by hand; test environments only (chit.businessdate.set)",
            description = "Needs CHIT_RESET_ENABLED=true. Cannot go backwards: clear all data to start again from today.")
    public BusinessDateView set(@RequestBody(required = false) SetDateRequest request,
                                @RequestHeader(value = "X-Actor", required = false) String actor) {
        return dates.set(day(request == null ? null : request.businessDate(), "businessDate"), Actor.required(actor));
    }

    @GetMapping("/business-date/working-day")
    @Operation(summary = "Is the given date a working day (chit.calendar.read)")
    public WorkingDayView workingDay(@RequestParam("date") String date) {
        LocalDate d = day(date, "date");
        return new WorkingDayView(d.toString(), dates.isWorkingDay(d));
    }

    @GetMapping("/holidays")
    @Operation(summary = "The weekend days and the holidays, optionally for one year (chit.calendar.read)")
    public CalendarView holidays(@RequestParam(value = "year", required = false) Integer year) {
        if (year != null && (year < 2000 || year > 2200)) {
            throw ChitException.badRequest("VALIDATION_FAILED", "year must be between 2000 and 2200");
        }
        return dates.calendarView(year);
    }

    @PutMapping("/holidays/{date}")
    @Operation(summary = "Add a holiday, or rename it if the date is already listed (chit.calendar.write)",
            description = "Cycle dates already laid out at activation do not change; the holiday applies to groups activated after it.")
    public Holiday putHoliday(@PathVariable("date") String date, @RequestBody(required = false) HolidayRequest request,
                              @RequestHeader(value = "X-Actor", required = false) String actor) {
        return dates.putHoliday(day(date, "date"), request == null ? null : request.name(), Actor.required(actor));
    }

    @DeleteMapping("/holidays/{date}")
    @Operation(summary = "Remove a holiday (chit.calendar.write)")
    public void deleteHoliday(@PathVariable("date") String date, @RequestHeader(value = "X-Actor", required = false) String actor) {
        dates.deleteHoliday(day(date, "date"), Actor.required(actor));
    }

    private static LocalDate day(String text, String field) {
        if (text == null || text.isBlank()) {
            throw ChitException.badRequest("VALIDATION_FAILED", field + " is required (yyyy-MM-dd)");
        }
        try {
            return LocalDate.parse(text.trim());
        } catch (DateTimeParseException e) {
            throw ChitException.badRequest("VALIDATION_FAILED", field + " must look like 2026-11-02");
        }
    }
}
