package harness;

import com.chitfund.portal.auth.*;
import com.chitfund.portal.auth.PortalAuthService.SignIn;
import com.chitfund.portal.chit.ChitGroupGateway;
import com.chitfund.portal.chit.GroupSummary;
import com.chitfund.portal.common.Audit;
import com.chitfund.portal.common.PortalException;
import com.chitfund.portal.idempotency.IdempotencyStore;
import com.chitfund.portal.invite.*;
import com.chitfund.portal.invite.InviteDtos.*;
import com.chitfund.portal.mail.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs the portal's real services (invites, outbox, sign-in, invitee view) against a real PostgreSQL with the real V1 migration.
 * Not Spring: no controllers, no proxies, no security filter. Google and chitfund-service are replaced by fakes; the mail gateway captures.
 */
public class PortalHarness {

    static PgJdbcTemplate jdbc;
    static InvitationService invites;
    static OutboxService outbox;
    static PortalAuthService auth;
    static PortalInviteService invitee;
    static MemberRepository members;

    static final Map<String, GroupSummary> GROUPS = new HashMap<>();
    static final List<OutgoingMail> SENT = new ArrayList<>();
    static boolean mailDown = false;
    static final Map<String, GoogleIdentity> TOKENS = new HashMap<>();

    static final OffsetDateTime T0 = OffsetDateTime.of(2026, 11, 1, 10, 0, 0, 0, ZoneOffset.UTC);
    static final Pattern TOKEN = Pattern.compile("/invite/([A-Za-z0-9_-]{43})");
    static int passed, failed;
    static long keySeq = 1;

    // ---- checks ----

    static void check(String what, boolean ok) {
        if (ok) { passed++; System.out.println("  ok   " + what); }
        else { failed++; System.out.println("  FAIL " + what); }
    }

    static void eq(String what, Object expected, Object actual) {
        boolean ok = expected instanceof BigDecimal e && actual instanceof BigDecimal a ? e.compareTo(a) == 0 : Objects.equals(expected, actual);
        if (ok) { passed++; System.out.println("  ok   " + what + " = " + actual); }
        else { failed++; System.out.println("  FAIL " + what + ": expected " + expected + " but was " + actual); }
    }

    static <T> T tx(Supplier<T> w) { return jdbc.inTx(w); }

    /** Models @Transactional(noRollbackFor = PortalException.class): commit even when a PortalException is thrown. */
    static <T> T txKeep(Supplier<T> w) {
        try {
            T r = w.get();
            jdbc.commit();
            return r;
        } catch (PortalException e) {
            jdbc.commit();
            throw e;
        } catch (RuntimeException e) {
            try { jdbc.rollback(); } catch (RuntimeException ignored) { }
            throw e;
        }
    }

    static boolean keepsAuditOnRefusal(Class<?> c, String method) {
        for (java.lang.reflect.Method m : c.getMethods()) {
            if (m.getName().equals(method)) {
                var t = m.getAnnotation(org.springframework.transaction.annotation.Transactional.class);
                if (t == null) return false;
                for (Class<?> x : t.noRollbackFor()) if (x == PortalException.class) return true;
            }
        }
        return false;
    }

    static void refused(String what, String code, Supplier<?> w) {
        refused(what, code, w, false);
    }

    static void refusedKeep(String what, String code, Supplier<?> w) {
        refused(what, code, w, true);
    }

    static void refused(String what, String code, Supplier<?> w, boolean keep) {
        try {
            if (keep) txKeep(w); else tx(w);
            failed++; System.out.println("  FAIL " + what + ": expected " + code + " but it succeeded");
        } catch (PortalException e) {
            if (code.equals(e.code())) { passed++; System.out.println("  ok   " + what + " refused " + code); }
            else { failed++; System.out.println("  FAIL " + what + ": expected " + code + " but got " + e.code() + " (" + e.getMessage() + ")"); }
        } catch (RuntimeException e) {
            failed++; System.out.println("  FAIL " + what + ": expected " + code + " but got " + e);
        }
    }

    static String key() { return "k-" + (keySeq++); }

    static long count(String sql, Object... a) { return jdbc.queryForObject(sql, Long.class, a); }

