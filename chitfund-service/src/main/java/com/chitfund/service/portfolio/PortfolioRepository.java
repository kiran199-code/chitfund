package com.chitfund.service.portfolio;

import com.chitfund.service.portfolio.PortfolioDtos.PaymentSummary;
import com.chitfund.service.portfolio.PortfolioDtos.RequestSummary;
import com.chitfund.service.portfolio.PortfolioDtos.TicketSummary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

/** Reads across groups for one customer. Writes nothing. Every query is keyed by the customer id. */
@Repository
public class PortfolioRepository {

    private final JdbcTemplate jdbc;

    public PortfolioRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A customer's ticket in a group, for working out auction eligibility. */
    public record MyTicket(long groupId, String groupCode, String groupName, int ticketNo, String status, boolean hasWon) {
    }

    /** A cycle that may have an auction, with the group it belongs to. */
    public record CycleRef(String groupCode, int cycleNo) {
    }

    public List<TicketSummary> tickets(long customerId) {
        return jdbc.query("SELECT g.group_code, g.name, g.status AS group_status, t.ticket_no, t.status AS ticket_status, t.prized_cycle_no, "
                        + "g.chit_value, g.subscription, g.frequency, g.cycle_count, g.start_date, "
                        + "(SELECT count(*) FROM chit_demand d WHERE d.ticket_id = t.id AND d.status IN ('OPEN','PAID','WRITTEN_OFF')) AS raised, "
                        + "(SELECT count(*) FROM chit_demand d WHERE d.ticket_id = t.id AND d.status IN ('PAID','WRITTEN_OFF')) AS paid, "
                        + "EXISTS (SELECT 1 FROM chit_cycle c WHERE c.winner_ticket_id = t.id) AS won, "
                        + "(SELECT COALESCE(sum(v.amount), 0) FROM chit_dividend v WHERE v.ticket_id = t.id AND v.status IN ('APPLIED','PAID')) AS dividends "
                        + "FROM chit_ticket t JOIN chit_group g ON g.id = t.group_id "
                        + "WHERE t.customer_id = ? AND t.status <> 'EXITED' AND g.status <> 'CANCELLED' ORDER BY g.group_code, t.ticket_no",
                (rs, i) -> {
                    int prized = rs.getInt("prized_cycle_no");
                    Integer prizedNo = rs.wasNull() ? null : prized;
                    return new TicketSummary(rs.getString("group_code"), rs.getString("name"), rs.getString("group_status"), rs.getInt("ticket_no"),
                            rs.getString("ticket_status"), rs.getBigDecimal("chit_value"), rs.getBigDecimal("subscription"), rs.getString("frequency"),
                            rs.getInt("cycle_count"), rs.getObject("start_date", LocalDate.class), rs.getInt("raised"), rs.getInt("paid"),
                            rs.getBoolean("won"), prizedNo, rs.getBigDecimal("dividends"));
                }, customerId);
    }

    public List<PaymentSummary> payments(long customerId, int limit, int offset) {
        return jdbc.query("SELECT p.payment_ref, g.group_code, g.name, t.ticket_no, p.amount, p.channel, p.status, p.business_date "
                        + "FROM chit_payment p JOIN chit_ticket t ON t.id = p.ticket_id JOIN chit_group g ON g.id = p.group_id "
                        + "WHERE t.customer_id = ? ORDER BY p.business_date DESC, p.id DESC LIMIT ? OFFSET ?",
                (rs, i) -> new PaymentSummary(rs.getString("payment_ref"), rs.getString("group_code"), rs.getString("name"), rs.getInt("ticket_no"),
                        rs.getBigDecimal("amount"), rs.getString("channel"), rs.getString("status"), rs.getObject("business_date", LocalDate.class)),
                customerId, limit, offset);
    }

