package com.chitfund.service.customer;

import com.chitfund.service.common.Actor;
import com.chitfund.service.customer.CustomerDtos.CustomerView;
import com.chitfund.service.customer.CustomerDtos.LookupRequest;
import com.chitfund.service.customer.CustomerDtos.LookupResult;
import com.chitfund.service.customer.CustomerDtos.ResolveRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/chit/customers")
@Tag(name = "Customers", description = "Customer master: e-mail is the unique key, the customer id is internal")
public class CustomerController {

    private final CustomerService service;

    public CustomerController(CustomerService service) {
        this.service = service;
    }

    @PostMapping("/resolve")
    @Operation(summary = "Find the customer for an e-mail, or create one (chit.customer.resolve)",
            description = "Body {email, name?, existingCustomerId?}. Finds the customer by e-mail; or, when existingCustomerId names an old customer "
                    + "that has no e-mail yet, attaches the e-mail to it; or makes a new customer with a fresh id. Calling again with the same input "
                    + "returns the same customer. 409 EMAIL_CUSTOMER_MISMATCH / CUSTOMER_EMAIL_TAKEN when the e-mail and id belong to different customers.")
    public CustomerView resolve(@RequestBody ResolveRequest request, @RequestHeader(value = "X-Actor", required = false) String actor) {
        return service.resolve(request, Actor.orService(actor));
    }

    @PostMapping("/lookup")
    @Operation(summary = "Customer ids for a list of e-mails that are already customers (chit.customer.read)",
            description = "Body {emails: [...]} up to 500. The answer is keyed by canonical e-mail; unknown e-mails are left out.")
    public LookupResult lookup(@RequestBody LookupRequest request) {
        return service.lookup(request == null ? null : request.emails());
    }

    @GetMapping("/{id}")
    @Operation(summary = "One customer by id (chit.customer.read)")
    public CustomerView get(@PathVariable long id) {
        return service.get(id);
    }
}
