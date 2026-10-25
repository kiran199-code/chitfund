package com.chitfund.portal.invite;

import com.chitfund.portal.chit.ChitGroupGateway;
import com.chitfund.portal.chit.GroupSummary;
import com.chitfund.portal.common.Audit;
import com.chitfund.portal.common.Hashing;
import com.chitfund.portal.common.PortalException;
import com.chitfund.portal.idempotency.IdempotencyCheck.Replay;
import com.chitfund.portal.idempotency.IdempotencyStore;
import com.chitfund.portal.invite.InviteDtos.*;
import com.chitfund.portal.invite.InviteModels.InviteRow;
import com.chitfund.portal.mail.InviteEmail;
import com.chitfund.portal.mail.OutboxService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The admin's side of invitations: invite a batch of addresses to a group, list, resend, revoke. Each change and the mail it queues
 * are written in one transaction; the mail is sent afterwards by the outbox, so a mail failure never undoes an invitation.
 *
 * <p>Seats are not reserved by an invitation (decision D1): an invitation is only an offer, and the seat is taken when the member
 * accepts and enrols (a later step). The batch result says how many seats are left and how many invitations are pending so the admin
 * can see when more people are invited than there are seats.
 */
@Service
public class InvitationService {

    static final String OP_BATCH = "INVITE_BATCH";
    static final String OP_RESEND = "INVITE_RESEND";

    private final InvitationRepository repo;
    private final ChitGroupGateway groups;
    private final OutboxService outbox;
    private final IdempotencyStore idempotency;
    private final Audit audit;
    private final ObjectMapper mapper;
    private final SecureRandom random = new SecureRandom();
    private final String portalBaseUrl;
    private final int lifetimeDays;
    private final int maxBatch;
    private final int maxTickets;
    private final ZoneId zone;

    public InvitationService(InvitationRepository repo, ChitGroupGateway groups, OutboxService outbox, IdempotencyStore idempotency, Audit audit,
                             ObjectMapper mapper,
                             @Value("${chit.portal.base-url}") String portalBaseUrl,
                             @Value("${chit.portal.invite.lifetime-days:7}") int lifetimeDays,
                             @Value("${chit.portal.invite.max-batch:200}") int maxBatch,
                             @Value("${chit.portal.invite.max-tickets-per-invite:5}") int maxTickets,
                             @Value("${chit.portal.timezone:Asia/Kolkata}") String timezone) {
        this.repo = repo;
        this.groups = groups;
        this.outbox = outbox;
        this.idempotency = idempotency;
        this.audit = audit;
        this.mapper = mapper;
        String b = portalBaseUrl == null ? "" : portalBaseUrl.trim();
        this.portalBaseUrl = b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
        this.lifetimeDays = lifetimeDays;
        this.maxBatch = maxBatch;
        this.maxTickets = maxTickets;
        this.zone = ZoneId.of(timezone);
    }

    // ---- invite a batch -------------------------------------------------------------------------------------------

