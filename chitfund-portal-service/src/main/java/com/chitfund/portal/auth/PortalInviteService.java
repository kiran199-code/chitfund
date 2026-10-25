package com.chitfund.portal.auth;

import com.chitfund.portal.auth.PortalInviteDtos.GroupInfo;
import com.chitfund.portal.auth.PortalInviteDtos.InviteView;
import com.chitfund.portal.chit.ChitEnrolmentGateway;
import com.chitfund.portal.chit.ChitGroupGateway;
import com.chitfund.portal.chit.GroupSummary;
import com.chitfund.portal.common.Audit;
import com.chitfund.portal.common.PortalException;
import com.chitfund.portal.invite.EnrolmentFlow;
import com.chitfund.portal.invite.InvitationRepository;
import com.chitfund.portal.invite.InviteLifecycle;
import com.chitfund.portal.invite.InviteModels.InviteRow;
import com.chitfund.portal.invite.InviteStatus;
import com.chitfund.portal.invite.InviteTokens;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;

/**
 * What the invited member can do with an invitation link while signed in: look at it, and decline it. Possession of the link is not enough:
 * the signed-in member's verified email must equal the invited address. An unknown, malformed, expired or revoked link all give the same
 * answer (INVITE_NOT_AVAILABLE), so links cannot be probed.
 */
@Service
public class PortalInviteService {

    private final InvitationRepository invitations;
    private final ChitGroupGateway groups;
    private final ChitEnrolmentGateway enrolmentGateway;
    private final EnrolmentFlow enrolment;
    private final Audit audit;

    public PortalInviteService(InvitationRepository invitations, ChitGroupGateway groups, ChitEnrolmentGateway enrolmentGateway, EnrolmentFlow enrolment, Audit audit) {
        this.invitations = invitations;
        this.groups = groups;
        this.enrolmentGateway = enrolmentGateway;
        this.enrolment = enrolment;
        this.audit = audit;
    }

    /** The mismatch audit row must survive the refusal; no other write happens before a refusal. */
    @Transactional(noRollbackFor = PortalException.class)
    public InviteView view(String token, SessionMember me, String ip, OffsetDateTime now) {
        InviteRow row = locate(token, me, ip, now);
        if (row.status() == InviteStatus.PENDING && row.firstOpenedAt() == null) {
            invitations.touchOpened(row.id(), now);
            audit.log("INVITATION", row.groupCode() + "#" + row.id(), "OPENED", me.email(), ip, null);
        }
        return toView(row, now);
    }

    @Transactional(noRollbackFor = PortalException.class)
    public InviteView decline(String token, SessionMember me, String ip, OffsetDateTime now) {
        InviteRow row = locate(token, me, ip, now);
        if (row.status() == InviteStatus.DECLINED) {
            return toView(row, now); // declining twice changes nothing
        }
        InviteStatus eff = InviteLifecycle.effective(row.status(), row.expiresAt(), now);
        if (!InviteLifecycle.canDecline(eff)) {
            throw PortalException.conflict("INVITE_NOT_DECLINABLE", "An invitation that is " + eff + " cannot be declined");
        }
        invitations.decline(row.id(), me.memberId(), now);
        audit.log("INVITATION", row.groupCode() + "#" + row.id(), "DECLINED", me.email(), ip, null);
        return toView(invitations.byId(row.id()).orElseThrow(), now);
    }

    /**
     * Yes. Makes the e-mail a customer and, when nothing is due, enrols the tickets at once; otherwise the invitation waits for the admin to
     * record the receipt. Accepting twice, or after it went through, changes nothing. A refusal keeps what was already done (noRollbackFor).
     */
    @Transactional(noRollbackFor = PortalException.class)
    public InviteView accept(String token, SessionMember me, String ip, OffsetDateTime now) {
        InviteRow row = locate(token, me, ip, now);
        InviteStatus eff = InviteLifecycle.effective(row.status(), row.expiresAt(), now);
        if (eff == InviteStatus.ACCEPTED || eff == InviteStatus.ACCEPTED_AWAITING_PAYMENT) {
            return toView(row, now);
        }
        if (eff != InviteStatus.PENDING) {
            throw PortalException.conflict("INVITE_NOT_ACCEPTABLE", "An invitation that is " + eff + " cannot be accepted");
        }
        enrolment.accept(row, me.memberId(), me.displayName(), me.email(), ip, now);
        return toView(invitations.byId(row.id()).orElseThrow(), now);
    }

    /** The invitation behind the link for this member, locked; or the neutral not-available / mismatch refusal. */
    private InviteRow locate(String token, SessionMember me, String ip, OffsetDateTime now) {
        if (!InviteTokens.plausible(token)) {
            throw notAvailable();
        }
        InviteRow row = invitations.byTokenHashForUpdate(InviteTokens.hash(token)).orElseThrow(PortalInviteService::notAvailable);
        InviteStatus eff = InviteLifecycle.effective(row.status(), row.expiresAt(), now);
        if (eff == InviteStatus.EXPIRED || eff == InviteStatus.REVOKED) {
            throw notAvailable();
        }
        if (!row.email().equals(me.email())) {
            audit.log("INVITATION", row.groupCode() + "#" + row.id(), "EMAIL_MISMATCH", me.email(), ip, null);
            throw PortalException.forbidden("INVITE_EMAIL_MISMATCH", "This invitation was sent to a different email address; sign in with the Google account it was sent to");
        }
        return row;
    }

    private InviteView toView(InviteRow row, OffsetDateTime now) {
        InviteStatus eff = InviteLifecycle.effective(row.status(), row.expiresAt(), now);
        GroupInfo info = null;
        boolean unavailable = false;
        try {
            GroupSummary g = groups.group(row.groupCode()).orElse(null);
            if (g != null) {
                info = new GroupInfo(g.status(), g.chitValue(), g.subscription(), g.cycleCount(), g.ticketCount(), g.startDate(), g.seatsLeft());
            } else {
                unavailable = true;
            }
        } catch (PortalException e) {
            unavailable = true;
        }
        BigDecimal due = null;
        if (eff == InviteStatus.PENDING || eff == InviteStatus.ACCEPTED_AWAITING_PAYMENT) {
            try {
                due = enrolmentGateway.quote(row.groupCode()).cashDue();
            } catch (PortalException e) {
                due = null; // the screen then shows no amount; accepting still works
            }
        }
        return new InviteView(row.groupCode(), row.groupName(), row.ticketsOffered(), eff.name(), row.expiresAt(), info, unavailable,
                InviteLifecycle.canDecline(eff), eff == InviteStatus.PENDING, row.ticketsEnrolled(),
                new ArrayList<>(invitations.ticketsOf(row.id()).values()), row.customerId(), due);
    }

    private static PortalException notAvailable() {
        return new PortalException("INVITE_NOT_AVAILABLE", "This invitation link is not valid any more");
    }
}
