package com.chitfund.admin.web;

import com.chitfund.admin.service.PortalClient;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.Map;

/** The organiser's text on the customer portal: how to pay, help line and office address (checked and stored by chitfund-portal-service). */
@RestController
@RequestMapping("/api/admin/portal/settings")
@Tag(name = "Portal settings", description = "How to pay, help line and address shown on the customer portal")
public class PortalSettingsController {

    private final PortalClient client;

    public PortalSettingsController(PortalClient client) {
        this.client = client;
    }

    @Operation(summary = "The current texts (blank when not set)")
    @GetMapping
    public JsonNode get() {
        return client.get("/settings", Map.of());
    }

    @Operation(summary = "Save texts", description = "Body is an object with any of payment_instructions, help_phone, help_hours, office_address; a blank value clears one.")
    @PostMapping
    public JsonNode save(@RequestBody JsonNode body, @RequestHeader(value = "X-Acting-As", required = false) String acting, Principal user) {
        if (body == null || !body.isObject() || body.isEmpty()) {
            throw new IllegalArgumentException("Nothing to save");
        }
        return client.exchange(HttpMethod.POST, "/settings", Map.of(), body, ChitController.actor(acting, user), null);
    }
}
