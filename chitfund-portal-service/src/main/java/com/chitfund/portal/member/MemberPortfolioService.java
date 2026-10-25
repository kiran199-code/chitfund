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
import com.chitfund.portal.invite.Emails;
import com.chitfund.portal.member.OfferSource.OfferView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Everything the signed-in member sees about their own chits, and the one thing they can do: bid.
 *
 * <p>Isolation: the customer id is never taken from the request. It is looked up from the verified sign-in e-mail
 * ({@code lookupCustomers}), and every chitfund-service call is scoped to that id. A bid or a withdrawal is checked against the member's own
 * auction list first, so a ticket or bid that is not theirs gets the same refusal as one that is not allowed.</p>
 */
@Service
public class MemberPortfolioService {

    public record Overview(boolean customer, List<String> unavailable, Summary summary, List<Due> nextPayments, List<Auction> auctions,
                           List<Request> requests, List<OfferView> offers, List<Ticket> tickets) {
    }

    public record Summary(int ticketsHeld, Due nextPayment, int overdueCount, BigDecimal overdueTotal, int liveAuctions, int upcomingAuctions, int openRequests) {
    }

    public record PaymentsPage(boolean customer, List<Due> overdue, List<Due> outstanding, List<Due> upcoming, List<Payment> history, String howToPay) {
    }

    public record BidInput(String groupCode, Integer cycleNo, Integer ticketNo, BigDecimal discountAmount) {
    }

    public record WithdrawInput(String groupCode, Integer cycleNo, Integer bidSeq, String reason) {
    }

    public record BidResult(int bidSeq, List<Auction> auctions) {
    }

    static final int UPCOMING_DAYS = 60;
    static final int HISTORY_LIMIT = 100;
    static final int NEXT_PAYMENTS = 3;

    private final ChitEnrolmentGateway customers;
    private final ChitPortfolioGateway chit;
    private final OfferSource offers;
    private final HowToPay howToPay;
    private final Audit audit;

    public MemberPortfolioService(ChitEnrolmentGateway customers, ChitPortfolioGateway chit, OfferSource offers, HowToPay howToPay, Audit audit) {
        this.customers = customers;
        this.chit = chit;
        this.offers = offers;
        this.howToPay = howToPay;
        this.audit = audit;
    }

    /** The member's customer id, or null when the e-mail is not a customer of the chit house. */
    Long customerId(SessionMember me) {
        String email = Emails.normalise(me.email());
        if (email == null) {
            return null;
        }
        Map<String, Long> found = customers.lookupCustomers(List.of(email));
        if (found == null || found.isEmpty()) {
            return null;
        }
        Long id = found.get(email);
        return id != null ? id : found.size() == 1 ? found.values().iterator().next() : null; // one e-mail asked, so one answer at most
    }

    private long requireCustomer(SessionMember me) {
        Long id = customerId(me);
        if (id == null) {
            throw PortalException.forbidden("NOT_A_CUSTOMER", "This sign-in has no chit account yet");
        }
        return id;
    }

    // ---------------------------------------------------------------- reads

    public Overview overview(SessionMember me, LocalDate today) {
        List<String> unavailable = new ArrayList<>();
        List<OfferView> offerList = safe("offers", unavailable, () -> offers.forMembers(today), List.of());
        Long id = customerId(me);
        if (id == null) {
            return new Overview(false, unavailable, new Summary(0, null, 0, BigDecimal.ZERO, 0, 0, 0), List.of(), List.of(), List.of(), offerList, List.of());
        }
        List<Ticket> tickets = safe("tickets", unavailable, () -> chit.tickets(id), List.of());
        List<Due> open = safe("payments", unavailable, () -> chit.dues(id, "OPEN", 0), List.of());
        List<Due> upcoming = safe("payments", unavailable, () -> chit.dues(id, "UPCOMING", UPCOMING_DAYS), List.of());
        List<Auction> auctions = safe("auctions", unavailable, () -> chit.auctions(id), List.of());
        List<Request> requests = safe("requests", unavailable, () -> chit.requests(id), List.of());

        List<Due> payable = new ArrayList<>(open);
        payable.addAll(upcoming);
        payable.sort(Comparator.comparing(Due::dueDate, Comparator.nullsLast(Comparator.naturalOrder())));
        List<Due> overdue = open.stream().filter(d -> "OVERDUE".equals(d.view()) || d.daysOverdue() > 0).toList();
        BigDecimal overdueTotal = overdue.stream().map(d -> nz(d.totalOutstanding())).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<Auction> liveOrUpcoming = auctions.stream().filter(a -> !"CLOSED".equals(a.state())).limit(3).toList();
        int live = (int) auctions.stream().filter(a -> "LIVE".equals(a.state())).count();
        int soon = (int) auctions.stream().filter(a -> "UPCOMING".equals(a.state())).count();
        int openReq = (int) requests.stream().filter(Request::inProgress).count();
        Summary s = new Summary(tickets.size(), payable.isEmpty() ? null : payable.get(0), overdue.size(), overdueTotal, live, soon, openReq);
        return new Overview(true, unavailable.stream().distinct().toList(), s, payable.stream().limit(NEXT_PAYMENTS).toList(), liveOrUpcoming,
                requests.stream().filter(Request::inProgress).limit(3).toList(), offerList, tickets);
    }

    public List<Ticket> tickets(SessionMember me) {
        Long id = customerId(me);
        return id == null ? List.of() : chit.tickets(id);
    }

