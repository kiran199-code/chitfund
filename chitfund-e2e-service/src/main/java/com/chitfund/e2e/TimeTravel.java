package com.chitfund.e2e;

import com.chitfund.e2e.Http.Resp;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Moves the platform's business date so nobody waits for nightly batches.
 *
 * <p>DAY_BY_DAY (default): for each calendar day it does what the nightly end of day does: start it, run the three daily jobs for that
 * day (demands, late fees, default check), then close the day and open the next. Late fees and default checks therefore see every day.
 * <br>LEAP: sets the date straight to the target (test environments only, forward only) and runs the three jobs once there. Much faster;
 * the days in between are skipped, so a late fee or a default that depends on daily runs may differ from DAY_BY_DAY. The report says which
 * mode each jump used.
 *
 * <p>The date never moves backwards (the platform refuses it too). The jobs for a day are idempotent on the platform side, so
 * {@link #runJobsToday()} may be called after arriving on a date without double-charging.
 */
public final class TimeTravel {
    public enum Mode { DAY_BY_DAY, LEAP }

    private static final String ACTOR = "e2e-time";
    private final ChitApi chit;
    private final Report report;
    private final Mode defaultMode;
    private LocalDate today;

    public TimeTravel(ChitApi chit, Report report, Mode defaultMode) {
        this.chit = chit;
        this.report = report;
        this.defaultMode = defaultMode;
    }

    /** Reads the business date from the platform and refuses a platform that follows the wall clock. */
    public LocalDate sync() {
        JsonNode v = chit.businessDate();
        if (!"MANAGED".equals(Json.text(v, "mode"))) {
            throw new IllegalStateException("The business date mode is " + Json.text(v, "mode") + ", not MANAGED: the date cannot be moved. Set CHIT_BUSINESS_DATE_MODE=MANAGED on chitfund-service.");
        }
        today = Json.date(v, "businessDate");
        report.businessDate(today.toString());
        return today;
    }

    public LocalDate now() {
        return today == null ? sync() : today;
    }

    public void jumpDays(int n) {
        jumpTo(now().plusDays(n));
    }

    public void jumpTo(LocalDate target) {
        jumpTo(target, defaultMode);
    }

    /** Moves to {@code target} (same day = nothing). Never backwards. */
    public void jumpTo(LocalDate target, Mode mode) {
        LocalDate from = now();
        if (target.isBefore(from)) {
            throw new IllegalArgumentException("Time travel never goes backwards: the date is " + from + ", asked for " + target);
        }
        if (target.equals(from)) {
            return;
        }
        long days = ChronoUnit.DAYS.between(from, target);
        long t0 = System.currentTimeMillis();
        if (mode == Mode.LEAP) {
            chit.setBusinessDate(target, ACTOR);
            today = target;
            report.businessDate(today.toString());
            runJobsToday();
            report.timeline(from + " -> " + target + "  LEAP over " + days + " day(s); jobs run once on " + target + "  (" + (System.currentTimeMillis() - t0) + " ms)");
        } else {
            while (today.isBefore(target)) {
                endOfDay();
            }
            report.timeline(from + " -> " + target + "  DAY_BY_DAY, " + days + " end-of-day run(s)  (" + (System.currentTimeMillis() - t0) + " ms)");
        }
    }

    /** One nightly cycle for the current day: start, three jobs, close; the date is then the next day. */
    void endOfDay() {
        LocalDate d = today;
        Resp start = chit.startEndOfDay(ACTOR);
        if (!start.ok() && start.status() != 409) {
            throw new Http.ApiException(start.status(), "POST", "/api/v1/chit/business-date/eod/start", start.body());
        }
        runJobs(d);
        JsonNode next = chit.advance(d, ACTOR);
        LocalDate moved = Json.date(next, "businessDate");
        if (!moved.equals(d.plusDays(1))) {
            throw new IllegalStateException("End of day for " + d + " left the business date at " + moved + " instead of " + d.plusDays(1));
        }
        today = moved;
        report.businessDate(today.toString());
    }

    /** Runs the three daily jobs for the current date without closing the day (after arriving on a due date, so demands exist today). */
    public void runJobsToday() {
        runJobs(now());
    }

    private void runJobs(LocalDate d) {
        chit.demandRun(d, ACTOR);
        chit.lateFeeRun(d, ACTOR);
        chit.defaultRun(d, ACTOR);
    }

    /** The first date after today on which something is due for the group: a due date, an auction date or a given extra date. */
    public static LocalDate nextEvent(JsonNode cycles, LocalDate after) {
        LocalDate best = null;
        for (JsonNode c : cycles) {
            for (String f : new String[]{"dueDate", "auctionDate"}) {
                LocalDate d = Json.date(c, f);
                if (d != null && d.isAfter(after) && (best == null || d.isBefore(best))) {
                    best = d;
                }
            }
        }
        return best;
    }
}
