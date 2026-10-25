package com.chitfund.service.recovery;

import com.chitfund.service.auction.AuctionModels.CoverRow;
import com.chitfund.service.recovery.RecoveryModels.ActionRow;
import com.chitfund.service.recovery.RecoveryModels.CaseRow;
import com.chitfund.service.recovery.RecoveryModels.WriteOffRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** SQL of recovery: default case figures, recovery steps, foreman covers and write-offs. Runs inside the caller's transaction. */
@Repository
public class RecoveryRepository {

    private static final String CASE_SELECT = "SELECT id, ticket_id, status, opened_on, shortfall_amount, security_applied, cover_recovered, "
            + "recovered_amount, written_off_amount, foreman_covered, closed_on FROM chit_default_case ";

    private static final String COVER_SELECT = "SELECT f.id, f.cycle_id, c.cycle_no, f.ticket_id, t.ticket_no, f.demand_id, f.amount, f.recovered_amount, "
            + "f.status, f.covered_on FROM chit_foreman_cover f JOIN chit_cycle c ON c.id = f.cycle_id JOIN chit_ticket t ON t.id = f.ticket_id ";

    private static final String ACTION_SELECT = "SELECT a.id, a.case_id, a.ticket_id, t.ticket_no, a.kind, a.arrears_amount, a.guarantee_amount, a.channel, "
            + "a.external_ref, a.payment_ref, a.business_date, a.created_by, a.created_at FROM chit_recovery_action a JOIN chit_ticket t ON t.id = a.ticket_id ";

    private static final String WRITE_OFF_SELECT = "SELECT w.id, w.case_id, w.ticket_id, t.ticket_no, w.status, w.subscription_amount, w.cover_amount, "
            + "w.fee_amount, w.reason, w.made_by, w.checked_by, w.decision_reason, w.requested_on, w.decided_on, w.created_at, w.decided_at "
            + "FROM chit_write_off w JOIN chit_ticket t ON t.id = w.ticket_id ";

    private final JdbcTemplate jdbc;

    public RecoveryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---- cases ----------------------------------------------------------------------------------------------------

    public Optional<CaseRow> openCase(long ticketId) {
        return first(jdbc.query(CASE_SELECT + "WHERE ticket_id = ? AND status = 'OPEN'", RecoveryRepository::mapCase, ticketId));
    }

    public Optional<CaseRow> openCaseForUpdate(long ticketId) {
        return first(jdbc.query(CASE_SELECT + "WHERE ticket_id = ? AND status = 'OPEN' FOR UPDATE", RecoveryRepository::mapCase, ticketId));
    }

    public Optional<CaseRow> findCase(long caseId) {
        return first(jdbc.query(CASE_SELECT + "WHERE id = ?", RecoveryRepository::mapCase, caseId));
    }

    /** The most recent case of the ticket, open or closed. */
    public Optional<CaseRow> latestCase(long ticketId) {
        return first(jdbc.query(CASE_SELECT + "WHERE ticket_id = ? ORDER BY id DESC LIMIT 1", RecoveryRepository::mapCase, ticketId));
    }

    /** Adds what a recovery step brought in; {@code recovered_amount} is always security applied plus covers repaid. */
    public void addRecovery(long caseId, BigDecimal securityApplied, BigDecimal coverRecovered) {
        jdbc.update("UPDATE chit_default_case SET security_applied = security_applied + ?, cover_recovered = cover_recovered + ?, "
                        + "recovered_amount = recovered_amount + ? + ?, version = version + 1 WHERE id = ?",
                securityApplied, coverRecovered, securityApplied, coverRecovered, caseId);
    }

    /** An approved write-off: what was written off, what the foreman paid into the pool for it, and who made and approved it. */
    public void addWriteOff(long caseId, BigDecimal writtenOff, BigDecimal foremanPaid, String madeBy, String checkedBy) {
        jdbc.update("UPDATE chit_default_case SET written_off_amount = written_off_amount + ?, foreman_covered = foreman_covered + ?, "
                        + "writeoff_made_by = ?, writeoff_checked_by = ?, version = version + 1 WHERE id = ?",
                writtenOff, foremanPaid, madeBy, checkedBy, caseId);
    }

