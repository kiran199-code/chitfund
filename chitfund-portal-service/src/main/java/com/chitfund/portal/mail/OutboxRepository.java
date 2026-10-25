package com.chitfund.portal.mail;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class OutboxRepository {

    public record Due(long id, String kind, String to, String subject, String text, int attempts, Long invitationId) {
    }

    public record Row(long id, String kind, String to, String subject, String status, int attempts, String lastError, String dedupeKey,
                      boolean bodyRedacted, OffsetDateTime sentAt) {
    }

    private final JdbcTemplate jdbc;

    public OutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Queues a mail; false if one with the same dedupe key already exists (a rerun), in which case nothing is written. */
    public boolean enqueue(String kind, String to, String subject, String text, String dedupeKey, Long invitationId, OffsetDateTime now) {
        List<Long> id = jdbc.query("INSERT INTO portal_outbox (kind, recipient_email, subject, body_text, dedupe_key, related_invitation_id, next_attempt_at) "
                        + "VALUES (?,?,?,?,?,?,?) ON CONFLICT (dedupe_key) DO NOTHING RETURNING id",
                (rs, i) -> rs.getLong(1), kind, to, subject, text, dedupeKey, invitationId, now);
        return !id.isEmpty();
    }

    /** Locks the mails that are due; another instance running at the same time skips the ones this one holds. */
    public List<Due> lockDue(OffsetDateTime now, int limit) {
        return jdbc.query("SELECT id, kind, recipient_email, subject, body_text, attempts, related_invitation_id FROM portal_outbox "
                        + "WHERE status = 'PENDING' AND next_attempt_at <= ? ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED",
                (rs, i) -> {
                    long inv = rs.getLong("related_invitation_id");
                    Long invitationId = rs.wasNull() ? null : inv;
                    return new Due(rs.getLong("id"), rs.getString("kind"), rs.getString("recipient_email"), rs.getString("subject"),
                            rs.getString("body_text"), rs.getInt("attempts"), invitationId);
                }, now, limit);
    }

    /** Marks a mail sent and blanks its body, so an invite link does not stay in the database. */
    public void markSent(long id, String providerMessageId, OffsetDateTime now) {
        jdbc.update("UPDATE portal_outbox SET status = 'SENT', sent_at = ?, attempts = attempts + 1, provider_message_id = ?, last_error = NULL, "
                + "body_text = '(sent; body removed)', body_redacted = TRUE WHERE id = ?", now, providerMessageId, id);
    }

    public void markFailed(long id, String error, OffsetDateTime nextAttemptAt, boolean giveUp) {
        String e = error == null ? "unknown error" : (error.length() > 500 ? error.substring(0, 500) : error);
        jdbc.update("UPDATE portal_outbox SET attempts = attempts + 1, last_error = ?, status = ?, next_attempt_at = ? WHERE id = ?",
                e, giveUp ? "FAILED" : "PENDING", nextAttemptAt, id);
    }

    public void touchInvitationSent(long invitationId, OffsetDateTime now) {
        jdbc.update("UPDATE portal_invitation SET sent_at = ? WHERE id = ?", now, invitationId);
    }

    public Optional<Row> byDedupeKey(String dedupeKey) {
        List<Row> rows = jdbc.query("SELECT id, kind, recipient_email, subject, status, attempts, last_error, dedupe_key, body_redacted, sent_at "
                        + "FROM portal_outbox WHERE dedupe_key = ?",
                (rs, i) -> new Row(rs.getLong("id"), rs.getString("kind"), rs.getString("recipient_email"), rs.getString("subject"),
                        rs.getString("status"), rs.getInt("attempts"), rs.getString("last_error"), rs.getString("dedupe_key"),
                        rs.getBoolean("body_redacted"), rs.getObject("sent_at", OffsetDateTime.class)), dedupeKey);
        return rows.stream().findFirst();
    }
}
