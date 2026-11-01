package com.chitfund.e2e;

import com.chitfund.e2e.Http.ApiException;
import com.chitfund.e2e.Http.Resp;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Calls to chitfund-portal-service: the member side (with a session per mock customer) and the staff side (portal admin login). */
public class PortalApi {
    public static final String ROOT = "/api/v1/portal";
    private final Config cfg;
    private final Http http;
    private final String adminAuth;

    public PortalApi(Config cfg, Http http) {
        this.cfg = cfg;
        this.http = http;
        this.adminAuth = Http.basic(cfg.portalAdminUser, cfg.portalAdminPassword);
    }

    /** A signed-in mock customer: the session cookie value the portal issued. */
    public static final class Session {
        public final String email;
        public final long memberId;
        private final String cookie;

        Session(String email, long memberId, String cookie) {
            this.email = email;
            this.memberId = memberId;
            this.cookie = cookie;
        }
    }

    private Map<String, String> memberHeaders(Session s, boolean write) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Accept", "application/json");
        h.put("Cookie", "portal_session=" + s.cookie);
        if (write) {
            h.put("Content-Type", "application/json");
            h.put("X-Portal-Client", "web");
            h.put("Origin", cfg.portalOrigin);
        }
        return h;
    }

    public Resp signInRaw(String idToken) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/json");
        h.put("Accept", "application/json");
        h.put("X-Portal-Client", "web");
        h.put("Origin", cfg.portalOrigin);
        return http.send("POST", cfg.portalUrl + ROOT + "/auth/google", h, Json.write(Json.obj().put("idToken", idToken)));
    }

    public Session signIn(Identities.Person p) {
        Resp r = signInRaw(p.idToken());
        if (!r.ok()) throw new ApiException(r.status(), "POST", ROOT + "/auth/google", r.body());
        String cookie = null;
        for (String sc : r.headers("Set-Cookie")) {
            if (sc.startsWith("portal_session=")) {
                cookie = sc.substring("portal_session=".length(), sc.indexOf(';') > 0 ? sc.indexOf(';') : sc.length());
            }
        }
        if (cookie == null || cookie.isBlank()) throw new IllegalStateException("sign-in gave no session cookie for " + p.signInEmail());
        JsonNode m = r.json();
        return new Session(Json.text(m, "email"), m.path("id").asLong(), cookie);
    }

    private JsonNode memberGet(Session s, String path) {
        Resp r = http.send("GET", cfg.portalUrl + ROOT + path, memberHeaders(s, false), null);
        if (!r.ok()) throw new ApiException(r.status(), "GET", ROOT + path, r.body());
        return r.json();
    }

    public Resp memberPostRaw(Session s, String path, JsonNode body) {
        return http.send("POST", cfg.portalUrl + ROOT + path, memberHeaders(s, true), Json.write(body == null ? Json.obj() : body));
    }

    private JsonNode memberPost(Session s, String path, JsonNode body) {
        Resp r = memberPostRaw(s, path, body);
        if (!r.ok()) throw new ApiException(r.status(), "POST", ROOT + path, r.body());
        return r.json();
    }

    // ---- public ----

    public JsonNode publicPlans() {
        Resp r = http.send("GET", cfg.portalUrl + ROOT + "/plans/public", Map.of("Accept", "application/json"), null);
        if (!r.ok()) throw new ApiException(r.status(), "GET", ROOT + "/plans/public", r.body());
        return r.json();
    }

    // ---- member ----

    public JsonNode requestSeat(Session s, String groupCode, int tickets) {
        return memberPost(s, "/me/join-requests", Json.obj().put("groupCode", groupCode).put("tickets", tickets));
    }

    public Resp requestSeatRaw(Session s, String groupCode, Integer tickets) {
        ObjectNode b = Json.obj().put("groupCode", groupCode);
        if (tickets != null) b.put("tickets", tickets);
        return memberPostRaw(s, "/me/join-requests", b);
    }

    public JsonNode myRequests(Session s) { return memberGet(s, "/me/join-requests"); }

    public JsonNode withdrawRequest(Session s, long id) { return memberPost(s, "/me/join-requests/" + id + "/withdraw", null); }

    public JsonNode dashboard(Session s) { return memberGet(s, "/me/dashboard"); }

    public JsonNode myTickets(Session s) { return memberGet(s, "/me/tickets"); }

    public JsonNode myPayments(Session s) { return memberGet(s, "/me/payments"); }

    public JsonNode myAuctions(Session s) { return memberGet(s, "/me/auctions"); }

    public Resp bidRaw(Session s, String groupCode, int cycleNo, int ticketNo, BigDecimal discount) {
        ObjectNode b = Json.obj().put("groupCode", groupCode).put("cycleNo", cycleNo).put("ticketNo", ticketNo);
        b.put("discountAmount", discount);
        Map<String, String> h = memberHeaders(s, true);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return http.send("POST", cfg.portalUrl + ROOT + "/me/bids", h, Json.write(b));
    }

    // ---- staff ----

    private Map<String, String> adminHeaders(String actor, boolean write) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", adminAuth);
        h.put("Accept", "application/json");
        if (write) h.put("Content-Type", "application/json");
        if (actor != null) h.put("X-Actor", actor);
        return h;
    }

    public JsonNode adminJoinRequests(String status) {
        String q = status == null ? "" : "?status=" + status;
        Resp r = http.send("GET", cfg.portalUrl + ROOT + "/admin/join-requests" + q, adminHeaders(null, false), null);
        if (!r.ok()) throw new ApiException(r.status(), "GET", ROOT + "/admin/join-requests", r.body());
        return r.json();
    }

    public JsonNode decide(long id, boolean approve, String note, String actor) {
        String path = "/admin/join-requests/" + id + (approve ? "/approve" : "/decline");
        Resp r = http.send("POST", cfg.portalUrl + ROOT + path, adminHeaders(actor, true), Json.write(Json.obj().put("note", note)));
        if (!r.ok()) throw new ApiException(r.status(), "POST", ROOT + path, r.body());
        return r.json();
    }
}