    @Transactional
    public BatchResult invite(String code, InviteRequest req, String idemKey, String actor, String sourceIp, OffsetDateTime now) {
        if (req == null || req.invitees() == null || req.invitees().isEmpty()) {
            throw PortalException.badRequest("VALIDATION_FAILED", "invitees must list at least one address");
        }
        if (req.invitees().size() > maxBatch) {
            throw PortalException.badRequest("VALIDATION_FAILED", "At most " + maxBatch + " invitations per request");
        }
        int days = req.expiryDays() == null ? lifetimeDays : req.expiryDays();
        if (days < 1 || days > 30) {
            throw PortalException.badRequest("VALIDATION_FAILED", "expiryDays must be between 1 and 30");
        }
        StringBuilder fp = new StringBuilder(code).append('|').append(days);
        for (Invitee i : req.invitees()) {
            fp.append('|').append(i == null ? "" : i.email() + "," + i.name() + "," + i.tickets());
        }
        Replay replay = idempotency.begin(idemKey, OP_BATCH, Hashing.sha256(fp.toString()));
        if (replay != null) {
            return readStored(replay.resultRef()).asReplay();
        }

        GroupSummary g = openGroup(code);
        OffsetDateTime expires = now.plusDays(days);
        List<InviteView> created = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (Invitee in : req.invitees()) {
            String raw = in == null ? null : in.email();
            String email = Emails.normalise(raw);
            if (email == null) {
                skipped.add(new Skipped(raw, "INVALID_EMAIL", "Not a valid email address"));
                continue;
            }
            if (!seen.add(email)) {
                skipped.add(new Skipped(email, "DUPLICATE_IN_BATCH", "Listed more than once in this request"));
                continue;
            }
            int tickets = in.tickets() == null ? 1 : in.tickets();
            if (tickets < 1 || tickets > maxTickets) {
                skipped.add(new Skipped(email, "INVALID_TICKET_COUNT", "Tickets per person must be between 1 and " + maxTickets));
                continue;
            }
            String name = in.name() == null || in.name().isBlank() ? null : in.name().trim();
            if (name != null && name.length() > 120) {
                skipped.add(new Skipped(email, "INVALID_NAME", "Name is longer than 120 characters"));
                continue;
            }
            InviteRow live = repo.liveForUpdate(code, email).orElse(null);
            if (live != null) {
                InviteStatus eff = InviteLifecycle.effective(live.status(), live.expiresAt(), now);
                if (eff == InviteStatus.EXPIRED) {
                    repo.markExpired(live.id()); // frees the address for a new invitation
                } else if (eff == InviteStatus.ACCEPTED) {
                    skipped.add(new Skipped(email, "ALREADY_MEMBER", "This address has already accepted an invitation to the group"));
                    continue;
                } else {
                    skipped.add(new Skipped(email, "ALREADY_INVITED", "This address already has a live invitation (" + eff + "); resend or revoke it instead"));
                    continue;
                }
            }
            String token = InviteTokens.generate(random);
            long id = repo.insert(g.groupCode(), g.name(), email, name, tickets, InviteTokens.hash(token), expires, actor);
            queueMail(id, 1, token, g, email, name, tickets, expires, now);
            audit.log("INVITATION", g.groupCode() + "#" + id, "INVITED", actor, sourceIp, email + ", tickets " + tickets);
            created.add(view(repo.byId(id).orElseThrow(), now));
        }
        BatchResult result = new BatchResult(g.groupCode(), created, skipped, g.seatsLeft(), repo.countPending(code, now), false);
        idempotency.complete(idemKey, store(result));
        return result;
    }

    // ---- list ------------------------------------------------------------------------------------------------------