    // ---- wiring ----

    static void wire() throws Exception {
        jdbc = new PgJdbcTemplate(System.getProperty("db", "portal1"), "/tmp/pg", 5544);
        Audit audit = new Audit(jdbc);
        IdempotencyStore idem = new IdempotencyStore(jdbc);
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        MailGateway gw = mail -> {
            if (mailDown) throw new IllegalStateException("smtp down");
            SENT.add(mail);
            return "msg-" + SENT.size();
        };
        outbox = new OutboxService(new OutboxRepository(jdbc), gw, 3);
        ChitGroupGateway groups = code -> {
            if ("DOWN".equals(code)) throw new PortalException("CHIT_SERVICE_UNAVAILABLE", "chitfund-service unreachable");
            return Optional.ofNullable(GROUPS.get(code));
        };
        InvitationRepository invRepo = new InvitationRepository(jdbc);
        invites = new InvitationService(invRepo, groups, outbox, idem, audit, mapper, "https://portal.example", 7, 200, 5, "Asia/Kolkata");
        members = new MemberRepository(jdbc);
        GoogleIdentityVerifier verifier = (idToken, now) -> {
            GoogleIdentity g = TOKENS.get(idToken);
            if (g == null) throw new PortalException("INVALID_ID_TOKEN", "bad token");
            return g;
        };
        auth = new PortalAuthService(verifier, members, new SessionRepository(jdbc), audit, 8);
        invitee = new PortalInviteService(invRepo, groups, audit);
        group("G1", "OPEN_FOR_ENROLMENT", 5);
        group("G-FULL", "OPEN_FOR_ENROLMENT", 0);
        group("G-ACTIVE", "ACTIVE", 3);
    }

    static void group(String code, String status, int seatsLeft) {
        GROUPS.put(code, new GroupSummary(code, "Group " + code, status, new BigDecimal("100000.00"), 20, 20, new BigDecimal("5000.00"),
                LocalDate.of(2026, 12, 1), seatsLeft));
    }

    static String tokenFrom(OutgoingMail m) {
        Matcher x = TOKEN.matcher(m.text());
        if (!x.find()) throw new IllegalStateException("no link in mail to " + m.to());
        return x.group(1);
    }

    static OutgoingMail lastMailTo(String email) {
        for (int i = SENT.size() - 1; i >= 0; i--) if (SENT.get(i).to().equals(email)) return SENT.get(i);
        throw new IllegalStateException("no mail sent to " + email);
    }

    static String idToken(String sub, String email, String name) {
        String t = "idt-" + sub + "-" + email;
        TOKENS.put(t, new GoogleIdentity(sub, email, name));
        return t;
    }

    static SignIn signIn(String sub, String email, OffsetDateTime now) {
        return tx(() -> auth.signIn(idToken(sub, email, "Name " + sub), "UA", "10.0.0.1", now));
    }

    static SessionMember me(String sub, String email, OffsetDateTime now) {
        return auth.resolve(signIn(sub, email, now).sessionToken(), now);
    }

    static InviteView find(String code, String email, OffsetDateTime now) {
        return invites.list(code, null, now).stream().filter(v -> v.email().equals(email)).findFirst().orElseThrow();
    }

    // ---- entry ----

    public static void main(String[] args) throws Exception {
        wire();
        String only = args.length > 0 ? args[0] : "";
        if (only.isEmpty() || only.equals("invite")) inviteBatch();
        if (only.isEmpty() || only.equals("lifecycle")) lifecycle();
        if (only.isEmpty() || only.equals("mail")) mail();
        if (only.isEmpty() || only.equals("auth")) authScenarios();
        if (only.isEmpty() || only.equals("invitee")) inviteeScenarios();
        System.out.println("\n" + passed + " passed, " + failed + " failed (" + jdbc.statements + " statements)");
        jdbc.close();
        System.exit(failed == 0 ? 0 : 1);
    }

    // ---- 1. a batch of invitations, replay, group rules ----

