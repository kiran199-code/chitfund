package com.chitfund.service.auction;

import com.chitfund.service.auction.AuctionDtos.AuctionView;
import com.chitfund.service.auction.AuctionDtos.BidView;
import com.chitfund.service.auction.AuctionDtos.CoverView;
import com.chitfund.service.auction.AuctionDtos.DividendPayResult;
import com.chitfund.service.auction.AuctionDtos.DividendView;
import com.chitfund.service.auction.AuctionDtos.DrawView;
import com.chitfund.service.auction.AuctionDtos.FloatRepaymentRequest;
import com.chitfund.service.auction.AuctionDtos.GateView;
import com.chitfund.service.auction.AuctionDtos.PayoutView;
import com.chitfund.service.auction.AuctionDtos.PlaceBidRequest;
import com.chitfund.service.auction.AuctionDtos.PoolPositionView;
import com.chitfund.service.auction.AuctionDtos.RejectPayoutRequest;
import com.chitfund.service.auction.AuctionDtos.WithdrawBidRequest;
import com.chitfund.service.businessdate.BusinessDateService;
import com.chitfund.service.common.Actor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Auctions, prize payouts and dividends. Every call that changes something needs {@code Idempotency-Key} and {@code X-Actor};
 * the business date and the working-day calendar come from this service, never from the caller.
 */
@RestController
@RequestMapping("/api/v1/chit")
@Tag(name = "Auction", description = "Open and close the auction of a cycle, take bids, approve and disburse the prize, pay dividends")
public class AuctionController {

    private final AuctionService auctions;
    private final PayoutService payouts;
    private final DividendService dividends;
    private final AuctionReadService reads;
    private final BusinessDateService dates;

    public AuctionController(AuctionService auctions, PayoutService payouts, DividendService dividends, AuctionReadService reads, BusinessDateService dates) {
        this.auctions = auctions;
        this.payouts = payouts;
        this.dividends = dividends;
        this.reads = reads;
        this.dates = dates;
    }

    // ---- auction --------------------------------------------------------------------------------------------------

    @GetMapping("/groups/{code}/cycles/{cycleNo}/auction")
    @Operation(summary = "The auction of a cycle: bids, winner, settlement and draw (chit.auction.read)",
            description = "In an open SEALED_BID auction the discounts are hidden until it closes. NOT_OPENED if no auction was opened yet.")
    public AuctionView auction(@PathVariable String code, @PathVariable int cycleNo) {
        return reads.auction(code, cycleNo);
    }

    @GetMapping("/groups/{code}/cycles/{cycleNo}/auction/gate")
    @Operation(summary = "Whether the auction may open now, and why not (chit.auction.read)",
            description = "Applies the group's auction_quorum_rule to what has been collected. Changes nothing.")
    public GateView gate(@PathVariable String code, @PathVariable int cycleNo) {
        return reads.gate(code, cycleNo, dates.today());
    }

    @PostMapping("/groups/{code}/cycles/{cycleNo}/auction/open")
    @Operation(summary = "Open the auction of a cycle (chit.auction.open)",
            description = "409 AUCTION_GATE_CLOSED if the quorum rule is not met; with FOREMAN_COVERS_SHORTFALL the unpaid instalments are covered by the "
                    + "foreman and recorded as receivables. 409 AUCTION_NOT_DUE before the auction date. Needs Idempotency-Key and X-Actor.")
    public AuctionView open(@PathVariable String code, @PathVariable int cycleNo, @RequestHeader("Idempotency-Key") String idempotencyKey,
                            @RequestHeader(value = "X-Actor", required = false) String actor) {
        return auctions.open(code, cycleNo, idempotencyKey, Actor.required(actor), dates.today());
    }

