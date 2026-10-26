package com.chitfund.portal.join;

import com.chitfund.portal.auth.ClientIp;
import com.chitfund.portal.auth.OriginGuard;
import com.chitfund.portal.auth.PortalAuthService;
import com.chitfund.portal.auth.RateLimiter;
import com.chitfund.portal.auth.SessionCookies;
import com.chitfund.portal.auth.SessionMember;
import com.chitfund.portal.join.JoinRequestService.MemberView;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

/** The signed-in member's own join requests. The member comes from the session cookie only, never from the request. */
@RestController
@RequestMapping("/api/v1/portal/me/join-requests")
public class JoinRequestController {

    public record NewRequest(String groupCode, Integer tickets) {
    }

    private final PortalAuthService auth;
    private final JoinRequestService service;
    private final Clock clock;
    private final OriginGuard originGuard;
    private final RateLimiter readLimit;
    private final RateLimiter writeLimit;
    private final boolean trustProxy;

    public JoinRequestController(PortalAuthService auth, JoinRequestService service, Clock clock,
                                 @Value("${chit.portal.allowed-origins:}") String allowedOrigins,
                                 @Value("${chit.portal.rate-limit.member-read-per-minute:120}") int readPerMinute,
                                 @Value("${chit.portal.rate-limit.join-request-per-minute:10}") int writePerMinute,
                                 @Value("${chit.portal.trust-forwarded-for:false}") boolean trustProxy) {
        this.auth = auth;
        this.service = service;
        this.clock = clock;
        this.originGuard = new OriginGuard(allowedOrigins);
        this.readLimit = new RateLimiter(readPerMinute, clock);
        this.writeLimit = new RateLimiter(writePerMinute, clock);
        this.trustProxy = trustProxy;
    }

    private SessionMember member(String session) {
        SessionMember m = auth.resolve(session, OffsetDateTime.now(clock));
        readLimit.hit("jr" + m.memberId());
        return m;
    }

    @GetMapping
    public List<MemberView> mine(@CookieValue(name = SessionCookies.NAME, required = false) String session) {
        return service.mine(member(session));
    }

    @PostMapping
    public MemberView create(@RequestBody NewRequest body, HttpServletRequest request,
                             @CookieValue(name = SessionCookies.NAME, required = false) String session,
                             @RequestHeader(value = "Origin", required = false) String origin,
                             @RequestHeader(value = OriginGuard.HEADER, required = false) String client,
                             @RequestHeader(value = "X-Forwarded-For", required = false) String forwardedFor) {
        originGuard.check(origin, client);
        SessionMember me = member(session);
        writeLimit.hit("jw" + me.memberId());
        return service.request(me, body == null ? null : body.groupCode(), body == null ? null : body.tickets(),
                ClientIp.resolve(request.getRemoteAddr(), forwardedFor, trustProxy));
    }

    @PostMapping("/{id}/withdraw")
    public MemberView withdraw(@PathVariable long id, HttpServletRequest request,
                               @CookieValue(name = SessionCookies.NAME, required = false) String session,
                               @RequestHeader(value = "Origin", required = false) String origin,
                               @RequestHeader(value = OriginGuard.HEADER, required = false) String client,
                               @RequestHeader(value = "X-Forwarded-For", required = false) String forwardedFor) {
        originGuard.check(origin, client);
        SessionMember me = member(session);
        writeLimit.hit("jw" + me.memberId());
        return service.withdraw(me, id, ClientIp.resolve(request.getRemoteAddr(), forwardedFor, trustProxy));
    }
}