    static void inviteBatch() {
        System.out.println("== invite: batch, skipped reasons, replay, group rules");
        InviteRequest req = new InviteRequest(List.of(
                new Invitee("a@x.com", "Asha", 2),
                new Invitee("  B@X.com ", null, null),
                new Invitee("not-an-email", null, null),
                new Invitee("a@x.com", null, null),
                new Invitee("c@x.com", null, 9),
                new Invitee("d@x.com", "D", 1)), null);
        String k = key();
        BatchResult r = tx(() -> invites.invite("G1", req, k, "admin1", "1.2.3.4", T0));
        eq("created count", 3, r.created().size());
        eq("skipped count", 3, r.skipped().size());
        Set<String> codes = new TreeSet<>();
        r.skipped().forEach(s -> codes.add(s.code()));
        eq("skipped reasons", new TreeSet<>(List.of("INVALID_EMAIL", "DUPLICATE_IN_BATCH", "INVALID_TICKET_COUNT")), codes);
        check("emails are normalised", r.created().stream().anyMatch(v -> v.email().equals("b@x.com")));
        eq("not a replay", false, r.replayed());
        eq("pending invites", 3, r.pendingInvites());
        eq("mails queued, none sent yet", 3L, count("SELECT count(*) FROM portal_outbox WHERE status='PENDING'"));
        eq("only hashes stored, no raw token column", 0L, count("SELECT count(*) FROM portal_invitation WHERE length(token_hash) <> 64"));

        BatchResult again = tx(() -> invites.invite("G1", req, k, "admin1", "1.2.3.4", T0.plusMinutes(1)));
        eq("same key and body replays", true, again.replayed());
        eq("replay creates nothing", 3L, count("SELECT count(*) FROM portal_invitation"));
        eq("replay queues nothing", 3L, count("SELECT count(*) FROM portal_outbox"));
        refused("same key, different body", "IDEMPOTENCY_KEY_REUSED",
                () -> invites.invite("G1", new InviteRequest(List.of(new Invitee("z@x.com", null, null)), null), k, "admin1", "1.2.3.4", T0));
        refused("no key", "IDEMPOTENCY_KEY_REQUIRED", () -> invites.invite("G1", req, null, "admin1", "1.2.3.4", T0));

        BatchResult dup = tx(() -> invites.invite("G1", new InviteRequest(List.of(new Invitee("A@x.com", null, null)), null), key(), "admin1", "ip", T0));
        eq("already invited is skipped", "ALREADY_INVITED", dup.skipped().get(0).code());

        refused("unknown group", "GROUP_NOT_FOUND", () -> invites.invite("NOPE", req, key(), "admin1", "ip", T0));
        refused("group not open", "GROUP_NOT_OPEN", () -> invites.invite("G-ACTIVE", req, key(), "admin1", "ip", T0));
        refused("group full", "GROUP_FULL", () -> invites.invite("G-FULL", req, key(), "admin1", "ip", T0));
        refused("chitfund-service down", "CHIT_SERVICE_UNAVAILABLE", () -> invites.invite("DOWN", req, key(), "admin1", "ip", T0));
        refused("empty list", "VALIDATION_FAILED", () -> invites.invite("G1", new InviteRequest(List.of(), null), key(), "admin1", "ip", T0));
        refused("expiryDays 0", "VALIDATION_FAILED",
                () -> invites.invite("G1", new InviteRequest(List.of(new Invitee("q@x.com", null, null)), 0), key(), "admin1", "ip", T0));
        check("the failed calls left no half-written invitations", count("SELECT count(*) FROM portal_invitation") == 3);
        check("invitation audit rows written", count("SELECT count(*) FROM portal_audit WHERE action='INVITED'") == 3);
    }

    // ---- 2. resend, revoke, expiry ----

