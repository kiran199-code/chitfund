package com.chitfund.service.transfer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** Database rows of the transfer table. */
public final class TransferModels {
    private TransferModels() {
    }

    public record TransferRow(long id, long groupId, long ticketId, int ticketNo, TransferKind kind, String status, long fromCustomerId,
                              long toCustomerId, BigDecimal fee, String reason, String decisionReason, String payoutAccountRef,
                              String previousStatus, LocalDate requestedOn, LocalDate effectiveOn, String madeBy, String checkedBy,
                              OffsetDateTime createdAt, OffsetDateTime decidedAt) {
    }
}
