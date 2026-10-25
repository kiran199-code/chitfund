package com.chitfund.service.portfolio;

import com.chitfund.service.auction.AuctionDtos.AuctionView;
import com.chitfund.service.auction.AuctionDtos.BidView;
import com.chitfund.service.auction.AuctionReadService;
import com.chitfund.service.common.ChitException;
import com.chitfund.service.portfolio.PortfolioDtos.AuctionSummary;
import com.chitfund.service.portfolio.PortfolioDtos.MyBid;
import com.chitfund.service.portfolio.PortfolioDtos.PaymentSummary;
import com.chitfund.service.portfolio.PortfolioDtos.RequestSummary;
import com.chitfund.service.portfolio.PortfolioDtos.TicketSummary;
import com.chitfund.service.portfolio.PortfolioRepository.CycleRef;
import com.chitfund.service.portfolio.PortfolioRepository.MyTicket;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything one customer may see of their own chit funds, in one place, for the customer portal. The caller is trusted to have worked out the
 * customer id from a verified sign-in; nothing here accepts a ticket or group to look at that is not already the customer's. Other customers
 * never appear: bids of others are reduced to the best discount, and a prize amount is shown only to its winner.
 */
@Service
public class PortfolioService {

    static final int CLOSED_WINDOW_DAYS = 60;
    static final String LIVE = "LIVE";
    static final String UPCOMING = "UPCOMING";
    static final String CLOSED = "CLOSED";

    private final PortfolioRepository repo;
    private final AuctionReadService auctions;

    public PortfolioService(PortfolioRepository repo, AuctionReadService auctions) {
        this.repo = repo;
        this.auctions = auctions;
    }

    @Transactional(readOnly = true)
    public List<TicketSummary> tickets(long customerId) {
        requirePositive(customerId);
        return repo.tickets(customerId);
    }

    @Transactional(readOnly = true)
    public List<PaymentSummary> payments(long customerId, int limit, int offset) {
        requirePositive(customerId);
        return repo.payments(customerId, Math.max(1, Math.min(limit, 200)), Math.max(0, offset));
    }

    /** Transfers and prize payouts involving the customer's tickets, those still in progress first. */
    @Transactional(readOnly = true)
    public List<RequestSummary> requests(long customerId) {
        requirePositive(customerId);
        List<RequestSummary> all = new ArrayList<>(repo.transfers(customerId, 50));
        all.addAll(repo.payouts(customerId, 50));
        all.sort(Comparator.comparing(RequestSummary::inProgress).reversed()
                .thenComparing(RequestSummary::requestedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return all;
    }

    /** Live, upcoming and recently closed auctions for the groups the customer holds tickets in. Live first, then upcoming, then closed. */
    @Transactional(readOnly = true)
    public List<AuctionSummary> auctions(long customerId, LocalDate today) {
        requirePositive(customerId);
        Map<String, List<MyTicket>> mine = new HashMap<>();
        for (MyTicket t : repo.liveTickets(customerId)) {
            mine.computeIfAbsent(t.groupCode(), k -> new ArrayList<>()).add(t);
        }
        List<AuctionSummary> out = new ArrayList<>();
        for (CycleRef ref : repo.auctionCycles(customerId, today.minusDays(CLOSED_WINDOW_DAYS))) {
            List<MyTicket> tickets = mine.get(ref.groupCode());
            if (tickets == null || tickets.isEmpty()) {
                continue;
            }
            out.add(summary(auctions.auction(ref.groupCode(), ref.cycleNo()), tickets));
        }
        out.sort(Comparator.comparingInt((AuctionSummary a) -> rank(a.state())).thenComparing(AuctionSummary::auctionDate)
                .thenComparing(AuctionSummary::groupCode));
        return out;
    }

    private static int rank(String state) {
        return LIVE.equals(state) ? 0 : UPCOMING.equals(state) ? 1 : 2;
    }

    static AuctionSummary summary(AuctionView v, List<MyTicket> tickets) {
        String state = "OPEN".equals(v.status()) ? LIVE : "NOT_OPENED".equals(v.status()) ? UPCOMING : CLOSED;
        String groupName = tickets.get(0).groupName();
        List<Integer> mine = tickets.stream().map(MyTicket::ticketNo).toList();
        List<Integer> eligible = tickets.stream().filter(t -> "ACTIVE".equals(t.status()) && !t.hasWon()).map(MyTicket::ticketNo).toList();
        boolean bidType = "OPEN_ASCENDING_DISCOUNT".equals(v.auctionType()) || "SEALED_BID".equals(v.auctionType());
        boolean canBid = LIVE.equals(state) && bidType && !v.lastCycle() && !eligible.isEmpty();
        String reason = null;
        if (LIVE.equals(state) && !canBid) {
            reason = v.lastCycle() ? "This is the last cycle: the one remaining ticket takes the whole pot, nobody bids."
                    : !bidType ? "In this group the winner is drawn, not bid for."
                    : "None of your tickets can bid: a ticket that has already won, or is not in good standing, cannot.";
        }
        boolean open = "OPEN".equals(v.status());
        boolean sealed = "SEALED_BID".equals(v.auctionType());
        List<MyBid> myBids = new ArrayList<>();
        if (v.bids() != null) {
            for (BidView b : v.bids()) {
                if (!mine.contains(b.ticketNo())) {
                    continue;
                }
                Boolean leading = null;
                if (open && !sealed && "VALID".equals(b.status()) && b.discountAmount() != null && v.highestBid() != null) {
                    leading = b.discountAmount().compareTo(v.highestBid()) == 0;
                }
                myBids.add(new MyBid(b.ticketNo(), b.bidSeq(), b.discountAmount(), b.status(), leading));
            }
        }
        boolean iWon = v.winnerTicketNo() != null && mine.contains(v.winnerTicketNo());
        BigDecimal winningDiscount = v.settlement() == null ? null : v.settlement().discount();
        BigDecimal prize = iWon && v.settlement() != null ? v.settlement().prize() : null;
        return new AuctionSummary(v.groupCode(), groupName, v.cycleNo(), state, v.auctionType(), v.auctionDate(), v.openedAt(), v.closedAt(), v.lastCycle(),
                v.minDiscount(), v.maxDiscount(), v.highestBid(), v.bidsHidden(), v.bidCount(), mine, eligible, canBid, reason, myBids, iWon,
                iWon ? v.winnerTicketNo() : null, winningDiscount, prize);
    }

    private static void requirePositive(long customerId) {
        if (customerId <= 0) {
            throw ChitException.badRequest("VALIDATION_FAILED", "customer id must be positive");
        }
    }
}