    public PaymentsPage payments(SessionMember me) {
        Long id = customerId(me);
        if (id == null) {
            return new PaymentsPage(false, List.of(), List.of(), List.of(), List.of(), "");
        }
        List<Due> open = chit.dues(id, "OPEN", 0);
        List<Due> overdue = open.stream().filter(d -> "OVERDUE".equals(d.view()) || d.daysOverdue() > 0).toList();
        List<Due> outstanding = open.stream().filter(d -> !overdue.contains(d)).toList();
        return new PaymentsPage(true, overdue, outstanding, chit.dues(id, "UPCOMING", UPCOMING_DAYS), chit.payments(id, HISTORY_LIMIT), instructions());
    }

    /** The instructions text never blocks the page: if settings cannot be read the page shows without it. */
    private String instructions() {
        try {
            String t = howToPay.text();
            return t == null ? "" : t;
        } catch (RuntimeException e) {
            return "";
        }
    }

    public List<Auction> auctions(SessionMember me) {
        Long id = customerId(me);
        return id == null ? List.of() : chit.auctions(id);
    }

    public List<Request> requests(SessionMember me) {
        Long id = customerId(me);
        return id == null ? List.of() : chit.requests(id);
    }

    // ---------------------------------------------------------------- bidding

    @Transactional(noRollbackFor = PortalException.class)
    public BidResult placeBid(SessionMember me, BidInput in, String browserKey, String ip) {
        if (in == null || in.groupCode() == null || in.groupCode().isBlank() || in.cycleNo() == null || in.cycleNo() < 1
                || in.ticketNo() == null || in.ticketNo() < 1 || in.discountAmount() == null || in.discountAmount().signum() <= 0) {
            throw PortalException.badRequest("VALIDATION_FAILED", "groupCode, cycleNo, ticketNo and a discountAmount above zero are required");
        }
        String key = key(browserKey);
        long customerId = requireCustomer(me);
        Auction a = ownAuction(customerId, in.groupCode(), in.cycleNo());
        if (a == null || !"LIVE".equals(a.state()) || !a.canBid() || a.eligibleTickets() == null || !a.eligibleTickets().contains(in.ticketNo())) {
            audit.log("BID", in.groupCode() + "/" + in.cycleNo() + "/" + in.ticketNo(), "BID_REFUSED", "member:" + me.memberId(), ip, "customer " + customerId);
            throw PortalException.conflict("BID_NOT_ALLOWED", a != null && a.cannotBidReason() != null && !a.cannotBidReason().isBlank()
                    ? a.cannotBidReason() : "You cannot bid on that ticket in this auction");
        }
        int seq = chit.placeBid(in.groupCode(), in.cycleNo(), in.ticketNo(), in.discountAmount(), customerId, "PORTAL-BID:" + customerId + ":" + key);
        audit.log("BID", in.groupCode() + "/" + in.cycleNo() + "/" + in.ticketNo(), "BID_PLACED", "member:" + me.memberId(), ip,
                "customer " + customerId + " seq " + seq + " discount " + in.discountAmount().toPlainString());
        return new BidResult(seq, chit.auctions(customerId));
    }

    @Transactional(noRollbackFor = PortalException.class)
    public BidResult withdrawBid(SessionMember me, WithdrawInput in, String browserKey, String ip) {
        if (in == null || in.groupCode() == null || in.groupCode().isBlank() || in.cycleNo() == null || in.cycleNo() < 1 || in.bidSeq() == null || in.bidSeq() < 1) {
            throw PortalException.badRequest("VALIDATION_FAILED", "groupCode, cycleNo and bidSeq are required");
        }
        String key = key(browserKey);
        long customerId = requireCustomer(me);
        Auction a = ownAuction(customerId, in.groupCode(), in.cycleNo());
        boolean mine = a != null && "LIVE".equals(a.state()) && a.myBids() != null
                && a.myBids().stream().anyMatch(b -> b.bidSeq() == in.bidSeq() && "VALID".equals(b.status()));
        if (!mine) {
            audit.log("BID", in.groupCode() + "/" + in.cycleNo() + "#" + in.bidSeq(), "WITHDRAW_REFUSED", "member:" + me.memberId(), ip, "customer " + customerId);
            throw PortalException.conflict("BID_NOT_ALLOWED", "That bid cannot be withdrawn");
        }
        String reason = in.reason() == null || in.reason().isBlank() ? "Withdrawn by member" : in.reason().trim();
        if (reason.length() > 120) {
            reason = reason.substring(0, 120);
        }
        chit.withdrawBid(in.groupCode(), in.cycleNo(), in.bidSeq(), reason, customerId, "PORTAL-WD:" + customerId + ":" + key);
        audit.log("BID", in.groupCode() + "/" + in.cycleNo() + "#" + in.bidSeq(), "BID_WITHDRAWN", "member:" + me.memberId(), ip, "customer " + customerId);
        return new BidResult(in.bidSeq(), chit.auctions(customerId));
    }

    private Auction ownAuction(long customerId, String groupCode, int cycleNo) {
        for (Auction a : chit.auctions(customerId)) {
            if (a.groupCode().equals(groupCode) && a.cycleNo() == cycleNo) {
                return a;
            }
        }
        return null;
    }

    private static String key(String k) {
        if (k == null || k.isBlank()) {
            throw PortalException.badRequest("IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key header is required");
        }
        if (!k.matches("[A-Za-z0-9_-]{8,64}")) {
            throw PortalException.badRequest("IDEMPOTENCY_KEY_INVALID", "Idempotency-Key must be 8-64 letters, digits, - or _");
        }
        return k;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /** One section failing (chitfund-service slow, offers table missing) must not blank the whole dashboard. */
    private static <T> T safe(String section, List<String> unavailable, Supplier<T> call, T fallback) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            unavailable.add(section);
            return fallback;
        }
    }
}
