package harness;

import com.chitfund.portal.auth.*;
import com.chitfund.portal.auth.PortalAuthService.SignIn;
import com.chitfund.portal.chit.ChitEnrolmentGateway;
import com.chitfund.portal.chit.ChitGroupGateway;
import com.chitfund.portal.chit.GroupSummary;
import com.chitfund.portal.common.Audit;
import com.chitfund.portal.common.Emails;
import com.chitfund.portal.common.PortalException;
import com.chitfund.portal.idempotency.IdempotencyStore;
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
 * Runs the portal's real services (sign-in, sessions, offers, site text, seat requests) against a real PostgreSQL with the real migrations.
 * Not Spring: no controllers, no proxies, no security filter. Google and chitfund-service are replaced by fakes.
 */
public class PortalHarness {

    static PgJdbcTemplate jdbc;
    static PortalAuthService auth;
    static MemberRepository members;

    static final Map<String, GroupSummary> GROUPS = new HashMap<>();
    static final Map<String, ChitEnrolmentGateway.Quote> QUOTES = new HashMap<>();
    static final Map<String, Long> CUSTOMERS = new HashMap<>();      // canonical e-mail -> customer id (the fake chitfund-service's master)
    static final Map<String, Integer> TICKETS = new HashMap<>();     // idempotency key -> ticket number
    static final Set<String> FAIL_KEYS = new HashSet<>();            // enrol keys that fail with CHIT_SERVICE_UNAVAILABLE
    static final List<ChitEnrolmentGateway.Received> RECEIVED = new ArrayList<>();
    static int enrolCalls = 0, nextTicket = 1;
    static long nextCustomer = 5000;
    static boolean customersDown = false;
    static final Map<String, GoogleIdentity> TOKENS = new HashMap<>();

