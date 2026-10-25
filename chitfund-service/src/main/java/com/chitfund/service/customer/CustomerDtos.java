package com.chitfund.service.customer;

import java.util.List;
import java.util.Map;

public final class CustomerDtos {
    private CustomerDtos() {
    }

    /** {@code existingCustomerId} (optional): an old customer id that has no e-mail yet; the e-mail is attached to it instead of making a new customer. */
    public record ResolveRequest(String email, String name, Long existingCustomerId) {
    }

    /** {@code created}: a new customer id was made. {@code linkedExisting}: the e-mail was attached to an already used customer id. Both false: the customer was already known. */
    public record CustomerView(long customerId, String email, String name, boolean created, boolean linkedExisting) {
    }

    public record LookupRequest(List<String> emails) {
    }

    /** Canonical e-mail to customer id, for the e-mails that are already customers. Others are left out. */
    public record LookupResult(Map<String, Long> customerIds) {
    }
}
