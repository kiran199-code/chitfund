package com.chitfund.portal.member;

import com.chitfund.portal.auth.SessionMember;
import com.chitfund.portal.chit.ChitEnrolmentGateway;
import com.chitfund.portal.chit.ChitPortfolioGateway;
import com.chitfund.portal.chit.ChitPortfolioGateway.Auction;
import com.chitfund.portal.chit.ChitPortfolioGateway.Due;
import com.chitfund.portal.chit.ChitPortfolioGateway.MyBid;
import com.chitfund.portal.chit.ChitPortfolioGateway.Payment;
import com.chitfund.portal.chit.ChitPortfolioGateway.Request;
import com.chitfund.portal.chit.ChitPortfolioGateway.Ticket;
import com.chitfund.portal.common.Audit;
import com.chitfund.portal.common.PortalException;
import com.chitfund.portal.config.ErrorStatus;
import com.chitfund.portal.member.MemberPortfolioService.BidInput;
import com.chitfund.portal.member.MemberPortfolioService.WithdrawInput;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Isolation and degradation rules of the member's portfolio, against fakes of chitfund-service. */
class MemberPortfolioServiceTest {

    static final LocalDate TODAY = LocalDate.of(2026, 10, 25);
    static final SessionMember ASHA = new SessionMember(1, "asha@x.com", "Asha");
    static final SessionMember BOB = new SessionMember(2, "bob@x.com", "Bob");
    static final SessionMember STRANGER = new SessionMember(3, "nobody@x.com", "Nobody");

    /** Customers: asha=11, bob=22. Each owns ticket 1 and 2 of G1; G1 cycle 3 is LIVE for both. */
    static class FakeChit implements ChitEnrolmentGateway, ChitPortfolioGateway {
        final List<String> calls = new ArrayList<>();
        boolean auctionsDown;

        public Map<String, Long> lookupCustomers(List<String> emails) {
            Map<String, Long> m = new LinkedHashMap<>();
            for (String e : emails) {
                if (e.equals("asha@x.com")) m.put(e, 11L);
                if (e.equals("bob@x.com")) m.put(e, 22L);
            }
            return m;
        }
        public CustomerRef resolveCustomer(String e, String n, Long x) { throw new UnsupportedOperationException(); }
        public Quote quote(String g) { throw new UnsupportedOperationException(); }
        public int enrol(String g, long c, Received r, String k) { throw new UnsupportedOperationException(); }

        public List<Ticket> tickets(long c) {
            return List.of(new Ticket("G1", "Group 1", "ACTIVE", c == 11 ? 1 : 2, "ACTIVE", bd(100000), bd(5000), "MONTHLY", 20, TODAY, 3, 2, false, null, bd(0)));
        }
        public List<Payment> payments(long c, int limit) { calls.add("payments:" + c); return List.of(); }
        public List<Due> dues(long c, String view, int days) {
            if (view.equals("OPEN")) {
                return List.of(new Due("OVERDUE", "G1", "Group 1", 1, "ACTIVE", 2, "D1", TODAY.minusDays(5), 5, bd(5000), bd(100), bd(5100)),
                        new Due("OPEN", "G1", "Group 1", 1, "ACTIVE", 3, "D2", TODAY.plusDays(5), 0, bd(5000), bd(0), bd(5000)));
            }
            return List.of(new Due("UPCOMING", "G1", "Group 1", 1, "ACTIVE", 4, null, TODAY.plusDays(35), 0, bd(5000), bd(0), bd(5000)));
        }
        public List<Request> requests(long c) { return List.of(new Request("TRANSFER", "G1", "Group 1", 1, "PENDING", true, "OUTGOING", null, null, null, null, null)); }
        public List<Auction> auctions(long c) {
            if (auctionsDown) throw new PortalException("CHIT_SERVICE_UNAVAILABLE", "down");
            int mine = c == 11 ? 1 : 2;
            return List.of(new Auction("G1", "Group 1", 3, "LIVE", "OPEN_ASCENDING", TODAY, null, null, false, bd(0), bd(5000), bd(1000), false, 2,
                    List.of(mine), List.of(mine), true, null, List.of(new MyBid(mine, c == 11 ? 7 : 17, bd(900), "VALID", false)), false, null, null, null),
                    new Auction("G1", "Group 1", 4, "UPCOMING", "OPEN_ASCENDING", TODAY.plusDays(30), null, null, false, bd(0), bd(5000), null, false, 0,
                            List.of(mine), List.of(mine), false, "Not open yet", List.of(), false, null, null, null));
        }
        public int placeBid(String g, int cy, int t, BigDecimal d, long c, String k) { calls.add("bid:" + c + ":" + t + ":" + k); return 8; }
        public void withdrawBid(String g, int cy, int seq, String r, long c, String k) { calls.add("withdraw:" + c + ":" + seq + ":" + k + ":" + r); }
    }

