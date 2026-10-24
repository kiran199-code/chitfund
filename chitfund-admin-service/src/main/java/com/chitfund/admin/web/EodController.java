package com.chitfund.admin.web;

import com.chitfund.admin.common.FeatureDisabledException;
import com.chitfund.admin.common.UpstreamException;
import com.chitfund.admin.service.BatchConfigClient;
import com.chitfund.admin.service.ChitClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

/**
 * End of day from the console: start or resume a run on chitfund-batch-service and follow it. The run itself, and its progress, live in the
 * batch service; this controller forwards the two calls and adds the business date as the chit service shows it, so the date is still
 * visible when the batch service is asleep.
 */
@RestController
@RequestMapping("/api/admin/eod")
@Tag(name = "End of day", description = "Start, resume and follow the end-of-day batch (run by chitfund-batch-service)")
public class EodController {

    private final BatchConfigClient batch;
    private final ChitClient chit;

    public EodController(BatchConfigClient batch, ChitClient chit) {
        this.batch = batch;
        this.chit = chit;
    }

    @Operation(summary = "Business date, whether end of day is running, per-job progress and the last run",
            description = "Safe to poll. When the batch service cannot be reached the answer still carries the "
                    + "chit service's business date, with reachable=false and the reason in error.")
    @GetMapping("/status")
    public JsonNode status() {
        ObjectNode out = JsonNodes.object();
        out.put("configured", batch.enabled());
        try {
            JsonNode d = chit.get("/business-date", Map.of());
            out.put("chitBusinessDate", d.path("businessDate").asText(null));
            out.put("chitStatus", d.path("status").asText(null));
            out.put("chitMode", d.path("mode").asText(null));
        } catch (UpstreamException | FeatureDisabledException e) {
            out.putNull("chitBusinessDate");
            out.putNull("chitStatus");
            out.put("chitError", e.getMessage());
        }
        if (!batch.enabled()) {
            out.put("reachable", false);
            out.put("error", "The admin service does not know the batch service address or password. "
                    + "Set BATCH_SERVICE_URL and BATCH_ADMIN_PASSWORD on it.");
            return out;
        }
        try {
            JsonNode s = batch.eodStatus();
            out.put("reachable", true);
            out.set("running", s.path("running"));
            out.set("businessDate", s.path("businessDate"));
            out.set("progress", s.path("progress"));
            out.set("lastRun", s.path("lastRun"));
        } catch (UpstreamException e) {
            out.put("reachable", false);
            out.put("error", e.getMessage());
        }
        return out;
    }

    @Operation(summary = "Start end of day for the current business date, or resume a failed run",
            description = "Body {\"expectedBusinessDate\":\"yyyy-MM-dd\"} is required: it is the date the operator saw on "
                    + "screen. If the business date has moved since, nothing starts (409), so a stale screen cannot close the wrong day. "
                    + "The batch service answers 202 when started and 409 when a run is already in progress.")
    @PostMapping("/run")
    public JsonNode run(@RequestBody(required = false) Map<String, String> body) {
        String expected = body == null ? null : body.get("expectedBusinessDate");
        if (expected == null || expected.isBlank()) {
            throw new IllegalArgumentException("expectedBusinessDate is required: the business date shown on the screen");
        }
        LocalDate expectedDate;
        try {
            expectedDate = LocalDate.parse(expected.trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("expectedBusinessDate must look like 2026-11-02");
        }
        // The batch service reads the date from the chit service itself and refuses (409) if it is not the one on screen.
        return batch.eodRun(expectedDate.toString());
    }
}
