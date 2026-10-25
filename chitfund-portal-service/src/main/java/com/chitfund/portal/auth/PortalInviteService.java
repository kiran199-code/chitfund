package com.chitfund.portal.auth;

import com.chitfund.portal.auth.PortalInviteDtos.GroupInfo;
import com.chitfund.portal.auth.PortalInviteDtos.InviteView;
import com.chitfund.portal.chit.ChitGroupGateway;
import com.chitfund.portal.chit.GroupSummary;
import com.chitfund.portal.common.Audit;
import com.chitfund.portal.common.PortalException;
import com.chitfund.portal.invite.InvitationRepository;
import com.chitfund.portal.invite.InviteLifecycle;
import com.chitfund.portal.invite.InviteModels.InviteRow;
import com.chitfund.portal.invite.InviteStatus;
import com.chitfund.portal.invite.InviteTokens;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * What the invited member can do with an invitation link while signed in: look at it, and decline it. Possession of the link is not enough:
 * the signed-in member's verified email must equal the invited address. An unknown, malformed, expired or revoked link all give the same
 * answer (INVITE_NOT_AVAILABLE), so links cannot be probed.
 */
@Service
public class PortalInviteService {

    private final InvitationRepository invitations;
    private final ChitGroupGateway groups;
    private final Audit audit;

    public PortalInviteService(InvitationRepository invitations, ChitGroupGateway groups, Audit audit) {
        this.invitations = invitations;
        this.groups = groups;
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
        return new InviteView(row.groupCode(), row.groupName(), row.ticketsOffered(), eff.name(), row.expiresAt(), info, unavailable,
                InviteLifecycle.canDecline(eff));
    }

    private static PortalException notAvailable() {
        return new PortalException("INVITE_NOT_AVAILABLE", "This invitation link is not valid any more");
    }
}
