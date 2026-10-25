package com.chitfund.portal.auth;

import com.chitfund.portal.auth.MemberRepository.MemberRow;
import com.chitfund.portal.common.Audit;
import com.chitfund.portal.common.Hashing;
import com.chitfund.portal.common.PortalException;
import com.chitfund.portal.invite.InviteTokens;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.OffsetDateTime;

/**
 * Google sign-in and member sessions. No password exists: a person proves who they are with a Google ID token, which is verified on the
 * server; the portal then issues its own random session value (only its hash is stored) that the browser keeps in an HttpOnly cookie.
 *
 * <p>Matching rules at sign-in: (1) the Google account id (sub) finds the member; (2) otherwise a member with the same email but no
 * account id yet is claimed by this account; (3) a member whose email is already tied to a DIFFERENT Google account is refused
 * (GOOGLE_ACCOUNT_MISMATCH) rather than handed over; (4) otherwise a new member is created. The email on file never changes at sign-in,
 * so an address change at Google does not silently move access; it is recorded in the audit trail.
 */
@Service
public class PortalAuthService {

    public record SignIn(String sessionToken, OffsetDateTime expiresAt, MemberView member) {
    }

    public record MemberView(long id, String email, String displayName) {
    }

    private final GoogleIdentityVerifier verifier;
    private final MemberRepository members;
    private final SessionRepository sessions;
    private final Audit audit;
    private final SecureRandom random = new SecureRandom();
    private final int sessionHours;

    public PortalAuthService(GoogleIdentityVerifier verifier, MemberRepository members, SessionRepository sessions, Audit audit,
                             @Value("${chit.portal.session.hours:8}") int sessionHours) {
        this.verifier = verifier;
        this.members = members;
        this.sessions = sessions;
        this.audit = audit;
        this.sessionHours = sessionHours;
    }

    /** A refusal is audited before it is thrown, so the audit row must survive the exception. Nothing else is written before a refusal. */
    @Transactional(noRollbackFor = PortalException.class)
    public SignIn signIn(String idToken, String userAgent, String ip, OffsetDateTime now) {
        GoogleIdentity who = verifier.verify(idToken, now);

        MemberRow m = members.bySub(who.sub()).orElse(null);
        if (m == null) {
            MemberRow byEmail = members.byEmail(who.email()).orElse(null);
            if (byEmail == null) {
                long id = members.insert(who.email(), who.name(), who.sub(), now);
                m = members.bySub(who.sub()).orElseThrow();
                audit.log("MEMBER", String.valueOf(id), "CREATED", who.email(), ip, null);
            } else if (byEmail.googleSub() == null) {
                members.linkSub(byEmail.id(), who.sub());
                m = members.bySub(who.sub()).orElseThrow();
                audit.log("MEMBER", String.valueOf(m.id()), "GOOGLE_LINKED", who.email(), ip, null);
            } else {
                audit.log("MEMBER", String.valueOf(byEmail.id()), "SIGN_IN_REFUSED", who.email(), ip, "email belongs to another Google account");
                throw PortalException.conflict("GOOGLE_ACCOUNT_MISMATCH", "This email address is already tied to a different Google account");
            }
        }
        if (!"ACTIVE".equals(m.status())) {
            audit.log("MEMBER", String.valueOf(m.id()), "SIGN_IN_REFUSED", who.email(), ip, "member blocked");
            throw PortalException.forbidden("MEMBER_BLOCKED", "This account cannot sign in");
        }
        if (!who.email().equals(m.email())) {
            // Google now reports another address for the same account. Access stays tied to the address on file: invitations match on it.
            audit.log("MEMBER", String.valueOf(m.id()), "EMAIL_CHANGED_AT_GOOGLE", m.email(), ip, "Google now reports " + who.email());
        }
        members.touchLogin(m.id(), who.name(), now);

        String token = InviteTokens.generate(random); // same 256-bit URL-safe generator
        OffsetDateTime expires = now.plusHours(sessionHours);
        sessions.insert(m.id(), Hashing.sha256(token), expires, userAgent, ip);
        audit.log("MEMBER", String.valueOf(m.id()), "SIGNED_IN", who.email(), ip, null);
        return new SignIn(token, expires, new MemberView(m.id(), m.email(), who.name() != null ? who.name() : m.displayName()));
    }

    /** The member behind a session cookie value, or NOT_SIGNED_IN. Same answer for a missing, unknown, expired or revoked session. */
    public SessionMember resolve(String sessionToken, OffsetDateTime now) {
        if (sessionToken == null || sessionToken.isBlank() || sessionToken.length() > 128) {
            throw new PortalException("NOT_SIGNED_IN", "Sign in to continue");
        }
        return sessions.active(Hashing.sha256(sessionToken), now).orElseThrow(() -> new PortalException("NOT_SIGNED_IN", "Sign in to continue"));
    }

    @Transactional
    public void signOut(String sessionToken, OffsetDateTime now) {
        if (sessionToken != null && !sessionToken.isBlank() && sessionToken.length() <= 128) {
            sessions.revoke(Hashing.sha256(sessionToken), now);
        }
    }
}
