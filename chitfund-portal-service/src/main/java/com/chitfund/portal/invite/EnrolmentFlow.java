package com.chitfund.portal.invite;

import com.chitfund.portal.chit.ChitEnrolmentGateway;
import com.chitfund.portal.chit.ChitEnrolmentGateway.CustomerRef;
import com.chitfund.portal.chit.ChitEnrolmentGateway.Quote;
import com.chitfund.portal.chit.ChitEnrolmentGateway.Received;
import com.chitfund.portal.chit.ChitGroupGateway;
import com.chitfund.portal.chit.GroupSummary;
import com.chitfund.portal.common.Audit;
import com.chitfund.portal.common.PortalException;
import com.chitfund.portal.invite.InviteDtos.EnrolRequest;
import com.chitfund.portal.invite.InviteModels.InviteRow;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Turns an accepted invitation into enrolled tickets in chitfund-service. Two entry points, both called with the invitation row locked and
 * inside the caller's transaction (which must not roll back on a PortalException, so the work done before a refusal stays):
 * <ul>
 *   <li>{@link #accept}: the member said yes. The e-mail becomes a customer (existing or new). If nothing is due for a ticket the tickets
 *       are enrolled straight away; otherwise the invitation waits (ACCEPTED_AWAITING_PAYMENT) for the admin to record the receipt.</li>
 *   <li>{@link #adminEnrol}: the admin confirms the fees are in; the tickets are enrolled with the amounts the quote asks for.</li>
 * </ul>
 * Every ticket has its own idempotency key ({@code PORTAL-ENROL:<invitation>:<seq>}), so a retry after a lost response or a crash
 * returns the ticket already made instead of making another. The first ticket decides success: if it fails nothing is marked. Later
 * tickets of a multi-ticket invitation are best effort; the invitation stays ACCEPTED with fewer tickets than offered and the admin
 * can top it up.
 */
@Component
public class EnrolmentFlow {

    private final InvitationRepository invitations;
    private final ChitGroupGateway groups;
    private final ChitEnrolmentGateway chit;
    private final Audit audit;

    public EnrolmentFlow(InvitationRepository invitations, ChitGroupGateway groups, ChitEnrolmentGateway chit, Audit audit) {
        this.invitations = invitations;
        this.groups = groups;
        this.chit = chit;
        this.audit = audit;
    }

    static String ticketKey(long invitationId, int seq) {
        return "PORTAL-ENROL:" + invitationId + ":" + seq;
    }

    /** The member accepted. {@code row} is PENDING (checked by the caller). Returns nothing; read the row again. */
    public void accept(InviteRow row, long memberId, String memberName, String actor, String ip, OffsetDateTime now) {
        GroupSummary g = openGroupWithSeat(row.groupCode());
        Quote quote = chit.quote(row.groupCode());
        CustomerRef customer = chit.resolveCustomer(row.email(), displayName(row, memberName), row.existingCustomerId());
        audit.log("INVITATION", row.groupCode() + "#" + row.id(), customer.created() ? "CUSTOMER_CREATED" : customer.linkedExisting() ? "CUSTOMER_LINKED" : "CUSTOMER_FOUND",
                actor, ip, "customer " + customer.customerId());
        if (!quote.selfService()) {
            InviteLifecycle.require(row.status(), InviteStatus.ACCEPTED_AWAITING_PAYMENT);
            invitations.markAwaitingPayment(row.id(), memberId, customer.customerId(), now);
            audit.log("INVITATION", row.groupCode() + "#" + row.id(), "ACCEPTED_AWAITING_PAYMENT", actor, ip, "cash due per ticket " + quote.cashDue());
            return;
        }
        enrolTickets(row, g, memberId, customer.customerId(), Received.nothing(), actor, ip, now);
    }

    /** The admin recorded that what is due for each ticket has been received. */
    public void adminEnrol(InviteRow row, EnrolRequest req, String actor, String ip, OffsetDateTime now) {
        boolean awaiting = row.status() == InviteStatus.ACCEPTED_AWAITING_PAYMENT;
        boolean topUp = row.status() == InviteStatus.ACCEPTED && row.ticketsEnrolled() < row.ticketsOffered();
        if (!awaiting && !topUp) {
            throw PortalException.conflict("INVITE_NOT_AWAITING_PAYMENT", "Only an accepted invitation that is waiting for payment, or accepted with tickets still to enrol, can be enrolled");
        }
        if (req == null || !Boolean.TRUE.equals(req.feesReceived())) {
            throw PortalException.badRequest("VALIDATION_FAILED", "feesReceived must be true: confirm the fees and papers for each ticket are in");
        }
        if (row.customerId() == null) {
            throw PortalException.conflict("DATA_CONFLICT", "The invitation has no customer yet");
        }
        GroupSummary g = openGroupWithSeat(row.groupCode());
        Quote q = chit.quote(row.groupCode());
        if (q.guarantorRequired() && req.guarantorCustomerId() == null) {
            throw PortalException.badRequest("VALIDATION_FAILED", "This group needs a guarantor: give guarantorCustomerId");
        }
        if (q.lienAmount().signum() > 0 && (req.lienReference() == null || req.lienReference().isBlank())) {
            throw PortalException.badRequest("VALIDATION_FAILED", "This group needs a lien: give lienReference");
        }
        Received received = new Received(q.enrolmentFee(), q.registrationFee(), q.securityDeposit(),
                q.guarantorRequired() ? req.guarantorCustomerId() : null,
                q.lienAmount().signum() > 0 ? req.lienReference().trim() : null,
                blankToNull(req.payoutAccountRef()) != null ? req.payoutAccountRef().trim() : row.payoutAccountRef());
        long memberId = row.memberId() == null ? 0 : row.memberId();
        enrolTickets(row, g, memberId, row.customerId(), received, actor, ip, now);
    }

    private void enrolTickets(InviteRow row, GroupSummary g, long memberId, long customerId, Received received, String actor, String ip, OffsetDateTime now) {
        Map<Integer, Integer> done = invitations.ticketsOf(row.id());
        boolean firstDone = !done.isEmpty();
        for (int seq = 1; seq <= row.ticketsOffered(); seq++) {
            if (done.containsKey(seq)) {
                continue;
            }
            int ticketNo;
            try {
                ticketNo = chit.enrol(row.groupCode(), customerId, received, ticketKey(row.id(), seq));
            } catch (PortalException e) {
                if (!firstDone) {
                    throw e; // nothing enrolled yet: refuse as a whole
                }
                audit.log("INVITATION", row.groupCode() + "#" + row.id(), "TICKET_NOT_ENROLLED", actor, ip, "seq " + seq + ": " + e.code());
                break;
            }
            invitations.recordTicket(row.id(), seq, ticketNo, now);
            audit.log("INVITATION", row.groupCode() + "#" + row.id(), "TICKET_ENROLLED", actor, ip, "seq " + seq + " ticket " + ticketNo + " customer " + customerId);
            if (!firstDone) {
                firstDone = true;
                InviteLifecycle.require(row.status(), InviteStatus.ACCEPTED);
                invitations.markAccepted(row.id(), memberId, customerId, now);
            } else {
                invitations.syncEnrolled(row.id());
            }
        }
        if (firstDone && row.status() != InviteStatus.ACCEPTED && !marked(row.id())) {
            // tickets recorded by an earlier attempt that crashed before marking
            InviteLifecycle.require(row.status(), InviteStatus.ACCEPTED);
            invitations.markAccepted(row.id(), memberId, customerId, now);
        }
    }

    private boolean marked(long id) {
        return invitations.byId(id).map(r -> r.status() == InviteStatus.ACCEPTED).orElse(false);
    }

    private GroupSummary openGroupWithSeat(String code) {
        GroupSummary g = groups.group(code).orElseThrow(() -> new PortalException("GROUP_NOT_FOUND", "No chit group with code " + code));
        if (!g.openForEnrolment()) {
            throw PortalException.conflict("ENROLMENT_NOT_OPEN", "Group " + code + " is " + g.status() + "; tickets can be taken only while it is OPEN_FOR_ENROLMENT");
        }
        if (g.seatsLeft() != null && g.seatsLeft() <= 0) {
            throw PortalException.conflict("GROUP_FULL", "Group " + code + " has no seats left");
        }
        return g;
    }

    private static String displayName(InviteRow row, String memberName) {
        String n = blankToNull(row.inviteeName());
        return n != null ? n : blankToNull(memberName);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