    // ---- recovery steps -------------------------------------------------------------------------------------------

    public long insertAction(Long caseId, long ticketId, String kind, BigDecimal arrears, BigDecimal guarantee, String channel, String externalRef,
                             String paymentRef, LocalDate businessDate, String actor) {
        return jdbc.query("INSERT INTO chit_recovery_action (case_id, ticket_id, kind, arrears_amount, guarantee_amount, channel, external_ref, "
                        + "payment_ref, business_date, created_by) VALUES (?,?,?,?,?,?,?,?,?,?) RETURNING id",
                (rs, i) -> rs.getLong(1), caseId, ticketId, kind, arrears, guarantee, channel, externalRef, paymentRef, Date.valueOf(businessDate), actor).get(0);
    }

    public void setActionPaymentRef(long actionId, String paymentRef) {
        jdbc.update("UPDATE chit_recovery_action SET payment_ref = ? WHERE id = ?", paymentRef, actionId);
    }

    /** True if a foreman cover already exists for this demand (one cover per demand; the table enforces it). */
    public boolean coverExistsForDemand(long demandId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM chit_foreman_cover WHERE demand_id = ?", Integer.class, demandId);
        return n != null && n > 0;
    }

    public List<ActionRow> actionsOfTicket(long ticketId) {
        return jdbc.query(ACTION_SELECT + "WHERE a.ticket_id = ? ORDER BY a.id", RecoveryRepository::mapAction, ticketId);
    }

    // ---- foreman covers -------------------------------------------------------------------------------------------

    /** Covers of the ticket the foreman is still owed on, oldest cycle first. */
    public List<CoverRow> openCovers(long ticketId) {
        return jdbc.query(COVER_SELECT + "WHERE f.ticket_id = ? AND f.status = 'OPEN' ORDER BY c.cycle_no, f.id", RecoveryRepository::mapCover, ticketId);
    }

    public List<CoverRow> coversOfTicket(long ticketId) {
        return jdbc.query(COVER_SELECT + "WHERE f.ticket_id = ? ORDER BY c.cycle_no, f.id", RecoveryRepository::mapCover, ticketId);
    }

    /** The ticket repaid part of a cover; the cover is RECOVERED when nothing is left. */
    public void addCoverRepayment(long coverId, BigDecimal amount) {
        jdbc.update("UPDATE chit_foreman_cover SET recovered_amount = recovered_amount + ?, "
                + "status = CASE WHEN recovered_amount + ? >= amount THEN 'RECOVERED' ELSE status END WHERE id = ?", amount, amount, coverId);
    }

    public void markCoverWrittenOff(long coverId) {
        jdbc.update("UPDATE chit_foreman_cover SET status = 'WRITTEN_OFF' WHERE id = ?", coverId);
    }

    // ---- demands --------------------------------------------------------------------------------------------------

    /** A late fee the foreman gives up: it is recorded as waived and the demand ends as WRITTEN_OFF. */
    public void waiveLateFee(long demandId, BigDecimal amount) {
        jdbc.update("UPDATE chit_demand SET late_fee_waived = late_fee_waived + ?, status = 'WRITTEN_OFF', version = version + 1 WHERE id = ?", amount, demandId);
    }

    // ---- write-offs -----------------------------------------------------------------------------------------------

    public long insertWriteOff(long caseId, long ticketId, BigDecimal subscription, BigDecimal covers, BigDecimal fees, String reason, String madeBy,
                               LocalDate requestedOn) {
        return jdbc.query("INSERT INTO chit_write_off (case_id, ticket_id, subscription_amount, cover_amount, fee_amount, reason, made_by, requested_on) "
                        + "VALUES (?,?,?,?,?,?,?,?) RETURNING id", (rs, i) -> rs.getLong(1),
                caseId, ticketId, subscription, covers, fees, reason, madeBy, Date.valueOf(requestedOn)).get(0);
    }

    public Optional<WriteOffRow> findWriteOff(long groupId, long id) {
        return first(jdbc.query(WRITE_OFF_SELECT + "WHERE t.group_id = ? AND w.id = ?", RecoveryRepository::mapWriteOff, groupId, id));
    }