    public List<RequestSummary> transfers(long customerId, int limit) {
        return jdbc.query("SELECT g.group_code, g.name, t.ticket_no, tr.status, tr.from_customer_id, tr.fee_amount, tr.created_at, tr.decided_at "
                        + "FROM chit_ticket_transfer tr JOIN chit_ticket t ON t.id = tr.ticket_id JOIN chit_group g ON g.id = t.group_id "
                        + "WHERE tr.from_customer_id = ? OR tr.to_customer_id = ? ORDER BY tr.id DESC LIMIT ?",
                (rs, i) -> new RequestSummary("TRANSFER", rs.getString("group_code"), rs.getString("name"), rs.getInt("ticket_no"), rs.getString("status"),
                        "PENDING".equals(rs.getString("status")), rs.getLong("from_customer_id") == customerId ? "OUTGOING" : "INCOMING", null,
                        rs.getBigDecimal("fee_amount"), null, rs.getObject("created_at", java.time.OffsetDateTime.class),
                        rs.getObject("decided_at", java.time.OffsetDateTime.class)),
                customerId, customerId, limit);
    }

    public List<RequestSummary> payouts(long customerId, int limit) {
        return jdbc.query("SELECT g.group_code, g.name, t.ticket_no, c.cycle_no, p.status, p.net_amount, p.hold_amount, p.created_at, p.disbursed_at "
                        + "FROM chit_prize_payout p JOIN chit_ticket t ON t.id = p.ticket_id JOIN chit_cycle c ON c.id = p.cycle_id "
                        + "JOIN chit_group g ON g.id = t.group_id WHERE t.customer_id = ? ORDER BY p.id DESC LIMIT ?",
                (rs, i) -> new RequestSummary("PRIZE_PAYOUT", rs.getString("group_code"), rs.getString("name"), rs.getInt("ticket_no"), rs.getString("status"),
                        !"DISBURSED".equals(rs.getString("status")) && !"REJECTED".equals(rs.getString("status")), null, rs.getInt("cycle_no"),
                        rs.getBigDecimal("net_amount"), rs.getBigDecimal("hold_amount"), rs.getObject("created_at", java.time.OffsetDateTime.class),
                        rs.getObject("disbursed_at", java.time.OffsetDateTime.class)),
                customerId, limit);
    }

    public List<MyTicket> liveTickets(long customerId) {
        return jdbc.query("SELECT g.id AS group_id, g.group_code, g.name, t.ticket_no, t.status, "
                        + "EXISTS (SELECT 1 FROM chit_cycle c WHERE c.winner_ticket_id = t.id) AS won "
                        + "FROM chit_ticket t JOIN chit_group g ON g.id = t.group_id "
                        + "WHERE t.customer_id = ? AND t.status <> 'EXITED' AND g.status IN ('ACTIVE','CLOSING') ORDER BY g.group_code, t.ticket_no",
                (rs, i) -> new MyTicket(rs.getLong("group_id"), rs.getString("group_code"), rs.getString("name"), rs.getInt("ticket_no"),
                        rs.getString("status"), rs.getBoolean("won")), customerId);
    }

    /**
     * The cycles whose auction matters to this customer: for each group they hold a ticket in, the next cycle that has not started its auction,
     * any cycle whose auction is open, and cycles closed on or after {@code closedSince}.
     */
    public List<CycleRef> auctionCycles(long customerId, LocalDate closedSince) {
        return jdbc.query("SELECT DISTINCT g.group_code, c.cycle_no FROM chit_group g JOIN chit_cycle c ON c.group_id = g.id "
                        + "WHERE g.status IN ('ACTIVE','CLOSING') AND EXISTS (SELECT 1 FROM chit_ticket t WHERE t.group_id = g.id AND t.customer_id = ? AND t.status <> 'EXITED') "
                        + "AND (c.status = 'AUCTION_OPEN' "
                        + "  OR c.cycle_no = (SELECT min(c2.cycle_no) FROM chit_cycle c2 WHERE c2.group_id = g.id AND c2.status IN ('SCHEDULED','COLLECTION_OPEN')) "
                        + "  OR (c.closed_on IS NOT NULL AND c.closed_on >= ?)) ORDER BY g.group_code, c.cycle_no",
                (rs, i) -> new CycleRef(rs.getString("group_code"), rs.getInt("cycle_no")), customerId, Date.valueOf(closedSince));
    }
}
