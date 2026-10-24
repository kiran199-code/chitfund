package com.chitfund.service.businessdate;

import com.chitfund.service.businessdate.BusinessDateModels.BusinessDateView;
import com.chitfund.service.businessdate.BusinessDateModels.CalendarView;
import com.chitfund.service.businessdate.BusinessDateModels.Holiday;
import com.chitfund.service.calendar.WorkingDayCalendar;
import com.chitfund.service.common.Audit;
import com.chitfund.service.common.ChitException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The chitfund's own business date and working-day calendar.
 *
 * <p>Mode MANAGED (default): the date is a stored row, moved forward by end of day (the batch service calls
 * {@link #startEndOfDay} and {@link #advance}), so a test environment can run ahead of the wall clock. Mode SYSTEM: the
 * date is the wall-clock date in the business time zone and nothing is stored or moved; use it once the service runs for
 * real, with a nightly end of day that only runs the jobs.
 *
 * <p>Working days: Monday to Friday by default ({@code chit.calendar.weekend}), minus the holidays in {@code chit_holiday}.
 */
@Service
public class BusinessDateService implements BusinessDateSource {

    private final JdbcTemplate jdbc;
    private final Audit audit;
    private final boolean managed;
    private final ZoneId zone;
    private final Set<DayOfWeek> weekend;
    private final boolean setAllowed;

    public BusinessDateService(JdbcTemplate jdbc, Audit audit,
                               @Value("${chit.business-date.mode:MANAGED}") String mode,
                               @Value("${chit.business-date.timezone:Asia/Kolkata}") String timezone,
                               @Value("${chit.calendar.weekend:SATURDAY,SUNDAY}") String weekend,
                               @Value("${chit.maintenance.reset-enabled:false}") boolean setAllowed) {
        this.jdbc = jdbc;
        this.audit = audit;
        if (!"MANAGED".equalsIgnoreCase(mode) && !"SYSTEM".equalsIgnoreCase(mode)) {
            throw new ChitException("CONFIG_INVALID", "chit.business-date.mode must be MANAGED or SYSTEM, not " + mode);
        }
        this.managed = "MANAGED".equalsIgnoreCase(mode);
        this.zone = ZoneId.of(timezone);
        this.weekend = WorkingDays.parseWeekend(weekend);
        this.setAllowed = setAllowed;
    }

    // ---- the date ---------------------------------------------------------------------------------------------

    @Override
    public LocalDate today() {
        return managed ? storedDate() : systemDate();
    }

    private LocalDate systemDate() {
        return LocalDate.now(zone);
    }

    private LocalDate storedDate() {
        List<Date> rows = jdbc.query("SELECT business_date FROM chit_business_date WHERE id = 1", (rs, i) -> rs.getDate(1));
        if (rows.isEmpty() || rows.get(0) == null) {
            throw new ChitException("BUSINESS_DATE_UNAVAILABLE", "The business date row is missing. Run the database migrations.");
        }
        return rows.get(0).toLocalDate();
    }

    private String storedStatus() {
        List<String> rows = jdbc.query("SELECT status FROM chit_business_date WHERE id = 1", (rs, i) -> rs.getString(1));
        return rows.isEmpty() ? BusinessDateRules.OPEN : rows.get(0);
    }

    @Transactional(readOnly = true)
    public BusinessDateView view() {
        LocalDate d = today();
        return viewOf(d, managed ? storedStatus() : BusinessDateRules.OPEN);
    }

    private BusinessDateView viewOf(LocalDate d, String status) {
        return new BusinessDateView(d.toString(), d.minusDays(1).toString(), d.plusDays(1).toString(), status,
                calendar().isWorkingDay(d), systemDate().toString(), managed ? "MANAGED" : "SYSTEM");
    }

    /** OPEN to EOD_RUNNING. In SYSTEM mode there is nothing to mark, so the call only reports the date. */
    @Transactional
    public BusinessDateView startEndOfDay(String actor) {
        if (!managed) {
            return view();
        }
        LocalDate d = lockRow();
        BusinessDateRules.requireOpenToStart(storedStatus(), d);
        jdbc.update("UPDATE chit_business_date SET status = ?, updated_at = now(), updated_by = ? WHERE id = 1",
                BusinessDateRules.EOD_RUNNING, actor);
        audit.log("BUSINESS_DATE", d.toString(), "EOD_STARTED", actor, "End of day started for " + d);
        return viewOf(d, BusinessDateRules.EOD_RUNNING);
    }

    /** Close {@code expected} and open the next day. Repeating the call after it worked changes nothing. */
    @Transactional
    public BusinessDateView advance(LocalDate expected, String actor) {
        if (!managed) {
            return view();
        }
        LocalDate d = lockRow();
        if (BusinessDateRules.advance(d, expected) == BusinessDateRules.Outcome.ALREADY_DONE) {
            return viewOf(d, storedStatus());
        }
        LocalDate next = d.plusDays(1);
        jdbc.update("UPDATE chit_business_date SET business_date = ?, status = ?, updated_at = now(), updated_by = ? WHERE id = 1",
                Date.valueOf(next), BusinessDateRules.OPEN, actor);
        audit.log("BUSINESS_DATE", next.toString(), "ADVANCED", actor, "Business date moved from " + d + " to " + next);
        audit.event("BusinessDateAdvanced", "BUSINESS_DATE", next.toString(),
                "{\"from\":\"" + d + "\",\"to\":\"" + next + "\",\"actor\":\"" + actor.replace("\"", "") + "\"}");
        return viewOf(next, BusinessDateRules.OPEN);
    }

    /** Set the date forward by hand. Only where clearing data is allowed (test environments). */
    @Transactional
    public BusinessDateView set(LocalDate requested, String actor) {
        if (!managed) {
            throw ChitException.conflict("BUSINESS_DATE_NOT_MANAGED",
                    "The business date follows the wall clock (CHIT_BUSINESS_DATE_MODE=SYSTEM) and cannot be set.");
        }
        if (!setAllowed) {
            throw ChitException.forbidden("RESET_DISABLED",
                    "Setting the business date by hand is switched off. Set CHIT_RESET_ENABLED=true (non-production only).");
        }
        LocalDate d = lockRow();
        BusinessDateRules.requireNotBackwards(d, requested);
        if (requested.equals(d)) {
            return viewOf(d, storedStatus());
        }
        jdbc.update("UPDATE chit_business_date SET business_date = ?, status = ?, updated_at = now(), updated_by = ? WHERE id = 1",
                Date.valueOf(requested), BusinessDateRules.OPEN, actor);
        audit.log("BUSINESS_DATE", requested.toString(), "SET", actor, "Business date set from " + d + " to " + requested);
        return viewOf(requested, BusinessDateRules.OPEN);
    }

    /** Back to today's wall-clock date, status OPEN (used by clear-all-data). */
    @Transactional
    public void resetToToday(String actor) {
        if (managed) {
            jdbc.update("UPDATE chit_business_date SET business_date = ?, status = ?, updated_at = now(), updated_by = ? WHERE id = 1",
                    Date.valueOf(systemDate()), BusinessDateRules.OPEN, actor);
        }
    }

    private LocalDate lockRow() {
        List<Date> rows = jdbc.query("SELECT business_date FROM chit_business_date WHERE id = 1 FOR UPDATE", (rs, i) -> rs.getDate(1));
        if (rows.isEmpty()) {
            throw new ChitException("BUSINESS_DATE_UNAVAILABLE", "The business date row is missing. Run the database migrations.");
        }
        return rows.get(0).toLocalDate();
    }

    // ---- the calendar -----------------------------------------------------------------------------------------

    /** A working-day rule over the holidays as they are now (one query). */
    @Transactional(readOnly = true)
    public WorkingDayCalendar calendar() {
        return new WorkingDays(weekend, holidayDates());
    }

    private Set<LocalDate> holidayDates() {
        return new HashSet<>(jdbc.query("SELECT holiday_date FROM chit_holiday", (rs, i) -> rs.getDate(1).toLocalDate()));
    }

    @Transactional(readOnly = true)
    public boolean isWorkingDay(LocalDate date) {
        return calendar().isWorkingDay(date);
    }

    @Transactional(readOnly = true)
    public CalendarView calendarView(Integer year) {
        List<Holiday> list = year == null
                ? jdbc.query("SELECT holiday_date, name, created_by FROM chit_holiday ORDER BY holiday_date", HOLIDAY)
                : jdbc.query("SELECT holiday_date, name, created_by FROM chit_holiday WHERE holiday_date >= ? AND holiday_date <= ? "
                        + "ORDER BY holiday_date", HOLIDAY, Date.valueOf(LocalDate.of(year, 1, 1)), Date.valueOf(LocalDate.of(year, 12, 31)));
        return new CalendarView(weekend.stream().sorted().map(DayOfWeek::name).collect(Collectors.toList()), list);
    }

    private static final org.springframework.jdbc.core.RowMapper<Holiday> HOLIDAY =
            (rs, i) -> new Holiday(rs.getDate(1).toLocalDate(), rs.getString(2), rs.getString(3));

    /** Add a holiday, or rename it if the date is already listed. */
    @Transactional
    public Holiday putHoliday(LocalDate date, String name, String actor) {
        if (date == null) {
            throw ChitException.badRequest("VALIDATION_FAILED", "date is required");
        }
        String n = name == null ? "" : name.trim();
        if (n.isEmpty() || n.length() > 100) {
            throw ChitException.badRequest("VALIDATION_FAILED", "name is required (up to 100 characters)");
        }
        jdbc.update("INSERT INTO chit_holiday (holiday_date, name, created_by) VALUES (?,?,?) "
                + "ON CONFLICT (holiday_date) DO UPDATE SET name = EXCLUDED.name", Date.valueOf(date), n, actor);
        audit.log("HOLIDAY", date.toString(), "PUT", actor, n);
        return new Holiday(date, n, actor);
    }

    @Transactional
    public void deleteHoliday(LocalDate date, String actor) {
        int n = jdbc.update("DELETE FROM chit_holiday WHERE holiday_date = ?", Date.valueOf(date));
        if (n == 0) {
            throw ChitException.notFound("holiday", date.toString());
        }
        audit.log("HOLIDAY", date.toString(), "DELETED", actor, "Holiday removed");
    }
}
