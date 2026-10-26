package com.chitfund.portal.join;

import com.chitfund.portal.chit.ChitPlanGateway;
import com.chitfund.portal.chit.ChitPlanGateway.OpenPlan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** The plan shelf: open groups from chitfund-service, kept for a short while so a busy landing page does not hit it on every view. */
@Service
public class PlanService {

    /** What the browser gets. Names match the web app's Plan type. A group nobody has labelled has an empty category, branch and state. */
    public record PlanView(String groupCode, String name, String category, String branch, String state, BigDecimal chitValue, BigDecimal subscription,
                           int cycles, String frequency, int tickets, int seatsLeft, LocalDate startDate) {
    }

    private final ChitPlanGateway gateway;
    private final Clock clock;
    private final Duration ttl;
    private volatile List<OpenPlan> cached = List.of();
    private volatile Instant loadedAt = Instant.EPOCH;

    public PlanService(ChitPlanGateway gateway, Clock clock, @Value("${chit.portal.plans-cache-seconds:30}") long ttlSeconds) {
        this.gateway = gateway;
        this.clock = clock;
        this.ttl = Duration.ofSeconds(Math.max(0, ttlSeconds));
    }

    private List<OpenPlan> plans(boolean fresh) {
        Instant now = clock.instant();
        if (fresh || Duration.between(loadedAt, now).compareTo(ttl) >= 0) {
            cached = gateway.openPlans();
            loadedAt = now;
        }
        return cached;
    }

    public List<PlanView> shelf() {
        return plans(false).stream().map(PlanService::view).toList();
    }

    /** The plan with this code as chitfund-service reports it right now (not from the cache), or empty if it is not open for enrolment. */
    public Optional<OpenPlan> openNow(String groupCode) {
        return plans(true).stream().filter(p -> p.groupCode().equals(groupCode)).findFirst();
    }

    static PlanView view(OpenPlan p) {
        return new PlanView(p.groupCode(), p.name(), nz(p.planCategory()), nz(p.branch()), nz(p.state()), p.chitValue(), p.subscription(), p.cycleCount(),
                p.frequency(), p.ticketCount(), p.seatsLeft(), p.startDate());
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
