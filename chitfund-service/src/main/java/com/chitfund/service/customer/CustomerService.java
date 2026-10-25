package com.chitfund.service.customer;

import com.chitfund.service.common.Audit;
import com.chitfund.service.common.ChitException;
import com.chitfund.service.common.Json;
import com.chitfund.service.customer.CustomerDtos.CustomerView;
import com.chitfund.service.customer.CustomerDtos.LookupResult;
import com.chitfund.service.customer.CustomerDtos.ResolveRequest;
import com.chitfund.service.customer.CustomerRepository.CustomerRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The customer master. The e-mail address (canonical form, see {@link CustomerEmails}) is the unique key; the customer id is internal.
 * {@link #resolve} is how a person becomes a customer: it finds the customer by e-mail, or attaches the e-mail to an existing customer id the
 * caller names, or makes a new customer with a fresh id. It is safe to call again with the same input: the second call finds the customer.
 */
@Service
public class CustomerService {

    private static final int MAX_LOOKUP = 500;

    private final CustomerRepository repo;
    private final Audit audit;

    public CustomerService(CustomerRepository repo, Audit audit) {
        this.repo = repo;
        this.audit = audit;
    }

    @Transactional
    public CustomerView resolve(ResolveRequest req, String actor) {
        if (req == null) {
            throw ChitException.badRequest("VALIDATION_FAILED", "A request body is required");
        }
        String email = CustomerEmails.normalise(req.email());
        if (email == null) {
            throw ChitException.badRequest("VALIDATION_FAILED", "email is not a valid address");
        }
        String name = cleanName(req.name());
        Long existing = req.existingCustomerId();
        if (existing != null && existing <= 0) {
            throw ChitException.badRequest("VALIDATION_FAILED", "existingCustomerId must be a positive number");
        }

        CustomerRow found = repo.byEmail(email).orElse(null);
        if (found != null) {
            if (existing != null && existing != found.id()) {
                throw ChitException.conflict("EMAIL_CUSTOMER_MISMATCH", "The e-mail " + email + " already belongs to customer " + found.id()
                        + ", not " + existing);
            }
            if (name != null && found.displayName() == null) {
                repo.setNameIfMissing(found.id(), name);
            }
            return new CustomerView(found.id(), email, found.displayName() != null ? found.displayName() : name, false, false);
        }

        if (existing != null) {
            if (repo.byId(existing).isPresent()) {
                throw ChitException.conflict("CUSTOMER_EMAIL_TAKEN", "Customer " + existing + " already has a different e-mail address");
            }
            if (!repo.idInUse(existing)) {
                throw new ChitException("CUSTOMER_UNKNOWN", "No customer with id " + existing + " is known (it has no ticket, transfer or guarantee)");
            }
            if (!repo.insert(existing, email, name, "LEGACY_LINK", actor)) {
                return resolve(new ResolveRequest(email, name, existing), actor); // someone attached this e-mail a moment ago
            }
            audit.log("CUSTOMER", String.valueOf(existing), "EMAIL_LINKED", actor, email);
            audit.event("CustomerEmailLinked", "CUSTOMER", String.valueOf(existing), Json.obj("customerId", existing, "actor", actor));
            return new CustomerView(existing, email, name, false, true);
        }

        long id = repo.allocateId();
        if (!repo.insert(id, email, name, "INVITATION", actor)) {
            return resolve(new ResolveRequest(email, name, null), actor); // a concurrent call created this customer
        }
        audit.log("CUSTOMER", String.valueOf(id), "CREATED", actor, email);
        audit.event("CustomerCreated", "CUSTOMER", String.valueOf(id), Json.obj("customerId", id, "actor", actor));
        return new CustomerView(id, email, name, true, false);
    }

    /** The customer ids of the e-mails that are already customers, keyed by canonical e-mail. Invalid e-mails are ignored. */
    @Transactional(readOnly = true)
    public LookupResult lookup(List<String> emails) {
        if (emails == null || emails.size() > MAX_LOOKUP) {
            throw ChitException.badRequest("VALIDATION_FAILED", "emails must list between 0 and " + MAX_LOOKUP + " addresses");
        }
        Map<String, Long> out = new LinkedHashMap<>();
        for (String raw : emails) {
            String e = CustomerEmails.normalise(raw);
            if (e != null && !out.containsKey(e)) {
                repo.byEmail(e).ifPresent(c -> out.put(e, c.id()));
            }
        }
        return new LookupResult(out);
    }

    @Transactional(readOnly = true)
    public CustomerView get(long id) {
        CustomerRow c = repo.byId(id).orElseThrow(() -> ChitException.notFound("Customer", String.valueOf(id)));
        return new CustomerView(c.id(), c.email(), c.displayName(), false, false);
    }

    private static String cleanName(String n) {
        if (n == null || n.isBlank()) {
            return null;
        }
        String t = n.trim();
        if (t.length() > 120) {
            throw ChitException.badRequest("VALIDATION_FAILED", "name is longer than 120 characters");
        }
        return t;
    }
}
