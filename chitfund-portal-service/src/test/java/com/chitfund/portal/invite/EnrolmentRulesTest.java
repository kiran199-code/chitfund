package com.chitfund.portal.invite;

import com.chitfund.portal.chit.ChitEnrolmentGateway.Quote;
import com.chitfund.portal.config.ErrorStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class EnrolmentRulesTest {

    private static Quote quote(String cash, String lien, boolean guarantor) {
        BigDecimal z = BigDecimal.ZERO;
        return new Quote(z, z, z, new BigDecimal(lien), guarantor, new BigDecimal(cash));
    }

    @Test
    void acceptingEnrolsStraightAwayOnlyWhenNothingIsDue() {
        assertTrue(quote("0", "0", false).selfService());
        assertTrue(quote("0.00", "0.00", false).selfService(), "scale does not matter");
        assertFalse(quote("1", "0", false).selfService(), "cash due");
        assertFalse(quote("0", "50000", false).selfService(), "a lien needs papers");
        assertFalse(quote("0", "0", true).selfService(), "a guarantor needs papers");
    }

    @Test
    void everyTicketHasItsOwnStableIdempotencyKey() {
        assertEquals("PORTAL-ENROL:12:1", EnrolmentFlow.ticketKey(12, 1));
        assertEquals("PORTAL-ENROL:12:2", EnrolmentFlow.ticketKey(12, 2));
        assertNotEquals(EnrolmentFlow.ticketKey(12, 1), EnrolmentFlow.ticketKey(13, 1));
    }

    @Test
    void enrolmentRefusalsHaveHttpStatuses() {
        assertEquals(409, ErrorStatus.of("INVITE_NOT_ACCEPTABLE"));
        assertEquals(409, ErrorStatus.of("INVITE_NOT_AWAITING_PAYMENT"));
        assertEquals(409, ErrorStatus.of("ENROLMENT_NOT_OPEN"));
        assertEquals(409, ErrorStatus.of("EMAIL_CUSTOMER_MISMATCH"));
        assertEquals(409, ErrorStatus.of("CUSTOMER_EMAIL_TAKEN"));
        assertEquals(404, ErrorStatus.of("CUSTOMER_UNKNOWN"));
    }
}
