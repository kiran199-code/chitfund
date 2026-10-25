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
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.Map;

/**
 * Offers and marketing material shown to members on the customer portal. Staff write them here; chitfund-portal-service stores and serves them
 * and checks the content (title, dates, http(s) links). CHIT_OPS and ADMIN only (see AccessPolicy: everything under /api/admin/portal).
 */
@RestController
@RequestMapping("/api/admin/portal/offers")
@Tag(name = "Offers", description = "Offers and marketing material for the customer portal (stored by chitfund-portal-service)")
public class PortalOfferController {

    private final PortalClient client;

    public PortalOfferController(PortalClient client) {
        this.client = client;
    }

    @Operation(summary = "All offers with their status (LIVE, SCHEDULED, ENDED, HIDDEN)")
    @GetMapping
    public JsonNode list() {
        return client.get("/offers", Map.of());
    }

    @Operation(summary = "Create an offer",
            description = "Body {title, body?, linkUrl?, imageUrl?, audience (PUBLIC or MEMBERS), startsOn, endsOn?}. Links must be http(s) web addresses; nothing is uploaded.")
    @PostMapping
    public JsonNode create(@RequestBody JsonNode body, @RequestHeader(value = "X-Acting-As", required = false) String acting, Principal user) {
        return client.exchange(HttpMethod.POST, "/offers", Map.of(), requireObject(body), ChitController.actor(acting, user), null);
    }

    @Operation(summary = "Change an offer (the whole offer is sent again)")
    @PostMapping("/{id}")
    public JsonNode update(@PathVariable long id, @RequestBody JsonNode body, @RequestHeader(value = "X-Acting-As", required = false) String acting, Principal user) {
        return client.exchange(HttpMethod.POST, "/offers/" + id, Map.of(), requireObject(body), ChitController.actor(acting, user), null);
    }

    @Operation(summary = "Stop showing an offer (it can be shown again)")
    @PostMapping("/{id}/hide")
    public JsonNode hide(@PathVariable long id, @RequestHeader(value = "X-Acting-As", required = false) String acting, Principal user) {
        return client.exchange(HttpMethod.POST, "/offers/" + id + "/hide", Map.of(), Map.of(), ChitController.actor(acting, user), null);
    }

    @Operation(summary = "Show a hidden offer again")
    @PostMapping("/{id}/show")
    public JsonNode show(@PathVariable long id, @RequestHeader(value = "X-Acting-As", required = false) String acting, Principal user) {
        return client.exchange(HttpMethod.POST, "/offers/" + id + "/show", Map.of(), Map.of(), ChitController.actor(acting, user), null);
    }

    private static JsonNode requireObject(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new IllegalArgumentException("The offer is required");
        }
        return body;
    }
}
