package com.chitfund.e2e;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What a run did, step by step, as JSON and as a plain HTML page. A step is PASS, FAIL, WARN (something unexpected that does not prove a
 * defect, for example a rule that is switched off) or INFO (a value worth recording). Nothing secret is ever put in here.
 */
public final class Report {
    public enum Status { PASS, FAIL, WARN, INFO }

    public record Step(int no, String scenario, String name, Status status, String detail, String businessDate, long millis) {
    }

    private final String runId;
    private final Instant started = Instant.now();
    private volatile Instant finished;
    private volatile String state = "RUNNING";
    private final List<Step> steps = Collections.synchronizedList(new ArrayList<>());
    private final List<String> timeline = Collections.synchronizedList(new ArrayList<>());
    private volatile String businessDate = "";
    private volatile String scenario = "";
    private long lastTick = System.currentTimeMillis();
    private volatile String options = "";

    public Report(String runId) {
        this.runId = runId;
    }

    public String runId() { return runId; }

    public void options(String o) { this.options = o; }

    public void scenario(String s) { this.scenario = s; }

    public void businessDate(String d) { this.businessDate = d; }

    public synchronized Step add(Status status, String name, String detail) {
        long now = System.currentTimeMillis();
        Step s = new Step(steps.size() + 1, scenario, name, status, detail == null ? "" : detail, businessDate, now - lastTick);
        lastTick = now;
        steps.add(s);
        return s;
    }

    /** One line of the date timeline: how the business date moved and which jobs ran. */
    public void timeline(String line) { timeline.add(line); }

    public List<Step> steps() { return List.copyOf(steps); }

    public long count(Status s) { return steps().stream().filter(x -> x.status() == s).count(); }

    public boolean failed() { return count(Status.FAIL) > 0; }

    public void finish(boolean aborted) {
        finished = Instant.now();
        state = aborted ? "ABORTED" : failed() ? "FAILED" : "PASSED";
    }

    public String state() { return state; }

    public ObjectNode summary() {
        ObjectNode o = Json.obj();
        o.put("runId", runId).put("state", state).put("scenario", scenario).put("businessDate", businessDate);
        o.put("started", started.toString()).put("finished", finished == null ? null : finished.toString());
        o.put("pass", count(Status.PASS)).put("fail", count(Status.FAIL)).put("warn", count(Status.WARN)).put("info", count(Status.INFO));
        o.put("options", options);
        return o;
    }

    public ObjectNode toJson() {
        ObjectNode o = summary();
        ArrayNode a = o.putArray("steps");
        for (Step s : steps()) {
            ObjectNode n = a.addObject();
            n.put("no", s.no()).put("scenario", s.scenario()).put("name", s.name()).put("status", s.status().name());
            n.put("detail", s.detail()).put("businessDate", s.businessDate()).put("millis", s.millis());
        }
        ArrayNode t = o.putArray("dateTimeline");
        synchronized (timeline) {
            timeline.forEach(t::add);
        }
        return o;
    }

    public String toHtml() {
        StringBuilder b = new StringBuilder();
        b.append("<!doctype html><html lang=en><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>")
                .append("<title>E2E run ").append(esc(runId)).append("</title><style>")
                .append("body{font:15px/1.45 system-ui,sans-serif;margin:16px;max-width:1100px}table{border-collapse:collapse;width:100%}")
                .append("td,th{border-bottom:1px solid #ddd;padding:6px 8px;text-align:left;vertical-align:top}")
                .append(".PASS{color:#0a7d33}.FAIL{color:#b00020;font-weight:700}.WARN{color:#a15c00}.INFO{color:#555}")
                .append("pre{white-space:pre-wrap;background:#f6f6f6;padding:8px}</style>");
        b.append("<h1>Run ").append(esc(runId)).append(": ").append(state).append("</h1>");
        b.append("<p>").append(count(Status.PASS)).append(" passed, ").append(count(Status.FAIL)).append(" failed, ")
                .append(count(Status.WARN)).append(" warnings, ").append(count(Status.INFO)).append(" notes. Started ").append(started)
                .append(finished == null ? " (still running)" : ", finished " + finished).append(".</p>");
        b.append("<p>").append(esc(options)).append("</p>");
        b.append("<table><tr><th>#<th>Scenario<th>Business date<th>Step<th>Result<th>Detail<th>ms</tr>");
        for (Step s : steps()) {
            b.append("<tr><td>").append(s.no()).append("<td>").append(esc(s.scenario())).append("<td>").append(esc(s.businessDate()))
                    .append("<td>").append(esc(s.name())).append("<td class=").append(s.status()).append(">").append(s.status())
                    .append("<td>").append(esc(s.detail())).append("<td>").append(s.millis()).append("</tr>");
        }
        b.append("</table><h2>Business date timeline</h2><pre>");
        synchronized (timeline) {
            timeline.forEach(l -> b.append(esc(l)).append('\n'));
        }
        b.append("</pre></html>");
        return b.toString();
    }

    static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
