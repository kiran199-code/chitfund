package com.chitfund.e2e;

import com.chitfund.e2e.Http.ApiException;
import com.chitfund.e2e.Http.Resp;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Calls to chitfund-service ({@code /api/v1/chit}). Every write carries an Idempotency-Key and the acting name. */
public class ChitApi {
    private final Config cfg;
    private final Http http;
    private final String auth;

    public ChitApi(Config cfg, Http http) {
        this.cfg = cfg;
        this.http = http;
        this.auth = Http.basic(cfg.chitUser, cfg.chitPassword);
    }

    // ---- plumbing ----

    public Resp raw(String method, String path, Map<String, ?> query, JsonNode body, String actor, String key) {
        StringBuilder url = new StringBuilder(cfg.chitUrl).append("/api/v1/chit").append(path);
        if (query != null && !query.isEmpty()) {
            String sep = "?";
            for (Map.Entry<String, ?> e : query.entrySet()) {
                if (e.getValue() == null) continue;
                url.append(sep).append(e.getKey()).append('=').append(URLEncoder.encode(String.valueOf(e.getValue()), StandardCharsets.UTF_8));
                sep = "&";
            }
        }
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", auth);
        h.put("Accept", "application/json");
        if (body != null) h.put("Content-Type", "application/json");
        if (!"GET".equals(method)) {
            h.put("Idempotency-Key", key == null ? UUID.randomUUID().toString() : key);
            if (actor != null) h.put("X-Actor", actor);
        }
        return http.send(method, url.toString(), h, body == null ? ("GET".equals(method) ? null : "{}") : Json.write(body));
    }

    public JsonNode call(String method, String path, Map<String, ?> query, JsonNode body, String actor, String key) {
        Resp r = raw(method, path, query, body, actor, key);
        if (!r.ok()) throw new ApiException(r.status(), method, "/api/v1/chit" + path, r.body());
        return r.json();
    }

    private JsonNode get(String path) {
        return call("GET", path, null, null, null, null);
    }

    private JsonNode get(String path, Map<String, ?> q) {
        return call("GET", path, q, null, null, null);
    }

    private JsonNode post(String path, JsonNode body, String actor) {
        return call("POST", path, null, body, actor, null);
    }

    // ---- business date and the daily jobs ----

    public JsonNode businessDate() { return get("/business-date"); }

    public JsonNode setBusinessDate(LocalDate d, String actor) {
        return call("PUT", "/business-date", null, Json.obj().put("businessDate", d.toString()), actor, null);
    }

    public Resp startEndOfDay(String actor) { return raw("POST", "/business-date/eod/start", null, null, actor, null); }

    public JsonNode advance(LocalDate expected, String actor) {
        return call("POST", "/business-date/advance", null, Json.obj().put("expectedBusinessDate", expected.toString()), actor, null);
    }

    public JsonNode demandRun(LocalDate d, String actor) { return call("POST", "/batch/demand-run", Map.of("businessDate", d.toString()), null, actor, null); }

    public JsonNode lateFeeRun(LocalDate d, String actor) { return call("POST", "/batch/late-fee-run", Map.of("businessDate", d.toString()), null, actor, null); }

    public JsonNode defaultRun(LocalDate d, String actor) { return call("POST", "/batch/default-run", Map.of("businessDate", d.toString()), null, actor, null); }

    // ---- maintenance (test environments only) ----

    public JsonNode maintenanceStatus() { return get("/maintenance/status"); }

    public JsonNode clearData(String actor) { return call("POST", "/maintenance/clear-data", null, Json.obj().put("confirm", "RESET"), actor, null); }

    /** One DRAFT group SAMPLE-nnn with the platform's ordinary sample rules; returns its code. */
    public String createSampleGroup(BigDecimal chitValue, int tickets, LocalDate startDate, String actor) {
        ObjectNode b = Json.obj().put("count", 1).put("ticketCount", tickets).put("startDate", startDate.toString());
        b.put("chitValue", chitValue);
        JsonNode r = call("POST", "/maintenance/sample-groups", null, b, actor, null);
        return r.path("groupCodes").path(0).asText();
    }

    // ---- groups, enrolment ----

    public JsonNode group(String code) { return get("/groups/" + code); }

    public JsonNode rules(String code) { return get("/groups/" + code + "/rules").path("rules"); }

    public JsonNode putRules(String code, JsonNode rules, String actor) { return call("PUT", "/groups/" + code + "/rules", null, rules, actor, null); }

    public JsonNode setListing(String code, String category, String branch, String state, String actor) {
        return call("PUT", "/groups/" + code + "/listing", null, Json.obj().put("planCategory", category).put("branch", branch).put("state", state), actor, null);
    }

    public JsonNode openEnrolment(String code, String actor) { return post("/groups/" + code + "/open-enrolment", null, actor); }

