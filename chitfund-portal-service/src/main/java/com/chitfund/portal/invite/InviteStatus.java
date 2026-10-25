package com.chitfund.portal.invite;

public enum InviteStatus {
    PENDING,
    ACCEPTED_AWAITING_PAYMENT,
    ACCEPTED,
    DECLINED,
    EXPIRED,
    REVOKED;

    /** A live invite blocks a second invite to the same address in the same group. */
    public boolean live() {
        return this == PENDING || this == ACCEPTED_AWAITING_PAYMENT || this == ACCEPTED;
    }
}