    static void lifecycle() {
        System.out.println("== lifecycle: dispatch, resend, revoke, expiry");
        int sent = tx(() -> outbox.dispatchDue(T0.plusMinutes(2), 50));
        eq("three invitation mails sent", 3, sent);
        OutgoingMail toA = lastMailTo("a@x.com");
        check("mail carries a portal link", tokenFrom(toA).length() == 43);
        eq("bodies redacted after sending", 3L, count("SELECT count(*) FROM portal_outbox WHERE body_redacted AND status='SENT'"));
        eq("no token left in the outbox", 0L, count("SELECT count(*) FROM portal_outbox WHERE body_text LIKE '%/invite/%'"));
        check("sent_at is stamped on the invitation", find("G1", "a@x.com", T0.plusMinutes(3)).sentAt() != null);
        eq("nothing more is due", 0, tx(() -> outbox.dispatchDue(T0.plusMinutes(3), 50)));

        InviteView a = find("G1", "a@x.com", T0.plusMinutes(3));
        String oldToken = tokenFrom(toA);
        SessionMember asha = me("sub-a", "a@x.com", T0.plusMinutes(4));
        PortalInviteDtos.InviteView seen = tx(() -> invitee.view(oldToken, asha, "ip", T0.plusMinutes(4)));
        eq("invitee sees PENDING", "PENDING", seen.status());

        InviteView re = tx(() -> invites.resend("G1", a.id(), key(), "admin1", "ip", T0.plusMinutes(5)));
        eq("resend keeps the invitation PENDING", "PENDING", re.status());
        check("resend queues a second mail (new dedupe key)", count("SELECT count(*) FROM portal_outbox WHERE dedupe_key = ?", "INVITE:" + a.id() + ":2") == 1);
        tx(() -> outbox.dispatchDue(T0.plusMinutes(6), 50));
        String newToken = tokenFrom(lastMailTo("a@x.com"));
        check("resend issues a different token", !newToken.equals(oldToken));
        refused("the old link no longer works", "INVITE_NOT_AVAILABLE", () -> invitee.view(oldToken, asha, "ip", T0.plusMinutes(7)));
        eq("the new link works", "PENDING", tx(() -> invitee.view(newToken, asha, "ip", T0.plusMinutes(7))).status());
        String rk = key();
        tx(() -> invites.resend("G1", a.id(), rk, "admin1", "ip", T0.plusMinutes(8)));
        long mailsAfter = count("SELECT count(*) FROM portal_outbox");
        tx(() -> invites.resend("G1", a.id(), rk, "admin1", "ip", T0.plusMinutes(9)));
        eq("resend replay queues nothing", mailsAfter, count("SELECT count(*) FROM portal_outbox"));
        refused("resend of an unknown id", "NOT_FOUND", () -> invites.resend("G1", 99999, key(), "admin1", "ip", T0));

        InviteView b = find("G1", "b@x.com", T0.plusMinutes(10));
        String bToken = tokenFrom(lastMailTo("b@x.com"));
        InviteView rv = tx(() -> invites.revoke("G1", b.id(), new RevokeRequest("wrong person"), "admin1", "ip", T0.plusMinutes(10)));
        eq("revoked", "REVOKED", rv.status());
        eq("revoke reason kept", "wrong person", rv.revokeReason());
        eq("revoke twice is harmless", "REVOKED", tx(() -> invites.revoke("G1", b.id(), null, "admin1", "ip", T0.plusMinutes(11))).status());
        SessionMember bob = me("sub-b", "b@x.com", T0.plusMinutes(12));
        refused("revoked link looks like any dead link", "INVITE_NOT_AVAILABLE", () -> invitee.view(bToken, bob, "ip", T0.plusMinutes(12)));
        refused("a revoked invite cannot be resent", "INVITE_NOT_RESENDABLE", () -> invites.resend("G1", b.id(), key(), "admin1", "ip", T0));
        BatchResult back = tx(() -> invites.invite("G1", new InviteRequest(List.of(new Invitee("b@x.com", null, null)), null), key(), "admin1", "ip", T0.plusMinutes(13)));
        eq("a revoked address can be invited again", 1, back.created().size());

        // expiry: invitations run for 7 days
        OffsetDateTime late = T0.plusDays(8);
        InviteView d = find("G1", "d@x.com", late);
        eq("past expiry reads as EXPIRED before the sweeper runs", "EXPIRED", d.status());
        eq("but is still stored PENDING", "PENDING", jdbc.queryForObject("SELECT status FROM portal_invitation WHERE id = ?", String.class, d.id()));
        eq("list filter uses the effective status", true, invites.list("G1", "EXPIRED", late).stream().anyMatch(v -> v.id() == d.id()));
        String dToken = null;
        for (OutgoingMail m : SENT) if (m.to().equals("d@x.com")) dToken = tokenFrom(m);
        SessionMember dee = me("sub-d", "d@x.com", late);
        String dt = dToken;
        refused("an expired link is a dead link", "INVITE_NOT_AVAILABLE", () -> invitee.view(dt, dee, "ip", late));
        int swept = tx(() -> invites.expireDue(late));
        check("sweeper wrote EXPIRED", swept >= 2);
        eq("stored status now EXPIRED", "EXPIRED", jdbc.queryForObject("SELECT status FROM portal_invitation WHERE id = ?", String.class, d.id()));
        eq("sweeping again does nothing", 0, tx(() -> invites.expireDue(late)));
        InviteView again = tx(() -> invites.resend("G1", d.id(), key(), "admin1", "ip", late));
        eq("an expired invitation can be resent", "PENDING", again.status());
        BatchResult fresh = tx(() -> invites.invite("G1", new InviteRequest(List.of(new Invitee("a@x.com", null, null)), null), key(), "admin1", "ip", late));
        eq("an expired address is freed for a new invitation", 1, fresh.created().size());
        eq("the old expired row is not the live one", "EXPIRED", jdbc.queryForObject("SELECT status FROM portal_invitation WHERE group_code='G1' AND email='a@x.com' ORDER BY id LIMIT 1", String.class));
    }

