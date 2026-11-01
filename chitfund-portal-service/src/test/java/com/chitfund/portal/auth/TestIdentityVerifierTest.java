package com.chitfund.portal.auth;

import com.chitfund.portal.common.PortalException;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class TestIdentityVerifierTest {

    static final OffsetDateTime NOW = OffsetDateTime.of(2026, 10, 26, 10, 0, 0, 0, ZoneOffset.UTC);
    final TestIdentityVerifier v = new TestIdentityVerifier();

    @Test
    void theTestVerifierExistsOnlyUnderTheE2eProfileAndTheRealOneOnlyWithout() {
        assertEquals(Arrays.asList("e2e"), Arrays.asList(TestIdentityVerifier.class.getAnnotation(Profile.class).value()));
        assertEquals(Arrays.asList("!e2e"), Arrays.asList(NimbusGoogleIdentityVerifier.class.getAnnotation(Profile.class).value()));
    }

    @Test
    void aTestTokenGivesTheIdentityWithTheEmailNormalisedLikeGoogleDoes() {
        GoogleIdentity who = v.verify("e2e|sub-1|E2E.Saver01+promo@Gmail.com|Saver One", NOW);
        assertEquals("sub-1", who.sub());
        assertEquals("e2esaver01@gmail.com", who.email());
        assertEquals("Saver One", who.name());
        assertNull(v.verify("e2e|sub-2|a@x.com", NOW).name());
    }

    @Test
    void anythingElseIsRefused() {
        for (String bad : Arrays.asList(null, "", "eyJhbGciOi.real.google.token", "e2e|", "e2e||a@x.com|N", "e2e|s|not-an-email|N")) {
            assertEquals("INVALID_ID_TOKEN", assertThrows(PortalException.class, () -> v.verify(bad, NOW)).code(), String.valueOf(bad));
        }
    }
}
