package com.chitfund.service.auction;

import com.chitfund.service.domain.AuctionType;
import com.chitfund.service.domain.CycleStatus;
import com.chitfund.service.domain.DividendMode;
import com.chitfund.service.domain.TicketStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** Database rows of the auction, payout and dividend tables. */
public final class AuctionModels {
    private AuctionModels() {
    }

    public record CycleInfo(long id, int cycleNo, CycleStatus status, LocalDate dueDate, LocalDate auctionDate, Long winnerTicketId,
                            BigDecimal discount, BigDecimal prize, BigDecimal commission, BigDecimal dividendPool, LocalDate closedOn) {
    }

    public record AuctionRow(long id, long cycleId, AuctionType type, String status, int attempt, OffsetDateTime openedAt, String openedBy,
                             OffsetDateTime closedAt, String closedBy, Long winningBidId, String closeBasis, BigDecimal discount,
                             Long drawRecordId, Long settlementJournalId, String gateNote) {
        public boolean isOpen() {
            return "OPEN".equals(status);
        }
    }

    public record BidRow(long id, long auctionId, long ticketId, int ticketNo, int bidSeq, BigDecimal discount, OffsetDateTime bidTime,
                         String placedBy, String status, String withdrawnReason) {
    }

    public record DrawRow(long id, long groupId, Long cycleId, String purpose, String algorithm, long seed, String candidates, String result,
                          OffsetDateTime createdAt, String createdBy) {
    }

    /** A live ticket of the group with whether it has won a cycle (a winner whose prize is still unpaid counts as having won). */
    public record TicketFlag(long ticketId, int ticketNo, long customerId, TicketStatus status, boolean hasWon, String payoutAccountRef) {
    }

    public record DividendRow(long id, long cycleId, int cycleNo, long ticketId, int ticketNo, BigDecimal amount, DividendMode mode, String status,
                              LocalDate declaredOn, LocalDate settledOn) {
    }

    public record PayoutRow(long id, long cycleId, int cycleNo, long ticketId, int ticketNo, long customerId, BigDecimal gross, BigDecimal hold,
                            BigDecimal net, String status, String madeBy, String checkedBy, String payoutRef, OffsetDateTime disbursedAt,
                            String disbursedBy, BigDecimal paidGross, BigDecimal paidHold, BigDecimal floatAmount, BigDecimal floatRepaid,
                            String decisionNote, OffsetDateTime approvedAt, String accountRef) {
        public BigDecimal remainingGross() {
            return gross.subtract(paidGross);
        }
    }

    public record LegRow(long id, long payoutId, int legNo, String kind, BigDecimal amount, BigDecimal holdAmount, String instructionRef,
                         LocalDate businessDate, OffsetDateTime createdAt, String createdBy) {
    }

    public record CoverRow(long id, long cycleId, int cycleNo, long ticketId, int ticketNo, long demandId, BigDecimal amount,
                           BigDecimal recovered, String status, LocalDate coveredOn) {
    }
}
