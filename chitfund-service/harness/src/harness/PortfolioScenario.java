package harness;

import com.chitfund.service.auction.AuctionDtos.PlaceBidRequest;
import com.chitfund.service.collection.CollectionDtos.DuesPage;
import com.chitfund.service.domain.ForemanGuaranteeMode;
import com.chitfund.service.portfolio.PortfolioDtos.AuctionSummary;
import com.chitfund.service.portfolio.PortfolioDtos.RequestSummary;
import com.chitfund.service.portfolio.PortfolioRepository;
import com.chitfund.service.portfolio.PortfolioService;
import com.chitfund.service.transfer.TransferDtos.TransferRequest;

import java.time.LocalDate;
import java.util.List;

import static harness.Phase6Harness.*;

/** The customer-scoped reads the portal uses, against a real PostgreSQL: each customer sees only their own tickets, dues, payments, requests and auctions. */
final class PortfolioScenario {
    private PortfolioScenario() {
    }

    static final LocalDate D1 = LocalDate.of(2026, 11, 1);
    static final LocalDate D6 = LocalDate.of(2026, 11, 6);
    static final LocalDate D10 = LocalDate.of(2026, 11, 10);

    static AuctionSummary cycle(List<AuctionSummary> all, String code, int cycleNo) {
        return all.stream().filter(a -> a.groupCode().equals(code) && a.cycleNo() == cycleNo).findFirst().orElse(null);
    }