    @PostMapping("/groups/{code}/cycles/{cycleNo}/auction/bids")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Record a bid on a member's behalf (chit.auction.bid)",
            description = "422 BID_OUT_OF_BOUNDS outside the discount limits, 422 BID_NOT_HIGHER in an ascending auction if it does not beat the highest, "
                    + "422 TICKET_NOT_ELIGIBLE for a ticket that has won or is not in good standing. The server records the time. Needs Idempotency-Key and X-Actor.")
    public BidView bid(@PathVariable String code, @PathVariable int cycleNo, @RequestBody PlaceBidRequest request,
                       @RequestHeader("Idempotency-Key") String idempotencyKey, @RequestHeader(value = "X-Actor", required = false) String actor) {
        return auctions.placeBid(code, cycleNo, request, idempotencyKey, Actor.required(actor));
    }

    @PostMapping("/groups/{code}/cycles/{cycleNo}/auction/bids/{bidSeq}/withdraw")
    @Operation(summary = "Withdraw a recorded bid while the auction is open (chit.auction.bid)", description = "A reason is required. Needs Idempotency-Key and X-Actor.")
    public BidView withdraw(@PathVariable String code, @PathVariable int cycleNo, @PathVariable int bidSeq, @RequestBody WithdrawBidRequest request,
                            @RequestHeader("Idempotency-Key") String idempotencyKey, @RequestHeader(value = "X-Actor", required = false) String actor) {
        return auctions.withdrawBid(code, cycleNo, bidSeq, request, idempotencyKey, Actor.required(actor));
    }

    @PostMapping("/groups/{code}/cycles/{cycleNo}/auction/close")
    @Operation(summary = "Close the auction: decide the winner and settle the pot (chit.auction.close)",
            description = "Highest discount wins, then the tie-break rule; LOTTERY and FIXED_ORDER use a recorded draw. With no bid the no_bid_rule applies "
                    + "(ROLL_OVER_CYCLE sends the cycle back to collection and moves the calendar). In the last cycle the one ticket left takes the whole pot. "
                    + "A second close of the same cycle is refused with 409 ILLEGAL_TRANSITION. Needs Idempotency-Key and X-Actor.")
    public AuctionView close(@PathVariable String code, @PathVariable int cycleNo, @RequestHeader("Idempotency-Key") String idempotencyKey,
                             @RequestHeader(value = "X-Actor", required = false) String actor) {
        return auctions.close(code, cycleNo, idempotencyKey, Actor.required(actor), dates.today(), dates.calendar());
    }

    // ---- payout ---------------------------------------------------------------------------------------------------

    @GetMapping("/groups/{code}/payouts")
    @Operation(summary = "Prize payouts of the group, by cycle (chit.payout.read)")
    public List<PayoutView> payouts(@PathVariable String code) {
        return reads.payouts(code);
    }

    @GetMapping("/groups/{code}/cycles/{cycleNo}/payout")
    @Operation(summary = "The prize payout of a cycle with its legs (chit.payout.read)")
    public PayoutView payout(@PathVariable String code, @PathVariable int cycleNo) {
        return reads.payout(code, cycleNo);
    }

    @PostMapping("/groups/{code}/cycles/{cycleNo}/payout/request")
    @Operation(summary = "Request the prize payout: the maker step (chit.payout.request)",
            description = "Checks security verified, no arrears, ticket in good standing and a payout account; applies payout_hold_pct. A rejected payout "
                    + "can be requested again. Needs Idempotency-Key and X-Actor.")
    public PayoutView request(@PathVariable String code, @PathVariable int cycleNo, @RequestHeader("Idempotency-Key") String idempotencyKey,
                              @RequestHeader(value = "X-Actor", required = false) String actor) {
        return payouts.request(code, cycleNo, idempotencyKey, Actor.required(actor), dates.today());
    }

    @PostMapping("/groups/{code}/cycles/{cycleNo}/payout/approve")
    @Operation(summary = "Approve the payout: the checker step (chit.payout.approve)",
            description = "403 MAKER_CHECKER_VIOLATION if the approver is the person who requested it and CHIT_MAKER_CHECKER_ENFORCE is on. Needs Idempotency-Key and X-Actor.")
    public PayoutView approve(@PathVariable String code, @PathVariable int cycleNo, @RequestHeader("Idempotency-Key") String idempotencyKey,
                              @RequestHeader(value = "X-Actor", required = false) String actor) {
        return payouts.approve(code, cycleNo, idempotencyKey, Actor.required(actor), dates.today());
    }

    @PostMapping("/groups/{code}/cycles/{cycleNo}/payout/reject")
    @Operation(summary = "Reject the payout request, with a reason (chit.payout.approve)", description = "Needs Idempotency-Key and X-Actor; same maker-checker rule as approve.")
    public PayoutView reject(@PathVariable String code, @PathVariable int cycleNo, @RequestBody RejectPayoutRequest request,
                             @RequestHeader("Idempotency-Key") String idempotencyKey, @RequestHeader(value = "X-Actor", required = false) String actor) {
        return payouts.reject(code, cycleNo, request, idempotencyKey, Actor.required(actor));
    }

    @PostMapping("/groups/{code}/cycles/{cycleNo}/payout/disburse")
    @Operation(summary = "Disburse the approved prize (chit.payout.disburse)",
            description = "Checks the pool's liquidity first. If it cannot pay: BLOCK_UNTIL_FUNDED answers 422 PAYOUT_LIQUIDITY_SHORT; FOREMAN_FLOATS has the "
                    + "foreman put in the difference and pays in full; PAY_AVAILABLE_AND_BALANCE_LATER pays what is available (call again for the balance). "
                    + "Posts the journals, marks the ticket PRIZED. Needs Idempotency-Key and X-Actor.")
    public PayoutView disburse(@PathVariable String code, @PathVariable int cycleNo, @RequestHeader("Idempotency-Key") String idempotencyKey,
                               @RequestHeader(value = "X-Actor", required = false) String actor) {
        return payouts.disburse(code, cycleNo, idempotencyKey, Actor.required(actor), dates.today());
    }

    @PostMapping("/groups/{code}/cycles/{cycleNo}/payout/float-repayments")
    @Operation(summary = "Repay the foreman's float from the pool (chit.payout.float)",
            description = "Only up to what is still owed and only from cash the pool holds beyond what it owes. Needs Idempotency-Key and X-Actor.")
    public PayoutView repayFloat(@PathVariable String code, @PathVariable int cycleNo, @RequestBody FloatRepaymentRequest request,
                                 @RequestHeader("Idempotency-Key") String idempotencyKey, @RequestHeader(value = "X-Actor", required = false) String actor) {
        return payouts.repayFloat(code, cycleNo, request, idempotencyKey, Actor.required(actor), dates.today());
    }

    // ---- dividends, draws, liquidity ------------------------------------------------------------------------------

    @PostMapping("/groups/{code}/cycles/{cycleNo}/dividends/pay")
    @Operation(summary = "Pay the cycle's declared dividends in cash, for dividend_mode PAY_OUT (chit.dividend.pay)",
            description = "Paid only if the pool can meet everything it owes afterwards (else 422 PAYOUT_LIQUIDITY_SHORT). Needs Idempotency-Key and X-Actor.")
    public DividendPayResult payDividends(@PathVariable String code, @PathVariable int cycleNo, @RequestHeader("Idempotency-Key") String idempotencyKey,
                                          @RequestHeader(value = "X-Actor", required = false) String actor) {
        return dividends.payOut(code, cycleNo, idempotencyKey, Actor.required(actor), dates.today());
    }

    @GetMapping("/groups/{code}/dividends")
    @Operation(summary = "Dividends declared in the group, by cycle; ticketNo narrows to one ticket (chit.dividend.read)")
    public List<DividendView> dividends(@PathVariable String code, @RequestParam(value = "ticketNo", required = false) Integer ticketNo) {
        return reads.dividends(code, ticketNo);
    }

    @GetMapping("/groups/{code}/draws")
    @Operation(summary = "Every draw made in the group: algorithm, seed, candidates and result, so each can be replayed (chit.auction.read)")
    public List<DrawView> draws(@PathVariable String code) {
        return reads.draws(code);
    }

    @GetMapping("/groups/{code}/foreman-covers")
    @Operation(summary = "Instalments the foreman covered at the auction gate and what has been recovered (chit.auction.read)")
    public List<CoverView> covers(@PathVariable String code) {
        return reads.covers(code);
    }

    @GetMapping("/groups/{code}/liquidity")
    @Operation(summary = "The pool's cash against what it owes; with cycleNo, what that cycle's prize would do under each liquidity rule (chit.payout.read)")
    public PoolPositionView liquidity(@PathVariable String code, @RequestParam(value = "cycleNo", required = false) Integer cycleNo) {
        return reads.liquidity(code, cycleNo);
    }
}
