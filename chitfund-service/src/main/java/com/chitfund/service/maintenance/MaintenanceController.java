package com.chitfund.service.maintenance;

import com.chitfund.service.common.Actor;
import com.chitfund.service.maintenance.MaintenanceDtos.ClearRequest;
import com.chitfund.service.maintenance.MaintenanceDtos.ClearResult;
import com.chitfund.service.maintenance.MaintenanceDtos.StatusView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/chit/maintenance")
@Tag(name = "Maintenance", description = "Start from scratch in a non-production environment: clear all chit data")
public class MaintenanceController {

    private final MaintenanceService service;

    public MaintenanceController(MaintenanceService service) {
        this.service = service;
    }

    @GetMapping("/status")
    @Operation(summary = "Whether clearing is enabled and the row counts (chit.maintenance.reset)")
    public StatusView status() {
        return service.status();
    }

    @PostMapping("/clear-data")
    @Operation(summary = "Delete all chit groups, tickets, payments, journals, events and audit rows (chit.maintenance.reset)",
            description = "Irreversible. Refused (403 RESET_DISABLED) unless CHIT_RESET_ENABLED=true, and (400) without "
                    + "{\"confirm\":\"RESET\"}.")
    public ClearResult clear(@RequestBody(required = false) ClearRequest request,
                             @RequestHeader(value = "X-Actor", required = false) String actor) {
        return service.clearAll(request == null ? null : request.confirm(), Actor.orService(actor));
    }
}
