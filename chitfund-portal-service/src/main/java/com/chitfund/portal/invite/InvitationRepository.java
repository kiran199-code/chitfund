package com.chitfund.portal.invite;

import com.chitfund.portal.invite.InviteModels.InviteRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class InvitationRepository {

    private static final String COLUMNS = "i.id, i.group_code, i.group_name, i.email, i.invitee_name, i.tickets_offered, i.status, i.token_hash, "
            + "i.token_version, i.expires_at, i.member_id, i.payout_account_ref, i.created_by, i.sent_at, i.first_opened_at, i.accepted_at, "
            + "i.declined_at, i.revoked_at, i.revoked_by, i.revoke_reason, i.created_at, "
            + "(SELECT o.status FROM portal_outbox o WHERE o.related_invitation_id = i.id ORDER BY o.id DESC LIMIT 1) AS mail_status, "
            + "(SELECT o.attempts FROM portal_outbox o WHERE o.related_invitation_id = i.id ORDER BY o.id DESC LIMIT 1) AS mail_attempts";

    private final JdbcTemplate jdbc;

    public InvitationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<InviteRow> MAP = (rs, i) -> {
        long member = rs.getLong("member_id");
        Long memberId = rs.wasNull() ? null : member;
        int attempts = rs.getInt("mail_attempts");
        Integer mailAttempts = rs.wasNull() ? null : attempts;
        return new InviteRow(rs.getLong("id"), rs.getString("group_code"), rs.getString("group_name"), rs.getString("email"),
                rs.getString("invitee_name"), rs.getInt("tickets_offered"), InviteStatus.valueOf(rs.getString("status")), rs.getString("token_hash"),
                rs.getInt("token_version"), rs.getObject("expires_at", OffsetDateTime.class), memberId, rs.getString("payout_account_ref"),
                rs.getString("created_by"), rs.getObject("sent_at", OffsetDateTime.class), rs.getObject("first_opened_at", OffsetDateTime.class),
                rs.getObject("accepted_at", OffsetDateTime.class), rs.getObject("declined_at", OffsetDateTime.class),
                rs.getObject("revoked_at", OffsetDateTime.class), rs.getString("revoked_by"), rs.getString("revoke_reason"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getString("mail_status"), mailAttempts);
    };

    public long insert(String groupCode, String groupName, String email, String name, int tickets, String tokenHash, OffsetDateTime expiresAt, String createdBy) {
        return jdbc.query("INSERT INTO portal_invitation (group_code, group_name, email, invitee_name, tickets_offered, token_hash, expires_at, created_by) "
                        + "VALUES (?,?,?,?,?,?,?,?) RETURNING id", (rs, i) -> rs.getLong(1),
                groupCode, groupName, email, name, tickets, tokenHash, expiresAt, createdBy).get(0);
    }

    /** The live (PENDING, AWAITING_PAYMENT or ACCEPTED) invitation for this address in this group, if any, locked for update. */
    public Optional<InviteRow> liveForUpdate(String groupCode, String email) {
        return jdbc.query("SELECT " + COLUMNS + " FROM portal_invitation i WHERE i.group_code = ? AND i.email = ? "
                        + "AND i.status IN ('PENDING','ACCEPTED_AWAITING_PAYMENT','ACCEPTED') FOR UPDATE", MAP, groupCode, email).stream().findFirst();
    }

    public Optional<InviteRow> byId(String groupCode, long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM portal_invitation i WHERE i.id = ? AND i.group_code = ?", MAP, id, groupCode).stream().findFirst();
    }

    public Optional<InviteRow> byIdForUpdate(String groupCode, long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM portal_invitation i WHERE i.id = ? AND i.group_code = ? FOR UPDATE", MAP, id, groupCode).stream().findFirst();
    }

    public Optional<InviteRow> byId(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM portal_invitation i WHERE i.id = ?", MAP, id).stream().findFirst();
    }

    public Optional<InviteRow> byTokenHashForUpdate(String tokenHash) {
        return jdbc.query("SELECT " + COLUMNS + " FROM portal_invitation i WHERE i.token_hash = ? FOR UPDATE", MAP, tokenHash).stream().findFirst();
    }

    public List<InviteRow> list(String groupCode) {
        return jdbc.query("SELECT " + COLUMNS + " FROM portal_invitation i WHERE i.group_code = ? ORDER BY i.id", MAP, groupCode);
    }

    public void markExpired(long id) {
        jdbc.update("UPDATE portal_invitation SET status = 'EXPIRED', version = version + 1 WHERE id = ? AND status IN ('PENDING','ACCEPTED_AWAITING_PAYMENT')", id);
    }

    /** A new token and a new expiry; the invitation is PENDING again and counts as not yet sent or opened. */
    public void resend(long id, String tokenHash, int tokenVersion, OffsetDateTime expiresAt) {
        jdbc.update("UPDATE portal_invitation SET status = 'PENDING', token_hash = ?, token_version = ?, expires_at = ?, sent_at = NULL, "
                + "first_opened_at = NULL, version = version + 1 WHERE id = ?", tokenHash, tokenVersion, expiresAt, id);
    }

    public void revoke(long id, String by, String reason, OffsetDateTime now) {
        jdbc.update("UPDATE portal_invitation SET status = 'REVOKED', revoked_at = ?, revoked_by = ?, revoke_reason = ?, version = version + 1 WHERE id = ?",
                now, by, reason, id);
    }

    public void decline(long id, Long memberId, OffsetDateTime now) {
        jdbc.update("UPDATE portal_invitation SET status = 'DECLINED', declined_at = ?, member_id = COALESCE(member_id, ?), version = version + 1 WHERE id = ?",
                now, memberId, id);
    }

    public void touchOpened(long id, OffsetDateTime now) {
        jdbc.update("UPDATE portal_invitation SET first_opened_at = ? WHERE id = ? AND first_opened_at IS NULL", now, id);
    }

    /** Writes EXPIRED on every live invitation past its expiry; returns how many. */
    public int expireDue(OffsetDateTime now) {
        return jdbc.update("UPDATE portal_invitation SET status = 'EXPIRED', version = version + 1 "
                + "WHERE status IN ('PENDING','ACCEPTED_AWAITING_PAYMENT') AND expires_at <= ?", now);
    }

    public int countPending(String groupCode, OffsetDateTime now) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM portal_invitation WHERE group_code = ? AND status = 'PENDING' AND expires_at > ?", Integer.class, groupCode, now);
        return n == null ? 0 : n;
    }
}
