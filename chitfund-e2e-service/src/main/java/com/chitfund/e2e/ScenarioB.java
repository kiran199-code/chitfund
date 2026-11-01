package com.chitfund.e2e;

import com.chitfund.e2e.Identities.Person;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Scenario B, late payment and default: five tickets, one customer never pays. With a 2-day grace, a flat late fee of 100 and a default after
 * 2 missed instalments, the suite moves the date past the grace period and the second due date and checks the late fee, the default flag, and
 * that paying everything owed clears it. No auctions are run (the default quorum rule would block them while a ticket owes).
 */
final class ScenarioB {
    private static final String OPS = "e2e-ops";
    private static final int TICKETS = 5;
    private ScenarioB() {
    }

    static void run(Ctx c) {
        RunOptions o = c.opt;
        c.report.scenario("B late and default");
        LocalDate start = c.time.now().withDayOfMonth(1).plusMonths(1);
        BigDecimal value = new BigDecimal("100000");
        String code = c.step("create the group", () -> c.newSampleGroup(value, TICKETS, start));

        ObjectNode rules = ((ObjectNode) c.chit.rules(code)).deepCopy();
        rules.put("graceDays", 2);
        rules.set("lateFeeRule", Json.obj().put("kind", "FLAT").put("flatAmount", 100));
        rules.set("defaultThreshold", Json.obj().put("missedInstalments", 2).putNull("daysOverdue"));
        c.chit.putRules(code, rules, OPS);
        JsonNode saved = c.chit.rules(code);
        c.require("late fee and default rules saved", "FLAT".equals(Json.text(saved.path("lateFeeRule"), "kind")) && Json.integer(saved, "graceDays", -1) == 2
                && Json.integer(saved.path("defaultThreshold"), "missedInstalments", -1) == 2, "rules now: grace " + Json.integer(saved, "graceDays", -1) + ", late fee " + saved.path("lateFeeRule") + ", default " + saved.path("defaultThreshold"));
        c.chit.openEnrolment(code, OPS);

        List<Person> people = Identities.saversFor(o.runId() + "b", TICKETS);
        for (int i = 0; i < TICKETS; i++) {
            Person p = people.get(i);
            long id = c.chit.resolveCustomer(p.email(), p.name(), OPS).path("customerId").asLong();
            c.chit.enrol(code, id, "E2E-ACC-B" + (i + 1), c.chit.quote(code), OPS);
        }
        c.chit.activate(code, OPS);
        JsonNode cycles = c.chit.cycles(code);
        LocalDate due1 = Json.date(cycles.get(0), "dueDate");
        LocalDate due2 = Json.date(cycles.get(1), "dueDate");

        c.time.jumpTo(due1);
        c.time.runJobsToday();
        payAll(c, code, 1, 4);
        BigDecimal owed5 = Json.money(c.chit.statement(code, 5), "totalOutstanding");
        c.check("ticket 5 owes its instalment", owed5 != null && owed5.signum() > 0, "owes " + owed5);

        c.time.jumpTo(due1.plusDays(4)); // past the 2-day grace; the late-fee run of the days in between has run (or the leap ran it once)
        c.time.runJobsToday();
        JsonNode st5 = c.chit.statement(code, 5);
        BigDecimal fee = Json.money(st5.path("demands").path(0), "lateFeeAccrued");
        c.check("ticket 5 was charged the flat late fee once", Money.near(fee, new BigDecimal("100")), "late fee accrued " + fee + (o.dateMode() == TimeTravel.Mode.LEAP ? " (LEAP: the jobs ran once, on the target day)" : ""));
        BigDecimal fee1 = Json.money(c.chit.statement(code, 1).path("demands").path(0), "lateFeeAccrued");
        c.check("a paid ticket has no late fee", fee1 == null || fee1.signum() == 0, "ticket 1 late fee " + fee1);

        c.time.jumpTo(due2);
        c.time.runJobsToday();
        payAll(c, code, 1, 4);
        JsonNode cases = c.chit.defaultCases(code);
        boolean flagged = false;
        for (JsonNode k : cases) if (k.path("ticketNo").asInt() == 5 && "OPEN".equals(Json.text(k, "status"))) flagged = true;
        c.check("ticket 5 is flagged as defaulted after 2 missed instalments", flagged, "default cases: " + cases);
        c.info("ticket 5 status", Json.text(c.chit.statement(code, 5), "ticketStatus"));
        for (int t = 1; t <= 4; t++) {
            c.check("ticket " + t + " is not flagged", !Json.text(c.chit.statement(code, t), "ticketStatus").equals("DEFAULTED"), Json.text(c.chit.statement(code, t), "ticketStatus"));
        }

        // cure: pay everything owed
        BigDecimal owed = Json.money(c.chit.statement(code, 5), "totalOutstanding");
        c.chit.pay(code, 5, owed, "E2E-" + o.runId() + "-cure", "E2E-" + o.runId() + "-cure", OPS);
        JsonNode after = c.chit.statement(code, 5);
        c.check("paying everything owed clears the balance", Json.money(after, "totalOutstanding").signum() == 0, "owes " + Json.money(after, "totalOutstanding"));
        boolean stillOpen = false;
        for (JsonNode k : c.chit.defaultCases(code)) if (k.path("ticketNo").asInt() == 5 && "OPEN".equals(Json.text(k, "status"))) stillOpen = true;
        c.check("the default case is closed and the ticket is active again", !stillOpen && !"DEFAULTED".equals(Json.text(after, "ticketStatus")), "ticket status " + Json.text(after, "ticketStatus"));
        c.ledgerInBalance("scenario B");
    }

    private static void payAll(Ctx c, String code, int from, int to) {
        for (int t = from; t <= to; t++) {
            BigDecimal out = Json.money(c.chit.statement(code, t), "totalOutstanding");
            if (out != null && out.signum() > 0) {
                String ref = "E2E-B-" + c.time.now() + "-" + t;
                c.chit.pay(code, t, out, ref, ref, OPS);
            }
        }
    }
}