    // ---- 3. outbox retry ----

    static void mail() {
        System.out.println("== mail: retry, back-off, give-up");
        OffsetDateTime t = T0.plusDays(20);
        tx(() -> invites.invite("G1", new InviteRequest(List.of(new Invitee("m@x.com", null, null)), null), key(), "admin1", "ip", t));
        mailDown = true;
        eq("failed send counts as zero sent", 0, tx(() -> outbox.dispatchDue(t, 50)));
        eq("attempt recorded", 1, jdbc.queryForObject("SELECT attempts FROM portal_outbox WHERE recipient_email='m@x.com'", Integer.class));
        eq("still PENDING after one failure", "PENDING", jdbc.queryForObject("SELECT status FROM portal_outbox WHERE recipient_email='m@x.com'", String.class));
        check("error text kept", jdbc.queryForObject("SELECT last_error FROM portal_outbox WHERE recipient_email='m@x.com'", String.class).contains("smtp down"));
        eq("not retried straight away", 0, tx(() -> outbox.dispatchDue(t.plusSeconds(30), 50)));
        eq("attempts unchanged by the early poll", 1, jdbc.queryForObject("SELECT attempts FROM portal_outbox WHERE recipient_email='m@x.com'", Integer.class));
        eq("retried after the delay (still down)", 0, tx(() -> outbox.dispatchDue(t.plusMinutes(2), 50)));
        eq("attempt 2", 2, jdbc.queryForObject("SELECT attempts FROM portal_outbox WHERE recipient_email='m@x.com'", Integer.class));
        mailDown = false;
        check("sent once the gateway is back", tx(() -> outbox.dispatchDue(t.plusMinutes(10), 50)) >= 1);
        eq("status SENT", "SENT", jdbc.queryForObject("SELECT status FROM portal_outbox WHERE recipient_email='m@x.com'", String.class));
        check("invitation shows the mail was sent", find("G1", "m@x.com", t.plusMinutes(11)).sentAt() != null);

        tx(() -> invites.invite("G1", new InviteRequest(List.of(new Invitee("n@x.com", null, null)), null), key(), "admin1", "ip", t));
        mailDown = true;
        OffsetDateTime p = t.plusHours(1);
        for (int i = 0; i < 5; i++) { final int n = i; tx(() -> outbox.dispatchDue(p.plusDays(n), 50)); }
        eq("gives up after max-attempts (3 in this harness)", "FAILED", jdbc.queryForObject("SELECT status FROM portal_outbox WHERE recipient_email='n@x.com'", String.class));
        eq("attempts stop at the limit", 3, jdbc.queryForObject("SELECT attempts FROM portal_outbox WHERE recipient_email='n@x.com'", Integer.class));
        eq("admin sees SEND_FAILED", "SEND_FAILED", find("G1", "n@x.com", p.plusDays(6)).mailStatus());
        mailDown = false;
    }

