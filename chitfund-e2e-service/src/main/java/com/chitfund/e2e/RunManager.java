package com.chitfund.e2e;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runs one test run at a time on a background thread and keeps the reports of the last few runs in memory. */
public final class RunManager {
    private static final int KEEP = 10;

    private final Config cfg;
    private final Http http;
    private final Http.Sleeper sleeper;
    private final Map<String, Report> reports = new LinkedHashMap<>();
    private final AtomicBoolean busy = new AtomicBoolean(false);

    public RunManager(Config cfg, Http http, Http.Sleeper sleeper) {
        this.cfg = cfg;
        this.http = http;
        this.sleeper = sleeper;
    }

    public boolean busy() { return busy.get(); }

    /** Starts a run; returns its report, or null when one is already running. */
    public Report start(RunOptions opt) {
        List<String> problems = cfg.problems();
        if (!problems.isEmpty()) {
            throw new IllegalStateException(String.join(" ", problems));
        }
        if (!busy.compareAndSet(false, true)) {
            return null;
        }
        Report report = new Report(opt.runId());
        report.options("scenarios " + opt.scenarios() + ", dates " + opt.dateMode() + ", " + opt.tickets() + " tickets, " + opt.customers()
                + " customers, chit value " + opt.chitValue().toPlainString() + ", reset first: " + opt.reset());
        synchronized (reports) {
            reports.put(opt.runId(), report);
            while (reports.size() > KEEP) {
                reports.remove(reports.keySet().iterator().next());
            }
        }
        Thread t = new Thread(() -> execute(opt, report), "e2e-run-" + opt.runId());
        t.setDaemon(true);
        t.start();
        return report;
    }

    public Report get(String id) {
        synchronized (reports) {
            return reports.get(id);
        }
    }

    public List<Report> all() {
        synchronized (reports) {
            List<Report> l = new ArrayList<>(reports.values());
            java.util.Collections.reverse(l);
            return l;
        }
    }

    private void execute(RunOptions opt, Report report) {
        boolean aborted = false;
        try {
            ChitApi chit = new ChitApi(cfg, http);
            PortalApi portal = new PortalApi(cfg, http);
            TimeTravel time = new TimeTravel(chit, report, opt.dateMode());
            Ctx c = new Ctx(cfg, chit, portal, time, report, opt, sleeper);
            report.scenario("preflight");
            if (!preflight(c)) {
                aborted = true;
                return;
            }
            for (String s : opt.scenarios()) {
                try {
                    switch (s) {
                        case "A" -> ScenarioA.run(c);
                        case "B" -> ScenarioB.run(c);
                        case "C" -> ScenarioC.run(c);
                        case "D" -> ScenarioD.run(c);
                        default -> throw new IllegalStateException("unknown scenario " + s);
                    }
                } catch (Ctx.Abort e) {
                    c.fail("scenario " + s + " stopped", e.getMessage());
                } catch (Http.ApiException e) {
                    c.fail("scenario " + s + " stopped on an unexpected answer", e.getMessage());
                } catch (RuntimeException e) {
                    c.fail("scenario " + s + " stopped on an error", e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
        } catch (RuntimeException e) {
            report.add(Report.Status.FAIL, "run stopped", e.getClass().getSimpleName() + ": " + e.getMessage());
            aborted = true;
        } finally {
            report.finish(aborted);
            busy.set(false);
        }
    }

    /** Checks everything a run depends on before touching data. Returns false (after recording why) when the run cannot go on. */
    boolean preflight(Ctx c) {
        boolean ok = true;
        try {
            JsonNode st = c.chit.maintenanceStatus();
            boolean enabled = Json.bool(st, "resetEnabled");
            ok &= c.check("chit-service allows reset", enabled, enabled ? "CHIT_RESET_ENABLED is on" : "set CHIT_RESET_ENABLED=true on chitfund-service");
        } catch (Http.ApiException e) {
            c.fail("chit-service reachable with these credentials", e.getMessage());
            ok = false;
        }
        try {
            c.time.sync();
            c.pass("business date is managed", "today is " + c.time.now());
        } catch (IllegalStateException | Http.ApiException e) {
            c.fail("business date can be moved", e.getMessage());
            ok = false;
        }
        try {
            Http.Resp r = c.portal.signInRaw(new Identities.Person("e2epreflight", "preflight.check@gmail.com", "Preflight").idToken());
            boolean good = r.ok();
            ok &= c.check("portal accepts mock sign-in", good, good ? "e2e profile is on"
                    : "HTTP " + r.status() + ": run chitfund-portal-service with SPRING_PROFILES_ACTIVE=e2e and PORTAL_ALLOWED_ORIGINS including " + cfg.portalOrigin);
        } catch (RuntimeException e) {
            c.fail("portal reachable", e.getMessage());
            ok = false;
        }
        if (ok && c.opt.reset()) {
            try {
                c.reset();
                c.pass("data cleared", "all chit data removed and the business date re-read: " + c.time.now());
            } catch (Http.ApiException e) {
                c.fail("clear data", e.getMessage());
                ok = false;
            }
        } else if (ok) {
            c.info("reset skipped", "the run uses the data already there; ledger checks are skipped");
        }
        return ok;
    }
}
