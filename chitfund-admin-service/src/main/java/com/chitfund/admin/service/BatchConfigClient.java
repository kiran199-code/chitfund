package com.chitfund.admin.service;

import com.chitfund.admin.common.FeatureDisabledException;
import com.chitfund.admin.common.UpstreamException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * The console's way into chitfund-batch-service's admin API (/api/v1/batch-admin) with that service's batch-admin login: start and follow
 * end of day, look at the jobs, and empty the job history. This service has no database; every change is made by the batch service.
 */
@Service
public class BatchConfigClient {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ROOT = "/api/v1/batch-admin";

    private final String baseUrl;
    private final String username;
    private final String password;
    private final RestClient client;
    /** Same service, short timeout: status checks come every few seconds and must not hang the screen while the service wakes up. */
    private final RestClient statusClient;

    public BatchConfigClient(@Value("${batch.service.base-url:}") String baseUrl,
                             @Value("${batch.service.username:batch-admin}") String username,
                             @Value("${batch.service.password:}") String password) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        this.username = username;
        this.password = password == null ? "" : password;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(90_000); // a sleeping free-tier service needs about a minute to wake
        this.client = enabled()
                ? RestClient.builder().baseUrl(this.baseUrl).requestFactory(factory)
                        .defaultHeaders(h -> h.setBasicAuth(this.username, this.password)).build()
                : null;
        SimpleClientHttpRequestFactory quick = new SimpleClientHttpRequestFactory();
        quick.setConnectTimeout(10_000);
        quick.setReadTimeout(20_000);
        this.statusClient = enabled()
                ? RestClient.builder().baseUrl(this.baseUrl).requestFactory(quick)
                        .defaultHeaders(h -> h.setBasicAuth(this.username, this.password)).build()
                : null;
    }

    /** Needs the batch service address and its admin password. */
    public boolean enabled() {
        return !baseUrl.isEmpty() && !password.isEmpty();
    }

    /** GET /api/v1/batch-admin/eod/status: running flag, business date, live progress and the last run. */
    public JsonNode eodStatus() {
        return getQuick("/eod/status");
    }

    /**
     * POST /api/v1/batch-admin/eod/run: starts end of day, or resumes a failed one (202); 409 if one is already running or if the
     * business date is no longer {@code expectedBusinessDate} (the date the operator saw on screen).
     */
    public JsonNode eodRun(String expectedBusinessDate) {
        require();
        return call(() -> client.post().uri(ROOT + "/eod/run?expectedBusinessDate={d}", expectedBusinessDate)
                .retrieve().onStatus(s -> s.isError(), (req, res) -> { throw upstream(res.getStatusCode().value(), res.getBody()); })
                .body(JsonNode.class));
    }

    /** GET /api/v1/batch-admin/jobs: every configured job and its settings. */
    public JsonNode jobs() {
        return getQuick("/jobs");
    }

    /** GET /api/v1/batch-admin/status/today: per job, the last outcome and the next fire time. */
    public JsonNode statusToday() {
        return getQuick("/status/today");
    }

    /** GET /api/v1/batch-admin/jobs/{job}/executions: recent runs of one job. */
    public JsonNode executions(String jobName, int limit) {
        require();
        return call(() -> statusClient.get().uri(ROOT + "/jobs/{job}/executions?limit={n}", jobName, limit)
                .retrieve().onStatus(s -> s.isError(), (req, res) -> { throw upstream(res.getStatusCode().value(), res.getBody()); })
                .body(JsonNode.class));
    }

    /** GET /api/v1/batch-admin/maintenance/status: whether clearing is enabled, whether something is running, row counts. */
    public JsonNode maintenanceStatus() {
        return getQuick("/maintenance/status");
    }

    /** POST /api/v1/batch-admin/maintenance/clear-data: empties the job execution history (403 unless BATCH_RESET_ENABLED, 409 while busy). */
    public JsonNode maintenanceClear(String actor) {
        require();
        return call(() -> {
            RestClient.RequestBodySpec spec = client.post().uri(ROOT + "/maintenance/clear-data")
                    .contentType(MediaType.APPLICATION_JSON);
            if (actor != null && !actor.isBlank()) {
                spec = spec.header("X-Actor", actor);
            }
            return spec.body(java.util.Map.of("confirm", "RESET"))
                    .retrieve().onStatus(s -> s.isError(), (req, res) -> { throw upstream(res.getStatusCode().value(), res.getBody()); })
                    .body(JsonNode.class);
        });
    }

    private JsonNode getQuick(String path) {
        require();
        return call(() -> statusClient.get().uri(ROOT + path)
                .retrieve().onStatus(s -> s.isError(), (req, res) -> { throw upstream(res.getStatusCode().value(), res.getBody()); })
                .body(JsonNode.class));
    }

    private void require() {
        if (!enabled()) {
            throw new FeatureDisabledException(
                    "Batch service access is not set up. Set BATCH_SERVICE_URL and BATCH_ADMIN_PASSWORD on the admin service to enable it.");
        }
    }

    private static <T> T call(java.util.function.Supplier<T> action) {
        try {
            return action.get();
        } catch (UpstreamException e) {
            throw e;
        } catch (RestClientException e) {
            throw new UpstreamException(502, "Could not reach chitfund-batch-service: " + e.getMessage());
        }
    }

    private static UpstreamException upstream(int status, java.io.InputStream body) {
        String text;
        try {
            text = new String(body.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            text = "";
        }
        String msg = message(text);
        if (status == 401 || status == 403) {
            msg = status == 403 && !msg.isBlank() && msg.contains("RESET")
                    ? msg
                    : "chitfund-batch-service rejected the admin login; check BATCH_ADMIN_USERNAME / BATCH_ADMIN_PASSWORD.";
        }
        return new UpstreamException(status, msg.isBlank() ? "chitfund-batch-service returned HTTP " + status : msg);
    }

    /** Pulls "message" out of a JSON body; falls back to the raw text. */
    static String message(String body) {
        if (body == null || body.isBlank()) return "";
        try {
            JsonNode n = JSON.readTree(body);
            if (n.hasNonNull("message")) return n.get("message").asText();
        } catch (IOException ignored) {
            // not JSON
        }
        return body.length() > 300 ? body.substring(0, 300) : body;
    }
}
