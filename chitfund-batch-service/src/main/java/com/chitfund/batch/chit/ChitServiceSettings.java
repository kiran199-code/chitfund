package com.chitfund.batch.chit;

import java.time.Duration;

/**
 * Connection settings for {@link ChitServiceClient} (env {@code CHIT_SERVICE_URL}, {@code CHIT_SERVICE_USERNAME},
 * {@code CHIT_SERVICE_PASSWORD}). The chit service is this batch service's only upstream. With no URL every call fails
 * with a clear message ({@code configured()} is false), and the prod profile refuses to start without one.
 */
public record ChitServiceSettings(
        String baseUrl,
        String username,
        String password,
        Duration connectTimeout,
        Duration requestTimeout,
        int maxAttempts,
        Duration retryBackoff
) {
    public ChitServiceSettings {
        baseUrl = baseUrl == null || baseUrl.isBlank() ? "" : (baseUrl.trim().endsWith("/") ? baseUrl.trim().substring(0, baseUrl.trim().length() - 1) : baseUrl.trim());
        connectTimeout = connectTimeout != null ? connectTimeout : Duration.ofSeconds(5);
        // A run can touch many groups, and a free-plan service may need a minute to wake first.
        requestTimeout = requestTimeout != null ? requestTimeout : Duration.ofSeconds(240);
        maxAttempts = Math.max(1, maxAttempts);
        retryBackoff = retryBackoff != null ? retryBackoff : Duration.ofMillis(500);
    }

    public boolean configured() {
        return !baseUrl.isEmpty();
    }

    public boolean hasCredentials() {
        return username != null && !username.isBlank();
    }
}
