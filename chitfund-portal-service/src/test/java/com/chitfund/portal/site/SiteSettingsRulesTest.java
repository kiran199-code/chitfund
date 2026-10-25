package com.chitfund.portal.site;

import com.chitfund.portal.common.PortalException;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SiteSettingsRulesTest {

    static String refused(Map<String, String> m) {
        try { SiteSettingsService.validate(m); } catch (PortalException e) { return e.code(); }
        return "NONE";
    }

    @Test
    void onlyKnownKeysAreAcceptedAndValuesAreTrimmed() {
        Map<String, String> m = new HashMap<>();
        m.put("help_phone", "  +91 99999 99999 ");
        m.put("help_hours", null);
        Map<String, String> clean = SiteSettingsService.validate(m);
        assertEquals("+91 99999 99999", clean.get("help_phone"));
        assertEquals("", clean.get("help_hours"), "blank clears");
        assertEquals("VALIDATION_FAILED", refused(Map.of("admin_password", "x")));
    }

    @Test
    void longValuesAreRefused() {
        assertEquals("VALIDATION_FAILED", refused(Map.of("help_phone", "1".repeat(31))));
        assertEquals("NONE", refused(Map.of("payment_instructions", "x".repeat(1000))));
        assertEquals("VALIDATION_FAILED", refused(Map.of("payment_instructions", "x".repeat(1001))));
    }
}
