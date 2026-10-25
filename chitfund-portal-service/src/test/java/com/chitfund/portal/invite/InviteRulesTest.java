package com.chitfund.portal.invite;

import com.chitfund.portal.common.PortalException;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;

import static com.chitfund.portal.invite.InviteStatus.*;
import static org.junit.jupiter.api.Assertions.*;

class InviteRulesTest {

    static final OffsetDateTime NOW = OffsetDateTime.of(2026, 10, 26, 10, 0, 0, 0, ZoneOffset.UTC);

    // ---- lifecycle ----

    @Test
    void aPendingInviteCanBeAcceptedDeclinedExpiredOrRevoked() {
        for (InviteStatus to : new InviteStatus[]{ACCEPTED_AWAITING_PAYMENT, ACCEPTED, DECLINED, EXPIRED, REVOKED}) {
            assertTrue(InviteLifecycle.allowed(PENDING, to), "PENDING to " + to);
        }
        assertTrue(InviteLifecycle.allowed(PENDING, ACCEPTED), "nothing due: enrolled at acceptance");
    }

    @Test
    void finishedInvitesStayFinished() {
        for (InviteStatus from : new InviteStatus[]{ACCEPTED, DECLINED, REVOKED}) {
            for (InviteStatus to : InviteStatus.values()) {
                assertFalse(InviteLifecycle.allowed(from, to), from + " to " + to);
            }
        }
    }

    @Test
    void onlyAResendBringsAnExpiredInviteBack() {
        assertTrue(InviteLifecycle.allowed(EXPIRED, PENDING));
        assertFalse(InviteLifecycle.allowed(EXPIRED, ACCEPTED));
        assertFalse(InviteLifecycle.allowed(EXPIRED, DECLINED));
    }

    @Test
    void requireRefusesAnIllegalMove() {
        PortalException e = assertThrows(PortalException.class, () -> InviteLifecycle.require(DECLINED, PENDING));
        assertEquals("ILLEGAL_TRANSITION", e.code());
    }

    @Test
    void aPendingInvitePastItsExpiryCountsAsExpired() {
        assertEquals(EXPIRED, InviteLifecycle.effective(PENDING, NOW.minusSeconds(1), NOW));
        assertEquals(EXPIRED, InviteLifecycle.effective(PENDING, NOW, NOW), "expiry instant itself is already expired");
        assertEquals(PENDING, InviteLifecycle.effective(PENDING, NOW.plusSeconds(1), NOW));
        assertEquals(EXPIRED, InviteLifecycle.effective(ACCEPTED_AWAITING_PAYMENT, NOW.minusDays(1), NOW));
    }

    @Test
    void anAcceptedDeclinedOrRevokedInviteNeverTurnsExpired() {
        assertEquals(ACCEPTED, InviteLifecycle.effective(ACCEPTED, NOW.minusDays(30), NOW));
        assertEquals(DECLINED, InviteLifecycle.effective(DECLINED, NOW.minusDays(30), NOW));
        assertEquals(REVOKED, InviteLifecycle.effective(REVOKED, NOW.minusDays(30), NOW));
    }

    @Test
    void whatTheAdminAndTheMemberMayDo() {
        assertTrue(InviteLifecycle.canResend(PENDING));
        assertTrue(InviteLifecycle.canResend(EXPIRED));
        assertFalse(InviteLifecycle.canResend(ACCEPTED));
        assertFalse(InviteLifecycle.canResend(REVOKED));
        assertTrue(InviteLifecycle.canRevoke(PENDING));
        assertTrue(InviteLifecycle.canRevoke(ACCEPTED_AWAITING_PAYMENT));
        assertFalse(InviteLifecycle.canRevoke(ACCEPTED));
        assertFalse(InviteLifecycle.canRevoke(EXPIRED));
        assertTrue(InviteLifecycle.canDecline(PENDING));
        assertFalse(InviteLifecycle.canDecline(ACCEPTED_AWAITING_PAYMENT));
    }

    @Test
    void liveStatusesAreTheOnesThatBlockASecondInvite() {
        assertTrue(PENDING.live());
        assertTrue(ACCEPTED_AWAITING_PAYMENT.live());
        assertTrue(ACCEPTED.live());
        assertFalse(DECLINED.live());
        assertFalse(EXPIRED.live());
        assertFalse(REVOKED.live());
    }

    // ---- emails ----

    @Test
    void addressesAreTrimmedAndLowerCased() {
        assertEquals("kiran.k@example.com", Emails.normalise("  Kiran.K@Example.COM "));
    }

    @Test
    void gmailAddressesFollowGooglesRules() {
        assertEquals("ab@gmail.com", Emails.normalise("A.B+Tag@gmail.com"));
        assertEquals("ab@gmail.com", Emails.normalise("a.b@googlemail.com"));
        assertEquals("ab@gmail.com", Emails.normalise("ab@gmail.com"));
        assertEquals("a.b+tag@example.com", Emails.normalise("A.B+Tag@example.com"));
        assertEquals("a.b@corp-gmail.com", Emails.normalise("a.b@corp-gmail.com"));
        assertNull(Emails.normalise("+only@gmail.com"));
    }

    @Test
    void implausibleAddressesAreRefused() {
        for (String bad : new String[]{null, "", "   ", "no-at-sign", "a@b", "a@@b.com", "a b@c.com", "a@b..com", "@b.com", "a@.com",
                "a,b@c.com", "a@b.com;c@d.com", "<a@b.com>", "a@b.c", "x".repeat(65) + "@b.com", "a@" + "b".repeat(250) + ".com"}) {
            assertTrue(Emails.normalise(bad) == null, "should refuse: " + bad);
        }
    }

    // ---- tokens ----

    @Test
    void aTokenIs43UrlSafeCharactersAndIsPlausible() {
        String t = InviteTokens.generate(new SecureRandom());
        assertEquals(43, t.length());
        assertTrue(InviteTokens.plausible(t));
    }

    @Test
    void tokensDoNotRepeat() {
        SecureRandom r = new SecureRandom();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            assertTrue(seen.add(InviteTokens.generate(r)));
        }
    }

    @Test
    void theHashIsStableHexAndDiffersFromTheToken() {
        String t = InviteTokens.generate(new SecureRandom());
        assertEquals(InviteTokens.hash(t), InviteTokens.hash(t));
        assertEquals(64, InviteTokens.hash(t).length());
        assertNotEquals(t, InviteTokens.hash(t));
        assertNotEquals(InviteTokens.hash(t), InviteTokens.hash(t + "x"));
    }

    @Test
    void whatIsNotShapedLikeATokenIsRejectedWithoutALookup() {
        assertFalse(InviteTokens.plausible(null));
        assertFalse(InviteTokens.plausible(""));
        assertFalse(InviteTokens.plausible("short"));
        assertFalse(InviteTokens.plausible("a".repeat(42) + "!"));
        assertFalse(InviteTokens.plausible("a".repeat(44)));
        assertFalse(InviteTokens.plausible("../../etc/passwd" + "a".repeat(27)));
    }

    @Test
    void theLogReferenceNeverContainsTheToken() {
        String t = InviteTokens.generate(new SecureRandom());
        String ref = InviteTokens.logRef(t);
        assertEquals(8, ref.length());
        assertTrue(ref.length() < t.length(), "reference must be much shorter than the token");
    }
}
