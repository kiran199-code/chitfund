package harness;

import com.chitfund.service.auction.*;
import com.chitfund.service.calendar.WorkingDayCalendar;
import com.chitfund.service.collection.*;
import com.chitfund.service.collection.CollectionDtos.*;
import com.chitfund.service.common.Audit;
import com.chitfund.service.common.ChitException;
import com.chitfund.service.config.ChitSettings;
import com.chitfund.service.domain.*;
import com.chitfund.service.domain.rules.*;
import com.chitfund.service.enrolment.*;
import com.chitfund.service.enrolment.EnrolmentDtos.*;
import com.chitfund.service.group.*;
import com.chitfund.service.group.GroupDtos.*;
import com.chitfund.service.idempotency.IdempotencyStore;
import com.chitfund.service.ledger.*;
import com.chitfund.service.recovery.*;
import com.chitfund.service.recovery.RecoveryDtos.*;
import com.chitfund.service.transfer.*;
import com.chitfund.service.transfer.TransferDtos.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Supplier;

/** Runs the Phase 6 services (real code, hand-driven transactions) against a real PostgreSQL. Not Spring: no controllers, no proxies. */
public class Phase6Harness {

    static PgJdbcTemplate jdbc;
    static GroupService groupSvc;
    static EnrolmentService enrolSvc;
    static ActivationService activation;
    static DemandWriter demands;
    static LateFeeWriter lateFees;
    static DefaultWriter defaults;
    static PaymentService payments;
    static RecoveryService recovery;
    static RecoveryReadService recoveryReads;
    static TransferService transfers;
    static TransferReadService transferReads;
    static LedgerRepository ledgerRepo;
    static AuctionService auctions;
    static PayoutService payouts;
    static ChitSettings settings = new ChitSettings(500, true, false);
    static final WorkingDayCalendar ALL_DAYS = d -> true;
    static int passed, failed;
    static long keySeq = 1;
    static long custSeq = 1000;

    static String key() { return "H-" + (keySeq++); }

    static <T> T tx(Supplier<T> w) { return jdbc.inTx(w); }

    static void check(String what, boolean ok) {
        if (ok) { passed++; System.out.println("  ok   " + what); }
        else { failed++; System.out.println("  FAIL " + what); }
    }

    static void eq(String what, Object expected, Object actual) {
        boolean ok = expected instanceof BigDecimal e && actual instanceof BigDecimal a ? e.compareTo(a) == 0 : Objects.equals(expected, actual);
        if (ok) { passed++; System.out.println("  ok   " + what + " = " + actual); }
        else { failed++; System.out.println("  FAIL " + what + ": expected " + expected + " but was " + actual); }
    }

    static void refused(String what, String code, Supplier<?> w) {
        try {
            tx(w);
            failed++; System.out.println("  FAIL " + what + ": expected " + code + " but it succeeded");
        } catch (ChitException e) {
            if (code.equals(e.code())) { passed++; System.out.println("  ok   " + what + " refused " + code); }
            else { failed++; System.out.println("  FAIL " + what + ": expected " + code + " but got " + e.code() + " (" + e.getMessage() + ")"); }
        } catch (RuntimeException e) {
            failed++; System.out.println("  FAIL " + what + ": expected " + code + " but got " + e);
        }
    }

    static BigDecimal d(String s) { return new BigDecimal(s); }

