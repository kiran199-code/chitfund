package com.chitfund.service.recovery;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** Database rows of the recovery tables. */
public final class RecoveryModels {
    private RecoveryModels() {
    }

    /** An open default case with the recovery figures; {@code recovered} = security applied + covers repaid. */
    public record CaseRow(long id, long ticketId, String status, LocalDate openedOn, BigDecimal shortfall, BigDecimal securityApplied,
                          BigDecimal coverRecovered, BigDecimal recovered, BigDecimal writtenOff, BigDecimal foremanCovered, LocalDate closedOn) {
    }

    public record ActionRow(long id, Long caseId, long ticketId, int ticketNo, String kind, BigDecimal arrears, BigDecimal guarantee,
                            String channel, String externalRef, String paymentRef, LocalDate businessDate, String createdBy,
                            OffsetDateTime createdAt) {
    }

    public record WriteOffRow(long id, long caseId, long ticketId, int ticketNo, String status, BigDecimal subscription, BigDecimal covers,
                              BigDecimal fees, String reason, String madeBy, String checkedBy, String decisionReason, LocalDate requestedOn,
                              LocalDate decidedOn, OffsetDateTime createdAt, OffsetDateTime decidedAt) {
        public BigDecimal total() {
            return subscription.add(covers).add(fees);
        }
    }
}
