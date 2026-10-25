package com.chitfund.portal.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;

@Repository
public class MemberRepository {

    public record MemberRow(long id, String email, String displayName, String googleSub, String status) {
    }

    private static final RowMapper<MemberRow> MAP = (rs, i) ->
            new MemberRow(rs.getLong("id"), rs.getString("email"), rs.getString("display_name"), rs.getString("google_sub"), rs.getString("status"));

    private final JdbcTemplate jdbc;

    public MemberRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<MemberRow> bySub(String sub) {
        return jdbc.query("SELECT id, email, display_name, google_sub, status FROM portal_member WHERE google_sub = ?", MAP, sub).stream().findFirst();
    }

    public Optional<MemberRow> byEmail(String email) {
        return jdbc.query("SELECT id, email, display_name, google_sub, status FROM portal_member WHERE email = ?", MAP, email).stream().findFirst();
    }

    public long insert(String email, String name, String sub, OffsetDateTime now) {
        return jdbc.query("INSERT INTO portal_member (email, display_name, google_sub, last_login_at) VALUES (?,?,?,?) RETURNING id",
                (rs, i) -> rs.getLong(1), email, name, sub, now).get(0);
    }

    public void linkSub(long id, String sub) {
        jdbc.update("UPDATE portal_member SET google_sub = ? WHERE id = ? AND google_sub IS NULL", sub, id);
    }

    /** Records the sign-in and keeps the name Google gave. The stored email is never changed here (see PortalAuthService). */
    public void touchLogin(long id, String name, OffsetDateTime now) {
        jdbc.update("UPDATE portal_member SET last_login_at = ?, display_name = COALESCE(?, display_name) WHERE id = ?", now, name, id);
    }
}
