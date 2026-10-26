package com.chitfund.portal.join;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class JoinRequestRepository {

    public record Row(long id, long memberId, String email, String memberName, String groupCode, String groupName, int tickets, String status,
                      String note, String decidedBy, OffsetDateTime decidedAt, OffsetDateTime createdAt) {
    }

    private static final String COLUMNS = "id, member_id, email, member_name, group_code, group_name, tickets, status, note, decided_by, decided_at, created_at";

    private static final RowMapper<Row> MAP = (rs, i) -> new Row(rs.getLong("id"), rs.getLong("member_id"), rs.getString("email"), rs.getString("member_name"),
            rs.getString("group_code"), rs.getString("group_name"), rs.getInt("tickets"), rs.getString("status"), rs.getString("note"), rs.getString("decided_by"),
            rs.getObject("decided_at", OffsetDateTime.class), rs.getObject("created_at", OffsetDateTime.class));

    private final JdbcTemplate jdbc;

    public JoinRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts a PENDING request unless this member already has a live one for the group; returns the new id, or empty when one already exists. */
    public Optional<Long> insertIfNoLive(long memberId, String email, String name, String groupCode, String groupName, int tickets) {
        return jdbc.query("INSERT INTO portal_join_request (member_id, email, member_name, group_code, group_name, tickets) VALUES (?,?,?,?,?,?) "
                        + "ON CONFLICT (member_id, group_code) WHERE status IN ('PENDING','APPROVED') DO NOTHING RETURNING id",
                (rs, i) -> rs.getLong(1), memberId, email, name, groupCode, groupName, tickets).stream().findFirst();
    }

    public Optional<Row> live(long memberId, String groupCode) {
        return jdbc.query("SELECT " + COLUMNS + " FROM portal_join_request WHERE member_id = ? AND group_code = ? AND status IN ('PENDING','APPROVED')", MAP, memberId, groupCode)
                .stream().findFirst();
    }

    public Optional<Row> byId(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM portal_join_request WHERE id = ?", MAP, id).stream().findFirst();
    }

    /** Locks the row for the rest of the transaction (decisions and withdrawals). */
    public Optional<Row> lockById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM portal_join_request WHERE id = ? FOR UPDATE", MAP, id).stream().findFirst();
    }

    public List<Row> forMember(long memberId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM portal_join_request WHERE member_id = ? ORDER BY id DESC LIMIT 100", MAP, memberId);
    }

    /** {@code status} null means all. Newest first. */
    public List<Row> list(String status, int limit) {
        if (status == null) {
            return jdbc.query("SELECT " + COLUMNS + " FROM portal_join_request ORDER BY id DESC LIMIT ?", MAP, limit);
        }
        return jdbc.query("SELECT " + COLUMNS + " FROM portal_join_request WHERE status = ? ORDER BY id DESC LIMIT ?", MAP, status, limit);
    }

    public int withdraw(long id) {
        return jdbc.update("UPDATE portal_join_request SET status = 'WITHDRAWN' WHERE id = ? AND status = 'PENDING'", id);
    }

    public int decide(long id, String status, String note, String actor, OffsetDateTime now) {
        return jdbc.update("UPDATE portal_join_request SET status = ?, note = ?, decided_by = ?, decided_at = ? WHERE id = ? AND status = 'PENDING'",
                status, note, actor, now, id);
    }
}
