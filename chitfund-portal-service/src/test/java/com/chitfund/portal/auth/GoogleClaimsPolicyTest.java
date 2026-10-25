package com.chitfund.portal.auth;

import com.chitfund.portal.common.PortalException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GoogleClaimsPolicyTest {

    static final OffsetDateTime NOW = OffsetDateTime.of(2026, 10, 26, 10, 0, 0, 0, ZoneOffset.UTC);
    static final String CLIENT = "client-123.apps.example";

    static Map<String, Object> good() {
        Map<String, Object> c = new HashMap<>();
        c.put("iss", "https://accounts.google.com");
        c.put("aud", CLIENT);
        c.put("exp", NOW.toInstant().plusSeconds(600));
        c.put("sub", "1234567890");
        c.put("email", "Kiran@Example.com");
        c.put("email_verified", true);
        c.put("name", "Kiran K");
        return c;
    }

    static String refusal(Map<String, Object> claims) {
        return assertThrows(PortalException.class, () -> GoogleClaimsPolicy.check(claims, CLIENT, NOW)).code();
    }

    @Test
    void goodClaimsGiveTheIdentityWithANormalisedEmail() {
        GoogleIdentity who = GoogleClaimsPolicy.check(good(), CLIENT, NOW);
        assertEquals("1234567890", who.sub());
        assertEquals("kiran@example.com", who.email());
        assertEquals("Kiran K", who.name());
    }

    @Test
    void bothIssuerSpellingsAreAccepted() {
        Map<String, Object> c = good();
        c.put("iss", "accounts.google.com");
        assertEquals("1234567890", GoogleClaimsPolicy.check(c, CLIENT, NOW).sub());
    }

    @Test
    void anotherIssuerIsRefused() {
        Map<String, Object> c = good();
        c.put("iss", "https://evil.example");
        assertEquals("INVALID_ID_TOKEN", refusal(c));
        c.remove("iss");
        assertEquals("INVALID_ID_TOKEN", refusal(c));
    }

    @Test
    void aTokenForAnotherAppIsRefused() {
        Map<String, Object> c = good();
        c.put("aud", "someone-elses-client");
        assertEquals("INVALID_ID_TOKEN", refusal(c));
    }

    @Test
    void anAudienceListThatIncludesOurClientIsAccepted() {
        Map<String, Object> c = good();
        c.put("aud", List.of("other", CLIENT));
        assertNotNull(GoogleClaimsPolicy.check(c, CLIENT, NOW));
        c.put("aud", List.of("other"));
        assertEquals("INVALID_ID_TOKEN", refusal(c));
    }

    @Test
    void anExpiredTokenIsRefused() {
        Map<String, Object> c = good();
        c.put("exp", NOW.toInstant().minusSeconds(1));
        assertEquals("INVALID_ID_TOKEN", refusal(c));
        c.put("exp", NOW.toInstant());
        assertEquals("INVALID_ID_TOKEN", refusal(c));
        c.remove("exp");
        assertEquals("INVALID_ID_TOKEN", refusal(c));
    }

    @Test
    void expiryGivenAsEpochSecondsWorksToo() {
        Map<String, Object> c = good();
        c.put("exp", NOW.toEpochSecond() + 300);
        assertNotNull(GoogleClaimsPolicy.check(c, CLIENT, NOW));
        c.put("exp", NOW.toEpochSecond() - 300);
        assertEquals("INVALID_ID_TOKEN", refusal(c));
    }

    @Test
    void anUnverifiedEmailIsRefused() {
        Map<String, Object> c = good();
        c.put("email_verified", false);
        assertEquals("INVALID_ID_TOKEN", refusal(c));
        c.remove("email_verified");
        assertEquals("INVALID_ID_TOKEN", refusal(c));
    }

    @Test
    void emailVerifiedAsTheTextTrueIsAccepted() {
        Map<String, Object> c = good();
        c.put("email_verified", "true");
        assertNotNull(GoogleClaimsPolicy.check(c, CLIENT, NOW));
        c.put("email_verified", "false");
        assertEquals("INVALID_ID_TOKEN", refusal(c));
    }

    @Test
    void aMissingSubjectOrEmailIsRefused() {
        Map<String, Object> c = good();
        c.remove("sub");
        assertEquals("INVALID_ID_TOKEN", refusal(c));
        c = good();
        c.put("sub", "  ");
        assertEquals("INVALID_ID_TOKEN", refusal(c));
        c = good();
        c.remove("email");
        assertEquals("INVALID_ID_TOKEN", refusal(c));
        c = good();
        c.put("email", "not-an-address");
        assertEquals("INVALID_ID_TOKEN", refusal(c));
    }

    @Test
    void aMissingNameIsFine() {
        Map<String, Object> c = good();
        c.remove("name");
        assertNull(GoogleClaimsPolicy.check(c, CLIENT, NOW).name());
    }

    @Test
    void noConfiguredClientIdMeansNothingSignsIn() {
        PortalException e = assertThrows(PortalException.class, () -> GoogleClaimsPolicy.check(good(), "", NOW));
        assertEquals("SIGN_IN_NOT_CONFIGURED", e.code());
        assertEquals("SIGN_IN_NOT_CONFIGURED", assertThrows(PortalException.class, () -> GoogleClaimsPolicy.check(good(), null, NOW)).code());
    }

    @Test
    void anInstantExpiryFromTheJwtLibraryIsUnderstood() {
        Map<String, Object> c = good();
        c.put("exp", Instant.parse("2026-10-26T10:05:00Z"));
        assertNotNull(GoogleClaimsPolicy.check(c, CLIENT, NOW));
    }
}
