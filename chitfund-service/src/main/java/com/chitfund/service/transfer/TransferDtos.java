package com.chitfund.service.transfer;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** Request and response shapes of the transfer and exit endpoints. */
public final class TransferDtos {
    private TransferDtos() {
    }

    /** Hand the ticket to {@code toCustomerId}. {@code payoutAccountRef} is the new holder's payout account; the old holder's never carries over. */
    public record TransferRequest(Long toCustomerId, String reason, String payoutAccountRef) {
    }

    /** A non-prized member leaves; {@code replacementCustomerId} takes over the same ticket. */
    public record ExitRequest(Long replacementCustomerId, String reason, String payoutAccountRef) {
    }

    public record RejectTransferRequest(String reason) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TransferView(long id, String groupCode, int ticketNo, String kind, String status, long fromCustomerId, long toCustomerId,
                               BigDecimal fee, String reason, String decisionReason, String payoutAccountRef, String ticketStatusBefore,
                               LocalDate requestedOn, LocalDate effectiveOn, String madeBy, String checkedBy, OffsetDateTime createdAt,
                               OffsetDateTime decidedAt, boolean replayed) {
        public TransferView asReplay() {
            return new TransferView(id, groupCode, ticketNo, kind, status, fromCustomerId, toCustomerId, fee, reason, decisionReason, payoutAccountRef,
                    ticketStatusBefore, requestedOn, effectiveOn, madeBy, checkedBy, createdAt, decidedAt, true);
        }
    }
}
