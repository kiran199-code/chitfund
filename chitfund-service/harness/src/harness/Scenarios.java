package harness;

import com.chitfund.service.recovery.RecoveryDtos.*;
import com.chitfund.service.transfer.TransferDtos.*;
import com.chitfund.service.domain.ForemanGuaranteeMode;
import com.chitfund.service.auction.AuctionDtos.*;

import java.math.BigDecimal;
import java.time.LocalDate;

import static harness.Phase6Harness.*;

class Scenarios {

    static final LocalDate D6 = LocalDate.of(2026, 11, 6);

    static Object scalar(String sql, Object... a) { return jdbc.queryForObject(sql, Object.class, a); }

    static BigDecimal bal(String code, String type, int ticketNo) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(CASE WHEN l.side=a.normal_side THEN l.amount ELSE -l.amount END),0) FROM ledger_account a "
                + "JOIN ledger_line l ON l.account_id=a.id JOIN chit_group g ON g.id=a.group_id LEFT JOIN chit_ticket t ON t.id=a.ticket_id "
                + "WHERE g.group_code=? AND a.account_type=? AND (? = 0 OR t.ticket_no=?)", BigDecimal.class, code, type, ticketNo, ticketNo);
    }

    static String ticketStatus(String code, int no) {
        return jdbc.queryForObject("SELECT t.status FROM chit_ticket t JOIN chit_group g ON g.id=t.group_id WHERE g.group_code=? AND t.ticket_no=?", String.class, code, no);
    }

    static String caseStatus(String code, int no) {
        return jdbc.queryForObject("SELECT c.status FROM chit_default_case c JOIN chit_ticket t ON t.id=c.ticket_id JOIN chit_group g ON g.id=t.group_id "
                + "WHERE g.group_code=? AND t.ticket_no=? ORDER BY c.id DESC LIMIT 1", String.class, code, no);
    }

    // ---------------------------------------------------------------------------------------------------------------------------

    static void transfers() {
        System.out.println("== transfer scenarios");
        String code = "H-TRF";
        long[] cust = startGroup(code, ForemanGuaranteeMode.COVER_PAYOUT_FOREMAN_BEARS_LOSS, "250.00");
        long newHolder = ++custSeq;
        LocalDate d0 = LocalDate.of(2026, 10, 26);

        // good standing, no arrears yet (no cycle raised): transfer ticket 2
        TransferView t = tx(() -> transfers.requestTransfer(code, 2, new TransferRequest(newHolder, "sold", "ACC-NEW"), key(), "maker", d0));
        eq("request status", "PENDING", t.status());
        eq("fee quoted", d("250.00"), t.fee());
        eq("ticket shows TRANSFERRED while pending", "TRANSFERRED", ticketStatus(code, 2));
        refused("second request while pending", "TRANSFER_PENDING",
                () -> transfers.requestTransfer(code, 2, new TransferRequest(++custSeq, "again", "ACC-X"), key(), "maker", d0));
        refused("maker approves own request", "MAKER_CHECKER_VIOLATION", () -> transfers.approve(code, t.id(), key(), "maker", d0));
        String ak = key();
        TransferView a = tx(() -> transfers.approve(code, t.id(), ak, "checker", d0));
        eq("approved", "APPROVED", a.status());
        eq("ticket back to ACTIVE", "ACTIVE", ticketStatus(code, 2));
        eq("ticket now belongs to new holder", newHolder, jdbc.queryForObject("SELECT t.customer_id FROM chit_ticket t JOIN chit_group g ON g.id=t.group_id WHERE g.group_code=? AND t.ticket_no=2", Long.class, code));
        eq("fee income posted", d("250.00"), bal(code, "TRANSFER_FEE_INCOME", 0));
        eq("subscriber links: 2 (old closed, new open)", 2L, jdbc.queryForObject("SELECT COUNT(*) FROM chit_subscriber_link s JOIN chit_ticket t ON t.id=s.ticket_id JOIN chit_group g ON g.id=t.group_id WHERE g.group_code=? AND t.ticket_no=2", Long.class, code));
        eq("exactly one open link", 1L, jdbc.queryForObject("SELECT COUNT(*) FROM chit_subscriber_link s JOIN chit_ticket t ON t.id=s.ticket_id JOIN chit_group g ON g.id=t.group_id WHERE g.group_code=? AND t.ticket_no=2 AND s.valid_to IS NULL", Long.class, code));
        eq("security stays with the ticket", d("1000.00"), bal(code, "SECURITY_DEPOSIT_LIABILITY", 2));
        TransferView again = tx(() -> transfers.approve(code, t.id(), ak, "checker", d0));
        check("replay of the approval is flagged and posts nothing twice", again.replayed() && bal(code, "TRANSFER_FEE_INCOME", 0).compareTo(d("250.00")) == 0);
        ledgerIdentities("after transfer", code);

        // reject restores status
        long other = ++custSeq;
        TransferView r = tx(() -> transfers.requestTransfer(code, 3, new TransferRequest(other, "sold", "ACC-O"), key(), "maker", d0));
        TransferView rj = tx(() -> transfers.reject(code, r.id(), new RejectTransferRequest("not agreed"), key(), "checker"));
        eq("rejected", "REJECTED", rj.status());
        eq("ticket 3 restored", "ACTIVE", ticketStatus(code, 3));
        eq("holder unchanged", cust[2], jdbc.queryForObject("SELECT t.customer_id FROM chit_ticket t JOIN chit_group g ON g.id=t.group_id WHERE g.group_code=? AND t.ticket_no=3", Long.class, code));

        // same customer / guarantor-type refusals
        refused("transfer to the same customer", "TRANSFER_SAME_CUSTOMER",
                () -> transfers.requestTransfer(code, 3, new TransferRequest(cust[2], "x", "A"), key(), "maker", d0));

        // replacement exit: no fee; ticket stays and link reason REPLACEMENT
        long repl = ++custSeq;
        TransferView e = tx(() -> transfers.requestExit(code, 4, new ExitRequest(repl, "leaving", "ACC-R"), key(), "maker", d0));
        eq("exit kind", "REPLACEMENT", e.kind());
        eq("exit carries no fee", d("0.00"), e.fee());
        tx(() -> transfers.approve(code, e.id(), key(), "checker", d0));
        eq("replacement holds ticket 4", repl, jdbc.queryForObject("SELECT t.customer_id FROM chit_ticket t JOIN chit_group g ON g.id=t.group_id WHERE g.group_code=? AND t.ticket_no=4", Long.class, code));
        eq("fee income unchanged by exit", d("250.00"), bal(code, "TRANSFER_FEE_INCOME", 0));
        eq("link reason REPLACEMENT recorded", 1L, jdbc.queryForObject("SELECT COUNT(*) FROM chit_subscriber_link s JOIN chit_ticket t ON t.id=s.ticket_id JOIN chit_group g ON g.id=t.group_id WHERE g.group_code=? AND t.ticket_no=4 AND s.reason='REPLACEMENT'", Long.class, code));
        ledgerIdentities("after replacement exit", code);

        // arrears block a transfer
        String code2 = "H-TRF2";
        startGroup(code2, ForemanGuaranteeMode.COVER_PAYOUT_FOREMAN_BEARS_LOSS, "250.00");
        raiseAndDefaultTicket5(code2);
        refused("ticket with arrears cannot transfer", "TRANSFER_ARREARS_EXIST",
                () -> transfers.requestTransfer(code2, 5, new TransferRequest(++custSeq, "x", "A"), key(), "maker", D6));
        refused("a defaulted ticket cannot use the replacement exit (it goes through a transfer once arrears are cleared)", "TRANSFER_TICKET_NOT_ELIGIBLE",
                () -> transfers.requestExit(code2, 5, new ExitRequest(++custSeq, "x", "A"), key(), "maker", D6));
        eq("refused request leaves the ticket DEFAULTED", "DEFAULTED", ticketStatus(code2, 5));
        ledgerIdentities("arrears group", code2);
    }

    static final LocalDate D10 = LocalDate.of(2026, 11, 10);

    static void applyOk(String code, int no, String amount, LocalDate day) {
        tx(() -> recovery.applySecurity(code, no, new ApplySecurityRequest(amount == null ? null : d(amount)), key(), "ops", day));
    }

    static WriteOffView woRequest(String code, int no, String maker, LocalDate day) {
        return tx(() -> recovery.requestWriteOff(code, no, new WriteOffRequest("borrower untraceable"), key(), maker, day));
    }

    static void recovery() {
        System.out.println("== recovery: deposit applied to arrears, then write-off (no covers)");
        String c1 = "H-REC1";
        startGroup(c1, ForemanGuaranteeMode.COVER_PAYOUT_FOREMAN_BEARS_LOSS, "250.00");
        raiseAndDefaultTicket5(c1);
        refused("write-off while the deposit is still held", "SECURITY_STILL_HELD", () -> recovery.requestWriteOff(c1, 5, new WriteOffRequest("x"), key(), "maker", D6));
        String ak = key();
        ActionView act = tx(() -> recovery.applySecurity(c1, 5, new ApplySecurityRequest(null), ak, "ops", D6));
        eq("deposit paid to arrears", d("1000.00"), act.toArrears());
        eq("nothing to foreman covers", d("0.00"), act.toForeman());
        ActionView act2 = tx(() -> recovery.applySecurity(c1, 5, new ApplySecurityRequest(null), ak, "ops", D6));
        check("replay returns the same action", act2.replayed() && act2.id() == act.id());
        eq("deposit liability now zero", d("0.00"), bal(c1, "SECURITY_DEPOSIT_LIABILITY", 5));
        eq("security row APPLIED", "APPLIED", jdbc.queryForObject("SELECT s.status FROM chit_security s JOIN chit_ticket t ON t.id=s.ticket_id JOIN chit_group g ON g.id=t.group_id WHERE g.group_code=? AND t.ticket_no=5", String.class, c1));
        eq("ticket still DEFAULTED", "DEFAULTED", ticketStatus(c1, 5));
        Position p = recoveryReads.position(c1, 5, D6);
        eq("late fee cleared first (default allocation order)", d("0.00"), p.overdueLateFees());
        eq("subscription still overdue", d("1100.00"), p.overdueSubscription());
        check("position says write-off possible", p.canWriteOff());
        refused("applying again with no deposit left", "NO_SECURITY_HELD", () -> recovery.applySecurity(c1, 5, new ApplySecurityRequest(null), key(), "ops", D6));
        ledgerIdentities("after apply-security", c1);

        WriteOffView w = woRequest(c1, 5, "maker", D6);
        eq("write-off total", d("1100.00"), w.total());
        refused("second pending write-off", "WRITE_OFF_PENDING", () -> recovery.requestWriteOff(c1, 5, new WriteOffRequest("again"), key(), "maker", D6));
        refused("maker approves own write-off", "MAKER_CHECKER_VIOLATION", () -> recovery.approveWriteOff(c1, w.id(), key(), "maker", D6));
        // the position moves between request and approval
        pay(c1, 5, "100.00", D6);
        refused("approval of a stale snapshot", "WRITE_OFF_STALE", () -> recovery.approveWriteOff(c1, w.id(), key(), "checker", D6));
        tx(() -> recovery.rejectWriteOff(c1, w.id(), new RejectWriteOffRequest("position changed"), key(), "checker", D6));
        eq("rejected", "REJECTED", jdbc.queryForObject("SELECT status FROM chit_write_off WHERE id=?", String.class, w.id()));
        ledgerIdentities("after stale write-off", c1);

        WriteOffView w2 = woRequest(c1, 5, "maker", D6);
        eq("fresh write-off total", d("1000.00"), w2.total());
        String wk = key();
        WriteOffView done = tx(() -> recovery.approveWriteOff(c1, w2.id(), wk, "checker", D6));
        eq("approved", "APPROVED", done.status());
        eq("loss expense = what the foreman bore", d("1000.00"), bal(c1, "GUARANTEE_LOSS_EXPENSE", 0));
        eq("foreman receivable cleared", d("0.00"), bal(c1, "FOREMAN_GUARANTEE_RECEIVABLE", 5));
        eq("demand PAID (its fee was already settled by the deposit, so nothing is waived)", "PAID", jdbc.queryForObject("SELECT d.status FROM chit_demand d JOIN chit_ticket t ON t.id=d.ticket_id JOIN chit_group g ON g.id=t.group_id WHERE g.group_code=? AND t.ticket_no=5", String.class, c1));
        eq("ticket stays DEFAULTED", "DEFAULTED", ticketStatus(c1, 5));
        eq("case stays OPEN", "OPEN", caseStatus(c1, 5));
        WriteOffView again = tx(() -> recovery.approveWriteOff(c1, w2.id(), wk, "checker", D6));
        check("approval replay posts nothing twice", again.replayed() && bal(c1, "GUARANTEE_LOSS_EXPENSE", 0).compareTo(d("1000.00")) == 0);
        ledgerIdentities("after write-off", c1);

        // a replacement subscriber takes the ticket; the case closes as WRITTEN_OFF
        long repl = ++custSeq;
        TransferView tr = tx(() -> transfers.requestTransfer(c1, 5, new TransferRequest(repl, "replacement found", "ACC-R"), key(), "maker", D6));
        eq("ticket shows TRANSFERRED during a defaulted transfer? (stays DEFAULTED by design)", "DEFAULTED", ticketStatus(c1, 5));
        tx(() -> transfers.approve(c1, tr.id(), key(), "checker", D6));
        eq("replacement restores ACTIVE", "ACTIVE", ticketStatus(c1, 5));
        eq("case closed as WRITTEN_OFF", "WRITTEN_OFF", caseStatus(c1, 5));
        eq("fee paid by the incoming holder", d("250.00"), bal(c1, "TRANSFER_FEE_INCOME", 0));
        ledgerIdentities("after replacement", c1);

        System.out.println("== recovery: no security held, write-off waives the late fee");
        String c5 = "H-REC5";
        noSecurity = true;
        startGroup(c5, ForemanGuaranteeMode.COVER_PAYOUT_FOREMAN_BEARS_LOSS, "0.00");
        noSecurity = false;
        raiseAndDefaultTicket5(c5);
        WriteOffView w5 = woRequest(c5, 5, "maker", D6);
        eq("subscription the foreman pays", d("2000.00"), w5.subscription());
        eq("late fee given up", d("100.00"), w5.lateFees());
        tx(() -> recovery.approveWriteOff(c5, w5.id(), key(), "checker", D6));
        eq("loss expense is the subscription only", d("2000.00"), bal(c5, "GUARANTEE_LOSS_EXPENSE", 0));
        eq("demand WRITTEN_OFF", "WRITTEN_OFF", jdbc.queryForObject("SELECT d.status FROM chit_demand d JOIN chit_ticket t ON t.id=d.ticket_id JOIN chit_group g ON g.id=t.group_id WHERE g.group_code=? AND t.ticket_no=5", String.class, c5));
        eq("late fee waived on the demand", d("100.00"), jdbc.queryForObject("SELECT d.late_fee_waived FROM chit_demand d JOIN chit_ticket t ON t.id=d.ticket_id JOIN chit_group g ON g.id=t.group_id WHERE g.group_code=? AND t.ticket_no=5", BigDecimal.class, c5));
        eq("pool whole: foreman paid the instalment", d("10000.00"), jdbc.queryForObject("SELECT d.subscription_paid FROM chit_demand d JOIN chit_ticket t ON t.id=d.ticket_id JOIN chit_group g ON g.id=t.group_id WHERE g.group_code=? AND t.ticket_no=5", BigDecimal.class, c5).add(d("8000.00")));
        ledgerIdentities("fee waiver", c5);
        long r5 = ++custSeq;
        TransferView t5 = tx(() -> transfers.requestTransfer(c5, 5, new TransferRequest(r5, "replacement", "ACC-5"), key(), "maker", D6));
        eq("no transfer fee configured", d("0.00"), t5.fee());
        tx(() -> transfers.approve(c5, t5.id(), key(), "checker", D6));
        eq("case WRITTEN_OFF", "WRITTEN_OFF", caseStatus(c5, 5));
        ledgerIdentities("after replacement (fee waiver)", c5);

        System.out.println("== recovery: mode NONE refuses write-off");
        String c3 = "H-REC3";
        startGroup(c3, ForemanGuaranteeMode.NONE, "0.00");
        raiseAndDefaultTicket5(c3);
        applyOk(c3, 5, null, D6);
        refused("write-off under foremanGuaranteeMode NONE", "WRITE_OFF_NOT_PERMITTED", () -> recovery.requestWriteOff(c3, 5, new WriteOffRequest("x"), key(), "maker", D6));
        ledgerIdentities("mode NONE", c3);

        System.out.println("== recovery: covers from the auction, deposit and repayments");
        String c2 = coveredGroup("H-REC2");
        Position p2 = recoveryReads.position(c2, 5, D10);
        eq("covers owed to foreman", d("2000.00"), p2.coversOwed());
        eq("late fee still overdue", d("100.00"), p2.overdueLateFees());
        eq("subscription no longer overdue (the foreman paid it)", d("0.00"), p2.overdueSubscription());
        eq("receivable asset on the books", d("2000.00"), bal(c2, "FOREMAN_GUARANTEE_RECEIVABLE", 5));
        ActionView a2 = tx(() -> recovery.applySecurity(c2, 5, new ApplySecurityRequest(null), key(), "ops", D10));
        eq("deposit: fee first", d("100.00"), a2.toArrears());
        eq("deposit: rest to the foreman's cover", d("900.00"), a2.toForeman());
        eq("cover balance now", d("1100.00"), recoveryReads.position(c2, 5, D10).coversOwed());
        refused("repaying more than owed", "COVER_REPAYMENT_TOO_LARGE", () -> recovery.repayCover(c2, 5, new RepayCoverRequest(d("1200.00"), "UPI", "R1"), key(), "ops", D10));
        tx(() -> recovery.repayCover(c2, 5, new RepayCoverRequest(d("500.00"), "UPI", "R2"), key(), "ops", D10));
        eq("after a 500 repayment", d("600.00"), recoveryReads.position(c2, 5, D10).coversOwed());
        eq("receivable follows", d("600.00"), bal(c2, "FOREMAN_GUARANTEE_RECEIVABLE", 5));
        ledgerIdentities("after repay", c2);
        tx(() -> recovery.repayCover(c2, 5, new RepayCoverRequest(d("600.00"), "UPI", "R3"), key(), "ops", D10));
        eq("covers cleared", d("0.00"), recoveryReads.position(c2, 5, D10).coversOwed());
        eq("ticket status after full recovery", "ACTIVE", ticketStatus(c2, 5));
        eq("case status after full recovery", "RECOVERED", caseStatus(c2, 5));
        ledgerIdentities("after full recovery", c2);

        System.out.println("== recovery: write-off of a cover balance");
        String c4 = coveredGroup("H-REC4");
        applyOk(c4, 5, null, D10);
        WriteOffView w4 = woRequest(c4, 5, "maker", D10);
        eq("covers to write off", d("1100.00"), w4.earlierCovers());
        eq("nothing more to pay into the pool", d("0.00"), w4.subscription());
        tx(() -> recovery.approveWriteOff(c4, w4.id(), key(), "checker", D10));
        eq("loss expense", d("1100.00"), bal(c4, "GUARANTEE_LOSS_EXPENSE", 0));
        eq("receivable cleared", d("0.00"), bal(c4, "FOREMAN_GUARANTEE_RECEIVABLE", 5));
        refused("repaying a written-off cover", "COVER_REPAYMENT_TOO_LARGE", () -> recovery.repayCover(c4, 5, new RepayCoverRequest(d("100.00"), "UPI", "R9"), key(), "ops", D10));
        ledgerIdentities("after cover write-off", c4);
        long r4 = ++custSeq;
        TransferView t4 = tx(() -> transfers.requestTransfer(c4, 5, new TransferRequest(r4, "replacement", "ACC-4"), key(), "maker", D10));
        tx(() -> transfers.approve(c4, t4.id(), key(), "checker", D10));
        eq("case WRITTEN_OFF after replacement", "WRITTEN_OFF", caseStatus(c4, 5));
        eq("ticket ACTIVE", "ACTIVE", ticketStatus(c4, 5));
        ledgerIdentities("after replacement (cover write-off)", c4);
    }

    static void prize() {
        System.out.println("== prize winner: exit refused, transfer waits for the payout");
        String code = "H-PRZ";
        startGroup(code, ForemanGuaranteeMode.COVER_PAYOUT_FOREMAN_BEARS_LOSS, "250.00");
        tx(() -> demands.raise(code, LocalDate.of(2026, 11, 5), "batch"));
        for (int t = 1; t <= 5; t++) pay(code, t, "2000.00", LocalDate.of(2026, 11, 5));
        tx(() -> auctions.open(code, 1, key(), "ops", D10));
        tx(() -> auctions.placeBid(code, 1, new PlaceBidRequest(2, d("1000.00")), key(), "ops"));
        tx(() -> auctions.close(code, 1, key(), "ops", D10, ALL_DAYS));
        eq("winner is still ACTIVE until the prize is paid", "ACTIVE", ticketStatus(code, 2));
        long x = ++custSeq;
        refused("winner cannot exit through a replacement", "EXIT_PRIZED_TICKET", () -> transfers.requestExit(code, 2, new ExitRequest(x, "leave", "A"), key(), "maker", D10));
        refused("transfer waits while the prize is unpaid", "TRANSFER_PAYOUT_PENDING", () -> transfers.requestTransfer(code, 2, new TransferRequest(x, "sell", "A"), key(), "maker", D10));
        tx(() -> payouts.request(code, 1, key(), "maker", D10));
        tx(() -> payouts.approve(code, 1, key(), "checker", D10));
        tx(() -> payouts.disburse(code, 1, key(), "cashier", D10));
        eq("winner is PRIZED once paid", "PRIZED", ticketStatus(code, 2));
        TransferView tv = tx(() -> transfers.requestTransfer(code, 2, new TransferRequest(x, "sell", "ACC-X"), key(), "maker", D10));
        eq("previous status remembered", "PRIZED", tv.ticketStatusBefore());
        tx(() -> transfers.approve(code, tv.id(), key(), "checker", D10));
        eq("prized ticket back to PRIZED", "PRIZED", ticketStatus(code, 2));
        ledgerIdentities("prize transfer", code);
    }

    /** Ticket 5 defaulted on 6 Nov; the auction of cycle 1 opens on 10 Nov and the foreman covers ticket 5's instalment. */
    static String coveredGroup(String code) {
        startGroup(code, ForemanGuaranteeMode.COVER_PAYOUT_FOREMAN_BEARS_LOSS, "250.00");
        raiseAndDefaultTicket5(code);
        tx(() -> auctions.open(code, 1, key(), "ops", D10));
        return code;
    }
}
