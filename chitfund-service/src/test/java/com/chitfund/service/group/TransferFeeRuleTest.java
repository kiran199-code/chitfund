package com.chitfund.service.group;

import com.chitfund.service.common.Money;
import com.chitfund.service.domain.TestRules;
import com.chitfund.service.domain.rules.ChitRules;
import com.chitfund.service.domain.rules.RulesDefaults;
import com.chitfund.service.domain.rules.RulesValidator;
import com.chitfund.service.common.RuleViolation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The transfer fee rule added in Phase 6: default 0, survives the snapshot, and an old snapshot without it still reads. */
class TransferFeeRuleTest {

    @Test
    void theFeeDefaultsToZero() {
        assertEquals(0, BigDecimal.ZERO.compareTo(TestRules.valid().transferFee()));
    }

    @Test
    void aFeeSurvivesAWriteAndRead() {
        ChitRules rules = TestRules.baseline().transferFee(new BigDecimal("250.00")).build();
        assertEquals(rules, RulesJson.read(RulesJson.write(rules)));
    }

    @Test
    void aSnapshotFrozenBeforePhase6ReadsWithoutTheFeeAndIsTreatedAsZero() {
        String json = RulesJson.write(TestRules.valid()).replaceAll(",\"transferFee\":-?[0-9.]+", "");
        assertTrue(!json.contains("transferFee"), json);
        ChitRules old = RulesJson.read(json);
        assertNull(old.transferFee());
        assertEquals(0, BigDecimal.ZERO.compareTo(Money.nz(old.transferFee())));
        // and a rules object filled with defaults has the zero in place
        assertEquals(0, BigDecimal.ZERO.compareTo(RulesDefaults.fill(old).transferFee()));
    }

    @Test
    void aNegativeFeeIsRejected() {
        List<RuleViolation> v = RulesValidator.validate(TestRules.baseline().transferFee(new BigDecimal("-1")).build());
        assertTrue(v.stream().anyMatch(x -> x.code().equals("FEE_NEGATIVE") && x.field().equals("transferFee")), v.toString());
    }

    @Test
    void aZeroOrPositiveFeeIsAccepted() {
        assertTrue(RulesValidator.validate(TestRules.baseline().transferFee(new BigDecimal("0")).build()).isEmpty());
        assertTrue(RulesValidator.validate(TestRules.baseline().transferFee(new BigDecimal("500.00")).build()).isEmpty());
    }
}
