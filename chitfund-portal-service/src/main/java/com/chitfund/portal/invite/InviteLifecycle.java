package com.chitfund.portal.invite;

import com.chitfund.portal.common.PortalException;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.chitfund.portal.invite.InviteStatus.*;

/**
 * Which status an invitation may move to. Pure, so the rules are tested without a database.
 * Expiry is evaluated lazily: a PENDING invite past its expiry time is treated as EXPIRED everywhere ({@link #effective}) even before
 * the sweeper writes that status.
 */
public final class InviteLifecycle {

    private static final Map<InviteStatus, Set<InviteStatus>> NEXT = Map.of(
            PENDING, EnumSet.of(ACCEPTED_AWAITING_PAYMENT, DECLINED, EXPIRED, REVOKED),
            ACCEPTED_AWAITING_PAYMENT, EnumSet.of(ACCEPTED, EXPIRED, REVOKED),
            // a resend gives an expired invitation a new token and a new expiry
            EXPIRED, EnumSet.of(PENDING),
            ACCEPTED, EnumSet.noneOf(InviteStatus.class),
            DECLINED, EnumSet.noneOf(InviteStatus.class),
            REVOKED, EnumSet.noneOf(InviteStatus.class));

    private InviteLifecycle() {
    }

    public static boolean allowed(InviteStatus from, InviteStatus to) {
        return NEXT.get(from).contains(to);
    }

    public static void require(InviteStatus from, InviteStatus to) {
        if (!allowed(from, to)) {
            throw PortalException.conflict("ILLEGAL_TRANSITION", "An invitation cannot go from " + from + " to " + to);
        }
    }

    /** The status that counts now: PENDING and AWAITING_PAYMENT past their expiry are EXPIRED. */
    public static InviteStatus effective(InviteStatus stored, OffsetDateTime expiresAt, OffsetDateTime now) {
        if ((stored == PENDING || stored == ACCEPTED_AWAITING_PAYMENT) && !expiresAt.isAfter(now)) {
            return EXPIRED;
        }
        return stored;
    }

    public static boolean canResend(InviteStatus effective) {
        return effective == PENDING || effective == EXPIRED;
    }

    public static boolean canRevoke(InviteStatus effective) {
        return effective == PENDING || effective == ACCEPTED_AWAITING_PAYMENT;
    }

    public static boolean canDecline(InviteStatus effective) {
        return effective == PENDING;
    }
}
