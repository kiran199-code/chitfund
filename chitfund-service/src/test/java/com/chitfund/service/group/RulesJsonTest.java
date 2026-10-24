package com.chitfund.service.group;

import com.chitfund.service.domain.AllocationBucket;
import com.chitfund.service.domain.SecurityRule;
import com.chitfund.service.domain.TestRules;
import com.chitfund.service.domain.rules.ChitRules;
import com.chitfund.service.domain.rules.DefaultThreshold;
import com.chitfund.service.domain.rules.LateFeeRule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The stored snapshot must read back as exactly the rules that were written, or a frozen version would drift. */
class RulesJsonTest {

    @Test
    void everyFieldSurvivesAWriteAndRead() {
        ChitRules rules = TestRules.baseline()
                .enrolmentFee(new BigDecimal("500.00"))
                .registrationFee(new BigDecimal("250.50"))
                .securityRule(SecurityRule.DEPOSIT)
                .securityDepositPct(new BigDecimal("10.00"))
                .graceDays(3)
                .lateFeeRule(LateFeeRule.percentPerDay(new BigDecimal("0.10"), new BigDecimal("500.00"), new BigDecimal("5")))
                .defaultThreshold(new DefaultThreshold(2, 45))
                .allocationOrder(List.of(AllocationBucket.ARREARS, AllocationBucket.LATE_FEES, AllocationBucket.CURRENT))
                .defaulterLosesDividend(Boolean.FALSE)
                .build();
        ChitRules back = RulesJson.read(RulesJson.write(rules));
        assertEquals(rules, back);
    }

    @Test
    void datesAreWrittenAsIsoText() {
        String json = RulesJson.write(TestRules.valid());
        assertTrue(json.contains("\"startDate\":\"2026-11-01\""), json);
    }

    @Test
    void anUnknownFieldInAnOlderOrNewerSnapshotIsIgnored() {
        String json = RulesJson.write(TestRules.valid());
        String extended = json.substring(0, json.length() - 1) + ",\"someFutureRule\":true}";
        assertEquals(TestRules.valid(), RulesJson.read(extended));
    }
}
