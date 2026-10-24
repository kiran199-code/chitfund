package com.chitfund.service.auction;

import com.chitfund.service.auction.AuctionModels.AuctionRow;
import com.chitfund.service.auction.AuctionModels.BidRow;
import com.chitfund.service.auction.AuctionModels.CoverRow;
import com.chitfund.service.auction.AuctionModels.CycleInfo;
import com.chitfund.service.auction.AuctionModels.DividendRow;
import com.chitfund.service.auction.AuctionModels.DrawRow;
import com.chitfund.service.auction.AuctionModels.LegRow;
import com.chitfund.service.auction.AuctionModels.PayoutRow;
import com.chitfund.service.auction.AuctionModels.TicketFlag;
import com.chitfund.service.calc.Draw;
import com.chitfund.service.calc.QuorumGate.DemandPosition;
import com.chitfund.service.common.Json;
import com.chitfund.service.domain.AuctionType;
import com.chitfund.service.domain.CycleStatus;
import com.chitfund.service.domain.DividendMode;
import com.chitfund.service.domain.TicketStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** JDBC access for auctions, bids, draws, payouts, dividends and foreman cover. Every method runs in the caller's transaction. */
@Repository
public class AuctionRepository {

    private static final String CYCLE_COLUMNS = "id, cycle_no, status, due_date, auction_date, winner_ticket_id, discount_amount, prize_amount, "
            + "commission_amount, dividend_pool, closed_on";
    private static final String AUCTION_COLUMNS = "id, cycle_id, auction_type, status, attempt, opened_at, opened_by, closed_at, closed_by, "
            + "winning_bid_id, close_basis, discount_amount, draw_record_id, settlement_journal_id, gate_note";
    private static final String PAYOUT_SELECT = "SELECT p.id, p.cycle_id, c.cycle_no, p.ticket_id, t.ticket_no, t.customer_id, p.gross_prize, "
            + "p.hold_amount, p.net_amount, p.status, p.made_by, p.checked_by, p.payout_ref, p.disbursed_at, p.disbursed_by, p.paid_gross, "
            + "p.paid_hold, p.float_amount, p.float_repaid, p.decision_note, p.approved_at, p.account_ref "
            + "FROM chit_prize_payout p JOIN chit_cycle c ON c.id = p.cycle_id JOIN chit_ticket t ON t.id = p.ticket_id ";
    private static final String DIVIDEND_SELECT = "SELECT v.id, v.cycle_id, c.cycle_no, v.ticket_id, t.ticket_no, v.amount, v.mode, v.status, "
            + "v.declared_on, v.settled_on FROM chit_dividend v JOIN chit_cycle c ON c.id = v.cycle_id JOIN chit_ticket t ON t.id = v.ticket_id ";

    private final JdbcTemplate jdbc;

    public AuctionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---- cycles ---------------------------------------------------------------------------------------------------

    public Optional<CycleInfo> cycle(long groupId, int cycleNo) {
        return first(jdbc.query("SELECT " + CYCLE_COLUMNS + " FROM chit_cycle WHERE group_id = ? AND cycle_no = ?",
                AuctionRepository::mapCycle, groupId, cycleNo));
    }

    public Optional<CycleInfo> cycleById(long cycleId) {
        return first(jdbc.query("SELECT " + CYCLE_COLUMNS + " FROM chit_cycle WHERE id = ?", AuctionRepository::mapCycle, cycleId));
    }

    public List<CycleInfo> cycles(long groupId) {
        return jdbc.query("SELECT " + CYCLE_COLUMNS + " FROM chit_cycle WHERE group_id = ? ORDER BY cycle_no", AuctionRepository::mapCycle, groupId);
    }