    static void run() {
        System.out.println("== portfolio: one customer's tickets, dues, payments, requests and auctions");
        String code = "H-PORT";
        long[] c = startGroup(code, ForemanGuaranteeMode.COVER_PAYOUT_FOREMAN_BEARS_LOSS, "250.00");
        PortfolioService pf = new PortfolioService(new PortfolioRepository(jdbc), auctionReadSvc);

        // tickets
        var t1 = tx(() -> pf.tickets(c[0]));
        eq("customer 1 holds one ticket", 1, t1.size());
        eq("ticket number", 1, t1.get(0).ticketNo());
        eq("group name is shown", "Harness " + code, t1.get(0).groupName());
        eq("ticket in good standing", "ACTIVE", t1.get(0).ticketStatus());
        eq("chit value", d("10000.00"), t1.get(0).chitValue());
        eq("nothing demanded yet", 0, t1.get(0).cyclesRaised());
        eq("a stranger has no tickets", 0, tx(() -> pf.tickets(9_999_999L)).size());
        refused("a customer id must be positive", "VALIDATION_FAILED", () -> pf.tickets(0));

        // auctions before anything opens: the first cycle is upcoming
        List<AuctionSummary> a0 = tx(() -> pf.auctions(c[1], D1));
        AuctionSummary up = cycle(a0, code, 1);
        check("cycle 1 is listed", up != null);
        eq("and is upcoming", "UPCOMING", up.state());
        eq("a customer sees which of their tickets exist", List.of(2), up.myTickets());
        eq("cannot bid before the auction opens", false, up.canBid());

        // demands and payments: tickets 1 to 4 pay, ticket 5 does not
        tx(() -> demands.raise(code, LocalDate.of(2026, 11, 5), "batch"));
        for (int t = 1; t <= 4; t++) pay(code, t, "2000.00", LocalDate.of(2026, 11, 5));
        var t1b = tx(() -> pf.tickets(c[0])).get(0);
        eq("one instalment raised", 1, t1b.cyclesRaised());
        eq("and paid", 1, t1b.cyclesPaid());
        var t5 = tx(() -> pf.tickets(c[4])).get(0);
        eq("ticket 5 raised", 1, t5.cyclesRaised());
        eq("ticket 5 paid none", 0, t5.cyclesPaid());

        DuesPage mine5 = tx(() -> collectionReadSvc.dues("OVERDUE", null, c[4], D6, 30, 100, 0));
        eq("customer 5 has one overdue instalment", 1, mine5.rows().size());
        eq("for ticket 5", 5, mine5.rows().get(0).ticketNo());
        eq("owing the instalment", d("2000.00"), mine5.rows().get(0).totalOutstanding());
        eq("customer 1 has nothing overdue", 0, tx(() -> collectionReadSvc.dues("OVERDUE", null, c[0], D6, 30, 100, 0)).rows().size());
        eq("without a customer filter the staff view still sees it", true,
                tx(() -> collectionReadSvc.dues("OVERDUE", null, null, D6, 30, 100, 0)).rows().stream().anyMatch(r -> r.ticketNo() == 5));
        DuesPage next = tx(() -> collectionReadSvc.dues("UPCOMING", null, c[0], D6, 40, 100, 0));
        check("customer 1 sees upcoming instalments", !next.rows().isEmpty());
        check("only for their own ticket", next.rows().stream().allMatch(r -> r.ticketNo() == 1 && r.customerId() == c[0]));

        // payments
        var p1 = tx(() -> pf.payments(c[0], 50, 0));
        eq("customer 1 has one payment", 1, p1.size());
        eq("for 2000", d("2000.00"), p1.get(0).amount());
        eq("customer 5 has none", 0, tx(() -> pf.payments(c[4], 50, 0)).size());

        // a live auction: others' bids are hidden, own bid shown
        tx(() -> auctions.open(code, 1, key(), "ops", D10));
        List<AuctionSummary> live2 = tx(() -> pf.auctions(c[1], D10));
        AuctionSummary l = cycle(live2, code, 1);
        eq("auction is live", "LIVE", l.state());
        eq("first in the list", live2.get(0).cycleNo(), 1);
        eq("customer 2 can bid", true, l.canBid());
        eq("with ticket 2", List.of(2), l.eligibleTickets());
        eq("no bids yet", 0, l.bidCount());
        tx(() -> auctions.placeBid(code, 1, new PlaceBidRequest(2, d("1000.00")), key(), "ops"));
        AuctionSummary mineBid = cycle(tx(() -> pf.auctions(c[1], D10)), code, 1);
        eq("customer 2 sees their bid", 1, mineBid.myBids().size());
        eq("and that they lead", true, mineBid.myBids().get(0).leading());
        AuctionSummary other = cycle(tx(() -> pf.auctions(c[2], D10)), code, 1);
        eq("customer 3 sees the best discount", d("1000.00"), other.highestBid());
        eq("but none of customer 2's bids", 0, other.myBids().size());
        eq("and no winner", null, other.winningTicketNo());
        check("a customer outside the group sees nothing of it", cycle(tx(() -> pf.auctions(++custSeq, D10)), code, 1) == null);

        // closed: the winner learns the prize, others only the discount
        tx(() -> auctions.close(code, 1, key(), "ops", D10, ALL_DAYS));
        AuctionSummary wonBy = cycle(tx(() -> pf.auctions(c[1], D10)), code, 1);
        eq("closed", "CLOSED", wonBy.state());
        eq("customer 2 won", true, wonBy.iWon());
        eq("their ticket", 2, wonBy.winningTicketNo());
        check("they see the prize", wonBy.prize() != null && wonBy.prize().signum() > 0);
        AuctionSummary lostBy = cycle(tx(() -> pf.auctions(c[2], D10)), code, 1);
        eq("customer 3 did not win", false, lostBy.iWon());
        eq("is not told who did", null, lostBy.winningTicketNo());
        eq("nor the prize", null, lostBy.prize());
        eq("but sees the winning discount", d("1000.00"), lostBy.winningDiscount());
        eq("the winner's ticket shows the prize", true, tx(() -> pf.tickets(c[1])).get(0).prizeWon());
        AuctionSummary nextCycle = cycle(tx(() -> pf.auctions(c[1], D10)), code, 2);
        check("the next cycle is listed as upcoming", nextCycle != null && "UPCOMING".equals(nextCycle.state()));
        eq("a ticket that has won cannot bid in the next one", false, nextCycle.canBid());

        // requests: a transfer and the prize payout
        long buyer = ++custSeq;
        tx(() -> transfers.requestTransfer(code, 3, new TransferRequest(buyer, "sold", "ACC-B"), key(), "maker", D10));
        List<RequestSummary> seller = tx(() -> pf.requests(c[2]));
        eq("the seller sees the transfer", 1, seller.size());
        eq("as outgoing", "OUTGOING", seller.get(0).role());
        eq("still in progress", true, seller.get(0).inProgress());
        List<RequestSummary> receiver = tx(() -> pf.requests(buyer));
        eq("the receiver sees it as incoming", "INCOMING", receiver.get(0).role());
        eq("another customer sees no transfer", 0, tx(() -> pf.requests(c[3])).size());
        tx(() -> payouts.request(code, 1, key(), "maker", D10));
        List<RequestSummary> winnerReqs = tx(() -> pf.requests(c[1]));
        eq("the winner sees the payout", "PRIZE_PAYOUT", winnerReqs.get(0).kind());
        eq("waiting for approval", "PENDING_APPROVAL", winnerReqs.get(0).status());
        tx(() -> payouts.approve(code, 1, key(), "checker", D10));
        tx(() -> payouts.disburse(code, 1, key(), "cashier", D10));
        RequestSummary paid = tx(() -> pf.requests(c[1])).get(0);
        eq("paid out", "DISBURSED", paid.status());
        eq("no longer in progress", false, paid.inProgress());
        ledgerIdentities("portfolio", code);
    }
}
