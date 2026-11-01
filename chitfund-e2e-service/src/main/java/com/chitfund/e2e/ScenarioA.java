package com.chitfund.e2e;

import com.chitfund.e2e.Http.Resp;
import com.chitfund.e2e.Identities.Person;
import com.chitfund.e2e.PortalApi.Session;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Scenario A, the full cycle: mock Gmail customers register on the portal and ask for seats, staff approve and enrol them, the group is
 * activated, and then every cycle runs: instalments are paid (full, in two parts, over, and a repeated request), members bid from the portal,
 * the auction closes, the prize is paid out by a maker and a different checker, and the date moves on. At the end every ticket has won once.
 */
final class ScenarioA {
    private static final String OPS = "e2e-ops";
    private ScenarioA() {
    }

    static void run(Ctx c) {
        RunOptions o = c.opt;
        c.report.scenario("A full cycle");
        LocalDate bd = c.time.now();
        LocalDate start = bd.withDayOfMonth(1).plusMonths(1);
        int tickets = o.tickets();

        // 1. the group
        String code = c.step("create the group", () -> c.newSampleGroup(o.chitValue(), tickets, start));
        c.pass("group created", code + ": " + tickets + " tickets, " + o.chitValue().toPlainString() + " chit value, first cycle from " + start);
        c.chit.setListing(code, "SHORT_TERM", "E2E Branch", "Telangana", OPS);
        c.chit.openEnrolment(code, OPS);
        JsonNode rules = c.chit.rules(code);
        BigDecimal commissionPct = Json.money(rules, "commissionPct");
        c.info("rules used", "auction " + Json.text(rules, "auctionType") + ", dividend " + Json.text(rules, "dividendMode") + ", quorum " + Json.text(rules, "auctionQuorumRule")
                + ", no-bid " + Json.text(rules, "noBidRule") + ", commission " + commissionPct + "%, last cycle " + Json.text(rules, "lastCycleCommissionRule"));

        // 2. registration on the portal
        List<Person> savers = Identities.saversFor(o.runId(), o.customers());
        Person declined = new Person("e2e-sub-" + o.runId() + "-dec", "e2e." + o.runId() + ".declined@gmail.com", "E2E Declined");
        Person withdrawn = new Person("e2e-sub-" + o.runId() + "-wd", "e2e." + o.runId() + ".withdrawn@gmail.com", "E2E Withdrawn");
        waitForPlan(c, code, tickets);

        Map<String, Session> sessions = new HashMap<>();
        for (Person p : savers) sessions.put(p.email(), c.step("sign in " + p.name(), () -> c.portal.signIn(p)));
        Session decSession = c.step("sign in declined", () -> c.portal.signIn(declined));
        Session wdSession = c.step("sign in withdrawn", () -> c.portal.signIn(withdrawn));
        c.pass("signed in with mock Gmail identities", (savers.size() + 2) + " customers");

        int[] seats = Identities.seatPlan(tickets, savers.size());
        Map<String, Long> requestIds = new HashMap<>();
        for (int i = 0; i < savers.size(); i++) {
            Person p = savers.get(i);
            JsonNode r = c.step("seat request " + p.name(), () -> c.portal.requestSeat(sessions.get(p.email()), code, 0 + seatCount(seats, savers, p)));
            requestIds.put(p.email(), r.path("id").asLong());
            c.check("request pending: " + p.name(), "PENDING".equals(Json.text(r, "status")) && Json.integer(r, "tickets", -1) == seats[i],
                    "status " + Json.text(r, "status") + ", seats " + Json.integer(r, "tickets", -1));
        }
        JsonNode first = c.portal.requestSeat(sessions.get(savers.get(0).email()), code, seats[0]);
        c.check("asking again returns the same request", first.path("id").asLong() == requestIds.get(savers.get(0).email()), "id " + first.path("id").asLong());
        long decId = c.portal.requestSeat(decSession, code, 1).path("id").asLong();
        long wdId = c.portal.requestSeat(wdSession, code, 1).path("id").asLong();

        JsonNode pending = c.portal.adminJoinRequests("PENDING");
        int seen = 0;
        for (JsonNode r : pending) if (code.equals(Json.text(r, "groupCode")) && r.path("email").asText().contains(o.runId())) seen++;
        c.check("staff see every request", seen == savers.size() + 2, seen + " pending requests for this run");

        // 3. staff decisions
        for (Person p : savers) c.portal.decide(requestIds.get(p.email()), true, "e2e approve", "e2e-staff");
        c.portal.decide(decId, false, "e2e decline", "e2e-staff");
        c.portal.withdrawRequest(wdSession, wdId);
        c.check("member sees approval", "APPROVED".equals(statusOf(c.portal.myRequests(sessions.get(savers.get(0).email())), requestIds.get(savers.get(0).email()))), "first saver");
        c.check("member sees decline", "DECLINED".equals(statusOf(c.portal.myRequests(decSession), decId)), "declined customer");
        c.check("member sees withdrawal", "WITHDRAWN".equals(statusOf(c.portal.myRequests(wdSession), wdId)), "withdrawn customer");

        // 4. enrolment by staff (approval only records the decision)
        JsonNode quote = c.chit.quote(code);
        Map<Integer, Integer> ownerOf = new HashMap<>(); // ticket -> index in savers
        Map<Integer, List<Integer>> ticketsOf = new HashMap<>();
        for (int i = 0; i < savers.size(); i++) {
            Person p = savers.get(i);
            long customerId = c.chit.resolveCustomer(p.email(), p.name(), OPS).path("customerId").asLong();
            for (int k = 0; k < seats[i]; k++) {
                JsonNode t = c.chit.enrol(code, customerId, "E2E-ACC-" + (i + 1), quote, OPS);
                int no = t.path("ticketNo").asInt();
                ownerOf.put(no, i);
                ticketsOf.computeIfAbsent(i, x -> new ArrayList<>()).add(no);
            }
        }
        c.check("all tickets enrolled", ownerOf.size() == tickets, ownerOf.size() + " of " + tickets);
        c.refused("enrolling on a full group is refused", c.chit.enrolRaw(code, c.chit.resolveCustomer(savers.get(0).email(), savers.get(0).name(), OPS).path("customerId").asLong(), "E2E-ACC-X", OPS), 409, "GROUP_FULL");
        c.check("seats left is zero", Json.integer(c.chit.group(code), "seatsLeft", -1) == 0, "seatsLeft " + Json.integer(c.chit.group(code), "seatsLeft", -1));
        Person probe = savers.get(0);
        c.check("member sees own tickets only", ticketNumbers(c.portal.myTickets(sessions.get(probe.email()))).equals(new TreeSet<>(ticketsOf.get(0))),
                "expected " + ticketsOf.get(0) + ", portal shows " + ticketNumbers(c.portal.myTickets(sessions.get(probe.email()))));

        // 5. activation
        JsonNode check = c.chit.activationCheck(code);
        c.require("group is ready to activate", Json.bool(check, "ready"), "blockers: " + check.path("blockers"));
        c.chit.activate(code, OPS);
        JsonNode cycles = c.chit.cycles(code);
        c.require("one cycle per ticket", cycles.size() == tickets, cycles.size() + " cycles");
        boolean ascending = true;
        for (int i = 1; i < cycles.size(); i++) ascending &= Json.date(cycles.get(i), "dueDate").isAfter(Json.date(cycles.get(i - 1), "dueDate"));
        c.check("due dates ascend", ascending, "first " + Json.text(cycles.get(0), "dueDate") + ", last " + Json.text(cycles.get(cycles.size() - 1), "dueDate"));

        // 6. the cycles
        TreeSet<Integer> won = new TreeSet<>();
        Map<Integer, Integer> winnerOfCycle = new HashMap<>();
        Integer overpayTicket = null;
        for (JsonNode cyc : cycles) {
            int n = cyc.path("cycleNo").asInt();
            c.report.scenario("A cycle " + n + "/" + tickets);
            LocalDate due = Json.date(cyc, "dueDate");
            LocalDate auctionDate = Json.date(cyc, "auctionDate");
            c.time.jumpTo(due);
            c.time.runJobsToday();

            // demands and payments
            List<JsonNode> demands = new ArrayList<>();
            for (JsonNode d : c.chit.demands(code)) if (d.path("cycleNo").asInt() == n) demands.add(d);
            c.check("cycle " + n + ": one demand per ticket", demands.size() == tickets, demands.size() + " demands");
            if (n == 2 && overpayTicket != null) checkAdvanceUsed(c, demands, overpayTicket, winnerOfCycle.get(1));
            for (JsonNode d : demands) {
                int no = d.path("ticketNo").asInt();
                BigDecimal out = Json.money(d, "totalOutstanding");
                if (out == null || out.signum() == 0) continue;
                String ref = "E2E-" + o.runId() + "-" + n + "-" + no;
                if (n == 1 && no % 4 == 1 && tickets >= 4) {
                    BigDecimal part = Money.pct(out, new BigDecimal("60"));
                    c.chit.pay(code, no, part, ref + "-a", ref + "-a", OPS);
                    BigDecimal mid = Json.money(c.chit.statement(code, no), "totalOutstanding");
                    c.check("part payment leaves the balance (ticket " + no + ")", Money.near(mid, out.subtract(part)), "owed " + mid + ", expected " + out.subtract(part));
                    c.chit.pay(code, no, out.subtract(part), ref + "-b", ref + "-b", OPS);
                } else if (n == 1 && no == 3) {
                    BigDecimal extra = new BigDecimal("100");
                    c.chit.pay(code, no, out.add(extra), ref, ref, OPS);
                    BigDecimal adv = Json.money(c.chit.statement(code, no), "advanceHeld");
                    c.check("overpayment is held as advance (ticket 3)", Money.near(adv, extra), "advance " + adv);
                    overpayTicket = no;
                } else {
                    JsonNode pay = c.chit.pay(code, no, out, ref, ref, OPS);
                    if (n == 1 && no == 2) {
                        JsonNode again = c.chit.pay(code, no, out, ref, ref, OPS);
                        c.check("repeating a payment with the same key changes nothing", pay.path("paymentRef").asText().equals(again.path("paymentRef").asText()),
                                "same reference " + pay.path("paymentRef").asText());
                    }
                }
                BigDecimal left = Json.money(c.chit.statement(code, no), "totalOutstanding");
                c.check("cycle " + n + ": ticket " + no + " fully paid", left != null && left.signum() == 0, "owed " + left);
            }

            // auction
            c.time.jumpTo(auctionDate);
            JsonNode gate = c.chit.gate(code, n);
            boolean last = Json.bool(gate, "lastCycle");
            c.require("cycle " + n + ": auction may open", Json.bool(gate, "open"), "rule " + Json.text(gate, "rule") + ": " + Json.text(gate, "reason") + " " + Json.text(gate, "blocker"));
            c.chit.openAuction(code, n, OPS);
            JsonNode av = c.chit.auction(code, n);
            Integer expectedWinner = null;
            BigDecimal discount = null;
            if (!last) {
                BigDecimal min = Json.money(av, "minDiscount");
                BigDecimal max = Json.money(av, "maxDiscount");
                List<int[]> bidders = new ArrayList<>(); // personIndex, ticketNo
                for (int i = 0; i < savers.size() && bidders.size() < 4; i++) {
                    JsonNode mine = ownAuction(c.portal.myAuctions(sessions.get(savers.get(i).email())), code, n);
                    if (mine != null && Json.bool(mine, "canBid") && mine.path("eligibleTickets").size() > 0) {
                        bidders.add(new int[]{i, mine.path("eligibleTickets").get(0).asInt()});
                    }
                }
                c.check("cycle " + n + ": members see the auction and can bid", bidders.size() >= Math.min(2, savers.size()), bidders.size() + " eligible bidders");
                if (!bidders.isEmpty()) {
                    int[] b0 = bidders.get(0);
                    Session s0 = sessions.get(savers.get(b0[0]).email());
                    if (min.compareTo(BigDecimal.ONE) > 0) c.refused("cycle " + n + ": a bid below the minimum is refused", c.portal.bidRaw(s0, code, n, b0[1], min.subtract(BigDecimal.ONE)));
                    c.refused("cycle " + n + ": a bid above the maximum is refused", c.portal.bidRaw(s0, code, n, b0[1], max.add(BigDecimal.ONE)));
                    List<BigDecimal> amounts = Money.ascendingBids(min, max, bidders.size());
                    for (int j = 0; j < amounts.size(); j++) {
                        int[] b = bidders.get(j);
                        Resp r = c.portal.bidRaw(sessions.get(savers.get(b[0]).email()), code, n, b[1], amounts.get(j));
                        c.require("cycle " + n + ": bid " + amounts.get(j) + " on ticket " + b[1], r.ok(), "HTTP " + r.status() + " " + Http.snippet(r.body()));
                    }
                    int placed = amounts.size();
                    expectedWinner = bidders.get(placed - 1)[1];
                    discount = amounts.get(placed - 1);
                    JsonNode after = c.chit.auction(code, n);
                    c.check("cycle " + n + ": bids recorded", Json.integer(after, "bidCount", -1) == placed && Money.same(Json.money(after, "highestBid"), discount),
                            "count " + Json.integer(after, "bidCount", -1) + ", highest " + Json.money(after, "highestBid"));
                }
            }
            JsonNode closed = c.chit.closeAuction(code, n, OPS);
            Integer winner = closed.hasNonNull("winnerTicketNo") ? closed.path("winnerTicketNo").asInt() : null;
            c.require("cycle " + n + ": auction has a winner", winner != null, "status " + Json.text(closed, "status"));
            c.check("cycle " + n + ": winner not won before", !won.contains(winner), "ticket " + winner);
            if (expectedWinner != null) c.check("cycle " + n + ": highest bid wins", winner.equals(expectedWinner), "winner " + winner + ", expected " + expectedWinner);
            if (last) {
                TreeSet<Integer> left = new TreeSet<>(); for (int t = 1; t <= tickets; t++) left.add(t); left.removeAll(won);
                c.check("last cycle: the only ticket left wins", left.size() == 1 && left.contains(winner), "left " + left + ", winner " + winner);
            }
            won.add(winner);
            winnerOfCycle.put(n, winner);
            checkSettlement(c, n, closed.path("settlement"), o.chitValue(), commissionPct, last, discount);

            // payout: a maker, and a different checker
            Resp req = c.chit.payoutRaw(code, n, "request", "e2e-maker");
            c.require("cycle " + n + ": payout requested", req.ok(), "HTTP " + req.status() + " " + Http.snippet(req.body()));
            Resp self = c.chit.payoutRaw(code, n, "approve", "e2e-maker");
            if (self.status() == 403) {
                c.pass("cycle " + n + ": the maker cannot approve their own payout", "HTTP 403 " + Json.text(self.json(), "code"));
                Resp ok = c.chit.payoutRaw(code, n, "approve", "e2e-checker");
                c.require("cycle " + n + ": a different checker approves", ok.ok(), "HTTP " + ok.status() + " " + Http.snippet(ok.body()));
            } else if (self.ok()) {
                c.warn("cycle " + n + ": maker-checker is not enforced", "the maker approved their own payout (CHIT_MAKER_CHECKER_ENFORCE is off on chitfund-service)");
            } else {
                c.require("cycle " + n + ": payout approval", false, "HTTP " + self.status() + " " + Http.snippet(self.body()));
            }
            c.chit.payout(code, n, "disburse", OPS);
            JsonNode pv = c.chit.payoutView(code, n);
            c.check("cycle " + n + ": prize paid to the winning ticket", "DISBURSED".equals(Json.text(pv, "status")) && Json.integer(pv, "ticketNo", -1) == winner,
                    "status " + Json.text(pv, "status") + ", ticket " + Json.integer(pv, "ticketNo", -1) + ", gross " + Json.money(pv, "grossPrize"));
            Resp div = c.chit.payDividendsRaw(code, n, OPS);
            c.info("cycle " + n + ": dividends", div.ok() ? "paid " + Json.text(div.json(), "paid") + " totalling " + Json.text(div.json(), "total") : "HTTP " + div.status() + " " + Http.snippet(div.body()) + " (normal when dividends adjust the next instalment)");
            c.ledgerInBalance("cycle " + n);
        }

        // 7. the end
        c.report.scenario("A end of group");
        TreeSet<Integer> all = new TreeSet<>(); for (int t = 1; t <= tickets; t++) all.add(t);
        c.check("every ticket won exactly once", won.equals(all) && winnerOfCycle.size() == tickets, "winners " + won);
        int unpaid = 0;
        for (int t = 1; t <= tickets; t++) if (Json.money(c.chit.statement(code, t), "totalOutstanding").signum() != 0) unpaid++;
        c.check("nothing is left owing", unpaid == 0, unpaid + " tickets still owe");
        c.info("group status at the end", Json.text(c.chit.group(code), "status") + " (closure is not built yet, so this is recorded, not asserted)");
        c.ledgerInBalance("end");
    }

