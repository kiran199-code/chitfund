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
import java.util.Optional;

/**
 * GET /api/v1/chit/groups/{code} on chitfund-service with the service credential. Reads the fields it needs from the JSON tree, so
 * unknown fields are ignored and no date module is needed. Reads only; the portal changes nothing in chitfund-service in this step.
 */
@Component
public class HttpChitGroupGateway implements ChitGroupGateway {

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
