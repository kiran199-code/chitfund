package com.chitfund.portal.offer;

import com.chitfund.portal.member.OfferSource.OfferView;
import com.chitfund.portal.auth.ClientIp;
import com.chitfund.portal.auth.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/** The offers a visitor sees before signing in: only those staff marked PUBLIC. No identity involved. */
@RestController
@RequestMapping("/api/v1/portal/offers")
public class OfferController {

    private final OfferService offers;
    private final Clock clock;
    private final RateLimiter limit;
    private final boolean trustProxy;

    public OfferController(OfferService offers, Clock clock,
                           @Value("${chit.portal.rate-limit.public-offers-per-minute:60}") int perMinute,
                           @Value("${chit.portal.trust-forwarded-for:false}") boolean trustProxy) {
        this.offers = offers;
        this.clock = clock;
        this.limit = new RateLimiter(perMinute, clock);
        this.trustProxy = trustProxy;
    }

    @GetMapping("/public")
    public List<OfferView> publicOffers(HttpServletRequest request, @RequestHeader(value = "X-Forwarded-For", required = false) String forwardedFor) {
        limit.hit(ClientIp.resolve(request.getRemoteAddr(), forwardedFor, trustProxy));
        return offers.forVisitors(LocalDate.now(clock.withZone(ZoneId.of("Asia/Kolkata"))));
    }
}