    private static int seatCount(int[] seats, List<Person> savers, Person p) {
        return seats[savers.indexOf(p)];
    }

    private static void waitForPlan(Ctx c, String code, int tickets) {
        for (int i = 0; i < 20; i++) {
            for (JsonNode p : c.portal.publicPlans()) {
                if (code.equals(Json.text(p, "groupCode"))) {
                    c.check("the plans page shows the group", Json.integer(p, "seatsLeft", -1) == tickets, "seats left " + Json.integer(p, "seatsLeft", -1) + ", category " + Json.text(p, "category"));
                    return;
                }
            }
            c.sleep(5_000); // the portal caches its plan list for about 30 seconds
        }
        c.require("the plans page shows the group", false, "not listed after about 100 seconds");
    }

    static String statusOf(JsonNode requests, long id) {
        for (JsonNode r : requests) if (r.path("id").asLong() == id) return Json.text(r, "status");
        return null;
    }

    static TreeSet<Integer> ticketNumbers(JsonNode tickets) {
        TreeSet<Integer> s = new TreeSet<>();
        for (JsonNode t : tickets) s.add(t.path("ticketNo").asInt());
        return s;
    }

    static JsonNode ownAuction(JsonNode auctions, String code, int cycleNo) {
        for (JsonNode a : auctions) if (code.equals(Json.text(a, "groupCode")) && Json.integer(a, "cycleNo", -1) == cycleNo) return a;
        return null;
    }