    public List<InviteView> list(String code, String statusFilter, OffsetDateTime now) {
        InviteStatus wanted = null;
        if (statusFilter != null && !statusFilter.isBlank()) {
            try {
                wanted = InviteStatus.valueOf(statusFilter.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw PortalException.badRequest("VALIDATION_FAILED", "Unknown status: " + statusFilter);
            }
        }
        List<InviteView> out = new ArrayList<>();
        for (InviteRow r : repo.list(code)) {
            InviteView v = view(r, now);
            if (wanted == null || wanted.name().equals(v.status())) {
                out.add(v);
            }
        }
        return out;
    }

    // ---- resend ----------------------------------------------------------------------------------------------------

    @Transactional
    public InviteView resend(String code, long id, String idemKey, String actor, String sourceIp, OffsetDateTime now) {
        Replay replay = idempotency.begin(idemKey, OP_RESEND, Hashing.sha256(code + "|" + id));
        if (replay != null) {
            return view(repo.byId(code, id).orElseThrow(() -> PortalException.notFound("Invitation", String.valueOf(id))), now);
        }
        InviteRow row = repo.byIdForUpdate(code, id).orElseThrow(() -> PortalException.notFound("Invitation", String.valueOf(id)));
        InviteStatus eff = InviteLifecycle.effective(row.status(), row.expiresAt(), now);
        if (!InviteLifecycle.canResend(eff)) {
            throw PortalException.conflict("INVITE_NOT_RESENDABLE", "An invitation that is " + eff + " cannot be sent again");
        }
        GroupSummary g = openGroup(code);
        String token = InviteTokens.generate(random);
        int version = row.tokenVersion() + 1;
        OffsetDateTime expires = now.plusDays(lifetimeDays);
        repo.resend(id, InviteTokens.hash(token), version, expires);
        queueMail(id, version, token, g, row.email(), row.inviteeName(), row.ticketsOffered(), expires, now);
        audit.log("INVITATION", code + "#" + id, "RESENT", actor, sourceIp, "token version " + version);
        idempotency.complete(idemKey, String.valueOf(id));
        return view(repo.byId(id).orElseThrow(), now);
    }

    // ---- revoke ----------------------------------------------------------------------------------------------------

    @Transactional
    public InviteView revoke(String code, long id, RevokeRequest req, String actor, String sourceIp, OffsetDateTime now) {
        InviteRow row = repo.byIdForUpdate(code, id).orElseThrow(() -> PortalException.notFound("Invitation", String.valueOf(id)));
        if (row.status() == InviteStatus.REVOKED) {
            return view(row, now); // revoking twice changes nothing
        }
        InviteStatus eff = InviteLifecycle.effective(row.status(), row.expiresAt(), now);
        if (!InviteLifecycle.canRevoke(eff)) {
            throw PortalException.conflict("INVITE_NOT_REVOCABLE", "An invitation that is " + eff + " cannot be revoked");
        }
        String reason = req == null || req.reason() == null || req.reason().isBlank() ? null : req.reason().trim();
        if (reason != null && reason.length() > 200) {
            throw PortalException.badRequest("VALIDATION_FAILED", "reason is longer than 200 characters");
        }
        repo.revoke(id, actor, reason, now);
        audit.log("INVITATION", code + "#" + id, "REVOKED", actor, sourceIp, reason);
        return view(repo.byId(id).orElseThrow(), now);
    }

    // ---- expiry sweep ------------------------------------------------------------------------------------------------

    /** Writes EXPIRED on invitations past their expiry. Reads already treat them as expired; this keeps the stored status honest. */
    @Transactional
    public int expireDue(OffsetDateTime now) {
        int n = repo.expireDue(now);
        if (n > 0) {
            audit.log("INVITATION", "-", "EXPIRED", "portal-sweeper", null, n + " invitations");
        }
        return n;
    }

    // ---- helpers -----------------------------------------------------------------------------------------------------

    private GroupSummary openGroup(String code) {
        GroupSummary g = groups.group(code).orElseThrow(() -> new PortalException("GROUP_NOT_FOUND", "No chit group with code " + code));
        if (!g.openForEnrolment()) {
            throw PortalException.conflict("GROUP_NOT_OPEN", "Group " + code + " is " + g.status() + "; invitations can be sent only while it is OPEN_FOR_ENROLMENT");
        }
        if (g.seatsLeft() != null && g.seatsLeft() <= 0) {
            throw PortalException.conflict("GROUP_FULL", "Group " + code + " has no seats left");
        }
        return g;
    }

    private void queueMail(long id, int tokenVersion, String token, GroupSummary g, String email, String name, int tickets, OffsetDateTime expires, OffsetDateTime now) {
        InviteEmail.Rendered m = InviteEmail.render(new InviteEmail.Data(g.name(), g.chitValue(), g.subscription(), g.cycleCount(), g.startDate(),
                name, email, tickets, expires, portalBaseUrl + "/invite/" + token, zone));
        outbox.queue("INVITATION", email, m.subject(), m.text(), "INVITE:" + id + ":" + tokenVersion, id, now);
    }

    InviteView view(InviteRow r, OffsetDateTime now) {
        InviteStatus eff = InviteLifecycle.effective(r.status(), r.expiresAt(), now);
        // "SEND_FAILED" is what the admin should see when the mail gave up; a mail still waiting or retrying is PENDING
        String mail = r.mailStatus() == null ? null : ("FAILED".equals(r.mailStatus()) ? "SEND_FAILED" : r.mailStatus());
        return new InviteView(r.id(), r.groupCode(), r.groupName(), r.email(), r.inviteeName(), r.ticketsOffered(), eff.name(), r.expiresAt(), r.sentAt(),
                r.firstOpenedAt(), r.acceptedAt(), r.declinedAt(), r.revokedAt(), r.revokeReason(), mail, r.mailAttempts(), r.createdBy(), r.createdAt());
    }

    private String store(BatchResult r) {
        try {
            return mapper.writeValueAsString(r);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private BatchResult readStored(String json) {
        if (json == null) {
            throw PortalException.conflict("DATA_CONFLICT", "The first request with this idempotency key has not finished");
        }
        try {
            return mapper.readValue(json, BatchResult.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
