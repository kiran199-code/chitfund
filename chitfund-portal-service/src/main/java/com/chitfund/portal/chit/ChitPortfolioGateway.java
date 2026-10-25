package com.chitfund.portal.chit;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * What one customer may see and do in chitfund-service: their tickets, what they owe, their payments, requests about their tickets and the
 * auctions of their groups; and place or withdraw a bid. Every call takes the customer id the portal worked out from the verified sign-in.
 * An interface so tests use a fake.
 */
public interface ChitPortfolioGateway {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Ticket(String groupCode, String groupName, String groupStatus, int ticketNo, String ticketStatus, BigDecimal chitValue, BigDecimal subscription,
                  String frequency, int cycleCount, LocalDate startDate, int cyclesRaised, int cyclesPaid, boolean prizeWon, Integer prizedCycleNo,
                  BigDecimal dividendsReceived) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Payment(String paymentRef, String groupCode, String groupName, int ticketNo, BigDecimal amount, String channel, String status, LocalDate businessDate) {
    }

    /** An instalment owed or coming up. {@code demandRef} is null for one not raised yet. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Due(String view, String groupCode, String groupName, int ticketNo, String ticketStatus, int cycleNo, String demandRef, LocalDate dueDate,
               long daysOverdue, BigDecimal subscriptionOutstanding, BigDecimal lateFeeOutstanding, BigDecimal totalOutstanding) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Request(String kind, String groupCode, String groupName, int ticketNo, String status, boolean inProgress, String role, Integer cycleNo,
                   BigDecimal amount, BigDecimal held, OffsetDateTime requestedAt, OffsetDateTime decidedAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record MyBid(int ticketNo, int bidSeq, BigDecimal discountAmount, String status, Boolean leading) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Auction(String groupCode, String groupName, int cycleNo, String state, String auctionType, LocalDate auctionDate, OffsetDateTime openedAt,
                   OffsetDateTime closedAt, boolean lastCycle, BigDecimal minDiscount, BigDecimal maxDiscount, BigDecimal highestBid, boolean bidsHidden,
                   int bidCount, List<Integer> myTickets, List<Integer> eligibleTickets, boolean canBid, String cannotBidReason, List<MyBid> myBids,
                   boolean iWon, Integer winningTicketNo, BigDecimal winningDiscount, BigDecimal prize) {
    }

    List<Ticket> tickets(long customerId);

    List<Payment> payments(long customerId, int limit);

    /** {@code view}: OVERDUE, OPEN (everything raised and unpaid) or UPCOMING (not raised yet, due within {@code withinDays}). */
    List<Due> dues(long customerId, String view, int withinDays);

    List<Request> requests(long customerId);

    List<Auction> auctions(long customerId);

    /** Places a bid for one of the customer's tickets; returns its sequence number. The idempotency key makes a retry return the same bid. */
    int placeBid(String groupCode, int cycleNo, int ticketNo, BigDecimal discountAmount, long customerId, String idempotencyKey);

    void withdrawBid(String groupCode, int cycleNo, int bidSeq, String reason, long customerId, String idempotencyKey);
}
