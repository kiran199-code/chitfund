package com.chitfund.batch.chit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The batch service's way into the chit service: the business date and end-of-day hand-over, and one call per daily job.
 * The three jobs are safe to repeat for a day (a second call changes nothing), as are the business-date calls, which is
 * what makes retrying on a timeout or a 502 safe. Only I/O errors and 429/502/503/504 are retried; anything else,
 * including 409 BUSINESS_DATE_MISMATCH, fails at once.
 *
 * <p>Not final so tests can substitute a hand-written fake.
 */
public class ChitServiceClient {

    /** The actor recorded in the chit service's audit trail for the nightly runs. */
    public static final String ACTOR = "chitfund-batch-service";

    /** One group the run could not finish; the others were done. */
    public record GroupFailure(String groupCode, String code, String message) {
    }

    /**
     * The chit business date as the chit service reports it. Dates are kept as ISO strings (no JavaTimeModule dependency).
     * {@code mode} is MANAGED (a stored date that end of day moves) or SYSTEM (the wall clock; end of day moves nothing).
     */
    public record BusinessDateInfo(String businessDate, String previousBusinessDate, String nextBusinessDate,
                                   String status, boolean workingDay, String systemDate, String mode) {
        public LocalDate date() {
            return LocalDate.parse(businessDate);
        }

        public boolean eodRunning() {
            return "EOD_RUNNING".equals(status);
        }
    }

    /**
     * What a run did. {@code figures} depend on the job: cyclesOpened, demandsRaised, advanceUsed, dividendApplied
     * (demand run); lateFeesPosted, lateFeeTotal (late-fee run); ticketsDefaulted (default check).
     */
    public record RunResult(String job, String businessDate, int groupsProcessed, int groupsFailed,
                            Map<String, BigDecimal> figures, List<GroupFailure> failures) {

        public boolean clean() {
            return groupsFailed == 0 && (failures == null || failures.isEmpty());
        }

        public String figuresText() {
            if (figures == null || figures.isEmpty()) {
                return "no figures";
            }
            return figures.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue().toPlainString()).collect(Collectors.joining(", "));
        }

