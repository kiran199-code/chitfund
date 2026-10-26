package com.chitfund.service.listing;

import com.chitfund.service.common.Actor;
import com.chitfund.service.listing.ListingRepository.OpenPlan;
import com.chitfund.service.listing.ListingService.ListingRequest;
import com.chitfund.service.listing.ListingService.ListingView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/chit")
@Tag(name = "Plan listing", description = "Groups open for enrolment and how they are shown on the customer portal")
public class ListingController {

    private final ListingService service;

    public ListingController(ListingService service) {
        this.service = service;
    }

    @GetMapping("/plans/open")
    @Operation(summary = "Groups open for enrolment, with plan category, branch, state and seats left")
    public List<OpenPlan> openPlans() {
        return service.openPlans();
    }

    @PutMapping("/groups/{code}/listing")
    @Operation(summary = "Set how a group is shown on the portal: planCategory (EDUCATION, HOME, WEDDING, SHORT_TERM, RETIREMENT, BUSINESS), branch, state. Blank clears.")
    public ListingView setListing(@PathVariable String code, @RequestBody(required = false) ListingRequest body,
                                  @RequestHeader(value = "X-Actor", required = false) String actor) {
        return service.set(code, body, Actor.orService(actor));
    }
}
