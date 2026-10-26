package com.chitfund.portal.join;

import com.chitfund.portal.auth.ClientIp;
import com.chitfund.portal.auth.RateLimiter;
import com.chitfund.portal.join.PlanService.PlanView;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.List;

/** The plans a visitor sees before signing in. No identity involved; nothing here reveals who is in a group. */
@RestController
@RequestMapping("/api/v1/portal/plans")
public class PlansController {

    private final PlanService plans;
    private final RateLimiter limit;
    private final boolean trustProxy;

    public PlansController(PlanService plans, Clock clock,
                           @Value("${chit.portal.rate-limit.public-plans-per-minute:60}") int perMinute,
                           @Value("${chit.portal.trust-forwarded-for:false}") boolean trustProxy) {
        this.plans = plans;
        this.limit = new RateLimiter(perMinute, clock);
        this.trustProxy = trustProxy;
    }

    @GetMapping("/public")
    public List<PlanView> publicPlans(HttpServletRequest request, @RequestHeader(value = "X-Forwarded-For", required = false) String forwardedFor) {
        limit.hit(ClientIp.resolve(request.getRemoteAddr(), forwardedFor, trustProxy));
        return plans.shelf();
    }
}