    static class RecordingAudit extends Audit {
        final List<String> rows = new ArrayList<>();
        RecordingAudit() { super(null); }
        @Override public void log(String t, String ref, String action, String actor, String ip, String detail) { rows.add(action + ":" + actor); }
    }

    FakeChit chit;
    RecordingAudit audit;
    MemberPortfolioService svc;
    boolean offersDown;

    MemberPortfolioServiceTest() {
        setUp();
    }

    private void setUp() {
        chit = new FakeChit();
        audit = new RecordingAudit();
        offersDown = false;
        svc = new MemberPortfolioService(chit, chit, d -> {
            if (offersDown) throw new IllegalStateException("table missing");
            return List.of();
        }, audit);
    }

    static BigDecimal bd(long v) { return BigDecimal.valueOf(v); }

    static String refused(Runnable r) {
        try { r.run(); } catch (PortalException e) { return e.code(); }
        return "NONE";
    }

    @Test
    void eachMemberSeesOnlyTheirOwnTickets() {
        assertEquals(1, svc.tickets(ASHA).get(0).ticketNo());
        assertEquals(2, svc.tickets(BOB).get(0).ticketNo());
    }

    @Test
    void aSignInWithNoChitAccountGetsEmptyViewsNotAnError() {
        var o = svc.overview(STRANGER, TODAY);
        assertFalse(o.customer());
        assertEquals(0, o.summary().ticketsHeld());
        assertTrue(svc.tickets(STRANGER).isEmpty());
        assertFalse(svc.payments(STRANGER).customer());
        assertEquals("NOT_A_CUSTOMER", refused(() -> svc.placeBid(STRANGER, new BidInput("G1", 3, 1, bd(950)), "key-12345678", "ip")));
        assertEquals(403, ErrorStatus.of("NOT_A_CUSTOMER"));
    }

    @Test
    void dashboardSummarisesPaymentsAuctionsAndRequests() {
        var o = svc.overview(ASHA, TODAY);
        assertTrue(o.customer());
        assertEquals(1, o.summary().ticketsHeld());
        assertEquals(1, o.summary().overdueCount());
        assertEquals(0, new BigDecimal("5100").compareTo(o.summary().overdueTotal()));
        assertEquals(1, o.summary().liveAuctions());
        assertEquals(1, o.summary().upcomingAuctions());
        assertEquals(1, o.summary().openRequests());
        assertEquals(3, o.nextPayments().size());
        assertEquals(TODAY.minusDays(5), o.nextPayments().get(0).dueDate(), "soonest due date first, overdue before upcoming");
        assertTrue(o.unavailable().isEmpty());
    }

    @Test
    void oneSectionFailingOnlyMarksThatSection() {
        chit.auctionsDown = true;
        offersDown = true;
        var o = svc.overview(ASHA, TODAY);
        assertTrue(o.unavailable().containsAll(List.of("auctions", "offers")));
        assertEquals(1, o.summary().ticketsHeld(), "the rest still shows");
        assertEquals(1, o.summary().overdueCount());
    }

    @Test
    void paymentsPageSplitsOverdueOutstandingUpcoming() {
        var p = svc.payments(ASHA);
        assertEquals(1, p.overdue().size());
        assertEquals(1, p.outstanding().size());
        assertEquals(1, p.upcoming().size());
    }

    @Test
    void aBidIsPlacedForTheMembersOwnCustomerWithAPrefixedKey() {
        var r = svc.placeBid(ASHA, new BidInput("G1", 3, 1, bd(950)), "key-12345678", "1.2.3.4");
        assertEquals(8, r.bidSeq());
        assertEquals(List.of("bid:11:1:PORTAL-BID:11:key-12345678"), chit.calls);
        assertTrue(audit.rows.contains("BID_PLACED:member:1"));
        svc.placeBid(BOB, new BidInput("G1", 3, 2, bd(950)), "key-12345678", "1.2.3.4");
        assertTrue(chit.calls.get(1).contains("PORTAL-BID:22:"), "the same browser key for another member is a different chit key");
    }

