package com.chitfund.portal.config;

import java.util.Map;

/** The one place that maps a {@code PortalException} code to an HTTP status. Unknown codes are 422. */
public final class ErrorStatus {

    private static final Map<String, Integer> STATUS = Map.ofEntries(
            Map.entry("VALIDATION_FAILED", 400),
            Map.entry("HEADER_REQUIRED", 400),
            Map.entry("IDEMPOTENCY_KEY_REQUIRED", 400),
            Map.entry("IDEMPOTENCY_KEY_INVALID", 400),
            Map.entry("INVALID_ID_TOKEN", 401),
            Map.entry("NOT_SIGNED_IN", 401),
            Map.entry("FORBIDDEN_ORIGIN", 403),
            Map.entry("CLIENT_HEADER_REQUIRED", 403),
            Map.entry("INVITE_EMAIL_MISMATCH", 403),
            Map.entry("MEMBER_BLOCKED", 403),
            Map.entry("NOT_FOUND", 404),
            Map.entry("INVITE_NOT_AVAILABLE", 404),
            Map.entry("GROUP_NOT_FOUND", 404),
            Map.entry("IDEMPOTENCY_KEY_REUSED", 409),
            Map.entry("DATA_CONFLICT", 409),
            Map.entry("GROUP_NOT_OPEN", 409),
            Map.entry("GROUP_FULL", 409),
            Map.entry("ILLEGAL_TRANSITION", 409),
            Map.entry("INVITE_NOT_RESENDABLE", 409),
            Map.entry("INVITE_NOT_REVOCABLE", 409),
            Map.entry("INVITE_NOT_DECLINABLE", 409),
            Map.entry("GOOGLE_ACCOUNT_MISMATCH", 409),
            Map.entry("INVITE_NOT_ACCEPTABLE", 409),
            Map.entry("INVITE_NOT_AWAITING_PAYMENT", 409),
            Map.entry("ENROLMENT_NOT_OPEN", 409),
            Map.entry("EMAIL_CUSTOMER_MISMATCH", 409),
            Map.entry("CUSTOMER_EMAIL_TAKEN", 409),
            Map.entry("CUSTOMER_UNKNOWN", 404),
            Map.entry("TOO_MANY_REQUESTS", 429),
            Map.entry("CHIT_SERVICE_UNAVAILABLE", 502),
            Map.entry("SIGN_IN_NOT_CONFIGURED", 503));

    private ErrorStatus() {
    }

    public static int of(String code) {
        return STATUS.getOrDefault(code, 422);
    }
}