    static void wire() throws Exception {
        jdbc = new PgJdbcTemplate(System.getProperty("db", "chit7"), "/tmp/pg", 5544);
        Audit audit = new Audit(jdbc);
        IdempotencyStore idem = new IdempotencyStore(jdbc);
        GroupRepository groupRepo = new GroupRepository(jdbc);
        EnrolmentRepository enrolRepo = new EnrolmentRepository(jdbc);
        groupSvc = new GroupService(groupRepo, enrolRepo, idem, audit);
        ledgerRepo = new LedgerRepository(jdbc);
        LedgerService ledger = new LedgerService(ledgerRepo);
        enrolSvc = new EnrolmentService(groupSvc, groupRepo, enrolRepo, ledger, idem, audit, settings);
        ActivationWriter aw = new ActivationWriter(groupSvc, groupRepo, enrolRepo, ledger, idem, audit);
        activation = new ActivationService(enrolRepo, groupSvc, aw);
        CollectionRepository coll = new CollectionRepository(jdbc);
        demands = new DemandWriter(groupSvc, coll, enrolRepo, ledger, audit);
        lateFees = new LateFeeWriter(groupSvc, coll, enrolRepo, ledger, audit);
        defaults = new DefaultWriter(groupSvc, coll, enrolRepo, audit);
        CollectionReadService collReads = new CollectionReadService(groupSvc, groupRepo, enrolRepo, coll);
        payments = new PaymentService(groupSvc, coll, enrolRepo, ledger, ledgerRepo, idem, audit, collReads);
        AuctionRepository auctionRepo = new AuctionRepository(jdbc);
        InternalPayments internal = new InternalPayments(coll, auctionRepo, ledger);
        AuctionReadService auctionReads = new AuctionReadService(groupSvc, auctionRepo, ledgerRepo);
        auctions = new AuctionService(groupSvc, auctionRepo, coll, ledger, idem, audit, internal, new DrawSeeds(), auctionReads);
        payouts = new PayoutService(groupSvc, auctionRepo, enrolRepo, ledger, idem, audit, settings, auctionReads, new CycleSettler(auctionRepo, audit));
        RecoveryRepository recRepo = new RecoveryRepository(jdbc);
        recoveryReads = new RecoveryReadService(groupSvc, recRepo, enrolRepo, coll, ledgerRepo);
        recovery = new RecoveryService(groupSvc, recRepo, enrolRepo, coll, ledger, ledgerRepo, internal, idem, audit, settings, recoveryReads);
        TransferRepository trRepo = new TransferRepository(jdbc);
        transferReads = new TransferReadService(groupSvc, trRepo, enrolRepo);
        transfers = new TransferService(groupSvc, trRepo, enrolRepo, coll, recRepo, ledger, idem, audit, settings, transferReads);
    }

    static boolean noSecurity;

    static ChitRules rules(ForemanGuaranteeMode mode, String transferFee) {
        return ChitRules.builder()
                .chitValue(d("10000.00")).ticketCount(5).cycleCount(5).subscription(d("2000.00"))
                .instalmentDueDay(5).auctionDay(10).startDate(LocalDate.of(2026, 11, 1))
                .commissionPct(d("5")).maxCommissionPct(d("5")).minDiscountPct(d("5")).maxDiscountPct(d("40"))
                .securityRule(noSecurity ? SecurityRule.NONE : SecurityRule.DEPOSIT).securityDepositPct(noSecurity ? null : d("10"))
                .foremanGuaranteeMode(mode).auctionQuorumRule(QuorumRule.FOREMAN_COVERS_SHORTFALL)
                .defaultThreshold(DefaultThreshold.ofMissedInstalments(1)).graceDays(0)
                .lateFeeRule(LateFeeRule.flat(d("100.00")))
                .transferFee(d(transferFee))
                .build();
    }

    /** A group created, filled with five customers, security verified, activated. Customer ids are returned in ticket order. */
    static long[] startGroup(String code, ForemanGuaranteeMode mode, String transferFee) {
        LocalDate today = LocalDate.of(2026, 10, 25);
        tx(() -> groupSvc.create(new CreateGroupRequest(code, "Harness " + code, "FOREMAN-1", rules(mode, transferFee)), key(), "maker"));
        tx(() -> groupSvc.openEnrolment(code, key(), "maker"));
        QuoteView q = enrolSvc.quote(code);
        long[] customers = new long[5];
        for (int i = 0; i < 5; i++) {
            customers[i] = ++custSeq;
            long c = customers[i];
            tx(() -> enrolSvc.enrol(code, new EnrolRequest(c, "ACC-" + c, q.enrolmentFee(), q.registrationFee(), q.securityDeposit(), null, null), key(), "maker", today));
            int no = i + 1;
            if (!noSecurity) tx(() -> enrolSvc.verifySecurity(code, no, key(), "checker"));
        }
        tx(() -> activation.activate(code, key(), "maker", today, ALL_DAYS));
        return customers;
    }

