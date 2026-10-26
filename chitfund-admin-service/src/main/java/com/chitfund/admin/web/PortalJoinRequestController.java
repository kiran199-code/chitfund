package com.chitfund.admin.web;

import com.chitfund.admin.service.PortalClient;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Seat requests that members make on the customer portal ("Request Seat"). Staff approve or decline them here; approving only records the
 * decision, staff then enrol the member through the normal enrolment steps. Everything is stored by chitfund-portal-service.
 * CHIT_OPS and ADMIN only (see AccessPolicy: everything under /api/admin/portal); the list shows e-mail addresses.
 */
@RestController
@RequestMapping("/api/admin/portal")
@Tag(name = "Join requests", description = "Seat requests from the customer portal; staff approve or decline (stored by chitfund-portal-service)")
public class PortalJoinRequestController {

    private static final Pattern WORD = Pattern.compile("[A-Za-z_]{1,30}");

    private final PortalClient client;

    public PortalJoinRequestController(PortalClient client) {
        this.client = client;
    }

    @Operation(summary = "Whether the customer portal is configured (portal service address and admin password set)")
    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("enabled", client.enabled());
    }

    @Operation(summary = "Join requests, optionally by status (PENDING, APPROVED, DECLINED, WITHDRAWN)")
    @GetMapping("/join-requests")
    public JsonNode list(@RequestParam(required = false) String status) {
        Map<String, Object> q = new LinkedHashMap<>();
        if (status != null && !status.isBlank()) {
            if (!WORD.matcher(status).matches()) {
                throw new IllegalArgumentException("status must be upper-case letters, for example PENDING");
            }
            q.put("status", status);
        }
        return client.get("/join-requests", q);
    }

    @Operation(summary = "Approve a pending request", description = "Body {note?}. Takes no seat and charges nothing; staff enrol the member afterwards.")
    @PostMapping("/join-requests/{id}/approve")
    public JsonNode approve(@PathVariable long id, @RequestBody(required = false) JsonNode body,
                            @RequestHeader(value = "X-Acting-As", required = false) String acting, Principal user) {
        return client.exchange(HttpMethod.POST, "/join-requests/" + id + "/approve", Map.of(), noteOnly(body), ChitController.actor(acting, user), null);
    }

    @Operation(summary = "Decline a pending request", description = "Body {note?}, shown to the member.")
    @PostMapping("/join-requests/{id}/decline")
    public JsonNode decline(@PathVariable long id, @RequestBody(required = false) JsonNode body,
                            @RequestHeader(value = "X-Acting-As", required = false) String acting, Principal user) {
        return client.exchange(HttpMethod.POST, "/join-requests/" + id + "/decline", Map.of(), noteOnly(body), ChitController.actor(acting, user), null);
    }

    private static Map<String, Object> noteOnly(JsonNode body) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (body != null && body.hasNonNull("note")) {
            String note = body.get("note").asText().trim();
            if (note.length() > 300) {
                throw new IllegalArgumentException("The note may be up to 300 characters");
            }
            m.put("note", note);
        }
        return m;
    }
}