    public Optional<WriteOffRow> findWriteOffForUpdate(long groupId, long id) {
        return first(jdbc.query(WRITE_OFF_SELECT + "WHERE t.group_id = ? AND w.id = ? FOR UPDATE OF w", RecoveryRepository::mapWriteOff, groupId, id));
    }

    public boolean pendingWriteOffExists(long caseId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM chit_write_off WHERE case_id = ? AND status = 'PENDING'", Integer.class, caseId);
        return n != null && n > 0;
    }

    public void approveWriteOff(long id, String checkedBy, LocalDate on) {
        jdbc.update("UPDATE chit_write_off SET status = 'APPROVED', checked_by = ?, decided_on = ?, decided_at = now() WHERE id = ?", checkedBy, Date.valueOf(on), id);
    }

    public void rejectWriteOff(long id, String checkedBy, String reason, LocalDate on) {
        jdbc.update("UPDATE chit_write_off SET status = 'REJECTED', checked_by = ?, decision_reason = ?, decided_on = ?, decided_at = now() WHERE id = ?",
                checkedBy, reason, Date.valueOf(on), id);
    }

    public List<WriteOffRow> writeOffsOfGroup(long groupId, String status) {
        return jdbc.query(WRITE_OFF_SELECT + "WHERE t.group_id = ? AND (?::text IS NULL OR w.status = ?::text) ORDER BY w.id DESC",
                RecoveryRepository::mapWriteOff, groupId, status, status);
    }

    public List<WriteOffRow> writeOffsOfTicket(long ticketId) {
        return jdbc.query(WRITE_OFF_SELECT + "WHERE w.ticket_id = ? ORDER BY w.id DESC", RecoveryRepository::mapWriteOff, ticketId);
    }

    // ---- mapping --------------------------------------------------------------------------------------------------

    private static CaseRow mapCase(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new CaseRow(rs.getLong("id"), rs.getLong("ticket_id"), rs.getString("status"), rs.getObject("opened_on", LocalDate.class),
                rs.getBigDecimal("shortfall_amount"), rs.getBigDecimal("security_applied"), rs.getBigDecimal("cover_recovered"),
                rs.getBigDecimal("recovered_amount"), rs.getBigDecimal("written_off_amount"), rs.getBigDecimal("foreman_covered"),
                rs.getObject("closed_on", LocalDate.class));
    }

    private static CoverRow mapCover(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new CoverRow(rs.getLong("id"), rs.getLong("cycle_id"), rs.getInt("cycle_no"), rs.getLong("ticket_id"), rs.getInt("ticket_no"),
                rs.getLong("demand_id"), rs.getBigDecimal("amount"), rs.getBigDecimal("recovered_amount"), rs.getString("status"),
                rs.getObject("covered_on", LocalDate.class));
    }

    private static ActionRow mapAction(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        long caseNo = rs.getLong("case_id");
        Long caseId = rs.wasNull() ? null : caseNo;
        return new ActionRow(rs.getLong("id"), caseId, rs.getLong("ticket_id"), rs.getInt("ticket_no"), rs.getString("kind"),
                rs.getBigDecimal("arrears_amount"), rs.getBigDecimal("guarantee_amount"), rs.getString("channel"), rs.getString("external_ref"),
                rs.getString("payment_ref"), rs.getObject("business_date", LocalDate.class), rs.getString("created_by"),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private static WriteOffRow mapWriteOff(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new WriteOffRow(rs.getLong("id"), rs.getLong("case_id"), rs.getLong("ticket_id"), rs.getInt("ticket_no"), rs.getString("status"),
                rs.getBigDecimal("subscription_amount"), rs.getBigDecimal("cover_amount"), rs.getBigDecimal("fee_amount"), rs.getString("reason"),
                rs.getString("made_by"), rs.getString("checked_by"), rs.getString("decision_reason"), rs.getObject("requested_on", LocalDate.class),
                rs.getObject("decided_on", LocalDate.class), rs.getObject("created_at", OffsetDateTime.class), rs.getObject("decided_at", OffsetDateTime.class));
    }

    private static <T> Optional<T> first(List<T> l) {
        return l.isEmpty() ? Optional.empty() : Optional.of(l.get(0));
    }
}
