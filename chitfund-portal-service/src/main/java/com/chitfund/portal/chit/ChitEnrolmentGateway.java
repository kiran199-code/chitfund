package com.chitfund.portal.chit;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** The portal's way of making a person a customer and enrolling tickets in chitfund-service. An interface so tests use a fake. */
public interface ChitEnrolmentGateway {

    /** What one ticket of the group owes at enrolment. {@code selfService()}: nothing to pay or provide, so accepting can enrol straight away. */
    record Quote(BigDecimal enrolmentFee, BigDecimal registrationFee, BigDecimal securityDeposit, BigDecimal lienAmount, boolean guarantorRequired,
                 BigDecimal cashDue) {
        public boolean selfService() {
            return cashDue.signum() == 0 && lienAmount.signum() == 0 && !guarantorRequired;
        }
    }

    /** The customer for an e-mail. {@code created}: a new id was made. {@code linkedExisting}: the e-mail was attached to an already used id. */
    record CustomerRef(long customerId, boolean created, boolean linkedExisting) {
    }

    /** What was received for one ticket. Amounts null count as zero. */
    record Received(BigDecimal enrolmentFee, BigDecimal registrationFee, BigDecimal securityDeposit, Long guarantorCustomerId, String lienReference,
                    String payoutAccountRef) {
        public static Received nothing() {
            return new Received(null, null, null, null, null, null);
        }
    }

    Quote quote(String groupCode);

    CustomerRef resolveCustomer(String email, String name, Long existingCustomerId);

    /** Canonical e-mail to customer id for the e-mails that are already customers. */
    Map<String, Long> lookupCustomers(List<String> emails);

    /** Enrols one ticket; the same idempotency key returns the same ticket. Returns the ticket number. */
    int enrol(String groupCode, long customerId, Received received, String idempotencyKey);
}
