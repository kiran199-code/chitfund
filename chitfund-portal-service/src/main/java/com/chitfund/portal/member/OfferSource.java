package com.chitfund.portal.member;

import java.util.List;

/** Offers and marketing material a signed-in member sees. Implemented by the offers module; an interface so the portfolio code is tested without it. */
public interface OfferSource {

    record OfferView(long id, String title, String body, String linkUrl, String imageUrl, String audience, java.time.LocalDate startsOn, java.time.LocalDate endsOn) {
    }

    /** Offers live on {@code today} for signed-in members (public and members-only). */
    List<OfferView> forMembers(java.time.LocalDate today);
}