    /** Cycles before {@code cycleNo} that have not had their auction closed yet. */
    public int cyclesBeforeStillOpen(long groupId, int cycleNo) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM chit_cycle WHERE group_id = ? AND cycle_no < ? "
                + "AND status IN ('SCHEDULED','COLLECTION_OPEN','AUCTION_OPEN')", Integer.class, groupId, cycleNo);
        return n == null ? 0 : n;
    }

    /** Cycles before {@code cycleNo} whose prize has not been paid out in full yet. */
    public int payoutsBeforeStillOpen(long groupId, int cycleNo) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM chit_cycle WHERE group_id = ? AND cycle_no < ? "
                + "AND status IN ('AUCTION_CLOSED','PAYOUT_PENDING')", Integer.class, groupId, cycleNo);
        return n == null ? 0 : n;
    }

    public boolean laterCycleStarted(long groupId, int cycleNo) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM chit_cycle WHERE group_id = ? AND cycle_no > ? AND status <> 'SCHEDULED'",
                Integer.class, groupId, cycleNo);
        return n != null && n > 0;
    }

    public int rollOversSoFar(long groupId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM chit_auction a JOIN chit_cycle c ON c.id = a.cycle_id "
                + "WHERE c.group_id = ? AND a.status = 'ROLLED_OVER'", Integer.class, groupId);
        return n == null ? 0 : n;
    }

    public void setCycleStatus(long cycleId, CycleStatus status) {
        jdbc.update("UPDATE chit_cycle SET status = ?, version = version + 1 WHERE id = ?", status.name(), cycleId);
    }

    public void settleCycle(long cycleId) {
        jdbc.update("UPDATE chit_cycle SET status = 'SETTLED', settled_at = now(), version = version + 1 WHERE id = ?", cycleId);
    }

    public void recordCycleResult(long cycleId, long winnerTicketId, BigDecimal discount, BigDecimal prize, BigDecimal commission,
                                  BigDecimal dividendPool, LocalDate closedOn) {
        jdbc.update("UPDATE chit_cycle SET status = 'AUCTION_CLOSED', winner_ticket_id = ?, discount_amount = ?, prize_amount = ?, "
                        + "commission_amount = ?, dividend_pool = ?, closed_on = ?, version = version + 1 WHERE id = ?",
                winnerTicketId, discount, prize, commission, dividendPool, Date.valueOf(closedOn), cycleId);
    }

    public void setCycleDates(long cycleId, LocalDate dueDate, LocalDate auctionDate) {
        jdbc.update("UPDATE chit_cycle SET due_date = ?, auction_date = ?, version = version + 1 WHERE id = ?",
                Date.valueOf(dueDate), Date.valueOf(auctionDate), cycleId);
    }

    public void setCycleAuctionDate(long cycleId, LocalDate auctionDate) {
        jdbc.update("UPDATE chit_cycle SET auction_date = ?, version = version + 1 WHERE id = ?", Date.valueOf(auctionDate), cycleId);
    }

    // ---- tickets and demands --------------------------------------------------------------------------------------

    public List<TicketFlag> ticketFlags(long groupId) {
        return jdbc.query("SELECT t.id, t.ticket_no, t.customer_id, t.status, t.payout_account_ref, "
                        + "EXISTS (SELECT 1 FROM chit_cycle c WHERE c.winner_ticket_id = t.id) AS has_won "
                        + "FROM chit_ticket t WHERE t.group_id = ? AND t.status <> 'EXITED' ORDER BY t.ticket_no",
                (rs, i) -> new TicketFlag(rs.getLong("id"), rs.getInt("ticket_no"), rs.getLong("customer_id"),
                        TicketStatus.valueOf(rs.getString("status")), rs.getBoolean("has_won"), rs.getString("payout_account_ref")), groupId);
    }

    /** Every raised demand of live tickets up to and including {@code cycleNo}, with what is due net of the dividend adjustment. */
    public List<DemandPosition> demandPositions(long groupId, int cycleNo) {
        return jdbc.query("SELECT d.id, d.ticket_id, t.ticket_no, c.cycle_no, d.subscription_amount - d.dividend_adjustment AS due, d.subscription_paid "
                        + "FROM chit_demand d JOIN chit_cycle c ON c.id = d.cycle_id JOIN chit_ticket t ON t.id = d.ticket_id "
                        + "WHERE c.group_id = ? AND c.cycle_no <= ? AND d.status <> 'CANCELLED' AND t.status <> 'EXITED' "
                        + "ORDER BY c.cycle_no, t.ticket_no",
                (rs, i) -> new DemandPosition(rs.getLong("id"), rs.getLong("ticket_id"), rs.getInt("ticket_no"), rs.getInt("cycle_no"),
                        rs.getBigDecimal("due"), rs.getBigDecimal("subscription_paid")), groupId, cycleNo);
    }

    /** Subscription and late fees still unpaid on the ticket's raised demands of cycles up to {@code cycleNo}, or due before {@code day}. */
    public BigDecimal arrearsOf(long ticketId, int cycleNo, LocalDate day) {
        BigDecimal v = jdbc.queryForObject("SELECT COALESCE(sum(GREATEST(d.subscription_amount - d.dividend_adjustment - d.subscription_paid, 0) "
                        + "+ GREATEST(d.late_fee_accrued - d.late_fee_paid, 0)), 0) FROM chit_demand d JOIN chit_cycle c ON c.id = d.cycle_id "
                        + "WHERE d.ticket_id = ? AND d.status = 'OPEN' AND (c.cycle_no <= ? OR d.due_date < ?)",
                BigDecimal.class, ticketId, cycleNo, Date.valueOf(day));
        return v == null ? BigDecimal.ZERO : v;
    }

    // ---- auctions and bids ----------------------------------------------------------------------------------------

    public int auctionsOfCycleCount(long cycleId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM chit_auction WHERE cycle_id = ?", Integer.class, cycleId);
        return n == null ? 0 : n;
    }

    public long insertAuction(long cycleId, AuctionType type, int attempt, String actor, String gateNote) {
        return jdbc.query("INSERT INTO chit_auction (cycle_id, auction_type, attempt, opened_by, gate_note) VALUES (?,?,?,?,?) RETURNING id",
                (rs, i) -> rs.getLong(1), cycleId, type.name(), attempt, actor, gateNote).get(0);
    }

    /** The cycle's live auction (the one not rolled over), row-locked. */
    public Optional<AuctionRow> liveAuctionForUpdate(long cycleId) {
        return first(jdbc.query("SELECT " + AUCTION_COLUMNS + " FROM chit_auction WHERE cycle_id = ? AND status <> 'ROLLED_OVER' FOR UPDATE",
                AuctionRepository::mapAuction, cycleId));
    }

    public Optional<AuctionRow> liveAuction(long cycleId) {
        return first(jdbc.query("SELECT " + AUCTION_COLUMNS + " FROM chit_auction WHERE cycle_id = ? AND status <> 'ROLLED_OVER'",
                AuctionRepository::mapAuction, cycleId));
    }

    public List<AuctionRow> auctionsOfCycle(long cycleId) {
        return jdbc.query("SELECT " + AUCTION_COLUMNS + " FROM chit_auction WHERE cycle_id = ? ORDER BY attempt", AuctionRepository::mapAuction, cycleId);
    }

    public void closeAuction(long auctionId, String actor, Long winningBidId, String basis, BigDecimal discount, Long drawRecordId, Long journalId) {
        jdbc.update("UPDATE chit_auction SET status = 'CLOSED', closed_at = now(), closed_by = ?, winning_bid_id = ?, close_basis = ?, "
                        + "discount_amount = ?, draw_record_id = ?, settlement_journal_id = ?, version = version + 1 WHERE id = ?",
                actor, winningBidId, basis, discount, drawRecordId, journalId, auctionId);
    }

    public void rollOverAuction(long auctionId, String actor) {
        jdbc.update("UPDATE chit_auction SET status = 'ROLLED_OVER', closed_at = now(), closed_by = ?, close_basis = 'ROLLED_OVER', "
                + "version = version + 1 WHERE id = ?", actor, auctionId);
    }

    public int nextBidSeq(long auctionId) {
        Integer n = jdbc.queryForObject("SELECT COALESCE(max(bid_seq), 0) + 1 FROM chit_bid WHERE auction_id = ?", Integer.class, auctionId);
        return n == null ? 1 : n;
    }

    public void insertBid(long auctionId, long ticketId, int seq, BigDecimal discount, String actor) {
        jdbc.update("INSERT INTO chit_bid (auction_id, ticket_id, bid_seq, discount_amount, placed_by) VALUES (?,?,?,?,?)",
                auctionId, ticketId, seq, discount, actor);
    }

    public List<BidRow> bids(long auctionId) {
        return jdbc.query("SELECT b.id, b.auction_id, b.ticket_id, t.ticket_no, b.bid_seq, b.discount_amount, b.bid_time, b.placed_by, b.status, "
                        + "b.withdrawn_reason FROM chit_bid b JOIN chit_ticket t ON t.id = b.ticket_id WHERE b.auction_id = ? ORDER BY b.bid_seq",
                (rs, i) -> new BidRow(rs.getLong("id"), rs.getLong("auction_id"), rs.getLong("ticket_id"), rs.getInt("ticket_no"), rs.getInt("bid_seq"),
                        rs.getBigDecimal("discount_amount"), rs.getObject("bid_time", OffsetDateTime.class), rs.getString("placed_by"),
                        rs.getString("status"), rs.getString("withdrawn_reason")), auctionId);
    }

    public void withdrawBid(long bidId, String reason, String actor) {
        jdbc.update("UPDATE chit_bid SET status = 'WITHDRAWN', withdrawn_reason = ?, withdrawn_by = ? WHERE id = ?", reason, actor, bidId);
    }

    // ---- draws ----------------------------------------------------------------------------------------------------

    public long insertDraw(long groupId, Long cycleId, String purpose, Draw.Outcome o, String actor) {
        return jdbc.query("INSERT INTO chit_draw_record (group_id, cycle_id, purpose, algorithm, seed, candidates, result, created_by) "
                        + "VALUES (?,?,?,?,?,?,?,?) RETURNING id", (rs, i) -> rs.getLong(1),
                groupId, cycleId, purpose, o.algorithm(), o.seed(), Json.write(o.candidates()), Json.write(o.result()), actor).get(0);
    }

    public Optional<DrawRow> fixedOrderDraw(long groupId) {
        return first(jdbc.query("SELECT id, group_id, cycle_id, purpose, algorithm, seed, candidates, result, created_at, created_by "
                + "FROM chit_draw_record WHERE group_id = ? AND purpose = 'FIXED_ORDER'", AuctionRepository::mapDraw, groupId));
    }

    public Optional<DrawRow> draw(long drawId) {
        return first(jdbc.query("SELECT id, group_id, cycle_id, purpose, algorithm, seed, candidates, result, created_at, created_by "
                + "FROM chit_draw_record WHERE id = ?", AuctionRepository::mapDraw, drawId));
    }

    public List<DrawRow> drawsOfGroup(long groupId) {
        return jdbc.query("SELECT id, group_id, cycle_id, purpose, algorithm, seed, candidates, result, created_at, created_by "
                + "FROM chit_draw_record WHERE group_id = ? ORDER BY id", AuctionRepository::mapDraw, groupId);
    }

    // ---- dividends ------------------------------------------------------------------------------------------------

    public long insertDividend(long cycleId, long ticketId, BigDecimal amount, DividendMode mode, String status, LocalDate declaredOn, LocalDate settledOn) {
        return jdbc.query("INSERT INTO chit_dividend (cycle_id, ticket_id, amount, mode, status, declared_on, settled_on) VALUES (?,?,?,?,?,?,?) RETURNING id",
                (rs, i) -> rs.getLong(1), cycleId, ticketId, amount, mode.name(), status, Date.valueOf(declaredOn),
                settledOn == null ? null : Date.valueOf(settledOn)).get(0);
    }

    public List<DividendRow> dividendsOfCycle(long cycleId) {
        return jdbc.query(DIVIDEND_SELECT + "WHERE v.cycle_id = ? ORDER BY t.ticket_no", AuctionRepository::mapDividend, cycleId);
    }

    public List<DividendRow> dividendsOfGroup(long groupId, Integer ticketNo) {
        return jdbc.query(DIVIDEND_SELECT + "WHERE c.group_id = ? AND (?::int IS NULL OR t.ticket_no = ?::int) ORDER BY c.cycle_no, t.ticket_no",
                AuctionRepository::mapDividend, groupId, ticketNo, ticketNo);
    }

    public int unpaidPayOutDividends(long cycleId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM chit_dividend WHERE cycle_id = ? AND mode = 'PAY_OUT' AND status = 'DECLARED'", Integer.class, cycleId);
        return n == null ? 0 : n;
    }

    public void markDividendPaid(long dividendId, LocalDate on) {
        jdbc.update("UPDATE chit_dividend SET status = 'PAID', settled_on = ? WHERE id = ?", Date.valueOf(on), dividendId);
    }

    // ---- payouts --------------------------------------------------------------------------------------------------

    public long insertPayout(long cycleId, long ticketId, BigDecimal gross, BigDecimal hold, BigDecimal net, String madeBy, String accountRef) {
        return jdbc.query("INSERT INTO chit_prize_payout (cycle_id, ticket_id, gross_prize, hold_amount, net_amount, made_by, account_ref) "
                        + "VALUES (?,?,?,?,?,?,?) RETURNING id", (rs, i) -> rs.getLong(1), cycleId, ticketId, gross, hold, net, madeBy, accountRef).get(0);
    }

    public Optional<PayoutRow> payoutOfCycle(long cycleId) {
        return first(jdbc.query(PAYOUT_SELECT + "WHERE p.cycle_id = ?", AuctionRepository::mapPayout, cycleId));
    }

    public Optional<PayoutRow> payoutOfCycleForUpdate(long cycleId) {
        return first(jdbc.query(PAYOUT_SELECT + "WHERE p.cycle_id = ? FOR UPDATE OF p", AuctionRepository::mapPayout, cycleId));
    }

    public List<PayoutRow> payoutsOfGroup(long groupId) {
        return jdbc.query(PAYOUT_SELECT + "WHERE c.group_id = ? ORDER BY c.cycle_no", AuctionRepository::mapPayout, groupId);
    }

    public void resubmitPayout(long payoutId, BigDecimal gross, BigDecimal hold, BigDecimal net, String madeBy) {
        jdbc.update("UPDATE chit_prize_payout SET status = 'PENDING_APPROVAL', made_by = ?, checked_by = NULL, approved_at = NULL, "
                + "decision_note = NULL, gross_prize = ?, hold_amount = ?, net_amount = ?, version = version + 1 WHERE id = ?", madeBy, gross, hold, net, payoutId);
    }

    public void decidePayout(long payoutId, String status, String checker, String note) {
        jdbc.update("UPDATE chit_prize_payout SET status = ?, checked_by = ?, decision_note = ?, "
                        + "approved_at = CASE WHEN ? = 'APPROVED' THEN now() ELSE NULL END, version = version + 1 WHERE id = ?",
                status, checker, note, status, payoutId);
    }

    public void recordDisbursement(long payoutId, BigDecimal paidGross, BigDecimal paidHold, BigDecimal floatAmount, boolean complete, String ref, String actor) {
        if (complete) {
            jdbc.update("UPDATE chit_prize_payout SET status = 'DISBURSED', paid_gross = ?, paid_hold = ?, float_amount = ?, payout_ref = ?, "
                    + "disbursed_at = now(), disbursed_by = ?, version = version + 1 WHERE id = ?", paidGross, paidHold, floatAmount, ref, actor, payoutId);
        } else {
            jdbc.update("UPDATE chit_prize_payout SET status = 'PART_PAID', paid_gross = ?, paid_hold = ?, float_amount = ?, payout_ref = ?, "
                    + "disbursed_by = ?, version = version + 1 WHERE id = ?", paidGross, paidHold, floatAmount, ref, actor, payoutId);
        }
    }

    public void recordFloatRepaid(long payoutId, BigDecimal repaid) {
        jdbc.update("UPDATE chit_prize_payout SET float_repaid = ?, version = version + 1 WHERE id = ?", repaid, payoutId);
    }

    public int nextLegNo(long payoutId) {
        Integer n = jdbc.queryForObject("SELECT COALESCE(max(leg_no), 0) + 1 FROM chit_payout_leg WHERE payout_id = ?", Integer.class, payoutId);
        return n == null ? 1 : n;
    }

    public void insertLeg(long payoutId, int legNo, String kind, BigDecimal amount, BigDecimal hold, String instructionRef, LocalDate on, String actor) {
        jdbc.update("INSERT INTO chit_payout_leg (payout_id, leg_no, kind, amount, hold_amount, instruction_ref, business_date, created_by) "
                + "VALUES (?,?,?,?,?,?,?,?)", payoutId, legNo, kind, amount, hold, instructionRef, Date.valueOf(on), actor);
    }

    public List<LegRow> legs(long payoutId) {
        return jdbc.query("SELECT id, payout_id, leg_no, kind, amount, hold_amount, instruction_ref, business_date, created_at, created_by "
                        + "FROM chit_payout_leg WHERE payout_id = ? ORDER BY leg_no",
                (rs, i) -> new LegRow(rs.getLong("id"), rs.getLong("payout_id"), rs.getInt("leg_no"), rs.getString("kind"), rs.getBigDecimal("amount"),
                        rs.getBigDecimal("hold_amount"), rs.getString("instruction_ref"), rs.getObject("business_date", LocalDate.class),
                        rs.getObject("created_at", OffsetDateTime.class), rs.getString("created_by")), payoutId);
    }

    // ---- foreman cover --------------------------------------------------------------------------------------------

    public void insertCover(long cycleId, long ticketId, long demandId, long paymentId, BigDecimal amount, LocalDate on, String actor) {
        jdbc.update("INSERT INTO chit_foreman_cover (cycle_id, ticket_id, demand_id, payment_id, amount, covered_on, created_by) VALUES (?,?,?,?,?,?,?)",
                cycleId, ticketId, demandId, paymentId, amount, Date.valueOf(on), actor);
    }

    public List<CoverRow> coversOfGroup(long groupId) {
        return jdbc.query("SELECT f.id, f.cycle_id, c.cycle_no, f.ticket_id, t.ticket_no, f.demand_id, f.amount, f.recovered_amount, f.status, f.covered_on "
                        + "FROM chit_foreman_cover f JOIN chit_cycle c ON c.id = f.cycle_id JOIN chit_ticket t ON t.id = f.ticket_id "
                        + "WHERE c.group_id = ? ORDER BY c.cycle_no, t.ticket_no",
                (rs, i) -> new CoverRow(rs.getLong("id"), rs.getLong("cycle_id"), rs.getInt("cycle_no"), rs.getLong("ticket_id"), rs.getInt("ticket_no"),
                        rs.getLong("demand_id"), rs.getBigDecimal("amount"), rs.getBigDecimal("recovered_amount"), rs.getString("status"),
                        rs.getObject("covered_on", LocalDate.class)), groupId);
    }

    // ---- mapping --------------------------------------------------------------------------------------------------

    private static <T> Optional<T> first(List<T> l) {
        return l.isEmpty() ? Optional.empty() : Optional.of(l.get(0));
    }

    private static Long nullableLong(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private static CycleInfo mapCycle(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new CycleInfo(rs.getLong("id"), rs.getInt("cycle_no"), CycleStatus.valueOf(rs.getString("status")), rs.getObject("due_date", LocalDate.class),
                rs.getObject("auction_date", LocalDate.class), nullableLong(rs, "winner_ticket_id"), rs.getBigDecimal("discount_amount"),
                rs.getBigDecimal("prize_amount"), rs.getBigDecimal("commission_amount"), rs.getBigDecimal("dividend_pool"),
                rs.getObject("closed_on", LocalDate.class));
    }

    private static AuctionRow mapAuction(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new AuctionRow(rs.getLong("id"), rs.getLong("cycle_id"), AuctionType.valueOf(rs.getString("auction_type")), rs.getString("status"),
                rs.getInt("attempt"), rs.getObject("opened_at", OffsetDateTime.class), rs.getString("opened_by"),
                rs.getObject("closed_at", OffsetDateTime.class), rs.getString("closed_by"), nullableLong(rs, "winning_bid_id"),
                rs.getString("close_basis"), rs.getBigDecimal("discount_amount"), nullableLong(rs, "draw_record_id"),
                nullableLong(rs, "settlement_journal_id"), rs.getString("gate_note"));
    }

    private static DrawRow mapDraw(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new DrawRow(rs.getLong("id"), rs.getLong("group_id"), nullableLong(rs, "cycle_id"), rs.getString("purpose"), rs.getString("algorithm"),
                rs.getLong("seed"), rs.getString("candidates"), rs.getString("result"), rs.getObject("created_at", OffsetDateTime.class),
                rs.getString("created_by"));
    }

    private static DividendRow mapDividend(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new DividendRow(rs.getLong("id"), rs.getLong("cycle_id"), rs.getInt("cycle_no"), rs.getLong("ticket_id"), rs.getInt("ticket_no"),
                rs.getBigDecimal("amount"), DividendMode.valueOf(rs.getString("mode")), rs.getString("status"),
                rs.getObject("declared_on", LocalDate.class), rs.getObject("settled_on", LocalDate.class));
    }

    private static PayoutRow mapPayout(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new PayoutRow(rs.getLong("id"), rs.getLong("cycle_id"), rs.getInt("cycle_no"), rs.getLong("ticket_id"), rs.getInt("ticket_no"),
                rs.getLong("customer_id"), rs.getBigDecimal("gross_prize"), rs.getBigDecimal("hold_amount"), rs.getBigDecimal("net_amount"),
                rs.getString("status"), rs.getString("made_by"), rs.getString("checked_by"), rs.getString("payout_ref"),
                rs.getObject("disbursed_at", OffsetDateTime.class), rs.getString("disbursed_by"), rs.getBigDecimal("paid_gross"),
                rs.getBigDecimal("paid_hold"), rs.getBigDecimal("float_amount"), rs.getBigDecimal("float_repaid"), rs.getString("decision_note"),
                rs.getObject("approved_at", OffsetDateTime.class), rs.getString("account_ref"));
    }
}