    @Test
    void aMemberCannotBidWithSomeoneElsesTicket() {
        assertEquals("BID_NOT_ALLOWED", refused(() -> svc.placeBid(ASHA, new BidInput("G1", 3, 2, bd(950)), "key-12345678", "ip")), "ticket 2 is Bob's");
        assertEquals("BID_NOT_ALLOWED", refused(() -> svc.placeBid(BOB, new BidInput("G1", 3, 1, bd(950)), "key-12345678", "ip")));
        assertTrue(chit.calls.isEmpty(), "nothing reached chitfund-service");
        assertTrue(audit.rows.contains("BID_REFUSED:member:1"));
        assertEquals(409, ErrorStatus.of("BID_NOT_ALLOWED"));
    }

    @Test
    void noBidOnAnAuctionThatIsNotLiveOrNotTheirs() {
        assertEquals("BID_NOT_ALLOWED", refused(() -> svc.placeBid(ASHA, new BidInput("G1", 4, 1, bd(950)), "key-12345678", "ip")), "upcoming");
        assertEquals("BID_NOT_ALLOWED", refused(() -> svc.placeBid(ASHA, new BidInput("G9", 3, 1, bd(950)), "key-12345678", "ip")), "a group they are not in");
        assertEquals("BID_NOT_ALLOWED", refused(() -> svc.placeBid(ASHA, new BidInput("G1", 99, 1, bd(950)), "key-12345678", "ip")), "no such cycle");
        assertTrue(chit.calls.isEmpty());
    }

    @Test
    void bidInputIsValidated() {
        assertEquals("VALIDATION_FAILED", refused(() -> svc.placeBid(ASHA, null, "key-12345678", "ip")));
        assertEquals("VALIDATION_FAILED", refused(() -> svc.placeBid(ASHA, new BidInput("G1", 3, 1, bd(0)), "key-12345678", "ip")));
        assertEquals("VALIDATION_FAILED", refused(() -> svc.placeBid(ASHA, new BidInput("G1", 3, 1, bd(-5)), "key-12345678", "ip")));
        assertEquals("VALIDATION_FAILED", refused(() -> svc.placeBid(ASHA, new BidInput(" ", 3, 1, bd(5)), "key-12345678", "ip")));
        assertEquals("IDEMPOTENCY_KEY_REQUIRED", refused(() -> svc.placeBid(ASHA, new BidInput("G1", 3, 1, bd(5)), null, "ip")));
        assertEquals("IDEMPOTENCY_KEY_INVALID", refused(() -> svc.placeBid(ASHA, new BidInput("G1", 3, 1, bd(5)), "bad key!", "ip")));
        assertTrue(chit.calls.isEmpty());
    }

    @Test
    void aMemberWithdrawsOnlyTheirOwnValidBid() {
        svc.withdrawBid(ASHA, new WithdrawInput("G1", 3, 7, null), "key-abcdefgh", "ip");
        assertEquals("withdraw:11:7:PORTAL-WD:11:key-abcdefgh:Withdrawn by member", chit.calls.get(0));
        assertTrue(audit.rows.contains("BID_WITHDRAWN:member:1"));
        chit.calls.clear();
        assertEquals("BID_NOT_ALLOWED", refused(() -> svc.withdrawBid(ASHA, new WithdrawInput("G1", 3, 99, "x"), "key-abcdefgh", "ip")), "not in my bids");
        assertEquals("BID_NOT_ALLOWED", refused(() -> svc.withdrawBid(BOB, new WithdrawInput("G1", 3, 7, "x"), "key-abcdefgh", "ip")), "bid 7 is Asha's");
        assertTrue(chit.calls.isEmpty());
    }

    @Test
    void longWithdrawReasonsAreCutToTheChitLimit() {
        svc.withdrawBid(ASHA, new WithdrawInput("G1", 3, 7, "x".repeat(300)), "key-abcdefgh", "ip");
        assertEquals(120, chit.calls.get(0).substring(chit.calls.get(0).lastIndexOf(':') + 1).length());
    }

    @Test
    void gmailDotsAndTagsStillFindTheCustomer() {
        SessionMember dotted = new SessionMember(9, "A.S.Ha+tag@gmail.com", "x");
        // normalised to asha@gmail.com, which the fake does not know: no account, no error
        assertFalse(svc.overview(dotted, TODAY).customer());
    }

}
