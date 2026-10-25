package com.chitfund.portal.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;

@Repository
public class SessionRepository {

    private final JdbcTemplate jdbc;

    public SessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long memberId, String tokenHash, OffsetDateTime expiresAt, String userAgent, String ip) {
        jdbc.update("INSERT INTO portal_session (member_id, token_hash, expires_at, user_agent, ip) VALUES (?,?,?,?,?)",
                memberId, tokenHash, expiresAt, userAgent == null ? null : (userAgent.length() > 200 ? userAgent.substring(0, 200) : userAgent), ip);
    }

    /** The member behind a live (not expired, not revoked) session whose member is not blocked. */
    public Optional<SessionMember> active(String tokenHash, OffsetDateTime now) {
        return jdbc.query("SELECT m.id, m.email, m.display_name FROM portal_session s JOIN portal_member m ON m.id = s.member_id "
                        + "WHERE s.token_hash = ? AND s.revoked_at IS NULL AND s.expires_at > ? AND m.status = 'ACTIVE'",
                (rs, i) -> new SessionMember(rs.getLong("id"), rs.getString("email"), rs.getString("display_name")), tokenHash, now).stream().findFirst();
    }

    public void revoke(String tokenHash, OffsetDateTime now) {
        jdbc.update("UPDATE portal_session SET revoked_at = ? WHERE token_hash = ? AND revoked_at IS NULL", now, tokenHash);
    }
}
