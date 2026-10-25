package com.chitfund.admin.service;

import com.chitfund.admin.common.FeatureDisabledException;
import com.chitfund.admin.common.UpstreamException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The console's way into chitfund-portal-service's admin API (/api/v1/portal/admin) with that service's admin login: invite people to a
 * group by e-mail, follow their replies, resend, revoke, and record the receipt that enrols an accepted invitation. This service has no
 * database; the invitations live in the portal service. Every call names the acting person in X-Actor; invite and resend carry an
 * Idempotency-Key.
 */
@Service
public class PortalClient {

    static final String ROOT = "/api/v1/portal/admin";

    private final String baseUrl;
    private final String password;
    private final RestClient client;

    public PortalClient(@Value("${portal.service.base-url:}") String baseUrl,
                        @Value("${portal.service.username:portal-admin}") String username,
                        @Value("${portal.service.password:}") String password) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        this.password = password == null ? "" : password;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(120_000); // a sleeping free-tier service needs about a minute to wake
        this.client = !this.baseUrl.isEmpty() && !this.password.isEmpty()
                ? RestClient.builder().baseUrl(this.baseUrl).requestFactory(factory)
                        .defaultHeaders(h -> h.setBasicAuth(username, this.password)).build()
                : null;
    }

    /** Needs the portal service's address and its admin password. */
    public boolean enabled() {
        return !baseUrl.isEmpty() && !password.isEmpty();
    }

    public JsonNode get(String path, Map<String, ?> query) {
        return exchange(HttpMethod.GET, path, query, null, null, null);
    }

    public JsonNode post(String path, Object body, String actor, String idempotencyKey) {
        return exchange(HttpMethod.POST, path, Map.of(), body, actor, idempotencyKey);
    }

    /** One call to {@code /api/v1/portal/admin<path>}; query parameters that are null or blank are left out. */
    public JsonNode exchange(HttpMethod method, String path, Map<String, ?> query, Object body, String actor, String idempotencyKey) {
        if (!enabled()) {
            throw new FeatureDisabledException(
                    "The customer portal is not set up. Set PORTAL_SERVICE_URL and PORTAL_ADMIN_PASSWORD on the admin service.");
        }
        return call(() -> {
            RestClient.RequestBodySpec spec = client.method(method).uri(b -> {
                        b.path(ROOT + path);
                        query.forEach((k, v) -> {
                            if (v != null && !v.toString().isBlank()) {
                                b.queryParam(k, v);
                            }
                        });
                        return b.build();
                    })
                    .contentType(MediaType.APPLICATION_JSON);
            if (actor != null) {
                spec = spec.header("X-Actor", actor);
            }
            if (idempotencyKey != null) {
                spec = spec.header("Idempotency-Key", idempotencyKey);
            }
            return (body == null ? spec : spec.body(body)).retrieve()
                    .onStatus(s -> s.isError(), (req, res) -> { throw upstream(res.getStatusCode().value(), res.getBody()); })
                    .body(JsonNode.class);
        });
    }

    private static <T> T call(Supplier<T> action) {
        try {
            return action.get();
        } catch (UpstreamException e) {
            throw e;
        } catch (RestClientException e) {
            throw new UpstreamException(502, "Could not reach chitfund-portal-service: " + e.getMessage());
        }
    }

    private static UpstreamException upstream(int status, java.io.InputStream body) {
        String text;
        try {
            text = new String(body.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            text = "";
        }
        String msg = status == 401 || status == 403
                ? "chitfund-portal-service rejected the admin login; check PORTAL_ADMIN_USERNAME / PORTAL_ADMIN_PASSWORD."
                : ChitClient.message(text); // the portal error body has the same {code, message} shape
        return new UpstreamException(status, msg.isBlank() ? "chitfund-portal-service returned HTTP " + status : msg);
    }
}
