package com.chitfund.service.transfer;

import com.chitfund.service.transfer.TransferModels.TransferRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** SQL of ticket transfers and replacement exits. Every method runs inside the caller's transaction. */
@Repository
public class TransferRepository {

    private static final String SELECT = "SELECT x.id, t.group_id, x.ticket_id, t.ticket_no, x.kind, x.status, x.from_customer_id, x.to_customer_id, "
            + "x.fee_amount, x.reason, x.decision_reason, x.payout_account_ref, x.previous_status, x.requested_on, x.effective_on, x.made_by, "
            + "x.checked_by, x.created_at, x.decided_at FROM chit_ticket_transfer x JOIN chit_ticket t ON t.id = x.ticket_id ";

    private final JdbcTemplate jdbc;

    public TransferRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long ticketId, long fromCustomerId, long toCustomerId, BigDecimal fee, TransferKind kind, String reason,
                       String payoutAccountRef, String previousStatus, LocalDate requestedOn, String madeBy) {
        return jdbc.query("INSERT INTO chit_ticket_transfer (ticket_id, from_customer_id, to_customer_id, fee_amount, kind, reason, "
                        + "payout_account_ref, previous_status, requested_on, made_by) VALUES (?,?,?,?,?,?,?,?,?,?) RETURNING id",
                (rs, i) -> rs.getLong(1), ticketId, fromCustomerId, toCustomerId, fee, kind.name(), reason, payoutAccountRef,
                previousStatus, Date.valueOf(requestedOn), madeBy).get(0);
    }

    public Optional<TransferRow> find(long groupId, long id) {
        return first(jdbc.query(SELECT + "WHERE t.group_id = ? AND x.id = ?", TransferRepository::map, groupId, id));
    }

    /** Same row, locked, for a decision. The group lock is already held, so this is belt and braces. */
    public Optional<TransferRow> findForUpdate(long groupId, long id) {
        return first(jdbc.query(SELECT + "WHERE t.group_id = ? AND x.id = ? FOR UPDATE OF x", TransferRepository::map, groupId, id));
    }

    public List<TransferRow> ofGroup(long groupId, String status) {
        return jdbc.query(SELECT + "WHERE t.group_id = ? AND (?::text IS NULL OR x.status = ?::text) ORDER BY x.id DESC",
                TransferRepository::map, groupId, status, status);
    }

    public List<TransferRow> ofTicket(long ticketId) {
        return jdbc.query(SELECT + "WHERE x.ticket_id = ? ORDER BY x.id DESC", TransferRepository::map, ticketId);
    }

    public boolean pendingExists(long ticketId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM chit_ticket_transfer WHERE ticket_id = ? AND status = 'PENDING'", Integer.class, ticketId);
        return n != null && n > 0;
    }

    public void approve(long id, String checkedBy, LocalDate effectiveOn) {
        jdbc.update("UPDATE chit_ticket_transfer SET status = 'APPROVED', checked_by = ?, effective_on = ?, decided_at = now() WHERE id = ?",
                checkedBy, Date.valueOf(effectiveOn), id);
    }

    public void reject(long id, String checkedBy, String reason) {
        jdbc.update("UPDATE chit_ticket_transfer SET status = 'REJECTED', checked_by = ?, decision_reason = ?, decided_at = now() WHERE id = ?",
                checkedBy, reason, id);
    }

    /** The prize of a cycle this ticket won has not been fully paid yet (the cycle is still AUCTION_CLOSED or PAYOUT_PENDING). */
    public boolean prizePayoutInProgress(long ticketId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM chit_cycle WHERE winner_ticket_id = ? AND status IN ('AUCTION_CLOSED','PAYOUT_PENDING')",
                Integer.class, ticketId);
        return n != null && n > 0;
    }

    private static TransferRow map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new TransferRow(rs.getLong("id"), rs.getLong("group_id"), rs.getLong("ticket_id"), rs.getInt("ticket_no"),
                TransferKind.valueOf(rs.getString("kind")), rs.getString("status"), rs.getLong("from_customer_id"), rs.getLong("to_customer_id"),
                rs.getBigDecimal("fee_amount"), rs.getString("reason"), rs.getString("decision_reason"), rs.getString("payout_account_ref"),
                rs.getString("previous_status"), rs.getObject("requested_on", LocalDate.class), rs.getObject("effective_on", LocalDate.class),
                rs.getString("made_by"), rs.getString("checked_by"), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("decided_at", OffsetDateTime.class));
    }

    private static <T> Optional<T> first(List<T> l) {
        return l.isEmpty() ? Optional.empty() : Optional.of(l.get(0));
    }
}
