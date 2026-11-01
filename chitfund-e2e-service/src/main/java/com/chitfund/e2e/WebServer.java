package com.chitfund.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.Executors;

/** The page and small API for starting a run and reading its report. Everything except /health needs the E2E_UI password. */
public final class WebServer {
    private final Config cfg;
    private final RunManager runs;
    private HttpServer server;

    public WebServer(Config cfg, RunManager runs) {
        this.cfg = cfg;
        this.runs = runs;
    }

    public int start(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/health", ex -> send(ex, 200, "application/json", "{\"status\":\"UP\"}"));
        server.createContext("/", this::route);
        server.start();
        return server.getAddress().getPort();
    }

    public void stop() {
        if (server != null) server.stop(0);
    }

    private void route(HttpExchange ex) throws IOException {
        try {
            if (!authorised(ex)) {
                ex.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"e2e\"");
                send(ex, 401, "application/json", "{\"error\":\"sign in\"}");
                return;
            }
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if (method.equals("GET") && path.equals("/")) {
                send(ex, 200, "text/html; charset=utf-8", page());
            } else if (method.equals("POST") && path.equals("/api/runs")) {
                startRun(ex);
            } else if (method.equals("GET") && path.equals("/api/runs")) {
                ArrayNode a = Json.arr();
                runs.all().forEach(r -> a.add(r.summary()));
                send(ex, 200, "application/json", Json.write(a));
            } else if (method.equals("GET") && path.startsWith("/api/runs/")) {
                Report r = runs.get(path.substring("/api/runs/".length()));
                if (r == null) send(ex, 404, "application/json", "{\"error\":\"no such run\"}");
                else send(ex, 200, "application/json", Json.write(r.toJson()));
            } else if (method.equals("GET") && path.startsWith("/runs/")) {
                Report r = runs.get(path.substring("/runs/".length()));
                if (r == null) send(ex, 404, "text/plain", "no such run");
                else send(ex, 200, "text/html; charset=utf-8", r.toHtml());
            } else {
                send(ex, 404, "application/json", "{\"error\":\"not found\"}");
            }
        } catch (RuntimeException e) {
            send(ex, 500, "application/json", Json.write(Json.obj().put("error", String.valueOf(e.getMessage()))));
        }
    }

    private void startRun(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        RunOptions opt;
        try {
            JsonNode n = body.isBlank() ? Json.obj() : Json.read(body);
            opt = RunOptions.from(n);
        } catch (IllegalArgumentException e) {
            send(ex, 400, "application/json", Json.write(Json.obj().put("error", e.getMessage())));
            return;
        }
        Report r;
        try {
            r = runs.start(opt);
        } catch (IllegalStateException e) {
            send(ex, 503, "application/json", Json.write(Json.obj().put("error", e.getMessage())));
            return;
        }
        if (r == null) {
            send(ex, 409, "application/json", "{\"error\":\"a run is already in progress\"}");
            return;
        }
        ObjectNode o = Json.obj().put("runId", opt.runId()).put("report", "/runs/" + opt.runId());
        send(ex, 202, "application/json", Json.write(o));
    }

    boolean authorised(HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("Authorization");
        if (h == null || !h.startsWith("Basic ") || cfg.uiPassword.isBlank()) return false;
        try {
            String got = new String(Base64.getDecoder().decode(h.substring(6)), StandardCharsets.UTF_8);
            String want = cfg.uiUser + ":" + cfg.uiPassword;
            return MessageDigest.isEqual(got.getBytes(StandardCharsets.UTF_8), want.getBytes(StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static void send(HttpExchange ex, int status, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    private String page() {
        StringBuilder b = new StringBuilder();
        b.append("""
                <!doctype html><html lang=en><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>
                <title>Chit fund end-to-end tests</title>
                <style>body{font:15px/1.45 system-ui,sans-serif;margin:16px;max-width:900px}label{display:block;margin:8px 0}
                input,select{padding:4px}button{padding:8px 16px;margin-top:8px}#msg{margin-top:12px;font-weight:600}
                table{border-collapse:collapse;width:100%;margin-top:16px}td,th{border-bottom:1px solid #ddd;padding:6px;text-align:left}</style>
                <h1>Chit fund end-to-end tests</h1>
                <p>Test environment only. A run clears the chit data (if ticked), moves the business date forward and creates sample groups and mock customers.</p>
                <form id=f>
                <fieldset><legend>Scenarios</legend>
                <label><input type=checkbox name=sc value=A checked> A: full cycle (seat requests, payments, bidding, payout)</label>
                <label><input type=checkbox name=sc value=B checked> B: late fee, default and cure</label>
                <label><input type=checkbox name=sc value=C checked> C: bidding rules and no-bid draw</label>
                <label><input type=checkbox name=sc value=D checked> D: Gmail variants, bad tokens, limits</label></fieldset>
                <label>Dates <select name=dateMode><option value=DAY_BY_DAY>Day by day (runs the jobs every day)</option><option value=LEAP>Leap (jump, run jobs once)</option></select></label>
                <label>Tickets <input name=tickets type=number value=10 min=4 max=40></label>
                <label>Customers <input name=customers type=number value=6 min=2 max=40></label>
                <label>Chit value <input name=chitValue value=100000></label>
                <label><input type=checkbox name=reset checked> Clear all chit data first</label>
                <button>Start run</button></form><div id=msg></div>
                <h2>Recent runs</h2><table id=t><tr><th>Run<th>State<th>Pass<th>Fail<th>Warn<th>Now</tr></table>
                <script>
                const f=document.getElementById('f'),msg=document.getElementById('msg'),t=document.getElementById('t');
                f.onsubmit=async e=>{e.preventDefault();const d=new FormData(f);
                const body={scenarios:d.getAll('sc'),dateMode:d.get('dateMode'),tickets:+d.get('tickets'),customers:+d.get('customers'),chitValue:d.get('chitValue'),reset:d.get('reset')==='on'};
                const r=await fetch('/api/runs',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});
                const j=await r.json();msg.textContent=r.ok?'Started run '+j.runId:(j.error||'Could not start');load();};
                async function load(){const r=await fetch('/api/runs');if(!r.ok)return;const a=await r.json();
                t.innerHTML='<tr><th>Run<th>State<th>Pass<th>Fail<th>Warn<th>Now</tr>';
                a.forEach(x=>{const tr=t.insertRow();const c=tr.insertCell();const l=document.createElement('a');l.href='/runs/'+x.runId;l.textContent=x.runId;c.appendChild(l);
                [x.state,x.pass,x.fail,x.warn,x.scenario+' '+x.businessDate].forEach(v=>{tr.insertCell().textContent=v;});});}
                load();setInterval(load,4000);
                </script></html>
                """);
        return b.toString();
    }
}