    // ---- 4. sign-in ----

    static void authScenarios() {
        System.out.println("== auth: sign-in matching, sessions");
        OffsetDateTime t = T0.plusDays(30);
        SignIn s1 = signIn("sub-new", "new@x.com", t);
        eq("first sign-in creates a member", "new@x.com", s1.member().email());
        check("session token is 43 chars", s1.sessionToken().length() == 43);
        eq("only the hash of the session token is stored", 0L, count("SELECT count(*) FROM portal_session WHERE token_hash = ?", s1.sessionToken()));
        SignIn s2 = signIn("sub-new", "new@x.com", t.plusMinutes(1));
        eq("same Google account is the same member", s1.member().id(), s2.member().id());
        eq("two sessions now", 2L, count("SELECT count(*) FROM portal_session WHERE member_id = ?", s1.member().id()));

        long pre = members.insert("pre@x.com", "Pre", null, t);
        SignIn claim = signIn("sub-pre", "pre@x.com", t);
        eq("an address with no Google link is claimed by its first Google sign-in", pre, claim.member().id());
        refusedKeep("a different Google account with the same address is refused", "GOOGLE_ACCOUNT_MISMATCH", () -> auth.signIn(idToken("sub-other", "pre@x.com", "X"), "UA", "ip", t));

        SignIn moved = signIn("sub-pre", "renamed@x.com", t.plusMinutes(2));
        eq("a changed address at Google does not change the stored one", "pre@x.com", moved.member().email());
        eq("and is audited", 1L, count("SELECT count(*) FROM portal_audit WHERE action='EMAIL_CHANGED_AT_GOOGLE'"));
        eq("no second member was created for it", 0L, count("SELECT count(*) FROM portal_member WHERE email='renamed@x.com'"));

        SessionMember who = auth.resolve(s1.sessionToken(), t.plusMinutes(5));
        eq("resolve finds the member", s1.member().id(), who.memberId());
        refused("an expired session", "NOT_SIGNED_IN", () -> auth.resolve(s1.sessionToken(), t.plusHours(9)));
        refused("an unknown token", "NOT_SIGNED_IN", () -> auth.resolve("x".repeat(43), t));
        refused("no token", "NOT_SIGNED_IN", () -> auth.resolve(null, t));
        tx(() -> { auth.signOut(s1.sessionToken(), t.plusMinutes(6)); return null; });
        refused("after sign-out", "NOT_SIGNED_IN", () -> auth.resolve(s1.sessionToken(), t.plusMinutes(7)));
        check("the other session is untouched", auth.resolve(s2.sessionToken(), t.plusMinutes(7)).memberId() == s1.member().id());
        tx(() -> { auth.signOut("junk", t); auth.signOut(null, t); return null; });
        check("sign-out of junk is harmless", true);

        refused("a bad id token", "INVALID_ID_TOKEN", () -> auth.signIn("garbage", "UA", "ip", t));
        jdbc.inTx(() -> jdbc.update("UPDATE portal_member SET status='BLOCKED' WHERE id = ?", s1.member().id()));
        check("sign-in and invitee refusals keep their audit rows (annotation present)", keepsAuditOnRefusal(PortalAuthService.class, "signIn") && keepsAuditOnRefusal(PortalInviteService.class, "view") && keepsAuditOnRefusal(PortalInviteService.class, "decline"));
        eq("refused sign-ins are audited", 1L, count("SELECT count(*) FROM portal_audit WHERE action='SIGN_IN_REFUSED'"));
        refusedKeep("a blocked member cannot sign in", "MEMBER_BLOCKED", () -> auth.signIn(idToken("sub-new", "new@x.com", "N"), "UA", "ip", t));
    }

    // ---- 5. the invitee's side of an invitation ----

