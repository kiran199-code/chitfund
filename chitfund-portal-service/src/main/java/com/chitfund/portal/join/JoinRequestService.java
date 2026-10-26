package com.chitfund.portal.join;

import com.chitfund.portal.auth.SessionMember;
import com.chitfund.portal.chit.ChitPlanGateway.OpenPlan;
import com.chitfund.portal.common.Audit;
import com.chitfund.portal.common.PortalException;
import com.chitfund.portal.join.JoinRequestRepository.Row;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

/**
 * A member asks to join an open plan; staff approve or decline. A request takes no seat and charges nothing: approval only records the decision so
 * staff can enrol the customer through the normal enrolment steps in chitfund-service.
 */
@Service
public class JoinRequestService {

    public static final int MAX_TICKETS = 3;
    private static final Set<String> STATUSES = Set.of("PENDING", "APPROVED", "DECLINED", "WITHDRAWN");

    /** Shown to the member. */
    public record MemberView(long id, String groupCode, String groupName, int tickets, String status, String requestedAt, String note) {
    }

    /** Shown to staff. */
    public record AdminView(long id, String email, String memberName, String groupCode, String groupName, int tickets, String status, String requestedAt,
                            String note, String decidedBy, String decidedAt) {
    }

    private final JoinRequestRepository repo;
    private final PlanService plans;
    private final Audit audit;

    public JoinRequestService(JoinRequestRepository repo, PlanService plans, Audit audit) {
        this.repo = repo;
        this.plans = plans;
        this.audit = audit;
    }

    /** Idempotent: asking again for a group where a request is already live returns that request. */
    @Transactional
    public MemberView request(SessionMember me, String groupCode, Integer tickets, String ip) {
        if (groupCode == null || groupCode.isBlank() || groupCode.length() > 30) {
            throw PortalException.badRequest("VALIDATION_FAILED", "groupCode is required");
        }
        if (tickets == null || tickets < 1 || tickets > MAX_TICKETS) {
            throw PortalException.badRequest("VALIDATION_FAILED", "tickets must be between 1 and " + MAX_TICKETS);
        }
        String code = groupCode.trim();
        var existing = repo.live(me.memberId(), code);
        if (existing.isPresent()) {
            return memberView(existing.get());
        }
        OpenPlan plan = plans.openNow(code).orElseThrow(() -> new PortalException("PLAN_NOT_OPEN", "This plan is not open for new members right now"));
        if (plan.seatsLeft() < tickets) {
            throw new PortalException("GROUP_FULL", plan.seatsLeft() <= 0 ? "This plan is full" : "Only " + plan.seatsLeft() + " seat(s) are left in this plan");
        }
        var id = repo.insertIfNoLive(me.memberId(), me.email(), me.displayName(), plan.groupCode(), plan.name(), tickets);
        Row row = id.flatMap(i -> repo.byId(i)).or(() -> repo.live(me.memberId(), code))
                .orElseThrow(() -> new PortalException("DATA_CONFLICT", "Your request could not be saved; please try again"));
        if (id.isPresent()) {
            audit.log("JOIN_REQUEST", String.valueOf(row.id()), "REQUESTED", me.email(), ip, plan.groupCode() + " x" + tickets);
        }
        return memberView(row);
    }

    @Transactional(readOnly = true)
    public List<MemberView> mine(SessionMember me) {
        return repo.forMember(me.memberId()).stream().map(JoinRequestService::memberView).toList();
    }

    /** Only the owner, only while pending. */
    @Transactional
    public MemberView withdraw(SessionMember me, long id, String ip) {
        Row r = repo.lockById(id).filter(x -> x.memberId() == me.memberId()).orElseThrow(() -> PortalException.notFound("Join request", String.valueOf(id)));
        if (!"PENDING".equals(r.status())) {
            throw new PortalException("JOIN_NOT_PENDING", "Only a request that is waiting for approval can be withdrawn");
        }
        repo.withdraw(id);
        audit.log("JOIN_REQUEST", String.valueOf(id), "WITHDRAWN", me.email(), ip, null);
        return memberView(repo.byId(id).orElseThrow());
    }

    // ---- staff ------------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<AdminView> list(String status) {
        String s = status == null || status.isBlank() ? null : status.trim().toUpperCase();
        if (s != null && !STATUSES.contains(s)) {
            throw PortalException.badRequest("VALIDATION_FAILED", "status must be one of " + String.join(", ", STATUSES.stream().sorted().toList()));
        }
        return repo.list(s, 300).stream().map(JoinRequestService::adminView).toList();
    }

    @Transactional
    public AdminView decide(long id, boolean approve, String note, String actor, OffsetDateTime now) {
        if (actor == null || actor.isBlank()) {
            throw PortalException.badRequest("HEADER_REQUIRED", "X-Actor is required");
        }
        String n = note == null || note.isBlank() ? null : note.trim();
        if (n != null && n.length() > 300) {
            throw PortalException.badRequest("VALIDATION_FAILED", "note must be at most 300 characters");
        }
        Row r = repo.lockById(id).orElseThrow(() -> PortalException.notFound("Join request", String.valueOf(id)));
        if (!"PENDING".equals(r.status())) {
            throw new PortalException("JOIN_NOT_PENDING", "This request is already " + r.status().toLowerCase());
        }
        repo.decide(id, approve ? "APPROVED" : "DECLINED", n, actor.trim(), now);
        audit.log("JOIN_REQUEST", String.valueOf(id), approve ? "APPROVED" : "DECLINED", actor.trim(), null, n);
        return adminView(repo.byId(id).orElseThrow());
    }

    private static MemberView memberView(Row r) {
        return new MemberView(r.id(), r.groupCode(), r.groupName(), r.tickets(), r.status(), r.createdAt().toInstant().toString(), r.note());
    }

    private static AdminView adminView(Row r) {
        return new AdminView(r.id(), r.email(), r.memberName(), r.groupCode(), r.groupName(), r.tickets(), r.status(), r.createdAt().toInstant().toString(), r.note(),
                r.decidedBy(), r.decidedAt() == null ? null : r.decidedAt().toInstant().toString());
    }
}
