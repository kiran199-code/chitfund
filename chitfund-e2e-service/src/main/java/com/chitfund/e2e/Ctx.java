package com.chitfund.e2e;

import com.chitfund.e2e.Http.Resp;
import com.chitfund.e2e.Report.Status;

import java.util.function.Supplier;

/** What a scenario works with: the services, the date helper, the report, and check helpers that record instead of throwing. */
public final class Ctx {
    /** Thrown to stop one scenario after a step it cannot continue without. The report already holds the reason. */
    public static final class Abort extends RuntimeException {
        public Abort(String message) {
            super(message);
        }
    }

    public final Config cfg;
    public final ChitApi chit;
    public final PortalApi portal;
    public final TimeTravel time;
    public final Report report;
    public final RunOptions opt;
    private long groupsSinceReset;
    private boolean resetDone;
    private final Http.Sleeper sleeper;

    public Ctx(Config cfg, ChitApi chit, PortalApi portal, TimeTravel time, Report report, RunOptions opt, Http.Sleeper sleeper) {
        this.cfg = cfg;
        this.chit = chit;
        this.portal = portal;
        this.time = time;
        this.report = report;
        this.opt = opt;
        this.sleeper = sleeper;
    }

    public void sleep(long ms) {
        sleeper.sleep(ms);
    }

    // ---- recording ----

    public void pass(String name, String detail) { report.add(Status.PASS, name, detail); }

    public void info(String name, String detail) { report.add(Status.INFO, name, detail); }

    public void warn(String name, String detail) { report.add(Status.WARN, name, detail); }

    public void fail(String name, String detail) { report.add(Status.FAIL, name, detail); }

    /** Records PASS or FAIL and carries on. */
    public boolean check(String name, boolean ok, String detail) {
        report.add(ok ? Status.PASS : Status.FAIL, name, detail);
        return ok;
    }

    /** Like {@link #check} but stops the scenario when it fails. */
    public void require(String name, boolean ok, String detail) {
        if (!check(name, ok, detail)) {
            throw new Abort(name + ": " + detail);
        }
    }

    /** A call that must be refused with a client error (4xx). */
    public boolean refused(String name, Resp r) {
        boolean ok = r.status() >= 400 && r.status() < 500;
        report.add(ok ? Status.PASS : Status.FAIL, name, "HTTP " + r.status() + (ok ? " (refused as it should be)" : " (should have been refused): " + Http.snippet(r.body())));
        return ok;
    }

    public boolean refused(String name, Resp r, int status, String code) {
        String got = null;
        try {
            got = Json.text(r.json(), "code");
        } catch (RuntimeException ignored) {
            // body is not JSON
        }
        boolean ok = r.status() == status && (code == null || code.equals(got));
        report.add(ok ? Status.PASS : Status.FAIL, name, "HTTP " + r.status() + (got == null ? "" : " " + got) + (ok ? "" : ", expected " + status + (code == null ? "" : " " + code)));
        return ok;
    }

    public <T> T step(String name, Supplier<T> action) {
        try {
            T v = action.get();
            return v;
        } catch (Http.ApiException e) {
            fail(name, e.getMessage());
            throw new Abort(name + ": " + e.getMessage());
        }
    }

    // ---- groups ----

    /** Counts groups made since the last reset, so the ledger checks can name the group id (ids restart at 1 after a reset). */
    public String newSampleGroup(java.math.BigDecimal value, int tickets, java.time.LocalDate start) {
        String code = chit.createSampleGroup(value, tickets, start, "e2e-setup");
        groupsSinceReset++;
        return code;
    }

    public Long lastGroupIdGuess() {
        return resetDone ? groupsSinceReset : null;
    }

    public void reset() {
        chit.clearData("e2e-setup");
        groupsSinceReset = 0;
        resetDone = true;
        time.sync();
    }

    /** Trial balance for the group made last, when the run started with a reset (otherwise the id is not known and the check is skipped). */
    public void ledgerInBalance(String label) {
        Long id = lastGroupIdGuess();
        if (id == null) {
            info(label + ": ledger check skipped", "the run did not start with a reset, so the group id is not known");
            return;
        }
        try {
            var tb = chit.trialBalance(id);
            check(label + ": trial balance in balance", Json.bool(tb, "inBalance"),
                    "debits " + Json.text(tb, "totalDebits") + ", credits " + Json.text(tb, "totalCredits"));
        } catch (Http.ApiException e) {
            warn(label + ": ledger check could not run", e.getMessage());
        }
    }
}
