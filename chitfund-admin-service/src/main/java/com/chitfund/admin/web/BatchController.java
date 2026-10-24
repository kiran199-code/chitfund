package com.chitfund.admin.web;

import com.chitfund.admin.service.BatchConfigClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.regex.Pattern;

/** The batch jobs, read through chitfund-batch-service: their settings, last outcome and next run. Settings are changed there, not here. */
@RestController
@RequestMapping("/api/admin/batch")
@Tag(name = "Batch jobs", description = "The chit jobs and their last runs (read from chitfund-batch-service)")
public class BatchController {

    private static final Pattern JOB = Pattern.compile("[A-Z0-9_]{1,80}");

    private final BatchConfigClient batch;

    public BatchController(BatchConfigClient batch) {
        this.batch = batch;
    }

    @Operation(summary = "Every job with its settings, and the last outcome and next fire time of each")
    @GetMapping("/jobs")
    public JsonNode jobs() {
        JsonNode jobs = batch.jobs();
        JsonNode status = batch.statusToday();
        ObjectNode byName = JsonNodes.object();
        if (status != null && status.isArray()) {
            status.forEach(s -> byName.set(s.path("jobName").asText(), s));
        }
        ArrayNode out = JsonNodes.array();
        if (jobs != null && jobs.isArray()) {
            for (JsonNode j : jobs) {
                ObjectNode row = j.deepCopy();
                JsonNode s = byName.path(j.path("jobName").asText());
                copy(row, s, "running");
                copy(row, s, "lastStatus");
                copy(row, s, "lastExitCode");
                copy(row, s, "lastRunStartTime");
                copy(row, s, "nextFireTime");
                out.add(row);
            }
        }
        return out;
    }

    private static void copy(ObjectNode row, JsonNode from, String field) {
        if (from.has(field)) {
            row.set(field, from.get(field));
        } else {
            row.putNull(field);
        }
    }

    @Operation(summary = "Recent runs of one job")
    @GetMapping("/jobs/{jobName}/executions")
    public JsonNode executions(@PathVariable String jobName, @RequestParam(defaultValue = "10") int limit) {
        if (!JOB.matcher(jobName).matches()) {
            throw new IllegalArgumentException("jobName must be upper-case letters, digits and underscores");
        }
        return batch.executions(jobName, Math.max(1, Math.min(limit, 50)));
    }
}