    static void inviteeScenarios() {
        System.out.println("== invitee: view, decline, email mismatch, neutral errors");
        OffsetDateTime t = T0.plusDays(40);
        tx(() -> invites.invite("G1", new InviteRequest(List.of(new Invitee("v@x.com", "Vee", 3)), null), key(), "admin1", "ip", t));
        tx(() -> outbox.dispatchDue(t, 50));
        String token = tokenFrom(lastMailTo("v@x.com"));
        SessionMember vee = me("sub-v", "v@x.com", t);
        SessionMember eve = me("sub-e", "e@x.com", t);

        refusedKeep("a different signed-in person gets a mismatch, not the invite", "INVITE_EMAIL_MISMATCH", () -> invitee.view(token, eve, "ip", t));
        refusedKeep("and cannot decline it", "INVITE_EMAIL_MISMATCH", () -> invitee.decline(token, eve, "ip", t));
        eq("mismatch is audited, both times", 2L, count("SELECT count(*) FROM portal_audit WHERE action='EMAIL_MISMATCH'"));
        eq("invitation untouched by the mismatch", "PENDING", find("G1", "v@x.com", t).status());
        check("not marked opened by the wrong person", find("G1", "v@x.com", t).firstOpenedAt() == null);

        refused("garbage token", "INVITE_NOT_AVAILABLE", () -> invitee.view("short", vee, "ip", t));
        refused("well-formed unknown token", "INVITE_NOT_AVAILABLE", () -> invitee.view("A".repeat(43), vee, "ip", t));

        PortalInviteDtos.InviteView v = tx(() -> invitee.view(token, vee, "ip", t.plusMinutes(1)));
        eq("tickets offered", 3, v.tickets());
        eq("group name shown", "Group G1", v.groupName());
        check("group terms come from chitfund-service", v.group() != null && v.group().subscription().compareTo(new BigDecimal("5000")) == 0);
        eq("can decline", true, v.canDecline());
        check("first open recorded", find("G1", "v@x.com", t.plusMinutes(2)).firstOpenedAt() != null);
        OffsetDateTime opened = find("G1", "v@x.com", t.plusMinutes(2)).firstOpenedAt();
        tx(() -> invitee.view(token, vee, "ip", t.plusHours(3)));
        eq("opening again keeps the first-open time", opened, find("G1", "v@x.com", t.plusHours(4)).firstOpenedAt());
        eq("one OPENED audit row", 1L, count("SELECT count(*) FROM portal_audit WHERE action='OPENED' AND entity_ref LIKE ?", "G1#" + find("G1", "v@x.com", t).id()));

        PortalInviteDtos.InviteView dec = tx(() -> invitee.decline(token, vee, "ip", t.plusHours(5)));
        eq("declined", "DECLINED", dec.status());
        eq("cannot decline again from the view", false, dec.canDecline());
        eq("declining twice changes nothing", "DECLINED", tx(() -> invitee.decline(token, vee, "ip", t.plusHours(6))).status());
        eq("admin sees DECLINED", "DECLINED", find("G1", "v@x.com", t.plusHours(7)).status());
        refused("a declined invite cannot be revoked", "INVITE_NOT_REVOCABLE", () -> invites.revoke("G1", find("G1", "v@x.com", t).id(), null, "admin1", "ip", t));
        refused("a declined invite cannot be resent", "INVITE_NOT_RESENDABLE", () -> invites.resend("G1", find("G1", "v@x.com", t).id(), key(), "admin1", "ip", t));
        BatchResult again = tx(() -> invites.invite("G1", new InviteRequest(List.of(new Invitee("v@x.com", null, null)), null), key(), "admin1", "ip", t.plusDays(1)));
        eq("a declined address can be invited again", 1, again.created().size());

        // chitfund-service unreachable while the invitee looks at it: the page still answers
        GROUPS.remove("G1");
        PortalInviteDtos.InviteView degraded = tx(() -> invitee.view(tokenFrom(lastMailToOrNull("v@x.com", t)), vee, "ip", t.plusDays(1)));
        eq("group details unavailable is reported, not thrown", true, degraded.groupUnavailable());
        group("G1", "OPEN_FOR_ENROLMENT", 5);
    }

    /** The newest invitation for the address was queued but not yet sent: send it, then read the link. */
    static OutgoingMail lastMailToOrNull(String email, OffsetDateTime t) {
        tx(() -> outbox.dispatchDue(t.plusDays(1), 50));
        return lastMailTo(email);
    }
}