    static final OffsetDateTime T0 = OffsetDateTime.of(2026, 11, 1, 10, 0, 0, 0, ZoneOffset.UTC);
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
        ChitGroupGateway groups = code -> {
            if ("DOWN".equals(code)) throw new PortalException("CHIT_SERVICE_UNAVAILABLE", "chitfund-service unreachable");
            return Optional.ofNullable(GROUPS.get(code));
        };
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
        members = new MemberRepository(jdbc);
        GoogleIdentityVerifier verifier = (idToken, now) -> {
            GoogleIdentity g = TOKENS.get(idToken);
            if (g == null) throw new PortalException("INVALID_ID_TOKEN", "bad token");
            return g;
        };
        auth = new PortalAuthService(verifier, members, new SessionRepository(jdbc), audit, 8);
        group("G1", "OPEN_FOR_ENROLMENT", 5);
        group("G-FULL", "OPEN_FOR_ENROLMENT", 0);
        group("G-ACTIVE", "ACTIVE", 3);
    }

    static void group(String code, String status, int seatsLeft) {
        GROUPS.put(code, new GroupSummary(code, "Group " + code, status, new BigDecimal("100000.00"), 20, 20, new BigDecimal("5000.00"),
                LocalDate.of(2026, 12, 1), seatsLeft));
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

    // ---- entry ----

    public static void main(String[] args) throws Exception {
        wire();
        String only = args.length > 0 ? args[0] : "";
        if (only.isEmpty() || only.equals("auth")) authScenarios();
        if (only.isEmpty() || only.equals("offers")) offerScenarios();
        if (only.isEmpty() || only.equals("site")) siteScenarios();
        if (only.isEmpty() || only.equals("join")) joinScenarios();
        System.out.println("\n" + passed + " passed, " + failed + " failed (" + jdbc.statements + " statements)");
        jdbc.close();
        System.exit(failed == 0 ? 0 : 1);
    }

    // ---- join requests (real SQL, fake chitfund-service plans) ----

    static void joinScenarios() {
        System.out.println("== join requests: request, idempotent repeat, seats, withdraw, staff decision");
        java.util.List<com.chitfund.portal.chit.ChitPlanGateway.OpenPlan> open = new java.util.ArrayList<>();
        open.add(new com.chitfund.portal.chit.ChitPlanGateway.OpenPlan("TG-EDU-1", "Education Plan 10 lakh, Siddipet", "EDUCATION", "Siddipet", "Telangana", new BigDecimal("1000000"), 40, 40, new BigDecimal("25000"), "MONTHLY", LocalDate.of(2026, 12, 1), 12));
        open.add(new com.chitfund.portal.chit.ChitPlanGateway.OpenPlan("TG-OLD-1", "Unlabelled", null, null, null, new BigDecimal("100000"), 10, 10, new BigDecimal("10000"), "MONTHLY", LocalDate.of(2026, 12, 1), 1));
        open.add(new com.chitfund.portal.chit.ChitPlanGateway.OpenPlan("TG-FULL-1", "Full plan", "HOME", "Medak", "Telangana", new BigDecimal("100000"), 10, 10, new BigDecimal("10000"), "MONTHLY", LocalDate.of(2026, 12, 1), 0));
        com.chitfund.portal.chit.ChitPlanGateway gw = () -> open;
        java.time.Clock clock = java.time.Clock.fixed(T0.toInstant(), ZoneOffset.UTC);
        var plans = new com.chitfund.portal.join.PlanService(gw, clock, 0);
        var repo = new com.chitfund.portal.join.JoinRequestRepository(jdbc);
        var svc = new com.chitfund.portal.join.JoinRequestService(repo, plans, new Audit(jdbc));

        var shelf = plans.shelf();
        eq("shelf lists open plans", 3, shelf.size());
        eq("unlabelled plan has empty labels", java.util.List.of("", "", ""), java.util.List.of(shelf.get(1).category(), shelf.get(1).branch(), shelf.get(1).state()));
        eq("shelf carries tickets and seats", java.util.List.of(40, 12, 40), java.util.List.of(shelf.get(0).tickets(), shelf.get(0).seatsLeft(), shelf.get(0).cycles()));

        long m1 = tx(() -> members.insert("asha.join@example.com", "Asha Rao", "sub-join-1", T0));
        long m2 = tx(() -> members.insert("ravi.join@example.com", "Ravi K", "sub-join-2", T0));
        var asha = new SessionMember(m1, "asha.join@example.com", "Asha Rao");
        var ravi = new SessionMember(m2, "ravi.join@example.com", "Ravi K");

        var r1 = tx(() -> svc.request(asha, "TG-EDU-1", 2, "1.2.3.4"));
        eq("new request is pending", java.util.List.of("PENDING", 2, "TG-EDU-1"), java.util.List.of(r1.status(), r1.tickets(), r1.groupCode()));
        eq("group name is snapshotted", "Education Plan 10 lakh, Siddipet", r1.groupName());
        var again = tx(() -> svc.request(asha, "TG-EDU-1", 1, "1.2.3.4"));
        eq("asking again returns the same request", r1.id(), again.id());
        eq("still one row", 1L, count("SELECT count(*) FROM portal_join_request WHERE member_id=" + m1));
        refused("tickets out of range", "VALIDATION_FAILED", () -> svc.request(asha, "TG-OLD-1", 4, null), true);
        refused("tickets missing", "VALIDATION_FAILED", () -> svc.request(asha, "TG-OLD-1", null, null), true);
        refused("unknown plan", "PLAN_NOT_OPEN", () -> svc.request(asha, "NOPE", 1, null), true);
        refused("full plan", "GROUP_FULL", () -> svc.request(asha, "TG-FULL-1", 1, null), true);
        refused("more tickets than seats", "GROUP_FULL", () -> svc.request(asha, "TG-OLD-1", 2, null), true);
        eq("other member can ask for the same plan", "PENDING", tx(() -> svc.request(ravi, "TG-EDU-1", 1, null)).status());
        eq("each sees only their own", java.util.List.of(1, 1), java.util.List.of(svc.mine(asha).size(), svc.mine(ravi).size()));

        refused("someone else cannot withdraw it", "NOT_FOUND", () -> svc.withdraw(ravi, r1.id(), null), true);
        var w = tx(() -> svc.withdraw(asha, r1.id(), null));
        eq("withdrawn", "WITHDRAWN", w.status());
        refused("withdrawing twice", "JOIN_NOT_PENDING", () -> svc.withdraw(asha, r1.id(), null), true);
        var r2 = tx(() -> svc.request(asha, "TG-EDU-1", 1, null));
        check("a new request after withdrawing gets a new id", r2.id() != r1.id());

        var pending = svc.list("PENDING");
        eq("staff see both pending", 2, pending.size());
        eq("staff see who asked", true, pending.stream().anyMatch(x -> x.email().equals("asha.join@example.com") && x.memberName().equals("Asha Rao")));
        refused("bad status filter", "VALIDATION_FAILED", () -> svc.list("MAYBE"), true);
        refused("decision needs an actor", "HEADER_REQUIRED", () -> svc.decide(r2.id(), true, null, " ", T0), true);
        var ok = tx(() -> svc.decide(r2.id(), true, " Call on Monday ", "staff1", T0));
        eq("approved with note", java.util.List.of("APPROVED", "Call on Monday", "staff1"), java.util.List.of(ok.status(), ok.note(), ok.decidedBy()));
        refused("deciding twice", "JOIN_NOT_PENDING", () -> svc.decide(r2.id(), false, null, "staff2", T0), true);
        refused("cannot withdraw an approved request", "JOIN_NOT_PENDING", () -> svc.withdraw(asha, r2.id(), null), true);
        var ravisId = pending.stream().filter(x -> x.email().startsWith("ravi")).findFirst().orElseThrow().id();
        eq("declined", "DECLINED", tx(() -> svc.decide(ravisId, false, "Group is for existing members", "staff1", T0)).status());
        var again2 = tx(() -> svc.request(ravi, "TG-EDU-1", 1, null));
        eq("after a decline a fresh request is allowed", "PENDING", again2.status());
        refused("unknown request", "NOT_FOUND", () -> svc.decide(99999, true, null, "s", T0), true);
        eq("by status", java.util.List.of(1, 1, 1, 1), java.util.List.of(svc.list("APPROVED").size(), svc.list("DECLINED").size(), svc.list("WITHDRAWN").size(), svc.list("PENDING").size()));
        eq("audit rows written", true, count("SELECT count(*) FROM portal_audit WHERE entity_type='JOIN_REQUEST'") >= 7);
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

    static void siteScenarios() {
        System.out.println("== site settings: save, update, clear, public view");
        com.chitfund.portal.site.SiteSettingsService svc = new com.chitfund.portal.site.SiteSettingsService(jdbc, new Audit(jdbc));
        eq("nothing set: all blank", java.util.List.of("", "", "", ""), new java.util.ArrayList<>(svc.all().values()));
        tx(() -> svc.save(java.util.Map.of("help_phone", "98765 43210", "payment_instructions", "UPI honest@upi"), "staff1"));
        eq("saved", "98765 43210", svc.publicSite().helpPhone());
        eq("how to pay saved", "UPI honest@upi", svc.text());
        tx(() -> svc.save(java.util.Map.of("help_phone", "11111"), "staff2"));
        eq("update replaces", "11111", svc.publicSite().helpPhone());
        tx(() -> svc.save(java.util.Map.of("help_phone", "  "), "staff2"));
        eq("blank clears", "", svc.publicSite().helpPhone());
        eq("one row per key", 1L, count("SELECT count(*) FROM portal_setting WHERE setting_key='payment_instructions'"));
        refused("unknown key", "VALIDATION_FAILED", () -> svc.save(java.util.Map.of("evil", "x"), "s"), false);
        eq("how to pay is not in the public view", false, svc.publicSite().toString().contains("honest@upi"));
        eq("audit rows", 4L, count("SELECT count(*) FROM portal_audit WHERE entity_type='SETTING'"));
    }

    static boolean dbRejects(String sql) {
        try { jdbc.update(sql); return false; } catch (RuntimeException e) { return true; }
    }

    // ---- 1. a batch of invitations, replay, group rules ----

    // ---- 2. resend, revoke, expiry ----

    // ---- 3. outbox retry ----

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
        check("sign-in refusals keep their audit rows (annotation present)", keepsAuditOnRefusal(PortalAuthService.class, "signIn"));
        eq("refused sign-ins are audited", 1L, count("SELECT count(*) FROM portal_audit WHERE action='SIGN_IN_REFUSED'"));
        refusedKeep("a blocked member cannot sign in", "MEMBER_BLOCKED", () -> auth.signIn(idToken("sub-new", "new@x.com", "N"), "UA", "ip", t));
    }

    // ---- 5. the invitee's side of an invitation ----

    // ---- 6. accepting enrols: customer by e-mail, self-service, awaiting payment, admin enrol, partial failure, retries ----

    /** The newest invitation for the address was queued but not yet sent: send it, then read the link. */
}
