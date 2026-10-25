package com.chitfund.portal.auth;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

public final class PortalInviteDtos {
    private PortalInviteDtos() {
    }

    /**
     * The invitation as the invited member sees it. {@code group} is read live from chitfund-service and is null if that service could
     * not be reached ({@code groupUnavailable}); the invitation itself is still shown. {@code canAccept} is only a hint for the screen
     * until accepting exists (a later step).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InviteView(String groupCode, String groupName, int tickets, String status, OffsetDateTime expiresAt,
                             GroupInfo group, boolean groupUnavailable, boolean canDecline) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GroupInfo(String status, BigDecimal chitValue, BigDecimal subscription, Integer cycles, Integer tickets, LocalDate startDate, Integer seatsLeft) {
    }

    public record DeclineRequest(String reason) {
    }
}
