package com.chitfund.service.maintenance;

import com.chitfund.service.domain.rules.ChitRules;
import com.chitfund.service.domain.rules.RulesDefaults;
import com.chitfund.service.domain.rules.RulesValidator;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SamplePlansTest {

    private static final LocalDate START = LocalDate.of(2026, 12, 1);

    @Test
    void twoOfEachOfTheSixTypes() {
        assertEquals(12, SamplePlans.ALL.size());
        for (String c : SamplePlans.CATEGORIES) {
            assertEquals(2, SamplePlans.ALL.stream().filter(p -> p.category().equals(c)).count(), c);
        }
    }

    @Test
    void everyPlanMakesValidRules() {
        for (SamplePlans.Plan p : SamplePlans.ALL) {
            assertNotNull(SampleGroups.subscription(p.chitValue(), p.tickets()), SamplePlans.name(p));
            ChitRules r = RulesDefaults.fill(SampleGroups.rules(p.chitValue(), p.tickets(), START));
            assertTrue(RulesValidator.validate(r).isEmpty(), SamplePlans.name(p) + " -> " + RulesValidator.validate(r));
        }
    }

    @Test
    void namesAreReadable() {
        assertEquals("Education Plan 10 lakh, Siddipet", SamplePlans.name(SamplePlans.ALL.get(0)));
        assertTrue(SamplePlans.ALL.stream().allMatch(p -> SamplePlans.name(p).length() <= 120));
    }
}
