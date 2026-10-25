package com.chitfund.service.maintenance;

import com.chitfund.service.common.ChitException;
import com.chitfund.service.domain.rules.ChitRules;
import com.chitfund.service.domain.rules.RulesDefaults;
import com.chitfund.service.domain.rules.RulesValidator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SampleGroupsTest {

    private static final LocalDate START = LocalDate.of(2026, 12, 1);

    private static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    private static void refused(int count, String value, int tickets, LocalDate start) {
        ChitException e = assertThrows(ChitException.class, () -> SampleGroups.validate(count, d(value), tickets, start));
        assertEquals("VALIDATION_FAILED", e.code());
    }

    @Test
    void ordinaryInputsAreAccepted() {
        SampleGroups.validate(1, d("100000"), 20, START);
        SampleGroups.validate(20, d("500000.00"), 25, START);
        SampleGroups.validate(3, d("10000.50"), 5, START);
    }

    @Test
    void badInputsAreRefused() {
        refused(0, "100000", 20, START);
        refused(21, "100000", 20, START);
        refused(1, "100000", 1, START);
        refused(1, "100000", 101, START);
        refused(1, "0", 20, START);
        refused(1, "-5", 20, START);
        refused(1, "100000.123", 20, START);
        refused(1, "100000", 20, null);
        refused(1, "10000", 3, START); // 3333.33 x 3 is not 10000
    }

    @Test
    void theSubscriptionIsTheChitValueOverTheTickets() {
        assertEquals(d("5000.00"), SampleGroups.subscription(d("100000"), 20));
        assertEquals(d("2000.00"), SampleGroups.subscription(d("10000.00"), 5));
        assertEquals(d("2000.10"), SampleGroups.subscription(d("10000.50"), 5));
        assertNull(SampleGroups.subscription(d("10000"), 3));
    }

    @Test
    void theRulesAreValidAcrossTypicalSizes() {
        for (String value : List.of("10000", "100000", "250000.00", "1000000")) {
            for (int tickets : List.of(2, 5, 10, 20, 25, 40, 50)) {
                if (SampleGroups.subscription(d(value), tickets) == null) {
                    continue;
                }
                ChitRules r = RulesDefaults.fill(SampleGroups.rules(d(value), tickets, START));
                assertTrue(RulesValidator.validate(r).isEmpty(), value + " x " + tickets + " -> " + RulesValidator.validate(r));
                assertEquals(tickets, r.cycleCount());
                assertEquals(START, r.startDate());
            }
        }
    }

    @Test
    void codesCountOnFromTheHighestExistingOne() {
        assertEquals("SAMPLE-001", SampleGroups.code(1));
        assertEquals("SAMPLE-120", SampleGroups.code(120));
        assertEquals(1, SampleGroups.nextNumber(List.of()));
        assertEquals(1, SampleGroups.nextNumber(List.of("G-1", "ABC")));
        assertEquals(4, SampleGroups.nextNumber(List.of("SAMPLE-001", "SAMPLE-003", "G-1")));
        assertEquals(8, SampleGroups.nextNumber(List.of("SAMPLE-007", "SAMPLE-X")));
        assertTrue(SampleGroups.code(999).length() <= 30);
    }
}
