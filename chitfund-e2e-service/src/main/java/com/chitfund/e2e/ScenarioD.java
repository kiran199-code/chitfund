package com.chitfund.e2e;

import com.chitfund.e2e.Http.Resp;
import com.chitfund.e2e.Identities.Person;
import com.chitfund.e2e.PortalApi.Session;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Scenario D, identities and seat requests: one Google account typed four ways is one member; another Google account on the same address is
 * refused; bad tokens are refused; seat-request limits; a repeated request returns the same one; and one member never sees another's data.
 */
final class ScenarioD {
    private static final String OPS = "e2e-ops";
    private ScenarioD() {
    }

    static void run(Ctx c) {
        RunOptions o = c.opt;
        c.report.scenario("D identities and requests");
        String run = o.runId();

        // the same Google account written four ways is one member
        Person base = new Person("e2e-sub-" + run + "-dup", "e2e." + run + ".dup.one@gmail.com", "E2E Dup");
        String[] variants = {base.signInEmail(), "e2e" + run + "dupone@gmail.com", "E2E." + run + ".Dup.One+promo@gmail.com", "e2e." + run + ".dup.one@googlemail.com"};
        long memberId = -1;
        boolean same = true;
        String stored = null;
        for (String v : variants) {
            Session s = c.step("sign in as " + v, () -> c.portal.signIn(base.withSignInEmail(v)));
            if (memberId == -1) { memberId = s.memberId; stored = s.email; }
            same &= s.memberId == memberId && s.email.equals(stored);
        }
        c.check("four spellings of one Gmail address are one member", same, "member " + memberId + ", stored as " + stored);
        c.check("the stored address follows Google's rule", Identities.canonical(base.signInEmail()).equals(stored), "stored " + stored + ", expected " + Identities.canonical(base.signInEmail()));
        c.refused("another Google account on the same address is refused", c.portal.signInRaw(new Person("e2e-sub-" + run + "-other", variants[1], "Intruder").idToken()), 409, "GOOGLE_ACCOUNT_MISMATCH");
        c.refused("a token that is not a sign-in token is refused", c.portal.signInRaw("garbage"));
        c.refused("a real-looking Google token is refused in the test setup", c.portal.signInRaw("eyJhbGciOiJSUzI1NiJ9.e30.sig"));

        // a plan to ask for
        LocalDate start = c.time.now().withDayOfMonth(1).plusMonths(1);
        BigDecimal value = new BigDecimal("40000");
        String code = c.step("create the group", () -> c.newSampleGroup(value, 4, start));
        c.chit.setListing(code, "WEDDING", "E2E Branch", "Telangana", OPS);
        c.chit.openEnrolment(code, OPS);
        Session a = c.portal.signIn(base);
        Person bP = new Person("e2e-sub-" + run + "-b", "e2e." + run + ".bee@gmail.com", "E2E Bee");
        Session b = c.portal.signIn(bP);
        for (int i = 0; i < 20 && !listed(c, code); i++) c.sleep(5_000); // the portal caches its plan list for about 30 seconds
        c.require("the plan is listed for the public", listed(c, code), "after waiting for the cache");

        c.refused("zero seats is refused", c.portal.requestSeatRaw(a, code, 0), 400, null);
        c.refused("more than 3 seats is refused", c.portal.requestSeatRaw(a, code, 4), 400, null);
        c.refused("a plan that does not exist is refused", c.portal.requestSeatRaw(a, "NOPE-999", 1), 409, "PLAN_NOT_OPEN");
        long id1 = c.portal.requestSeat(a, code, 2).path("id").asLong();
        c.check("asking again returns the same request", c.portal.requestSeat(a, code, 2).path("id").asLong() == id1, "request " + id1);
        c.check("the other member does not see it", ScenarioA.statusOf(c.portal.myRequests(b), id1) == null, "member B's requests: " + c.portal.myRequests(b).size());
        c.check("withdrawing works", "WITHDRAWN".equals(Json.text(c.portal.withdrawRequest(a, id1), "status")), "request " + id1);
        long id2 = c.portal.requestSeat(a, code, 1).path("id").asLong();
        c.check("a new request after withdrawing is a new request", id2 != id1, id1 + " then " + id2);

        // data isolation: A holds a ticket, B does not
        long customerA = c.chit.resolveCustomer(base.email(), base.name(), OPS).path("customerId").asLong();
        c.chit.enrol(code, customerA, "E2E-ACC-D1", c.chit.quote(code), OPS);
        JsonNode ta = c.portal.myTickets(a);
        JsonNode tb = c.portal.myTickets(b);
        c.check("a member sees their own ticket", ta.size() == 1, ta.size() + " ticket(s)");
        c.check("another member sees none of it", tb.size() == 0, tb.size() + " ticket(s)");
        JsonNode dash = c.portal.dashboard(b);
        c.check("the other member's dashboard shows no tickets", Json.integer(dash.path("summary"), "ticketsHeld", -1) == 0, "ticketsHeld " + Json.integer(dash.path("summary"), "ticketsHeld", -1));
        c.info("not covered", "a blocked member cannot be created through any API, so that refusal is only covered by the portal's own tests");
    }

    private static boolean listed(Ctx c, String code) {
        for (JsonNode p : c.portal.publicPlans()) if (code.equals(Json.text(p, "groupCode"))) return true;
        return false;
    }
}
