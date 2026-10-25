package com.chitfund.portal.offer;

import com.chitfund.portal.common.PortalException;
import com.chitfund.portal.offer.OfferRepository.OfferRow;
import com.chitfund.portal.offer.OfferService.OfferInput;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class OfferRulesTest {

    static final LocalDate D = LocalDate.of(2026, 10, 25);

    static OfferInput in(String title, String link, String audience, LocalDate s, LocalDate e) {
        return new OfferInput(title, "body", link, null, audience, s, e, null);
    }

    static String refused(Runnable r) {
        try { r.run(); } catch (PortalException e) { return e.code(); }
        return "NONE";
    }

    @Test
    void linksMustBeWebAddresses() {
        assertEquals("https://x.com/a", OfferService.url(" https://x.com/a ", "linkUrl"));
        assertNull(OfferService.url("  ", "linkUrl"));
        assertNull(OfferService.url(null, "linkUrl"));
        for (String bad : new String[]{"javascript:alert(1)", "data:text/html,x", "ftp://x.com", "//x.com", "not a url", "https://", "http://" + "a".repeat(500)}) {
            assertEquals("VALIDATION_FAILED", refused(() -> OfferService.url(bad, "linkUrl")), bad);
        }
    }

    @Test
    void anOfferNeedsATitleAnAudienceAndSaneDates() {
        assertEquals("MEMBERS", OfferService.clean(in("Diwali", null, null, D, null)).audience(), "members-only by default");
        assertEquals("PUBLIC", OfferService.clean(in("Diwali", null, " public ", D, D)).audience());
        assertEquals("VALIDATION_FAILED", refused(() -> OfferService.clean(in(" ", null, null, D, null))));
        assertEquals("VALIDATION_FAILED", refused(() -> OfferService.clean(in("x".repeat(121), null, null, D, null))));
        assertEquals("VALIDATION_FAILED", refused(() -> OfferService.clean(in("A", null, "EVERYONE", D, null))));
        assertEquals("VALIDATION_FAILED", refused(() -> OfferService.clean(in("A", null, null, null, null))));
        assertEquals("VALIDATION_FAILED", refused(() -> OfferService.clean(in("A", null, null, D, D.minusDays(1)))));
        assertEquals("VALIDATION_FAILED", refused(() -> OfferService.clean(null)));
        assertEquals("VALIDATION_FAILED", refused(() -> OfferService.clean(new OfferInput("A", "x".repeat(601), null, null, null, D, null, null))));
    }

    @Test
    void statusFollowsDatesAndVisibility() {
        assertEquals("LIVE", OfferService.status(row(D.minusDays(1), null, true), D));
        assertEquals("LIVE", OfferService.status(row(D, D, true), D), "both ends inclusive");
        assertEquals("SCHEDULED", OfferService.status(row(D.plusDays(1), null, true), D));
        assertEquals("ENDED", OfferService.status(row(D.minusDays(9), D.minusDays(1), true), D));
        assertEquals("HIDDEN", OfferService.status(row(D.minusDays(1), null, false), D));
    }

    private static OfferRow row(LocalDate s, LocalDate e, boolean active) {
        return new OfferRow(1, "t", "b", null, null, "MEMBERS", s, e, active, "a");
    }
}
