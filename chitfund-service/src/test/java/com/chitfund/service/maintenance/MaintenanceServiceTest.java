package com.chitfund.service.maintenance;

import com.chitfund.service.common.ChitException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The two refusals happen before the database is touched, so no database is needed (the template is null on purpose). */
class MaintenanceServiceTest {

    @Test
    void clearingIsRefusedWhileTheSwitchIsOff() {
        MaintenanceService off = new MaintenanceService(null, null, null, false);
        ChitException e = assertThrows(ChitException.class, () -> off.clearAll("RESET", "admin"));
        assertEquals("RESET_DISABLED", e.code());
    }

    @Test
    void clearingNeedsTheConfirmationWord() {
        MaintenanceService on = new MaintenanceService(null, null, null, true);
        assertEquals("CONFIRMATION_MISSING", assertThrows(ChitException.class, () -> on.clearAll(null, "admin")).code());
        assertEquals("CONFIRMATION_MISSING", assertThrows(ChitException.class, () -> on.clearAll("reset", "admin")).code());
        assertEquals("CONFIRMATION_MISSING", assertThrows(ChitException.class, () -> on.clearAll("yes", "admin")).code());
    }

    @Test
    void sampleGroupsAreRefusedWhileTheSwitchIsOff() {
        MaintenanceService off = new MaintenanceService(null, null, null, false);
        ChitException e = assertThrows(ChitException.class, () -> off.createSampleGroups(
                new MaintenanceDtos.SampleGroupsRequest(1, new java.math.BigDecimal("100000"), 20, java.time.LocalDate.of(2026, 12, 1)), "admin"));
        assertEquals("RESET_DISABLED", e.code());
    }

    @Test
    void sampleGroupsNeedAllFourInputs() {
        MaintenanceService on = new MaintenanceService(null, null, null, true);
        assertEquals("VALIDATION_FAILED", assertThrows(ChitException.class, () -> on.createSampleGroups(null, "admin")).code());
        assertEquals("VALIDATION_FAILED", assertThrows(ChitException.class, () -> on.createSampleGroups(
                new MaintenanceDtos.SampleGroupsRequest(null, new java.math.BigDecimal("100000"), 20, java.time.LocalDate.of(2026, 12, 1)), "admin")).code());
        assertEquals("VALIDATION_FAILED", assertThrows(ChitException.class, () -> on.createSampleGroups(
                new MaintenanceDtos.SampleGroupsRequest(1, new java.math.BigDecimal("100000"), 20, null), "admin")).code());
    }
}
