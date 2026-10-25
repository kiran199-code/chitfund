package com.chitfund.portal.idempotency;

import com.chitfund.portal.common.PortalException;
import com.chitfund.portal.idempotency.IdempotencyCheck.Replay;
import com.chitfund.portal.idempotency.IdempotencyCheck.Stored;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Claims an idempotency key inside the caller's transaction. The first caller inserts the row and goes on; a later caller with the
 * same key either gets the earlier result back (same request) or a 409 (different request). Racing callers are serialised by the
 * primary key.
 */
@Component
public class IdempotencyStore {

    private final JdbcTemplate jdbc;

    public IdempotencyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Returns null when this call owns the key, or the replay of the earlier identical request. */
    public Replay begin(String key, String operation, String requestHash) {
        if (key == null || key.isBlank()) {
            throw PortalException.badRequest("IDEMPOTENCY_KEY_REQUIRED", "An idempotency key is required for this operation");
        }
        if (key.length() > 100) {
            throw PortalException.badRequest("IDEMPOTENCY_KEY_INVALID", "The idempotency key is longer than 100 characters");
        }
        int inserted = jdbc.update(
                "INSERT INTO idempotency_key (idem_key, operation, request_hash) VALUES (?,?,?) ON CONFLICT (idem_key) DO NOTHING",
                key, operation, requestHash);
        if (inserted == 1) {
            return null;
        }
        List<Stored> rows = jdbc.query("SELECT operation, request_hash, result_ref FROM idempotency_key WHERE idem_key = ?",
                (rs, i) -> new Stored(rs.getString("operation"), rs.getString("request_hash"), rs.getString("result_ref")), key);
        if (rows.isEmpty()) {
            throw PortalException.conflict("DATA_CONFLICT", "Idempotency key '" + key + "' changed while being read; retry");
        }
        return IdempotencyCheck.decide(key, rows.get(0), operation, requestHash);
    }

    public void complete(String key, String resultRef) {
        jdbc.update("UPDATE idempotency_key SET result_ref = ? WHERE idem_key = ?", resultRef, key);
    }
}
