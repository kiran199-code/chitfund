package com.chitfund.portal.member;

import com.chitfund.portal.auth.ClientIp;
import com.chitfund.portal.auth.OriginGuard;
import com.chitfund.portal.auth.PortalAuthService;
import com.chitfund.portal.auth.RateLimiter;
import com.chitfund.portal.auth.SessionCookies;
import com.chitfund.portal.auth.SessionMember;
import com.chitfund.portal.chit.ChitPortfolioGateway.Auction;
import com.chitfund.portal.chit.ChitPortfolioGateway.Request;
import com.chitfund.portal.chit.ChitPortfolioGateway.Ticket;
import com.chitfund.portal.member.MemberPortfolioService.BidInput;
import com.chitfund.portal.member.MemberPortfolioService.BidResult;
import com.chitfund.portal.member.MemberPortfolioService.Overview;
import com.chitfund.portal.member.MemberPortfolioService.PaymentsPage;
import com.chitfund.portal.member.MemberPortfolioService.WithdrawInput;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * The signed-in member's own view: {@code /api/v1/portal/me/...}. The member comes from the session cookie only; no id, e-mail or customer
 * number is ever read from the request, so there is nothing to tamper with to see someone else's chits.
 */
@RestController
@RequestMapping("/api/v1/portal/me")
public class MemberController {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final PortalAuthService auth;
    private final MemberPortfolioService portfolio;
    private final Clock clock;
    private final OriginGuard originGuard;
    private final RateLimiter readLimit;
    private final RateLimiter bidLimit;
    private final boolean trustProxy;

    public MemberController(PortalAuthService auth, MemberPortfolioService portfolio, Clock clock,
                            @Value("${chit.portal.allowed-origins:}") String allowedOrigins,
                            @Value("${chit.portal.rate-limit.member-read-per-minute:120}") int readPerMinute,
                            @Value("${chit.portal.rate-limit.bid-per-minute:20}") int bidPerMinute,
                            @Value("${chit.portal.trust-forwarded-for:false}") boolean trustProxy) {
        this.auth = auth;
        this.portfolio = portfolio;
        this.clock = clock;
        this.originGuard = new OriginGuard(allowedOrigins);
        this.readLimit = new RateLimiter(readPerMinute, clock);
        this.bidLimit = new RateLimiter(bidPerMinute, clock);
        this.trustProxy = trustProxy;
    }

    private SessionMember member(String session) {
        SessionMember m = auth.resolve(session, OffsetDateTime.now(clock));
        readLimit.hit("m" + m.memberId());
        return m;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(IST));
    }

    @GetMapping("/dashboard")
    public Overview dashboard(@CookieValue(name = SessionCookies.NAME, required = false) String session) {
        return portfolio.overview(member(session), today());
    }

    @GetMapping("/tickets")
    public List<Ticket> tickets(@CookieValue(name = SessionCookies.NAME, required = false) String session) {
        return portfolio.tickets(member(session));
    }

    @GetMapping("/payments")
    public PaymentsPage payments(@CookieValue(name = SessionCookies.NAME, required = false) String session) {
        return portfolio.payments(member(session));
    }

    @GetMapping("/auctions")
    public List<Auction> auctions(@CookieValue(name = SessionCookies.NAME, required = false) String session) {
        return portfolio.auctions(member(session));
    }

    @GetMapping("/requests")
    public List<Request> requests(@CookieValue(name = SessionCookies.NAME, required = false) String session) {
        return portfolio.requests(member(session));
    }

    @PostMapping("/bids")
    public BidResult bid(@RequestBody BidInput body, HttpServletRequest request,
                         @CookieValue(name = SessionCookies.NAME, required = false) String session,
                         @RequestHeader(value = "Origin", required = false) String origin,
                         @RequestHeader(value = OriginGuard.HEADER, required = false) String client,
                         @RequestHeader(value = "Idempotency-Key", required = false) String key,
                         @RequestHeader(value = "X-Forwarded-For", required = false) String forwardedFor) {
        originGuard.check(origin, client);
        SessionMember me = member(session);
        bidLimit.hit("b" + me.memberId());
        return portfolio.placeBid(me, body, key, ClientIp.resolve(request.getRemoteAddr(), forwardedFor, trustProxy));
    }

    @PostMapping("/bids/withdraw")
    public BidResult withdraw(@RequestBody WithdrawInput body, HttpServletRequest request,
                              @CookieValue(name = SessionCookies.NAME, required = false) String session,
                              @RequestHeader(value = "Origin", required = false) String origin,
                              @RequestHeader(value = OriginGuard.HEADER, required = false) String client,
                              @RequestHeader(value = "Idempotency-Key", required = false) String key,
                              @RequestHeader(value = "X-Forwarded-For", required = false) String forwardedFor) {
        originGuard.check(origin, client);
        SessionMember me = member(session);
        bidLimit.hit("b" + me.memberId());
        return portfolio.withdrawBid(me, body, key, ClientIp.resolve(request.getRemoteAddr(), forwardedFor, trustProxy));
    }
}
