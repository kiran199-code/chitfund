package com.chitfund.portal.chit;

import com.chitfund.portal.common.PortalException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Calls chitfund-service with the service credential: reads a group and the enrolment quote, finds or creates a customer by e-mail, and enrols
 * tickets. Reads the fields it needs from the JSON tree, so unknown fields are ignored and no date module is needed. A refusal from
 * chitfund-service (HTTP 4xx) becomes a PortalException that keeps chitfund-service's own error code and message.
 */
@Component
public class HttpChitGroupGateway implements ChitGroupGateway, ChitEnrolmentGateway {

    private final ObjectMapper mapper;
    private final HttpClient http;
    private final String baseUrl;
    private final String authorization;
    private final Duration requestTimeout;

    public HttpChitGroupGateway(ObjectMapper mapper,
                                @Value("${chit.service.base-url:}") String baseUrl,
                                @Value("${chit.service.username:}") String username,
                                @Value("${chit.service.password:}") String password,
                                @Value("${chit.service.connect-timeout-seconds:5}") long connectTimeoutSeconds,
                                @Value("${chit.service.request-timeout-seconds:20}") long requestTimeoutSeconds) {
        this.mapper = mapper;
        String b = baseUrl == null ? "" : baseUrl.trim();
        this.baseUrl = b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
        this.authorization = username == null || username.isBlank() ? null
                : "Basic " + Base64.getEncoder().encodeToString((username + ":" + (password == null ? "" : password)).getBytes(StandardCharsets.UTF_8));
        this.requestTimeout = Duration.ofSeconds(requestTimeoutSeconds);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(connectTimeoutSeconds)).version(HttpClient.Version.HTTP_1_1).build();
    }

    @Override
    public Optional<GroupSummary> group(String groupCode) {
        if (baseUrl.isEmpty()) {
            throw new PortalException("CHIT_SERVICE_UNAVAILABLE", "The chitfund service address is not set (CHIT_SERVICE_BASE_URL)");
        }
        String path = "/api/v1/chit/groups/" + URLEncoder.encode(groupCode, StandardCharsets.UTF_8);
        try {
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(requestTimeout)
                    .header("Accept", "application/json").header("X-Actor", "chitfund-portal-service").GET();
            if (authorization != null) {
                req.header("Authorization", authorization);
            }
            HttpResponse<String> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
            int status = resp.statusCode();
            if (status == 404) {
                return Optional.empty();
            }
            if (status < 200 || status >= 300) {
                throw new PortalException("CHIT_SERVICE_UNAVAILABLE", "chitfund-service answered HTTP " + status + " for " + path);
            }
            return Optional.of(parse(mapper.readTree(resp.body())));
        } catch (IOException e) {
            throw new PortalException("CHIT_SERVICE_UNAVAILABLE", "chitfund-service could not be reached: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PortalException("CHIT_SERVICE_UNAVAILABLE", "The call to chitfund-service was interrupted");
        }
    }

    // ---- enrolment and customers ------------------------------------------------------------------------------------

    @Override
    public Quote quote(String groupCode) {
        JsonNode n = call("GET", "/api/v1/chit/groups/" + enc(groupCode) + "/enrolment-quote", null, null);
        return new Quote(dec0(n, "enrolmentFee"), dec0(n, "registrationFee"), dec0(n, "securityDeposit"), dec0(n, "lienAmount"),
                n.path("guarantorRequired").asBoolean(false), dec0(n, "cashDue"));
    }

    @Override
    public CustomerRef resolveCustomer(String email, String name, Long existingCustomerId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("name", name);
        body.put("existingCustomerId", existingCustomerId);
        JsonNode n = call("POST", "/api/v1/chit/customers/resolve", body, null);
        return new CustomerRef(n.path("customerId").asLong(), n.path("created").asBoolean(false), n.path("linkedExisting").asBoolean(false));
    }

    @Override
    public Map<String, Long> lookupCustomers(List<String> emails) {
        Map<String, Long> out = new LinkedHashMap<>();
        if (emails.isEmpty()) {
            return out;
        }
        JsonNode n = call("POST", "/api/v1/chit/customers/lookup", Map.of("emails", emails), null).path("customerIds");
        n.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asLong()));
        return out;
    }

    @Override
    public int enrol(String groupCode, long customerId, Received r, String idempotencyKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customerId", customerId);
        body.put("payoutAccountRef", r.payoutAccountRef());
        body.put("enrolmentFeeReceived", r.enrolmentFee());
        body.put("registrationFeeReceived", r.registrationFee());
        body.put("securityDepositReceived", r.securityDeposit());
        body.put("guarantorCustomerId", r.guarantorCustomerId());
        body.put("lienReference", r.lienReference());
        JsonNode n = call("POST", "/api/v1/chit/groups/" + enc(groupCode) + "/tickets", body, idempotencyKey);
        return n.path("ticketNo").asInt();
    }

    /** One call; returns the JSON body. 4xx keeps chitfund-service's code and message; anything else is CHIT_SERVICE_UNAVAILABLE. */
    private JsonNode call(String method, String path, Object body, String idempotencyKey) {
        if (baseUrl.isEmpty()) {
            throw new PortalException("CHIT_SERVICE_UNAVAILABLE", "The chitfund service address is not set (CHIT_SERVICE_BASE_URL)");
        }
        try {
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(requestTimeout)
                    .header("Accept", "application/json").header("X-Actor", "chitfund-portal-service");
            if (authorization != null) {
                req.header("Authorization", authorization);
            }
            if (idempotencyKey != null) {
                req.header("Idempotency-Key", idempotencyKey);
            }
            if ("POST".equals(method)) {
                req.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body == null ? Map.of() : body)));
            } else {
                req.GET();
            }
            HttpResponse<String> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
            int status = resp.statusCode();
            if (status >= 200 && status < 300) {
                return resp.body() == null || resp.body().isBlank() ? mapper.createObjectNode() : mapper.readTree(resp.body());
            }
            if (status >= 400 && status < 500) {
                JsonNode err = parseQuietly(resp.body());
                String code = err != null && err.hasNonNull("code") ? err.get("code").asText() : "CHIT_SERVICE_REFUSED";
                String message = err != null && err.hasNonNull("message") ? err.get("message").asText() : "chitfund-service refused the request (HTTP " + status + ")";
                throw new PortalException(code, message);
            }
            throw new PortalException("CHIT_SERVICE_UNAVAILABLE", "chitfund-service answered HTTP " + status + " for " + path);
        } catch (IOException e) {
            throw new PortalException("CHIT_SERVICE_UNAVAILABLE", "chitfund-service could not be reached: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PortalException("CHIT_SERVICE_UNAVAILABLE", "The call to chitfund-service was interrupted");
        }
    }

    private JsonNode parseQuietly(String body) {
        try {
            return body == null || body.isBlank() ? null : mapper.readTree(body);
        } catch (IOException e) {
            return null;
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static BigDecimal dec0(JsonNode n, String f) {
        BigDecimal v = dec(n, f);
        return v == null ? BigDecimal.ZERO : v;
    }

    static GroupSummary parse(JsonNode n) {
        return new GroupSummary(text(n, "groupCode"), text(n, "name"), text(n, "status"), dec(n, "chitValue"), integer(n, "ticketCount"),
                integer(n, "cycleCount"), dec(n, "subscription"), date(n, "startDate"), integer(n, "seatsLeft"));
    }

    private static String text(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static BigDecimal dec(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v == null || v.isNull() ? null : new BigDecimal(v.asText());
    }

    private static Integer integer(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v == null || v.isNull() ? null : v.asInt();
    }

    private static LocalDate date(JsonNode n, String f) {
        String s = text(n, f);
        return s == null ? null : LocalDate.parse(s);
    }
}
