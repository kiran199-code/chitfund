package com.chitfund.service.portfolio;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** What one customer sees of their own chit funds. Built for the customer portal: it never names another customer. */
public final class PortfolioDtos {
    private PortfolioDtos() {
    }

    /** A ticket the customer holds. {@code cyclesRaised}: instalments demanded so far; {@code cyclesPaid}: of those, settled. */
    public record TicketSummary(String groupCode, String groupName, String groupStatus, int ticketNo, String ticketStatus, BigDecimal chitValue,
                                BigDecimal subscription, String frequency, int cycleCount, LocalDate startDate, int cyclesRaised, int cyclesPaid,
                                boolean prizeWon, Integer prizedCycleNo, BigDecimal dividendsReceived) {
    }

    public record PaymentSummary(String paymentRef, String groupCode, String groupName, int ticketNo, BigDecimal amount, String channel, String status,
                                 LocalDate businessDate) {
    }

    /**
     * Something happening to the customer's ticket that staff decide: a ticket transfer (the customer gives or receives it, {@code role}
     * OUTGOING or INCOMING) or a prize payout. {@code inProgress} is true until it is decided or paid.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RequestSummary(String kind, String groupCode, String groupName, int ticketNo, String status, boolean inProgress, String role,
                                 Integer cycleNo, BigDecimal amount, BigDecimal held, OffsetDateTime requestedAt, OffsetDateTime decidedAt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MyBid(int ticketNo, int bidSeq, BigDecimal discountAmount, String status, Boolean leading) {
    }

    /**
     * One auction round as the customer sees it. {@code state}: UPCOMING (not opened), LIVE (open for bids), CLOSED. Other bidders are never
     * shown: only the best discount (open auctions) and this customer's own bids. {@code highestBid} is null for a sealed auction that is open.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AuctionSummary(String groupCode, String groupName, int cycleNo, String state, String auctionType, LocalDate auctionDate,
                                 OffsetDateTime openedAt, OffsetDateTime closedAt, boolean lastCycle, BigDecimal minDiscount, BigDecimal maxDiscount,
                                 BigDecimal highestBid, boolean bidsHidden, int bidCount, List<Integer> myTickets, List<Integer> eligibleTickets,
                                 boolean canBid, String cannotBidReason, List<MyBid> myBids, boolean iWon, Integer winningTicketNo,
                                 BigDecimal winningDiscount, BigDecimal prize) {
    }
}
