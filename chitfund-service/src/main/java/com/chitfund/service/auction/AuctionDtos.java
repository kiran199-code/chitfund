package com.chitfund.service.auction;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** Request and response shapes of the auction, payout and dividend endpoints. */
public final class AuctionDtos {
    private AuctionDtos() {
    }

    // ---- requests -------------------------------------------------------------------------------------------------

    /** A bid typed in by the operator for a member: the ticket and the discount offered, in rupees. */
    public record PlaceBidRequest(Integer ticketNo, BigDecimal discountAmount) {
    }

    public record WithdrawBidRequest(String reason) {
    }

    public record RejectPayoutRequest(String reason) {
    }

    public record FloatRepaymentRequest(BigDecimal amount) {
    }

    // ---- auction --------------------------------------------------------------------------------------------------

    /**
     * One bid. In a SEALED_BID auction that is still open the discount is hidden ({@code null}); it appears when the auction closes.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BidView(int bidSeq, int ticketNo, BigDecimal discountAmount, OffsetDateTime bidTime, String placedBy, String status,
                          String withdrawnReason, boolean winning, boolean replayed) {
        public BidView asReplay() {
            return new BidView(bidSeq, ticketNo, discountAmount, bidTime, placedBy, status, withdrawnReason, winning, true);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DrawView(long id, String purpose, String algorithm, @JsonFormat(shape = JsonFormat.Shape.STRING) long seed, List<Integer> candidates, List<Integer> result,
                           OffsetDateTime createdAt, String createdBy) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DividendView(int cycleNo, int ticketNo, BigDecimal amount, String mode, String status, LocalDate declaredOn, LocalDate settledOn) {
    }

    /** The money result of a closed cycle: {@code chitValue = prize + commission + dividendPool}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SettlementView(BigDecimal chitValue, BigDecimal discount, BigDecimal prize, BigDecimal commission, BigDecimal dividendPool,
                                 boolean balanced, List<DividendView> dividends) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UnpaidView(int ticketNo, BigDecimal amount) {
    }

    /** Whether the auction of a cycle may open, and why. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GateView(String groupCode, int cycleNo, String cycleStatus, LocalDate auctionDate, String rule, boolean open, boolean due,
                           String reason, BigDecimal dueAmount, BigDecimal collected, BigDecimal collectedPct, BigDecimal shortfall,
                           List<UnpaidView> unpaid, boolean lastCycle, String blocker) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AuctionView(String groupCode, int cycleNo, String cycleStatus, LocalDate dueDate, LocalDate auctionDate, String auctionType,
                              boolean lastCycle, Integer attempt, String status, OffsetDateTime openedAt, String openedBy, OffsetDateTime closedAt,
                              String closedBy, String closeBasis, String gateNote, BigDecimal minDiscount, BigDecimal maxDiscount,
                              BigDecimal highestBid, int bidCount, boolean bidsHidden, List<BidView> bids, Integer winnerTicketNo,
                              Long winnerCustomerId, SettlementView settlement, DrawView draw, List<Integer> rolledOverAttempts, boolean replayed) {
        public AuctionView asReplay() {
            return new AuctionView(groupCode, cycleNo, cycleStatus, dueDate, auctionDate, auctionType, lastCycle, attempt, status, openedAt, openedBy,
                    closedAt, closedBy, closeBasis, gateNote, minDiscount, maxDiscount, highestBid, bidCount, bidsHidden, bids, winnerTicketNo,
                    winnerCustomerId, settlement, draw, rolledOverAttempts, true);
        }
    }

    // ---- payout ---------------------------------------------------------------------------------------------------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LegView(int legNo, String kind, BigDecimal amount, BigDecimal holdAmount, String instructionRef, LocalDate businessDate,
                          OffsetDateTime createdAt, String createdBy) {
    }

    /** What a payout would do right now under each liquidity rule, and which one the group has. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LiquidityOutcome(String rule, boolean configured, String action, BigDecimal payNow, BigDecimal floatAmount,
                                   BigDecimal balanceLater, BigDecimal shortfall) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PoolPositionView(BigDecimal poolCash, BigDecimal commissionIncome, BigDecimal lateFeeIncome, BigDecimal dividendPayable,
                                   BigDecimal prizePayable, BigDecimal advanceHeld, BigDecimal availableForPrize, BigDecimal surplus,
                                   BigDecimal prizeRemaining, List<LiquidityOutcome> outcomes) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PayoutView(String groupCode, int cycleNo, int ticketNo, long customerId, BigDecimal grossPrize, BigDecimal holdAmount,
                             BigDecimal netAmount, String status, String madeBy, String checkedBy, String decisionNote, OffsetDateTime approvedAt,
                             String payoutRef, String accountRef, OffsetDateTime disbursedAt, String disbursedBy, BigDecimal paidGross,
                             BigDecimal paidHold, BigDecimal remainingGross, BigDecimal floatAmount, BigDecimal floatRepaid,
                             List<LegView> legs, boolean replayed) {
        public PayoutView asReplay() {
            return new PayoutView(groupCode, cycleNo, ticketNo, customerId, grossPrize, holdAmount, netAmount, status, madeBy, checkedBy, decisionNote,
                    approvedAt, payoutRef, accountRef, disbursedAt, disbursedBy, paidGross, paidHold, remainingGross, floatAmount, floatRepaid, legs, true);
        }
    }

    /** The result of paying out the dividends of one cycle. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DividendPayResult(String groupCode, int cycleNo, int paid, BigDecimal total, List<DividendView> dividends, boolean replayed) {
        public DividendPayResult asReplay() {
            return new DividendPayResult(groupCode, cycleNo, paid, total, dividends, true);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CoverView(int cycleNo, int ticketNo, BigDecimal amount, BigDecimal recovered, String status, LocalDate coveredOn) {
    }
}
