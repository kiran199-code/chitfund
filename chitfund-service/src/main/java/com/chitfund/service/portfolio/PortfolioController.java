package com.chitfund.service.portfolio;

import com.chitfund.service.businessdate.BusinessDateSource;
import com.chitfund.service.portfolio.PortfolioDtos.AuctionSummary;
import com.chitfund.service.portfolio.PortfolioDtos.PaymentSummary;
import com.chitfund.service.portfolio.PortfolioDtos.RequestSummary;
import com.chitfund.service.portfolio.PortfolioDtos.TicketSummary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** What one customer sees of their own chit funds (chit.customer.portfolio.read). Used by the customer portal. */
@RestController
@RequestMapping("/api/v1/chit/customers/{customerId}")
@Tag(name = "Customer portfolio", description = "Tickets, payments, requests and auctions of one customer, across groups")
public class PortfolioController {

    private final PortfolioService service;
    private final BusinessDateSource dates;

    public PortfolioController(PortfolioService service, BusinessDateSource dates) {
        this.service = service;
        this.dates = dates;
    }

    @GetMapping("/tickets")
    @Operation(summary = "The customer's tickets across groups, with progress")
    public List<TicketSummary> tickets(@PathVariable long customerId) {
        return service.tickets(customerId);
    }

    @GetMapping("/payments")
    @Operation(summary = "The customer's payments across groups, newest first")
    public List<PaymentSummary> payments(@PathVariable long customerId, @RequestParam(value = "limit", defaultValue = "50") int limit,
                                         @RequestParam(value = "offset", defaultValue = "0") int offset) {
        return service.payments(customerId, limit, offset);
    }

    @GetMapping("/requests")
    @Operation(summary = "Ticket transfers and prize payouts involving the customer, those in progress first")
    public List<RequestSummary> requests(@PathVariable long customerId) {
        return service.requests(customerId);
    }

    @GetMapping("/auctions")
    @Operation(summary = "Live, upcoming and recently closed auctions of the groups the customer holds tickets in",
            description = "Other bidders are never shown: only the best discount of an open auction and the customer's own bids.")
    public List<AuctionSummary> auctions(@PathVariable long customerId) {
        return service.auctions(customerId, dates.today());
    }
}