    public JsonNode quote(String code) { return get("/groups/" + code + "/enrolment-quote"); }

    public JsonNode resolveCustomer(String email, String name, String actor) {
        return post("/customers/resolve", Json.obj().put("email", email).put("name", name), actor);
    }

    /** Enrols one ticket, sending exactly what the quote says is due (nothing extra). Returns the ticket. */
    public JsonNode enrol(String code, long customerId, String payoutAccountRef, JsonNode quote, String actor) {
        ObjectNode b = Json.obj().put("customerId", customerId).put("payoutAccountRef", payoutAccountRef);
        putIfPositive(b, "enrolmentFeeReceived", Json.money(quote, "enrolmentFee"));
        putIfPositive(b, "registrationFeeReceived", Json.money(quote, "registrationFee"));
        putIfPositive(b, "securityDepositReceived", Json.money(quote, "securityDeposit"));
        return post("/groups/" + code + "/tickets", b, actor);
    }

    public Resp enrolRaw(String code, long customerId, String payoutAccountRef, String actor) {
        return raw("POST", "/groups/" + code + "/tickets", null, Json.obj().put("customerId", customerId).put("payoutAccountRef", payoutAccountRef), actor, null);
    }

    private static void putIfPositive(ObjectNode b, String f, BigDecimal v) {
        if (v != null && v.signum() > 0) b.put(f, v);
    }

    public JsonNode tickets(String code) { return get("/groups/" + code + "/tickets"); }

    public JsonNode activationCheck(String code) { return get("/groups/" + code + "/activation-check"); }

    public JsonNode activate(String code, String actor) { return post("/groups/" + code + "/activate", null, actor); }

    public JsonNode cycles(String code) { return get("/groups/" + code + "/cycles"); }

    // ---- collection ----

    public JsonNode demands(String code) { return get("/groups/" + code + "/demands", Map.of("limit", 500)); }

    public JsonNode statement(String code, int ticketNo) { return get("/groups/" + code + "/tickets/" + ticketNo + "/statement"); }

    public JsonNode defaultCases(String code) { return get("/groups/" + code + "/default-cases"); }

    public JsonNode pay(String code, int ticketNo, BigDecimal amount, String externalRef, String key, String actor) {
        ObjectNode b = Json.obj().put("channel", "UPI").put("externalRef", externalRef);
        b.put("amount", amount);
        return call("POST", "/groups/" + code + "/tickets/" + ticketNo + "/payments", null, b, actor, key);
    }

    // ---- auction, payout, dividends ----

    public JsonNode auction(String code, int cycleNo) { return get("/groups/" + code + "/cycles/" + cycleNo + "/auction"); }

    public JsonNode gate(String code, int cycleNo) { return get("/groups/" + code + "/cycles/" + cycleNo + "/auction/gate"); }

    public JsonNode openAuction(String code, int cycleNo, String actor) { return post("/groups/" + code + "/cycles/" + cycleNo + "/auction/open", null, actor); }

    public Resp bidRaw(String code, int cycleNo, int ticketNo, BigDecimal discount, String key, String actor) {
        ObjectNode b = Json.obj().put("ticketNo", ticketNo);
        b.put("discountAmount", discount);
        return raw("POST", "/groups/" + code + "/cycles/" + cycleNo + "/auction/bids", null, b, actor, key);
    }

    public JsonNode withdrawBid(String code, int cycleNo, int bidSeq, String actor) {
        return post("/groups/" + code + "/cycles/" + cycleNo + "/auction/bids/" + bidSeq + "/withdraw", Json.obj().put("reason", "e2e withdrawal"), actor);
    }

    public JsonNode closeAuction(String code, int cycleNo, String actor) { return post("/groups/" + code + "/cycles/" + cycleNo + "/auction/close", null, actor); }

    public Resp payoutRaw(String code, int cycleNo, String step, String actor) {
        return raw("POST", "/groups/" + code + "/cycles/" + cycleNo + "/payout/" + step, null, null, actor, null);
    }

    public JsonNode payout(String code, int cycleNo, String step, String actor) { return post("/groups/" + code + "/cycles/" + cycleNo + "/payout/" + step, null, actor); }

    public JsonNode payoutView(String code, int cycleNo) { return get("/groups/" + code + "/cycles/" + cycleNo + "/payout"); }

    public Resp payDividendsRaw(String code, int cycleNo, String actor) { return raw("POST", "/groups/" + code + "/cycles/" + cycleNo + "/dividends/pay", null, null, actor, null); }

    public JsonNode draws(String code) { return get("/groups/" + code + "/draws"); }

    // ---- ledger ----

    public JsonNode trialBalance(long groupId) { return get("/ledger/groups/" + groupId + "/trial-balance"); }
}