        public String failuresText() {
            if (failures == null || failures.isEmpty()) {
                return "";
            }
            return failures.stream().map(f -> f.groupCode() + " (" + f.code() + ": " + f.message() + ")").collect(Collectors.joining("; "));
        }
    }

    private final ChitServiceSettings settings;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public ChitServiceClient(ChitServiceSettings settings, ObjectMapper mapper) {
        this.settings = settings;
        this.mapper = mapper.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.http = HttpClient.newBuilder().connectTimeout(settings.connectTimeout()).version(HttpClient.Version.HTTP_1_1).build();
    }

    /** False when no URL was set; the jobs then skip with a warning. */
    public boolean configured() {
        return settings.configured();
    }

    /** GET /api/v1/chit/business-date, retried for about two minutes: end of day cannot start without it. */
    public BusinessDateInfo businessDate() {
        requireConfigured();
        return call(patientAttempts(), "GET", "/api/v1/chit/business-date", null, null, BusinessDateInfo.class);
    }

    /** GET /api/v1/chit/business-date with the normal (short) retry; for status displays that must not wait for a cold-starting service. */
    public BusinessDateInfo businessDateOnce() {
        requireConfigured();
        return call(settings.maxAttempts(), "GET", "/api/v1/chit/business-date", null, null, BusinessDateInfo.class);
    }

    /** POST /api/v1/chit/business-date/eod/start: OPEN to EOD_RUNNING; 409 EOD_ALREADY_RUNNING (ChitServiceException) if it already is. */
    public BusinessDateInfo startEndOfDay(String actor) {
        requireConfigured();
        return call(patientAttempts(), "POST", "/api/v1/chit/business-date/eod/start", null, actor, BusinessDateInfo.class);
    }

    /**
     * POST /api/v1/chit/business-date/advance: close {@code expected} and open the next calendar day. Safe to repeat: a second
     * call for the same day answers with the date already moved. 409 BUSINESS_DATE_MISMATCH if the date is neither.
     */
    public BusinessDateInfo advanceBusinessDate(LocalDate expected, String actor) {
        requireConfigured();
        return call(patientAttempts(), "POST", "/api/v1/chit/business-date/advance", Map.of("expectedBusinessDate", expected.toString()),
                actor, BusinessDateInfo.class);
    }

    /** POST /api/v1/chit/batch/demand-run: opens every cycle that has fallen due and raises the instalments. */
    public RunResult runDemands(LocalDate businessDate) {
        return run("/api/v1/chit/batch/demand-run", businessDate);
    }

    /** POST /api/v1/chit/batch/late-fee-run: charges the late fee for each overdue instalment up to the date. */
    public RunResult runLateFees(LocalDate businessDate) {
        return run("/api/v1/chit/batch/late-fee-run", businessDate);
    }

    /** POST /api/v1/chit/batch/default-run: flags tickets that have breached their group's default threshold. */
    public RunResult runDefaultCheck(LocalDate businessDate) {
        return run("/api/v1/chit/batch/default-run", businessDate);
    }

    private RunResult run(String path, LocalDate businessDate) {
        requireConfigured();
        return call(settings.maxAttempts(), "POST", path + "?businessDate=" + businessDate, null, null, RunResult.class);
    }

    private void requireConfigured() {
        if (!settings.configured()) {
            throw new ChitServiceException("The chitfund service URL is not set (CHIT_SERVICE_URL)", 0, null);
        }
    }

    /** Longest pause between two attempts; the doubling backoff stops growing here. */
    private static final long MAX_BACKOFF_MILLIS = 30_000;
    /** Attempts for the calls end of day cannot do without (business date read / start / advance): about two minutes of retrying. */
    private static final int PATIENT_ATTEMPTS = 9;

    /**
     * A free-plan chit service that is cold-starting or redeploying answers 502 for up to a minute or two, so the
     * business-date calls keep retrying much longer than a job's run call does. Retrying is off when the settings say a single attempt.
     */
    private int patientAttempts() {
        return settings.maxAttempts() > 1 ? Math.max(settings.maxAttempts(), PATIENT_ATTEMPTS) : 1;
    }

    private <T> T call(int maxAttempts, String method, String pathAndQuery, Object body, String actor, Class<T> type) {
        URI uri = URI.create(settings.baseUrl() + pathAndQuery);
        String json;
        try {
            json = body == null ? null : mapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new ChitServiceException("Cannot serialise request for " + method + " " + pathAndQuery, 0, e);
        }
        ChitServiceException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpRequest.Builder req = HttpRequest.newBuilder(uri)
                        .timeout(settings.requestTimeout())
                        .header("Accept", "application/json")
                        .header("X-Actor", actor == null || actor.isBlank() ? ACTOR : actor);
                if (settings.hasCredentials()) {
                    String token = settings.username() + ":" + (settings.password() == null ? "" : settings.password());
                    req.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8)));
                }
                if (json != null) {
                    req.header("Content-Type", "application/json");
                    req.method(method, HttpRequest.BodyPublishers.ofString(json));
                } else {
                    req.method(method, HttpRequest.BodyPublishers.noBody());
                }
                HttpResponse<String> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
                int status = resp.statusCode();
                if (status >= 200 && status < 300) {
                    return parse(resp.body(), type, method, pathAndQuery);
                }
                last = new ChitServiceException(method + " " + pathAndQuery + " -> HTTP " + status + ": " + abbreviate(resp.body()), status, null);
                if (!isTransient(status)) {
                    throw last;
                }
            } catch (IOException e) {
                last = new ChitServiceException(method + " " + pathAndQuery + " failed: " + e, 0, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ChitServiceException(method + " " + pathAndQuery + " interrupted", 0, e);
            }
            if (attempt < maxAttempts) {
                sleep(Math.min(MAX_BACKOFF_MILLIS, settings.retryBackoff().toMillis() * (1L << Math.min(attempt - 1, 20))));
            }
        }
        throw last;
    }

    private <T> T parse(String body, Class<T> type, String method, String path) {
        if (body == null || body.isBlank()) {
            throw new ChitServiceException(method + " " + path + " returned an empty body", 200, null);
        }
        try {
            return mapper.readValue(body, type);
        } catch (JsonProcessingException e) {
            throw new ChitServiceException("Unreadable response from " + method + " " + path + ": " + abbreviate(body), 200, e);
        }
    }

    private static boolean isTransient(int status) {
        return status == 429 || status == 502 || status == 503 || status == 504;
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > 300 ? t.substring(0, 300) + "..." : t;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
