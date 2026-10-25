package com.chitfund.portal.auth;

import com.chitfund.portal.common.PortalException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class WebGuardsTest {

    // ---- cookies ----

    @Test
    void theSessionCookieIsHttpOnlyAndScoped() {
        String c = SessionCookies.issue("abc", 28800, true, "Lax");
        assertTrue(c.startsWith("portal_session=abc;"));
        assertTrue(c.contains("HttpOnly"));
        assertTrue(c.contains("Secure"));
        assertTrue(c.contains("SameSite=Lax"));
        assertTrue(c.contains("Max-Age=28800"));
        assertTrue(c.contains("Path=/"));
    }

    @Test
    void secureCanBeSwitchedOffOnlyForLocalHttp() {
        assertFalse(SessionCookies.issue("abc", 10, false, "Lax").contains("Secure"));
        assertTrue(SessionCookies.issue("abc", 10, false, "Lax").contains("HttpOnly"));
    }

    @Test
    void clearingTheCookieEmptiesItAndExpiresItNow() {
        String c = SessionCookies.clear(true, "Lax");
        assertTrue(c.startsWith("portal_session=;"));
        assertTrue(c.contains("Max-Age=0"));
        assertTrue(c.contains("HttpOnly"));
    }

    // ---- CSRF guard ----

    @Test
    void aCallWithoutTheClientHeaderIsRefused() {
        OriginGuard g = new OriginGuard("https://portal.example");
        assertEquals("CLIENT_HEADER_REQUIRED", assertThrows(PortalException.class, () -> g.check("https://portal.example", null)).code());
        assertEquals("CLIENT_HEADER_REQUIRED", assertThrows(PortalException.class, () -> g.check(null, "mobile")).code());
    }

    @Test
    void anOriginThatIsNotListedIsRefused() {
        OriginGuard g = new OriginGuard("https://portal.example, http://localhost:5173");
        assertEquals("FORBIDDEN_ORIGIN", assertThrows(PortalException.class, () -> g.check("https://evil.example", "web")).code());
    }

    @Test
    void aListedOriginPassesWithOrWithoutATrailingSlash() {
        OriginGuard g = new OriginGuard("https://portal.example/, http://localhost:5173");
        g.check("https://portal.example", "web");
        g.check("https://portal.example/", "web");
        g.check("http://localhost:5173", "web");
    }

    @Test
    void noOriginHeaderPassesWhenTheClientHeaderIsThere() {
        new OriginGuard("https://portal.example").check(null, "web");
    }

    @Test
    void noAllowedOriginsMeansAnyBrowserOriginIsRefused() {
        OriginGuard g = new OriginGuard("");
        assertEquals("FORBIDDEN_ORIGIN", assertThrows(PortalException.class, () -> g.check("https://portal.example", "web")).code());
    }

    // ---- rate limit ----

    static final class MutableClock extends Clock {
        long millis = 1_000_000;
        public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId z) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(millis); }
        public long millis() { return millis; }
    }

    @Test
    void theLimitAllowsTheConfiguredCallsPerMinuteThenRefuses() {
        MutableClock clock = new MutableClock();
        RateLimiter r = new RateLimiter(3, clock);
        r.hit("1.1.1.1");
        r.hit("1.1.1.1");
        r.hit("1.1.1.1");
        assertEquals("TOO_MANY_REQUESTS", assertThrows(PortalException.class, () -> r.hit("1.1.1.1")).code());
    }

    @Test
    void theLimitIsPerKey() {
        RateLimiter r = new RateLimiter(1, new MutableClock());
        r.hit("a");
        r.hit("b");
        assertThrows(PortalException.class, () -> r.hit("a"));
    }

    @Test
    void theLimitResetsAfterAMinute() {
        MutableClock clock = new MutableClock();
        RateLimiter r = new RateLimiter(1, clock);
        r.hit("a");
        assertThrows(PortalException.class, () -> r.hit("a"));
        clock.millis += 60_000;
        r.hit("a");
    }

    // ---- client address ----

    @Test
    void theSocketAddressIsUsedUnlessAProxyIsTrusted() {
        assertEquals("10.0.0.5", ClientIp.resolve("10.0.0.5", "6.6.6.6", false));
        assertEquals("10.0.0.5", ClientIp.resolve("10.0.0.5", null, true));
    }

    @Test
    void behindATrustedProxyTheLastForwardedEntryIsUsedNotTheFirst() {
        assertEquals("203.0.113.9", ClientIp.resolve("10.0.0.5", "6.6.6.6, 203.0.113.9", true));
        assertEquals("203.0.113.9", ClientIp.resolve("10.0.0.5", "203.0.113.9", true));
    }

    @Test
    void anUnusableForwardedHeaderFallsBackToTheSocket() {
        assertEquals("10.0.0.5", ClientIp.resolve("10.0.0.5", " , ", true));
        assertEquals("unknown", ClientIp.resolve(null, null, false));
    }
}
