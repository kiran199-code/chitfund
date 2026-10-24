package com.chitfund.admin.web;

import com.chitfund.admin.service.MaintenanceService;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/maintenance")
@Tag(name = "Maintenance", description = "Start from scratch: clear all chit data and the batch job history, and reset the business date to today (done by chitfund-service and chitfund-batch-service)")
public class MaintenanceController {

    private final MaintenanceService service;

    public MaintenanceController(MaintenanceService service) {
        this.service = service;
    }

    @Operation(summary = "Whether clearing is enabled on the chit service and the batch service, row counts of both, and the business date")
    @GetMapping("/status")
    public JsonNode status() {
        return service.status();
    }

    @Operation(summary = "Delete all chit groups, tickets, payments and journals and the batch job history, and set the business date to today (irreversible)",
            description = "Body {\"confirm\":\"RESET\"} is required. chitfund-service refuses with 403 unless it runs with "
                    + "CHIT_RESET_ENABLED=true; chitfund-batch-service needs BATCH_RESET_ENABLED=true and must not be busy. The batch service is checked first, so a refusal there deletes nothing.")
    @PostMapping("/clear-data")
    public JsonNode clearData(@RequestBody(required = false) Map<String, String> body, Principal user) {
        if (body == null || !"RESET".equals(body.get("confirm"))) {
            throw new IllegalArgumentException("Type RESET to confirm clearing all data");
        }
        return service.clearData(user == null ? null : user.getName());
    }
}
