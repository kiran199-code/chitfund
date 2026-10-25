package com.chitfund.portal.invite;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.util.List;

public final class InviteDtos {
    private InviteDtos() {
    }

    /** One person to invite. {@code tickets} defaults to 1. {@code existingCustomerId} is optional: an old customer id to attach this e-mail to. */
    public record Invitee(String email, String name, Integer tickets, Long existingCustomerId) {
        public Invitee(String email, String name, Integer tickets) {
            this(email, name, tickets, null);
        }
    }

    /** What the admin confirms when recording that the money and papers for an accepted invitation are in. */
    public record EnrolRequest(Boolean feesReceived, Long guarantorCustomerId, String lienReference, String payoutAccountRef) {
    }

    /** {@code expiryDays} (1 to 30) overrides the default lifetime for this batch. */
    public record InviteRequest(List<Invitee> invitees, Integer expiryDays) {
    }

    /** What the admin sees about one invitation. {@code status} is the status that counts now (an expired PENDING shows as EXPIRED). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InviteView(long id, String groupCode, String groupName, String email, String inviteeName, int tickets, String status,
                             OffsetDateTime expiresAt, OffsetDateTime sentAt, OffsetDateTime firstOpenedAt, OffsetDateTime acceptedAt,
                             OffsetDateTime declinedAt, OffsetDateTime revokedAt, String revokeReason, String mailStatus, Integer mailAttempts,
                             String createdBy, OffsetDateTime createdAt, Long customerId, Long existingCustomerId, int ticketsEnrolled) {
    }

    public record Skipped(String email, String code, String message) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BatchResult(String groupCode, List<InviteView> created, List<Skipped> skipped, Integer seatsLeft, int pendingInvites, boolean replayed) {
        public BatchResult asReplay() {
            return new BatchResult(groupCode, created, skipped, seatsLeft, pendingInvites, true);
        }
    }

    public record RevokeRequest(String reason) {
    }
}