    static PaymentView pay(String code, int ticketNo, String amount, LocalDate today) {
        return tx(() -> payments.record(code, ticketNo, new RecordPaymentRequest(d(amount), "UPI", "UTR-" + keySeq), key(), "cashier", today));
    }

    /** Cycle 1 raised on 5 Nov; tickets 1-4 pay, ticket 5 does not; accrue and default on 6 Nov. */
    static void raiseAndDefaultTicket5(String code) {
        LocalDate due = LocalDate.of(2026, 11, 5);
        tx(() -> demands.raise(code, due, "batch"));
        for (int t = 1; t <= 4; t++) pay(code, t, "2000.00", due);
        LocalDate next = LocalDate.of(2026, 11, 6);
        tx(() -> lateFees.accrue(code, next, "batch"));
        tx(() -> defaults.detect(code, next, "batch"));
    }

    // ---- invariants after every scenario ------------------------------------------------------------------------------------

    static void ledgerIdentities(String label, String code) {
        long gid = jdbc.queryForObject("SELECT id FROM chit_group WHERE group_code = ?", Long.class, code);
        BigDecimal debits = jdbc.queryForObject("SELECT COALESCE(SUM(l.amount),0) FROM ledger_line l JOIN ledger_account a ON a.id=l.account_id WHERE a.group_id=? AND l.side='D'", BigDecimal.class, gid);
        BigDecimal credits = jdbc.queryForObject("SELECT COALESCE(SUM(l.amount),0) FROM ledger_line l JOIN ledger_account a ON a.id=l.account_id WHERE a.group_id=? AND l.side='C'", BigDecimal.class, gid);
        eq(label + ": trial balance debits = credits", debits, credits);
        // per ticket: receivable = subscription demanded + fees accrued - paid - waived - dividend adjustment
        List<Map<String, Object>> bad = new ArrayList<>();
        jdbc.query("SELECT t.ticket_no, "
                + " COALESCE((SELECT SUM(CASE WHEN l.side=a.normal_side THEN l.amount ELSE -l.amount END) FROM ledger_account a JOIN ledger_line l ON l.account_id=a.id "
                + "           WHERE a.ticket_id=t.id AND a.account_type='SUBSCRIBER_RECEIVABLE'),0) AS ledger_recv, "
                + " COALESCE((SELECT SUM(d.subscription_amount - d.dividend_adjustment - d.subscription_paid + d.late_fee_accrued - d.late_fee_paid - d.late_fee_waived) "
                + "           FROM chit_demand d WHERE d.ticket_id=t.id AND d.status <> 'CANCELLED'),0) AS demand_recv "
                + "FROM chit_ticket t WHERE t.group_id=?",
                (rs, i) -> {
                    BigDecimal a = rs.getBigDecimal("ledger_recv"), b = rs.getBigDecimal("demand_recv");
                    if (a.compareTo(b) != 0) bad.add(Map.of("ticket", rs.getInt("ticket_no"), "ledger", a, "demands", b));
                    return null;
                }, gid);
        check(label + ": per-ticket receivable matches demands " + bad, bad.isEmpty());
    }

    public static void main(String[] args) throws Exception {
        wire();
        String only = args.length > 0 ? args[0] : "";
        if (only.isEmpty() || only.equals("smoke")) smoke();
        if (only.isEmpty() || only.equals("transfer")) Scenarios.transfers();
        if (only.isEmpty() || only.equals("recovery")) Scenarios.recovery();
        if (only.isEmpty() || only.equals("prize")) Scenarios.prize();
        if (only.isEmpty() || only.equals("sample")) sampleGroups();
        System.out.println("\n" + passed + " passed, " + failed + " failed (" + jdbc.statements + " statements)");
        jdbc.close();
        System.exit(failed == 0 ? 0 : 1);
    }

