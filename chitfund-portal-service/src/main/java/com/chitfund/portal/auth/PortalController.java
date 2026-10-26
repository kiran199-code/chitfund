package com.chitfund.portal.auth;

import com.chitfund.portal.auth.PortalAuthService.MemberView;
import com.chitfund.portal.auth.PortalAuthService.SignIn;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * The member-facing API. Authentication is the session cookie, checked here for every call; the admin credential is never accepted on these
 * paths and a member session is never accepted on the admin paths (they are separate security rules, see SecurityConfig).
 * Every POST goes through {@link OriginGuard} (CSRF) first.
 */
@RestController
@RequestMapping("/api/v1/portal")
public class PortalController {

    public record GoogleSignInRequest(String idToken) {
    }

    private final PortalAuthService auth;
    private final Clock clock;
    private final OriginGuard originGuard;
    private final RateLimiter signInLimit;
    private final boolean secure;
    private final String sameSite;
    private final boolean trustProxy;

    public PortalController(PortalAuthService auth, Clock clock,
                            @Value("${chit.portal.allowed-origins:}") String allowedOrigins,
                            @Value("${chit.portal.rate-limit.sign-in-per-minute:20}") int signInPerMinute,
                            @Value("${chit.portal.session.cookie-secure:true}") boolean secure,
                            @Value("${chit.portal.session.cookie-same-site:Lax}") String sameSite,
                            @Value("${chit.portal.trust-forwarded-for:false}") boolean trustProxy) {
        this.auth = auth;
        this.clock = clock;
        this.originGuard = new OriginGuard(allowedOrigins);
        this.signInLimit = new RateLimiter(signInPerMinute, clock);
        this.secure = secure;
        this.sameSite = sameSite;
        this.trustProxy = trustProxy;
    }

    @PostMapping("/auth/google")
    public ResponseEntity<MemberView> signIn(@RequestBody GoogleSignInRequest body, HttpServletRequest request,
                                             @RequestHeader(value = "Origin", required = false) String origin,
                                             @RequestHeader(value = OriginGuard.HEADER, required = false) String client,
                                             @RequestHeader(value = "X-Forwarded-For", required = false) String forwardedFor,
                                             @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        originGuard.check(origin, client);
        String ip = ClientIp.resolve(request.getRemoteAddr(), forwardedFor, trustProxy);
        signInLimit.hit(ip);
        SignIn s = auth.signIn(body == null ? null : body.idToken(), userAgent, ip, OffsetDateTime.now(clock));
        long maxAge = Duration.between(OffsetDateTime.now(clock), s.expiresAt()).toSeconds();
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, SessionCookies.issue(s.sessionToken(), maxAge, secure, sameSite)).body(s.member());
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<Void> signOut(@CookieValue(name = SessionCookies.NAME, required = false) String session,
                                        @RequestHeader(value = "Origin", required = false) String origin,
                                        @RequestHeader(value = OriginGuard.HEADER, required = false) String client) {
        originGuard.check(origin, client);
        auth.signOut(session, OffsetDateTime.now(clock));
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, SessionCookies.clear(secure, sameSite)).build();
    }

    @GetMapping("/me")
    public MemberView me(@CookieValue(name = SessionCookies.NAME, required = false) String session) {
        SessionMember m = auth.resolve(session, OffsetDateTime.now(clock));
        return new MemberView(m.memberId(), m.email(), m.displayName());
    }
}
