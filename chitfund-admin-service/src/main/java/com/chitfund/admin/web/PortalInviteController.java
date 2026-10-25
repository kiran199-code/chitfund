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
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Invite people to a chit group by e-mail, from the enrolment screen. Everything is done by chitfund-portal-service; this controller checks the
 * shape of the request, adds the acting person's name and an idempotency key, and passes it on. CHIT_OPS and ADMIN only (see AccessPolicy):
 * the list shows e-mail addresses.
 */
@RestController
@RequestMapping("/api/admin/portal")
@Tag(name = "Invitations", description = "Invite people to a group by e-mail; they accept on the customer portal and are enrolled (done by chitfund-portal-service)")
public class PortalInviteController {

    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9_.:\\-]{1,64}");
    private static final Pattern WORD = Pattern.compile("[A-Za-z_]{1,30}");

    private final PortalClient client;

    public PortalInviteController(PortalClient client) {
        this.client = client;
    }

    @Operation(summary = "Whether invitations are configured (portal service address and admin password set)")
    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("enabled", client.enabled());
    }

    @Operation(summary = "Invitations of a group, optionally by status (PENDING, ACCEPTED_AWAITING_PAYMENT, ACCEPTED, DECLINED, EXPIRED, REVOKED)")
    @GetMapping("/groups/{code}/invitations")
    public JsonNode list(@PathVariable String code, @RequestParam(required = false) String status) {
        Map<String, Object> q = new LinkedHashMap<>();
        if (status != null && !status.isBlank()) {
            if (!WORD.matcher(status).matches()) {
                throw new IllegalArgumentException("status is malformed");
            }
            q.put("status", status);
        }
        return client.get(path(code, ""), q);
    }

    @Operation(summary = "Invite several people by e-mail",
            description = "Body {invitees:[{email, name?, tickets? (default 1), existingCustomerId?}], expiryDays?}. The e-mail is the key: "
                    + "an e-mail that is already a customer is matched; existingCustomerId (optional) names an old customer id to attach the e-mail to. "
                    + "The result lists the invitations created and the addresses skipped, each with a reason.")
    @PostMapping("/groups/{code}/invitations")
    public JsonNode invite(@PathVariable String code, @RequestBody JsonNode body,
                           @RequestHeader(value = "Idempotency-Key", required = false) String key,
                           @RequestHeader(value = "X-Acting-As", required = false) String acting, Principal user) {
        if (body == null || !body.isObject() || !body.path("invitees").isArray() || body.path("invitees").isEmpty()) {
            throw new IllegalArgumentException("invitees must list at least one person");
        }
        return client.post(path(code, ""), body, ChitController.actor(acting, user), key(key));
    }

    @Operation(summary = "Send the invitation again with a new link (pending or expired invitations)")
    @PostMapping("/groups/{code}/invitations/{id}/resend")
    public JsonNode resend(@PathVariable String code, @PathVariable long id,
                           @RequestHeader(value = "Idempotency-Key", required = false) String key,
                           @RequestHeader(value = "X-Acting-As", required = false) String acting, Principal user) {
        return client.post(path(code, "/" + id + "/resend"), null, ChitController.actor(acting, user), key(key));
    }

    @Operation(summary = "Withdraw an invitation that is pending or waiting for payment")
    @PostMapping("/groups/{code}/invitations/{id}/revoke")
    public JsonNode revoke(@PathVariable String code, @PathVariable long id, @RequestBody(required = false) JsonNode body,
                           @RequestHeader(value = "X-Acting-As", required = false) String acting, Principal user) {
        return client.exchange(HttpMethod.POST, path(code, "/" + id + "/revoke"), Map.of(), body == null ? Map.of() : body,
                ChitController.actor(acting, user), null);
    }

    @Operation(summary = "Record that the fees and papers of an accepted invitation are in, and enrol its tickets",
            description = "Body {feesReceived:true, guarantorCustomerId?, lienReference?, payoutAccountRef?}. Each ticket is enrolled with exactly what the "
                    + "group's enrolment quote asks for. Safe to repeat: tickets already enrolled are not made twice.")
    @PostMapping("/groups/{code}/invitations/{id}/enrol")
    public JsonNode enrol(@PathVariable String code, @PathVariable long id, @RequestBody JsonNode body,
                          @RequestHeader(value = "X-Acting-As", required = false) String acting, Principal user) {
        if (body == null || !body.isObject() || !body.path("feesReceived").asBoolean(false)) {
            throw new IllegalArgumentException("Confirm that the fees were received (feesReceived: true)");
        }
        return client.exchange(HttpMethod.POST, path(code, "/" + id + "/enrol"), Map.of(), body, ChitController.actor(acting, user), null);
    }

    private static String path(String code, String tail) {
        if (code == null || !CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("code is malformed");
        }
        return "/groups/" + code + "/invitations" + tail;
    }

    private static String key(String sent) {
        return sent == null || sent.isBlank() ? UUID.randomUUID().toString() : sent.trim();
    }
}
