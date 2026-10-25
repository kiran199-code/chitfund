package com.chitfund.admin.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessPolicyTest {

    private static final List<String> OPS_ROLES = List.of("CHIT_OPS", "BATCH_OPS");
    private static final String[][] INQUIRIES = {
            {"GET", "/api/admin/me"}, {"GET", "/api/admin/business-date"}, {"GET", "/api/admin/calendar/holidays"},
            {"GET", "/api/admin/calendar/working-day"}, {"GET", "/api/admin/chit/groups"}, {"GET", "/api/admin/chit/groups/G1/tickets"},
            {"GET", "/api/admin/chit/dues"}, {"GET", "/api/admin/chit/groups/G1/cycles/2/auction"}, {"GET", "/api/admin/chit/groups/G1/cycles/2/payout"},
            {"GET", "/api/admin/chit/groups/G1/liquidity"}, {"GET", "/api/admin/chit/groups/G1/draws"}, {"GET", "/api/admin/chit/groups/G1/dividends"}, {"GET", "/api/admin/chit/groups/G1/payments/RCPT-1"}, {"GET", "/api/admin/chit/status"}};

    @Test
    void everyOperationsUserGetsEveryInquiry() {
        for (String role : OPS_ROLES) {
            for (String[] call : INQUIRIES) {
                assertTrue(AccessPolicy.allowed(call[0], call[1], List.of(role)), role + " " + call[1]);
            }
        }
    }

    @Test
    void adminCanDoEverythingIncludingUnlistedPaths() {
        assertTrue(AccessPolicy.allowed("POST", "/api/admin/maintenance/clear-data", List.of("ADMIN")));
        assertTrue(AccessPolicy.allowed("PUT", "/api/admin/calendar/business-date", List.of("ADMIN")));
        assertTrue(AccessPolicy.allowed("PUT", "/api/admin/something/new", List.of("ADMIN")));
    }

    @Test
    void chitUserChangesGroupsPaymentsAndHolidaysButNotBatchOrTheDate() {
        List<String> r = List.of("CHIT_OPS");
        assertTrue(AccessPolicy.allowed("POST", "/api/admin/chit/groups", r));
        assertTrue(AccessPolicy.allowed("POST", "/api/admin/chit/groups/G1/activate", r));
        assertTrue(AccessPolicy.allowed("POST", "/api/admin/chit/groups/G1/tickets/2/payments", r));
        assertTrue(AccessPolicy.allowed("POST", "/api/admin/chit/groups/G1/payments/RCPT-1/reverse", r));
        assertTrue(AccessPolicy.allowed("POST", "/api/admin/chit/batch/demand-run", r));
        assertTrue(AccessPolicy.allowed("PUT", "/api/admin/calendar/holidays/2026-11-14", r));
        assertTrue(AccessPolicy.allowed("DELETE", "/api/admin/calendar/holidays/2026-11-14", r));
        assertFalse(AccessPolicy.allowed("PUT", "/api/admin/calendar/business-date", r));
        assertFalse(AccessPolicy.allowed("POST", "/api/admin/eod/run", r));
        assertFalse(AccessPolicy.allowed("GET", "/api/admin/eod/status", r));
        assertFalse(AccessPolicy.allowed("GET", "/api/admin/batch/jobs", r));
        assertFalse(AccessPolicy.allowed("POST", "/api/admin/maintenance/clear-data", r));
    }

    @Test
    void auctionsAndPayoutsAreChangedByChitOperatorsOnly() {
        String[] writes = {"/api/admin/chit/groups/G1/cycles/2/auction/open", "/api/admin/chit/groups/G1/cycles/2/auction/bids",
                "/api/admin/chit/groups/G1/cycles/2/auction/bids/1/withdraw", "/api/admin/chit/groups/G1/cycles/2/auction/close",
                "/api/admin/chit/groups/G1/cycles/2/payout/request", "/api/admin/chit/groups/G1/cycles/2/payout/approve",
                "/api/admin/chit/groups/G1/cycles/2/payout/reject", "/api/admin/chit/groups/G1/cycles/2/payout/disburse",
                "/api/admin/chit/groups/G1/cycles/2/payout/float-repayments", "/api/admin/chit/groups/G1/cycles/2/dividends/pay"};
        for (String path : writes) {
            assertTrue(AccessPolicy.allowed("POST", path, List.of("CHIT_OPS")), path);
            assertTrue(AccessPolicy.allowed("POST", path, List.of("ADMIN")), path);
            assertFalse(AccessPolicy.allowed("POST", path, List.of("BATCH_OPS")), path);
        }
    }

    @Test
    void batchUserRunsEndOfDayAndTheDailyRunsOnly() {
        List<String> r = List.of("BATCH_OPS");
        assertTrue(AccessPolicy.allowed("GET", "/api/admin/batch/jobs", r));
        assertTrue(AccessPolicy.allowed("GET", "/api/admin/eod/status", r));
        assertTrue(AccessPolicy.allowed("POST", "/api/admin/eod/run", r));
        assertTrue(AccessPolicy.allowed("POST", "/api/admin/chit/batch/late-fee-run", r));
        assertTrue(AccessPolicy.allowed("POST", "/api/admin/chit/batch/default-run", r));
        assertFalse(AccessPolicy.allowed("POST", "/api/admin/chit/groups", r));
        assertFalse(AccessPolicy.allowed("POST", "/api/admin/chit/groups/G1/tickets/2/payments", r));
        assertFalse(AccessPolicy.allowed("PUT", "/api/admin/calendar/holidays/2026-11-14", r));
        assertFalse(AccessPolicy.allowed("PUT", "/api/admin/calendar/business-date", r));
        assertFalse(AccessPolicy.allowed("POST", "/api/admin/maintenance/clear-data", r));
    }

    @Test
    void maintenanceAndUnlistedPathsAreAdminOnly() {
        for (String role : OPS_ROLES) {
            List<String> r = List.of(role);
            assertFalse(AccessPolicy.allowed("GET", "/api/admin/maintenance/status", r), role);
            assertFalse(AccessPolicy.allowed("POST", "/api/admin/maintenance/clear-data", r), role);
            assertFalse(AccessPolicy.allowed("GET", "/api/admin/something/new", r), role);
        }
    }

    @Test
    void aWriteIsNeverOpenJustBecauseTheReadIs() {
        for (String role : OPS_ROLES) {
            assertFalse(AccessPolicy.allowed("POST", "/api/admin/business-date", List.of(role)), role);
            assertFalse(AccessPolicy.allowed("DELETE", "/api/admin/me", List.of(role)), role);
        }
    }

    @Test
    void usersExistOnlyWhenTheirPasswordIsSet() {
        var users = AdminUsers.build("admin", "pw", "chit-ops", "", "batch-ops", "x");
        assertEquals(2, users.size());
        assertEquals("admin", users.get(0).username());
        assertEquals("ADMIN", users.get(0).role());
        assertEquals("batch-ops", users.get(1).username());
        assertEquals("BATCH_OPS", users.get(1).role());
        var all = AdminUsers.build("admin", "pw", "c", "1", "b", "2");
        assertEquals(3, all.size());
        assertEquals("CHIT_OPS", all.get(1).role());
    }

    @Test
    void duplicateOrBlankNamesAreRefused() {
        assertThrows(IllegalStateException.class, () -> AdminUsers.build("admin", "pw", "admin", "1", "b", ""));
        assertThrows(IllegalStateException.class, () -> AdminUsers.build("admin", "pw", " ", "1", "b", ""));
    }

    @Test
    void invitationsAreForChitUsersAndAdminOnly() {
        for (String[] call : new String[][]{{"GET", "/api/admin/portal/groups/G1/invitations"}, {"POST", "/api/admin/portal/groups/G1/invitations"},
                {"POST", "/api/admin/portal/groups/G1/invitations/4/enrol"}, {"GET", "/api/admin/portal/status"}}) {
            assertTrue(AccessPolicy.allowed(call[0], call[1], List.of("CHIT_OPS")), "chit ops " + call[1]);
            assertTrue(AccessPolicy.allowed(call[0], call[1], List.of("ADMIN")), "admin " + call[1]);
            assertFalse(AccessPolicy.allowed(call[0], call[1], List.of("BATCH_OPS")), "batch ops " + call[1]);
        }
    }
}