    static void checkSettlement(Ctx c, int n, JsonNode s, BigDecimal chitValue, BigDecimal commissionPct, boolean last, BigDecimal discount) {
        BigDecimal prize = Json.money(s, "prize"), commission = Json.money(s, "commission"), pool = Json.money(s, "dividendPool");
        if (prize == null) {
            c.fail("cycle " + n + ": settlement present", "the closed auction has no settlement");
            return;
        }
        c.check("cycle " + n + ": chit value = prize + commission + dividend pool", Money.near(chitValue, prize.add(commission).add(pool)), chitValue + " vs " + prize + " + " + commission + " + " + pool);
        c.check("cycle " + n + ": settlement balanced", Json.bool(s, "balanced"), "balanced=" + Json.bool(s, "balanced"));
        if (!last && discount != null) {
            c.check("cycle " + n + ": prize = chit value - discount", Money.near(prize, chitValue.subtract(discount)), prize + " vs " + chitValue.subtract(discount));
            BigDecimal expectedCommission = Money.pct(prize.add(discount), commissionPct); // commission is a percent of the chit value
            c.check("cycle " + n + ": commission = rule percent of the chit value", Money.near(commission, expectedCommission), commission + " vs " + expectedCommission);
            c.check("cycle " + n + ": dividend pool = discount - commission", Money.near(pool, discount.subtract(commission)), pool + " vs " + discount.subtract(commission));
        }
    }

    static void checkAdvanceUsed(Ctx c, List<JsonNode> demands, int overpayTicket, Integer cycle1Winner) {
        JsonNode over = null, reference = null;
        for (JsonNode d : demands) {
            int no = d.path("ticketNo").asInt();
            if (no == overpayTicket) over = d;
            else if (reference == null && no != 3 && no % 4 != 1 && (cycle1Winner == null || no != cycle1Winner)) reference = d;
        }
        if (over == null || reference == null || (cycle1Winner != null && cycle1Winner == overpayTicket)) {
            c.info("advance used in cycle 2", "not checked (the overpaying ticket won cycle 1, or no plain ticket to compare with)");
            return;
        }
        BigDecimal a = Json.money(over, "totalOutstanding"), b = Json.money(reference, "totalOutstanding");
        c.check("the held advance reduced the next instalment by 100", Money.near(a, b.subtract(new BigDecimal("100"))), "ticket " + overpayTicket + " owes " + a + ", a plain ticket owes " + b);
    }
}