    static void smoke() {
        System.out.println("== smoke: group, enrol, activate, raise, pay, default");
        String code = "H-SMOKE";
        startGroup(code, ForemanGuaranteeMode.COVER_PAYOUT_FOREMAN_BEARS_LOSS, "250.00");
        raiseAndDefaultTicket5(code);
        Position p = recoveryReads.position(code, 5, LocalDate.of(2026, 11, 6));
        eq("ticket 5 status", "DEFAULTED", p.ticketStatus());
        eq("overdue subscription", d("2000.00"), p.overdueSubscription());
        eq("overdue late fee", d("100.00"), p.overdueLateFees());
        eq("deposit held", d("1000.00"), p.depositHeld());
        check("a case is open", p.caseId() != null);
        ledgerIdentities("smoke", code);
    }

    static void sampleGroups() {
        System.out.println("== maintenance: sample groups");
        com.chitfund.service.maintenance.MaintenanceService m = new com.chitfund.service.maintenance.MaintenanceService(jdbc, null, groupSvc, true);
        com.chitfund.service.maintenance.MaintenanceService off = new com.chitfund.service.maintenance.MaintenanceService(jdbc, null, groupSvc, false);
        LocalDate start = LocalDate.of(2026, 12, 1);
        var r = tx(() -> m.createSampleGroups(new com.chitfund.service.maintenance.MaintenanceDtos.SampleGroupsRequest(3, d("100000"), 20, start), "admin"));
        eq("codes", java.util.List.of("SAMPLE-001", "SAMPLE-002", "SAMPLE-003"), r.groupCodes());
        eq("subscription", d("5000.00"), r.subscription());
        var g = groupSvc.get("SAMPLE-002");
        eq("status", GroupStatus.DRAFT, g.status());
        eq("chit value", d("100000.00"), g.chitValue());
        eq("tickets", 20, g.ticketCount());
        eq("cycles", 20, g.cycleCount());
        eq("start date", start, g.startDate());
        eq("created by", "admin", g.createdBy());
        eq("cycles not generated before activation", 0, groupSvc.cycles("SAMPLE-002").size());

        var r2 = tx(() -> m.createSampleGroups(new com.chitfund.service.maintenance.MaintenanceDtos.SampleGroupsRequest(2, d("250000"), 25, LocalDate.of(2027, 1, 1)), "admin"));
        eq("numbering carries on", java.util.List.of("SAMPLE-004", "SAMPLE-005"), r2.groupCodes());
        eq("second batch has its own values", d("10000.00"), groupSvc.get("SAMPLE-005").subscription());
        eq("five sample groups in all", 5L, jdbc.queryForObject("SELECT count(*) FROM chit_group WHERE group_code LIKE 'SAMPLE-%'", Long.class));

        // a sample group is a real group: it can be opened for enrolment
        tx(() -> groupSvc.openEnrolment("SAMPLE-001", key(), "maker"));
        eq("sample group can open for enrolment", GroupStatus.OPEN_FOR_ENROLMENT, groupSvc.get("SAMPLE-001").status());

        // a gap in the numbering is not reused
        jdbc.inTx(() -> jdbc.update("DELETE FROM chit_group_rules_version WHERE group_id = (SELECT id FROM chit_group WHERE group_code='SAMPLE-005')"));
        jdbc.inTx(() -> jdbc.update("DELETE FROM chit_group WHERE group_code='SAMPLE-005'"));
        var r3 = tx(() -> m.createSampleGroups(new com.chitfund.service.maintenance.MaintenanceDtos.SampleGroupsRequest(1, d("100000"), 20, start), "admin"));
        eq("numbering follows the highest remaining", java.util.List.of("SAMPLE-005"), r3.groupCodes());

        long before = jdbc.queryForObject("SELECT count(*) FROM chit_group", Long.class);
        refused("switch off", "RESET_DISABLED", () -> off.createSampleGroups(new com.chitfund.service.maintenance.MaintenanceDtos.SampleGroupsRequest(1, d("100000"), 20, start), "admin"));
        refused("chit value that does not divide", "VALIDATION_FAILED", () -> m.createSampleGroups(new com.chitfund.service.maintenance.MaintenanceDtos.SampleGroupsRequest(1, d("10000"), 3, start), "admin"));
        eq("refusals created nothing", before, jdbc.queryForObject("SELECT count(*) FROM chit_group", Long.class));
        check("audit rows written for the sample groups", jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE entity_ref LIKE 'SAMPLE-%' AND action='CREATED'", Long.class) >= 5);
    }
}
