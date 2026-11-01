package com.chitfund.e2e;

import com.chitfund.e2e.Http.Resp;
import com.chitfund.e2e.Identities.Person;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Scenario C, bidding rules, driven by staff through the chit API: bids outside the band, lower and equal bids in an ascending auction, a repeated
 * request with the same key, a withdrawn winning bid, and a cycle with no bids (decided by the no-bid rule, a recorded draw).
 */
final class ScenarioC {
    private static final String OPS = "e2e-ops";
    private static final int TICKETS = 6;
    private ScenarioC() {
    }

    static void run(Ctx c) {
        RunOptions o = c.opt;
        c.report.scenario("C bidding rules");
        LocalDate start = c.time.now().withDayOfMonth(1).plusMonths(1);
        BigDecimal value = new BigDecimal("120000");
        String code = c.step("create the group", () -> c.newSampleGroup(value, TICKETS, start));
        c.chit.openEnrolment(code, OPS);
        List<Person> people = Identities.saversFor(o.runId() + "c", TICKETS);
        for (int i = 0; i < TICKETS; i++) {
            Person p = people.get(i);
            long id = c.chit.resolveCustomer(p.email(), p.name(), OPS).path("customerId").asLong();
            c.chit.enrol(code, id, "E2E-ACC-C" + (i + 1), c.chit.quote(code), OPS);
        }
        c.chit.activate(code, OPS);
        JsonNode cycles = c.chit.cycles(code);
        BigDecimal commissionPct = Json.money(c.chit.rules(code), "commissionPct");
        String noBid = Json.text(c.chit.rules(code), "noBidRule");

        // cycle 1: bids
        JsonNode c1 = cycles.get(0);
        c.time.jumpTo(Json.date(c1, "dueDate"));
        c.time.runJobsToday();
        payAll(c, code, 1, o.runId());
        c.time.jumpTo(Json.date(c1, "auctionDate"));
        c.require("auction 1 may open", Json.bool(c.chit.gate(code, 1), "open"), String.valueOf(c.chit.gate(code, 1)));
        c.chit.openAuction(code, 1, OPS);
        JsonNode av = c.chit.auction(code, 1);
        BigDecimal min = Json.money(av, "minDiscount"), max = Json.money(av, "maxDiscount");
        c.info("bid band", min + " to " + max + ", auction type " + Json.text(av, "auctionType"));
        List<BigDecimal> amounts = Money.ascendingBids(min, max, 3);
        c.require("the band has room for three bids", amounts.size() == 3, "bids " + amounts);

        c.refused("a bid below the minimum is refused", c.chit.bidRaw(code, 1, 1, min.subtract(BigDecimal.ONE), null, OPS));
        c.refused("a bid above the maximum is refused", c.chit.bidRaw(code, 1, 1, max.add(BigDecimal.ONE), null, OPS));
        String key = UUID.randomUUID().toString();
        Resp b1 = c.chit.bidRaw(code, 1, 1, amounts.get(0), key, OPS);
        c.require("first valid bid", b1.ok(), "HTTP " + b1.status() + " " + Http.snippet(b1.body()));
        Resp again = c.chit.bidRaw(code, 1, 1, amounts.get(0), key, OPS);
        c.check("repeating a bid with the same key returns the same bid", again.ok() && Json.integer(again.json(), "bidSeq", -1) == Json.integer(b1.json(), "bidSeq", -2),
                "seq " + Json.integer(b1.json(), "bidSeq", -2) + " then " + Json.integer(again.json(), "bidSeq", -1));
        c.check("the repeat did not add a bid", Json.integer(c.chit.auction(code, 1), "bidCount", -1) == 1, "bid count " + Json.integer(c.chit.auction(code, 1), "bidCount", -1));
        c.refused("an equal bid is refused", c.chit.bidRaw(code, 1, 2, amounts.get(0), null, OPS));
        Resp b2 = c.chit.bidRaw(code, 1, 2, amounts.get(1), null, OPS);
        c.require("higher bid from ticket 2", b2.ok(), "HTTP " + b2.status() + " " + Http.snippet(b2.body()));
        c.refused("a lower bid than the highest is refused", c.chit.bidRaw(code, 1, 3, amounts.get(0), null, OPS));
        Resp b3 = c.chit.bidRaw(code, 1, 3, amounts.get(2), null, OPS);
        c.require("highest bid from ticket 3", b3.ok(), "HTTP " + b3.status() + " " + Http.snippet(b3.body()));
        c.chit.withdrawBid(code, 1, Json.integer(b3.json(), "bidSeq", -1), OPS);
        c.check("after withdrawing the top bid the next one is highest", Money.same(Json.money(c.chit.auction(code, 1), "highestBid"), amounts.get(1)),
                "highest " + Json.money(c.chit.auction(code, 1), "highestBid") + ", expected " + amounts.get(1));
        JsonNode closed = c.chit.closeAuction(code, 1, OPS);
        c.check("the second bid wins", Json.integer(closed, "winnerTicketNo", -1) == 2, "winner " + Json.integer(closed, "winnerTicketNo", -1));
        JsonNode s = closed.path("settlement");
        c.check("prize = chit value - winning discount", Money.near(Json.money(s, "prize"), value.subtract(amounts.get(1))), Json.money(s, "prize") + " vs " + value.subtract(amounts.get(1)));
        c.check("commission = rule percent of the chit value", Money.near(Json.money(s, "commission"), Money.pct(value, commissionPct)), Json.money(s, "commission") + " vs " + Money.pct(value, commissionPct));
        payout(c, code, 1);

        // cycle 2: nobody bids
        JsonNode c2 = cycles.get(1);
        c.time.jumpTo(Json.date(c2, "dueDate"));
        c.time.runJobsToday();
        payAll(c, code, 2, o.runId());
        c.time.jumpTo(Json.date(c2, "auctionDate"));
        c.require("auction 2 may open", Json.bool(c.chit.gate(code, 2), "open"), String.valueOf(c.chit.gate(code, 2)));
        c.chit.openAuction(code, 2, OPS);
        JsonNode closed2 = c.chit.closeAuction(code, 2, OPS);
        Integer winner2 = closed2.hasNonNull("winnerTicketNo") ? closed2.path("winnerTicketNo").asInt() : null;
        c.info("no bids: rule " + noBid, "status " + Json.text(closed2, "status") + ", basis " + Json.text(closed2, "closeBasis") + ", winner " + winner2);
        if ("LOTTERY".equals(noBid)) {
            c.check("a cycle with no bids is decided by a recorded draw", closed2.hasNonNull("draw") && winner2 != null && winner2 != 2, "draw " + closed2.path("draw") + ", winner " + winner2);
            c.check("the draw is on record", c.chit.draws(code).size() >= 1, c.chit.draws(code).size() + " draw(s)");
        } else {
            c.warn("no-bid rule is " + noBid, "the lottery check was skipped");
        }
        c.ledgerInBalance("scenario C");
    }

    private static void payAll(Ctx c, String code, int cycle, String run) {
        JsonNode demands = c.chit.demands(code);
        for (JsonNode d : demands) {
            if (d.path("cycleNo").asInt() != cycle) continue;
            BigDecimal out = Json.money(d, "totalOutstanding");
            if (out != null && out.signum() > 0) {
                String ref = "E2E-" + run + "-C-" + cycle + "-" + d.path("ticketNo").asInt();
                c.chit.pay(code, d.path("ticketNo").asInt(), out, ref, ref, OPS);
            }
        }
    }

    private static void payout(Ctx c, String code, int n) {
        Resp req = c.chit.payoutRaw(code, n, "request", "e2e-maker");
        c.require("payout requested", req.ok(), "HTTP " + req.status() + " " + Http.snippet(req.body()));
        Resp ok = c.chit.payoutRaw(code, n, "approve", "e2e-checker");
        c.require("payout approved by a different person", ok.ok(), "HTTP " + ok.status() + " " + Http.snippet(ok.body()));
        c.chit.payout(code, n, "disburse", OPS);
        c.check("payout disbursed", "DISBURSED".equals(Json.text(c.chit.payoutView(code, n), "status")), Json.text(c.chit.payoutView(code, n), "status"));
    }
}
