package com.chitfund.service.customer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CustomerEmailsTest {

    @Test
    void addressesAreTrimmedAndLowerCased() {
        assertEquals("kiran.k@example.com", CustomerEmails.normalise("  Kiran.K@Example.COM "));
    }

    @Test
    void gmailFollowsGooglesRule() {
        assertEquals("ab@gmail.com", CustomerEmails.normalise("A.B+Tag@gmail.com"));
        assertEquals("ab@gmail.com", CustomerEmails.normalise("a.b@googlemail.com"));
        assertEquals("ab@gmail.com", CustomerEmails.normalise("ab@gmail.com"));
        assertNull(CustomerEmails.normalise("+only@gmail.com"));
    }

    @Test
    void otherDomainsKeepDotsAndTags() {
        assertEquals("a.b+tag@example.com", CustomerEmails.normalise("A.B+Tag@example.com"));
        assertEquals("a.b@corp-gmail.com", CustomerEmails.normalise("a.b@corp-gmail.com"));
    }

    @Test
    void implausibleAddressesAreRefused() {
        for (String bad : new String[]{null, "", "nope", "a@b", "a b@x.com", "a@@x.com", "a..b@x.com", "<a@x.com>", "a@x.c"}) {
            assertNull(CustomerEmails.normalise(bad), String.valueOf(bad));
        }
    }

    @Test
    void theSameAnswerAsThePortalService() {
        // These pairs are asserted identically in the portal service's InviteRulesTest (Emails.normalise).
        assertEquals("ab@gmail.com", CustomerEmails.normalise("A.B+Tag@gmail.com"));
        assertEquals("a.b+tag@example.com", CustomerEmails.normalise("A.B+Tag@example.com"));
    }
}
