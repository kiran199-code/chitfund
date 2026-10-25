package com.chitfund.admin.service;

import com.chitfund.admin.common.FeatureDisabledException;
import com.chitfund.admin.common.UpstreamException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Start from scratch across two services. chitfund-service clears the groups, tickets, payments and journals and puts the business date on
 * today's date; chitfund-batch-service empties the job execution history, so a job is not refused for a business date it already ran for
 * before the clear. The console itself has no database and never writes to one.
 *
 * <p>Order matters: before anything is deleted, the batch service is asked whether it allows the clear and whether it is busy, so a
 * switched-off, busy or unreachable batch service stops the whole action instead of leaving the chit data cleared and the job history
 * behind. When the batch service is not set up on this console (no BATCH_SERVICE_URL / BATCH_ADMIN_PASSWORD) that part is skipped and the
 * answer says so.
 */
@Service
public class MaintenanceService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ChitClient chit;
    private final BatchConfigClient batch;

    public MaintenanceService(ChitClient chit, BatchConfigClient batch) {
        this.chit = chit;
        this.batch = batch;
    }

    /**
     * The chit service's maintenance status (resetEnabled, rowCounts) plus {@code businessDate} and {@code businessDateStatus}, and a
     * {@code batch} object (configured, reachable, resetEnabled, busy, busyReason, rowCounts, error).
     */
    public JsonNode status() {
        ObjectNode out = asObject(chit.get("/maintenance/status", Map.of()));
        JsonNode d = chit.get("/business-date", Map.of());
        out.put("businessDate", d.path("businessDate").asText(null));
        out.put("businessDateStatus", d.path("status").asText(null));
        out.put("businessDateMode", d.path("mode").asText(null));
        batchStatus(out.putObject("batch"));
        return out;
    }

    /** Whether the batch service is set up, reachable and reports nothing running; the screen uses it to tell a failed end of day from a live one. */
    private void batchStatus(ObjectNode b) {
        b.put("configured", batch.enabled());
        if (!batch.enabled()) {
            return;
        }
        try {
            JsonNode st = batch.maintenanceStatus();
            b.put("reachable", true);
            b.put("resetEnabled", st.path("resetEnabled").asBoolean(false));
            b.put("busy", st.path("busy").asBoolean(false));
            if (st.hasNonNull("busyReason")) {
                b.put("busyReason", st.get("busyReason").asText());
            }
            b.set("rowCounts", st.path("rowCounts").isMissingNode() ? JSON.createObjectNode() : st.get("rowCounts"));
        } catch (RuntimeException e) {
            // The page must still load when the batch service is asleep.
            b.put("reachable", false);
            b.put("resetEnabled", false);
            b.put("busy", false);
            b.set("rowCounts", JSON.createObjectNode());
            b.put("error", e.getMessage());
        }
    }

    /** Clears the chit service, then the batch history. The result is the chit service's answer with a {@code batch} object added. */
    public JsonNode clearData(String actor) {
        boolean batchIdle = false;
        if (batch.enabled()) {
            JsonNode st = batch.maintenanceStatus(); // unreachable or too old: stops here, nothing deleted yet
            if (!st.path("resetEnabled").asBoolean(false)) {
                throw new FeatureDisabledException("Clearing is switched off on chitfund-batch-service. Set BATCH_RESET_ENABLED=true on it "
                        + "and redeploy; otherwise the chit data would be cleared and the old job history left behind, and end of day would refuse "
                        + "dates that already ran.");
            }
            if (st.path("busy").asBoolean(false)) {
                throw new UpstreamException(409, st.path("busyReason").asText("The batch service is busy") + ". Wait for it to finish, then clear again. Nothing was deleted.");
            }
            batchIdle = true;
        }
        JsonNode d = chit.get("/business-date", Map.of());
        if ("EOD_RUNNING".equals(d.path("status").asText()) && !batchIdle) {
            // End of day marked the date as running; without a verified-idle batch service we cannot tell a live run from a failed one.
            throw new UpstreamException(409, "End of day is marked as running and the batch service cannot confirm that it is idle. "
                    + "Wait for end of day to finish (or connect and check the batch service), then clear again. Nothing was deleted.");
        }
        ObjectNode out = asObject(chit.exchange(HttpMethod.POST, "/maintenance/clear-data", Map.of(), Map.of("confirm", "RESET"), actor, null));
        if (batch.enabled()) {
            try {
                ObjectNode b = asObject(batch.maintenanceClear(actor));
                b.put("configured", true);
                out.set("batch", b);
                out.put("message", out.path("message").asText("") + " " + b.path("message").asText(""));
            } catch (UpstreamException e) {
                throw new UpstreamException(e.getStatus(), "The chit data was cleared and the business date was reset, but clearing "
                        + "the batch job history failed: " + e.getMessage() + " Press Clear all data again to retry (it clears the chit data a second time, which is harmless).");
            }
        } else {
            out.putObject("batch").put("configured", false);
            out.put("message", out.path("message").asText("") + " The batch job history was not cleared because this console is not "
                    + "connected to chitfund-batch-service.");
        }
        return out;
    }

    /** Asks chitfund-service to create {@code count} sample groups (chit value, tickets, start date). The chit service validates and answers with the new group codes. */
    public JsonNode createSampleGroups(Map<String, Object> body, String actor) {
        return chit.exchange(HttpMethod.POST, "/maintenance/sample-groups", Map.of(), body, actor, null);
    }

    private static ObjectNode asObject(JsonNode n) {
        if (n instanceof ObjectNode o) {
            return o;
        }
        ObjectNode wrap = JSON.createObjectNode();
        if (n != null && !n.isNull()) {
            wrap.set("result", n);
        }
        return wrap;
    }
}
