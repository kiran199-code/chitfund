package com.chitfund.portal.idempotency;

import com.chitfund.portal.common.PortalException;

/** The decision half of idempotency, without the database: same request again, or a different one reusing the key? */
public final class IdempotencyCheck {

    public record Stored(String operation, String requestHash, String resultRef) {
    }

    public record Replay(String resultRef) {
    }

    private IdempotencyCheck() {
    }

    public static Replay decide(String key, Stored stored, String operation, String requestHash) {
        if (!stored.operation().equals(operation) || !stored.requestHash().equals(requestHash)) {
            throw PortalException.conflict("IDEMPOTENCY_KEY_REUSED", "Idempotency key '" + key + "' was already used for a different request");
        }
        return new Replay(stored.resultRef());
    }
}
