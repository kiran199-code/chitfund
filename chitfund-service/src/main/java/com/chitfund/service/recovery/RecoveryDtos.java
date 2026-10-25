package com.chitfund.service.recovery;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** Request and response shapes of the recovery and write-off endpoints. */
public final class RecoveryDtos {
    private RecoveryDtos() {
    }

    /** {@code amount} is optional: when left out, as much of the held deposit is applied as the ticket owes. */
    public record ApplySecurityRequest(BigDecimal amount) {
    }

    /** Money the ticket's holder pays to the foreman against what he covered. {@code channel}: UPI, BANK_TRANSFER, CASH_AT_FOREMAN or AUTO_DEBIT. */
    public record RepayCoverRequest(BigDecimal amount, String channel, String externalRef) {
    }

    public record WriteOffRequest(String reason) {
    }

    public record RejectWriteOffRequest(String reason) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ActionView(long id, int ticketNo, String kind, BigDecimal toArrears, BigDecimal toForeman, BigDecimal total, String channel,
                             String externalRef, String paymentRef, LocalDate businessDate, String createdBy, OffsetDateTime createdAt, boolean replayed) {
        public ActionView asReplay() {
            return new ActionView(id, ticketNo, kind, toArrears, toForeman, total, channel, externalRef, paymentRef, businessDate, createdBy, createdAt, true);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WriteOffView(long id, String groupCode, int ticketNo, String status, BigDecimal subscription, BigDecimal earlierCovers, BigDecimal lateFees,
                               BigDecimal total, String reason, String madeBy, String checkedBy, String decisionReason, LocalDate requestedOn,
                               LocalDate decidedOn, OffsetDateTime createdAt, OffsetDateTime decidedAt, boolean replayed) {
        public WriteOffView asReplay() {
            return new WriteOffView(id, groupCode, ticketNo, status, subscription, earlierCovers, lateFees, total, reason, madeBy, checkedBy, decisionReason,
                    requestedOn, decidedOn, createdAt, decidedAt, true);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CoverView(long id, int cycleNo, BigDecimal amount, BigDecimal repaid, BigDecimal owed, String status, LocalDate coveredOn) {
    }

    /**
     * Where a ticket stands in recovery. {@code overdue*} are what the pool is still owed on past-due demands; {@code coversOwed} is what the foreman is
     * owed; {@code depositHeld} is the deposit (and any prize hold) still held for the ticket. {@code canWriteOff} is false with the reason in
     * {@code writeOffBlockedBy} (for example the deposit has not been applied yet).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Position(String groupCode, int ticketNo, long customerId, String ticketStatus, LocalDate asOf, Long caseId, String caseStatus,
                           LocalDate caseOpenedOn, BigDecimal overdueSubscription, BigDecimal overdueLateFees, BigDecimal coversOwed,
                           BigDecimal depositHeld, BigDecimal securityApplied, BigDecimal coversRepaid, BigDecimal writtenOff, boolean canWriteOff,
                           String writeOffBlockedBy, List<CoverView> covers, List<ActionView> actions, List<WriteOffView> writeOffs) {
    }
}
