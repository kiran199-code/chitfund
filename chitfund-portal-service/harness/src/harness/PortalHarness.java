package harness;

import com.chitfund.portal.auth.*;
import com.chitfund.portal.auth.PortalAuthService.SignIn;
import com.chitfund.portal.chit.ChitEnrolmentGateway;
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
    static final Map<String, ChitEnrolmentGateway.Quote> QUOTES = new HashMap<>();
    static final Map<String, Long> CUSTOMERS = new HashMap<>();      // canonical e-mail -> customer id (the fake chitfund-service's master)
    static final Map<String, Integer> TICKETS = new HashMap<>();     // idempotency key -> ticket number
    static final Set<String> FAIL_KEYS = new HashSet<>();            // enrol keys that fail with CHIT_SERVICE_UNAVAILABLE
    static final List<ChitEnrolmentGateway.Received> RECEIVED = new ArrayList<>();
    static int enrolCalls = 0, nextTicket = 1;
    static long nextCustomer = 5000;
    static final List<OutgoingMail> SENT = new ArrayList<>();
    static boolean mailDown = false;
    static boolean customersDown = false;
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
        ChitEnrolmentGateway chit = new ChitEnrolmentGateway() {
            public Quote quote(String groupCode) {
                return QUOTES.getOrDefault(groupCode, new Quote(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, false, BigDecimal.ZERO));
            }
            public CustomerRef resolveCustomer(String email, String name, Long existing) {
                String e = Emails.normalise(email);
                Long known = CUSTOMERS.get(e);
                if (known != null) {
                    if (existing != null && !existing.equals(known)) throw new PortalException("EMAIL_CUSTOMER_MISMATCH", "e-mail belongs to " + known);
                    return new CustomerRef(known, false, false);
                }
                if (existing != null) {
                    if (CUSTOMERS.containsValue(existing)) throw new PortalException("CUSTOMER_EMAIL_TAKEN", "id has another e-mail");
                    CUSTOMERS.put(e, existing);
                    return new CustomerRef(existing, false, true);
                }
                CUSTOMERS.put(e, nextCustomer);
                return new CustomerRef(nextCustomer++, true, false);
            }
            public Map<String, Long> lookupCustomers(List<String> emails) {
                if (customersDown) throw new PortalException("CHIT_SERVICE_UNAVAILABLE", "down");
                Map<String, Long> out = new HashMap<>();
                for (String e : emails) if (CUSTOMERS.containsKey(e)) out.put(e, CUSTOMERS.get(e));
                return out;
            }
            public int enrol(String groupCode, long customerId, Received received, String key) {
                enrolCalls++;
                if (FAIL_KEYS.contains(key)) throw new PortalException("CHIT_SERVICE_UNAVAILABLE", "chit down for " + key);
                RECEIVED.add(received);
                return TICKETS.computeIfAbsent(key, k -> nextTicket++);
            }
        };
        EnrolmentFlow flow = new EnrolmentFlow(invRepo, groups, chit, audit);
        invites = new InvitationService(invRepo, groups, chit, flow, outbox, idem, audit, mapper, "https://portal.example", 7, 200, 5, "Asia/Kolkata");
        members = new MemberRepository(jdbc);
        GoogleIdentityVerifier verifier = (idToken, now) -> {
            GoogleIdentity g = TOKENS.get(idToken);
            if (g == null) throw new PortalException("INVALID_ID_TOKEN", "bad token");
            return g;
        };
        auth = new PortalAuthService(verifier, members, new SessionRepository(jdbc), audit, 8);
        invitee = new PortalInviteService(invRepo, groups, chit, flow, audit);
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
        if (only.isEmpty() || only.equals("enrol")) enrolmentScenarios();
        if (only.isEmpty() || only.equals("offers")) offerScenarios();
        System.out.println("\n" + passed + " passed, " + failed + " failed (" + jdbc.statements + " statements)");
        jdbc.close();
        System.exit(failed == 0 ? 0 : 1);
    }

    // ---- offers (real SQL) ----

    static void offerScenarios() {
        System.out.println("== offers: create, live window, audience, hide, update");
        com.chitfund.portal.offer.OfferService svc = new com.chitfund.portal.offer.OfferService(new com.chitfund.portal.offer.OfferRepository(jdbc), new Audit(jdbc));
        java.time.LocalDate d = java.time.LocalDate.of(2026, 10, 25);
        var pub = tx(() -> svc.create(new com.chitfund.portal.offer.OfferService.OfferInput("Diwali chit", "Join before 5 Nov", "https://example.com/d", "https://example.com/d.png", "PUBLIC", d.minusDays(1), d.plusDays(10), null), "staff1", d));
        var mem = tx(() -> svc.create(new com.chitfund.portal.offer.OfferService.OfferInput("Members only", "", null, null, "MEMBERS", d, null, null), "staff1", d));
        var soon = tx(() -> svc.create(new com.chitfund.portal.offer.OfferService.OfferInput("Next month", "", null, null, "PUBLIC", d.plusDays(5), null, null), "staff1", d));
        var old = tx(() -> svc.create(new com.chitfund.portal.offer.OfferService.OfferInput("Ended", "", null, null, "PUBLIC", d.minusDays(20), d.minusDays(2), null), "staff1", d));
        eq("statuses", java.util.List.of("LIVE", "LIVE", "SCHEDULED", "ENDED"), java.util.List.of(pub.status(), mem.status(), soon.status(), old.status()));
        eq("visitors see live public offers only", java.util.List.of("Diwali chit"), svc.forVisitors(d).stream().map(o -> o.title()).toList());
        eq("members see live public and members offers", java.util.Set.of("Diwali chit", "Members only"), new java.util.HashSet<>(svc.forMembers(d).stream().map(o -> o.title()).toList()));
        eq("the scheduled one appears on its start day", true, svc.forVisitors(d.plusDays(5)).stream().anyMatch(o -> o.title().equals("Next month")));
        eq("the last day is inclusive", true, svc.forVisitors(d.plusDays(10)).stream().anyMatch(o -> o.title().equals("Diwali chit")));
        eq("gone the day after", false, svc.forVisitors(d.plusDays(11)).stream().anyMatch(o -> o.title().equals("Diwali chit")));
        var hidden = tx(() -> svc.setActive(pub.id(), false, "staff2", d));
        eq("hidden status", "HIDDEN", hidden.status());
        eq("a hidden offer is not shown", false, svc.forMembers(d).stream().anyMatch(o -> o.id() == pub.id()));
        var shown = tx(() -> svc.update(pub.id(), new com.chitfund.portal.offer.OfferService.OfferInput("Diwali chit 2", "x", null, null, "MEMBERS", d, null, true), "staff2", d));
        eq("update re-shows and changes audience", java.util.List.of("LIVE", "MEMBERS", "Diwali chit 2"), java.util.List.of(shown.status(), shown.audience(), shown.title()));
        eq("members-only now hidden from visitors", false, svc.forVisitors(d).stream().anyMatch(o -> o.id() == pub.id()));
        refused("update of a missing offer", "NOT_FOUND", () -> svc.update(99999, new com.chitfund.portal.offer.OfferService.OfferInput("x", "", null, null, "PUBLIC", d, null, null), "s", d), false);
        refused("bad link", "VALIDATION_FAILED", () -> svc.create(new com.chitfund.portal.offer.OfferService.OfferInput("x", "", "javascript:alert(1)", null, "PUBLIC", d, null, null), "s", d), false);
        eq("offer audit rows (4 created, 1 hidden, 1 updated)", 6L, count("SELECT count(*) FROM portal_audit WHERE entity_type='OFFER'"));
        eq("the DB itself rejects an end before the start", true, dbRejects("INSERT INTO portal_offer (title,audience,starts_on,ends_on,created_by) VALUES ('x','PUBLIC','2026-10-25','2026-10-01','s')"));
        eq("the DB itself rejects an unknown audience", true, dbRejects("INSERT INTO portal_offer (title,audience,starts_on,created_by) VALUES ('x','ALL','2026-10-25','s')"));
    }

    static boolean dbRejects(String sql) {
        try { jdbc.update(sql); return false; } catch (RuntimeException e) { return true; }
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

    // ---- 6. accepting enrols: customer by e-mail, self-service, awaiting payment, admin enrol, partial failure, retries ----

    static String inviteToken(String code, String email, String name, Integer tickets, Long existing, OffsetDateTime t) {
        tx(() -> invites.invite(code, new InviteRequest(List.of(new Invitee(email, name, tickets, existing)), null), key(), "admin1", "ip", t));
        tx(() -> outbox.dispatchDue(t, 50));
        return tokenFrom(lastMailTo(email));
    }

    static void enrolmentScenarios() {
        System.out.println("== enrol: accept makes the e-mail a customer and enrols; waits when something is due");
        OffsetDateTime t = T0.plusDays(60);
        eq("accept keeps the refusal audit rows (noRollbackFor)", true, keepsAuditOnRefusal(PortalInviteService.class, "accept"));
        eq("admin enrol keeps what it did before a refusal", true, keepsAuditOnRefusal(InvitationService.class, "enrol"));

        // 1. nothing due, new person, two tickets: enrolled straight away
        String t1 = inviteToken("G1", "new1@x.com", "Nia", 2, null, t);
        SessionMember nia = me("en-n1", "new1@x.com", t);
        PortalInviteDtos.InviteView v0 = tx(() -> invitee.view(t1, nia, "ip", t));
        eq("pending invitation can be accepted", true, v0.canAccept());
        eq("nothing due is shown as 0", true, v0.cashDuePerTicket() != null && v0.cashDuePerTicket().signum() == 0);
        PortalInviteDtos.InviteView a1 = txKeep(() -> invitee.accept(t1, nia, "ip", t.plusMinutes(5)));
        eq("accepted and enrolled at once", "ACCEPTED", a1.status());
        eq("both tickets enrolled", 2, a1.ticketsEnrolled());
        eq("two ticket numbers shown", 2, a1.ticketNos().size());
        check("a new customer id was created and shown", a1.customerId() != null && a1.customerId() >= 5000);
        eq("the e-mail is the customer's key in chitfund-service", a1.customerId(), CUSTOMERS.get("new1@x.com"));
        eq("canAccept false afterwards", false, a1.canAccept());
        InviteView adm1 = find("G1", "new1@x.com", t.plusMinutes(6));
        eq("admin sees ACCEPTED with the customer id", a1.customerId(), adm1.customerId());
        eq("admin sees tickets enrolled", 2, adm1.ticketsEnrolled());
        eq("a CUSTOMER_CREATED audit row", 1L, count("SELECT count(*) FROM portal_audit WHERE action='CUSTOMER_CREATED' AND entity_ref = ?", "G1#" + adm1.id()));
        int calls = enrolCalls;
        PortalInviteDtos.InviteView a1b = txKeep(() -> invitee.accept(t1, nia, "ip", t.plusMinutes(9)));
        eq("accepting again changes nothing", "ACCEPTED", a1b.status());
        eq("and makes no chitfund-service call", calls, enrolCalls);
        refused("an accepted invitation cannot be declined", "INVITE_NOT_DECLINABLE", () -> invitee.decline(t1, nia, "ip", t.plusMinutes(10)));
        refused("an accepted invitation cannot be revoked", "INVITE_NOT_REVOCABLE", () -> invites.revoke("G1", adm1.id(), null, "admin1", "ip", t));
        refused("admin cannot enrol an invitation that is already complete", "INVITE_NOT_AWAITING_PAYMENT",
                () -> invites.enrol("G1", adm1.id(), new EnrolRequest(true, null, null, null), "admin1", "ip", t));
        refused("another address cannot be invited as already accepted", "ALREADY_MEMBER",
                () -> { BatchResult r = invites.invite("G1", new InviteRequest(List.of(new Invitee("new1@x.com", null, null)), null), key(), "admin1", "ip", t);
                        throw new PortalException(r.skipped().get(0).code(), "x"); });

        // 2. the e-mail is already a customer: the invite shows it, accept reuses it
        CUSTOMERS.put("known@x.com", 777L);
        String t2 = inviteToken("G1", "known@x.com", "Kay", 1, null, t);
        eq("existing customer id prefilled on the invitation", 777L, find("G1", "known@x.com", t).customerId());
        PortalInviteDtos.InviteView a2 = txKeep(() -> invitee.accept(t2, me("en-k", "known@x.com", t), "ip", t.plusMinutes(5)));
        eq("enrolled under the existing customer", 777L, a2.customerId());
        eq("no new customer made", 0L, count("SELECT count(*) FROM portal_audit WHERE action='CUSTOMER_CREATED' AND entity_ref = ?", "G1#" + find("G1", "known@x.com", t).id()));
        eq("Gmail address variants are the same customer", true, CUSTOMERS.containsKey("known@x.com") && Emails.normalise("Known@X.com").equals("known@x.com"));

        // 3. admin names an old customer id with the e-mail
        BatchResult mism = tx(() -> invites.invite("G1", new InviteRequest(List.of(new Invitee("known@x.com", null, null, 999L), new Invitee("bad@x.com", null, 1, -3L)), null), key(), "admin1", "ip", t));
        eq("e-mail already customer 777, id 999 named: skipped", "EMAIL_CUSTOMER_MISMATCH", mism.skipped().get(0).code());
        eq("negative customer id skipped", "INVALID_CUSTOMER_ID", mism.skipped().get(1).code());
        String t3 = inviteToken("G1", "legacy@x.com", "Lee", 1, 4242L, t);
        eq("old id stored on the invitation", 4242L, find("G1", "legacy@x.com", t).existingCustomerId());
        PortalInviteDtos.InviteView a3 = txKeep(() -> invitee.accept(t3, me("en-l", "legacy@x.com", t), "ip", t.plusMinutes(5)));
        eq("the e-mail was attached to the old id", 4242L, a3.customerId());
        eq("a CUSTOMER_LINKED audit row", 1L, count("SELECT count(*) FROM portal_audit WHERE action='CUSTOMER_LINKED' AND entity_ref = ?", "G1#" + find("G1", "legacy@x.com", t).id()));
        String t3b = inviteToken("G1", "thief@x.com", null, 1, 4242L, t);
        refusedKeep("an old id that belongs to another e-mail is refused", "CUSTOMER_EMAIL_TAKEN", () -> invitee.accept(t3b, me("en-t", "thief@x.com", t), "ip", t.plusMinutes(5)));
        eq("that invitation is still pending", "PENDING", find("G1", "thief@x.com", t).status());

        // 4. something is due: accept waits, admin records the receipt
        QUOTES.put("G-FEE", new ChitEnrolmentGateway.Quote(new BigDecimal("1000"), new BigDecimal("500"), new BigDecimal("8500"), BigDecimal.ZERO, false, new BigDecimal("10000")));
        group("G-FEE", "OPEN_FOR_ENROLMENT", 5);
        String t4 = inviteToken("G-FEE", "pay@x.com", "Pia", 2, null, t);
        SessionMember pia = me("en-p", "pay@x.com", t);
        eq("the amount due per ticket is shown before accepting", new BigDecimal("10000"), tx(() -> invitee.view(t4, pia, "ip", t)).cashDuePerTicket());
        int before = enrolCalls;
        PortalInviteDtos.InviteView a4 = txKeep(() -> invitee.accept(t4, pia, "ip", t.plusMinutes(5)));
        eq("accepted but waiting for payment", "ACCEPTED_AWAITING_PAYMENT", a4.status());
        eq("no ticket enrolled yet", 0, a4.ticketsEnrolled());
        check("customer already known so the receipt can name it", a4.customerId() != null);
        eq("no chitfund-service enrol call", before, enrolCalls);
        eq("cannot be accepted twice into a different state", "ACCEPTED_AWAITING_PAYMENT", txKeep(() -> invitee.accept(t4, pia, "ip", t.plusMinutes(6))).status());
        refused("a waiting invitation cannot be declined", "INVITE_NOT_DECLINABLE", () -> invitee.decline(t4, pia, "ip", t.plusMinutes(7)));
        long id4 = find("G-FEE", "pay@x.com", t).id();
        refusedKeep("admin must confirm the fees", "VALIDATION_FAILED", () -> invites.enrol("G-FEE", id4, new EnrolRequest(false, null, null, null), "admin1", "ip", t.plusHours(1)));
        RECEIVED.clear();
        InviteView e4 = txKeep(() -> invites.enrol("G-FEE", id4, new EnrolRequest(true, null, null, "ACC-1"), "admin1", "ip", t.plusHours(1)));
        eq("admin enrol completes it", "ACCEPTED", e4.status());
        eq("both tickets enrolled", 2, e4.ticketsEnrolled());
        eq("each ticket received exactly the quoted fees", new BigDecimal("8500"), RECEIVED.get(0).securityDeposit());
        eq("and the registration fee", new BigDecimal("500"), RECEIVED.get(1).registrationFee());
        eq("payout account passed on", "ACC-1", RECEIVED.get(0).payoutAccountRef());
        eq("the enrolment audit names the actor", true, count("SELECT count(*) FROM portal_audit WHERE action='TICKET_ENROLLED' AND actor='admin1' AND entity_ref = ?", "G-FEE#" + id4) == 2L);

        // a revoked waiting invitation can no longer be accepted or enrolled
        String t5 = inviteToken("G-FEE", "rev@x.com", null, 1, null, t);
        txKeep(() -> invitee.accept(t5, me("en-r", "rev@x.com", t), "ip", t.plusMinutes(5)));
        long id5 = find("G-FEE", "rev@x.com", t).id();
        tx(() -> invites.revoke("G-FEE", id5, new RevokeRequest("changed mind"), "admin1", "ip", t.plusMinutes(8)));
        refusedKeep("revoked: accept is not available", "INVITE_NOT_AVAILABLE", () -> invitee.accept(t5, me("en-r", "rev@x.com", t), "ip", t.plusMinutes(9)));
        refused("revoked: admin cannot enrol", "INVITE_NOT_AWAITING_PAYMENT", () -> invites.enrol("G-FEE", id5, new EnrolRequest(true, null, null, null), "admin1", "ip", t.plusMinutes(9)));

        // guarantor and lien groups need those papers named
        QUOTES.put("G-GUAR", new ChitEnrolmentGateway.Quote(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("50000"), true, BigDecimal.ZERO));
        group("G-GUAR", "OPEN_FOR_ENROLMENT", 5);
        String t6 = inviteToken("G-GUAR", "gua@x.com", null, 1, null, t);
        eq("guarantor/lien means accept waits even when no cash is due", "ACCEPTED_AWAITING_PAYMENT", txKeep(() -> invitee.accept(t6, me("en-g", "gua@x.com", t), "ip", t.plusMinutes(5))).status());
        long id6 = find("G-GUAR", "gua@x.com", t).id();
        refusedKeep("guarantor required", "VALIDATION_FAILED", () -> invites.enrol("G-GUAR", id6, new EnrolRequest(true, null, "LIEN-1", null), "admin1", "ip", t.plusHours(1)));
        refusedKeep("lien reference required", "VALIDATION_FAILED", () -> invites.enrol("G-GUAR", id6, new EnrolRequest(true, 4242L, null, null), "admin1", "ip", t.plusHours(1)));
        InviteView e6 = txKeep(() -> invites.enrol("G-GUAR", id6, new EnrolRequest(true, 4242L, "LIEN-1", null), "admin1", "ip", t.plusHours(1)));
        eq("enrolled with guarantor and lien", "ACCEPTED", e6.status());
        eq("guarantor passed on", 4242L, RECEIVED.get(RECEIVED.size() - 1).guarantorCustomerId());
        eq("lien reference passed on", "LIEN-1", RECEIVED.get(RECEIVED.size() - 1).lienReference());

        // 5. failures: first ticket, later ticket, then top-up
        String t7 = inviteToken("G1", "part@x.com", null, 3, null, t);
        SessionMember pat = me("en-pt", "part@x.com", t);
        long id7 = find("G1", "part@x.com", t).id();
        FAIL_KEYS.add("PORTAL-ENROL:" + id7 + ":1");
        refusedKeep("first ticket fails: the accept is refused", "CHIT_SERVICE_UNAVAILABLE", () -> invitee.accept(t7, pat, "ip", t.plusMinutes(5)));
        eq("invitation still pending, nothing marked", "PENDING", find("G1", "part@x.com", t).status());
        eq("no ticket rows", 0L, count("SELECT count(*) FROM portal_invitation_ticket WHERE invitation_id = ?", id7));
        FAIL_KEYS.clear();
        FAIL_KEYS.add("PORTAL-ENROL:" + id7 + ":2");
        PortalInviteDtos.InviteView a7 = txKeep(() -> invitee.accept(t7, pat, "ip", t.plusMinutes(6)));
        eq("retry: accepted", "ACCEPTED", a7.status());
        eq("a later ticket failed: only the first is enrolled", 1, a7.ticketsEnrolled());
        eq("TICKET_NOT_ENROLLED audited", 1L, count("SELECT count(*) FROM portal_audit WHERE action='TICKET_NOT_ENROLLED' AND entity_ref = ?", "G1#" + id7));
        FAIL_KEYS.clear();
        InviteView top = txKeep(() -> invites.enrol("G1", id7, new EnrolRequest(true, null, null, null), "admin1", "ip", t.plusHours(2)));
        eq("admin tops up the missing tickets", 3, top.ticketsEnrolled());
        check("ticket numbers are distinct", new HashSet<>(invitee.getClass() == null ? List.of() : tx(() -> invitee.view(t7, pat, "ip", t.plusHours(3))).ticketNos()).size() == 3);

        // a crash after chitfund-service made the ticket but before the portal wrote it: the retry reuses that ticket
        String t8 = inviteToken("G1", "crash@x.com", null, 1, null, t);
        long id8 = find("G1", "crash@x.com", t).id();
        TICKETS.put("PORTAL-ENROL:" + id8 + ":1", 900);
        PortalInviteDtos.InviteView a8 = txKeep(() -> invitee.accept(t8, me("en-c", "crash@x.com", t), "ip", t.plusMinutes(5)));
        eq("the ticket made earlier is the one recorded", List.of(900), a8.ticketNos());

        // 6. the group closed or filled between invite and accept
        group("G-TMP", "OPEN_FOR_ENROLMENT", 5);
        String t9 = inviteToken("G-TMP", "late@x.com", null, 1, null, t);
        SessionMember late = me("en-lt", "late@x.com", t);
        group("G-TMP", "ACTIVE", 5);
        refusedKeep("group no longer open", "ENROLMENT_NOT_OPEN", () -> invitee.accept(t9, late, "ip", t.plusMinutes(5)));
        group("G-TMP", "OPEN_FOR_ENROLMENT", 0);
        refusedKeep("group full", "GROUP_FULL", () -> invitee.accept(t9, late, "ip", t.plusMinutes(6)));
        eq("still pending after both refusals", "PENDING", find("G-TMP", "late@x.com", t).status());

        // wrong person, expired, declined, lookup down
        String t10 = inviteToken("G1", "own@x.com", null, 1, null, t);
        SessionMember intruder = me("en-i", "intruder@x.com", t);
        refusedKeep("someone else cannot accept", "INVITE_EMAIL_MISMATCH", () -> invitee.accept(t10, intruder, "ip", t.plusMinutes(5)));
        refusedKeep("an expired link is not available", "INVITE_NOT_AVAILABLE", () -> invitee.accept(t10, me("en-o", "own@x.com", t.plusDays(8)), "ip", t.plusDays(8)));
        String t11 = inviteToken("G1", "dec@x.com", null, 1, null, t);
        SessionMember dec = me("en-d", "dec@x.com", t);
        tx(() -> invitee.decline(t11, dec, "ip", t.plusMinutes(5)));
        refusedKeep("a declined invitation cannot be accepted", "INVITE_NOT_ACCEPTABLE", () -> invitee.accept(t11, dec, "ip", t.plusMinutes(6)));
        customersDown = true;
        BatchResult down = tx(() -> invites.invite("G1", new InviteRequest(List.of(new Invitee("down@x.com", null, null)), null), key(), "admin1", "ip", t));
        customersDown = false;
        eq("customer lookup down at invite time does not block the invitation", 1, down.created().size());
    }

    /** The newest invitation for the address was queued but not yet sent: send it, then read the link. */
    static OutgoingMail lastMailToOrNull(String email, OffsetDateTime t) {
        tx(() -> outbox.dispatchDue(t.plusDays(1), 50));
        return lastMailTo(email);
    }
}
