package com.chitfund.portal.invite;

import java.time.OffsetDateTime;

public final class InviteModels {
    private InviteModels() {
    }

    /** One invitation row as stored. {@code status} is the stored status; use {@link InviteLifecycle#effective} for the one that counts now. */
    public record InviteRow(long id, String groupCode, String groupName, String email, String inviteeName, int ticketsOffered, InviteStatus status,
                            String tokenHash, int tokenVersion, OffsetDateTime expiresAt, Long memberId, String payoutAccountRef,
                            String createdBy, OffsetDateTime sentAt, OffsetDateTime firstOpenedAt, OffsetDateTime acceptedAt,
                            OffsetDateTime declinedAt, OffsetDateTime revokedAt, String revokedBy, String revokeReason,
                            OffsetDateTime createdAt, String mailStatus, Integer mailAttempts,
                            Long existingCustomerId, Long customerId, int ticketsEnrolled) {
    }
}
