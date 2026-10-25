package com.chitfund.service.recovery;

import com.chitfund.service.maintenance.ResetTables;
import com.chitfund.service.transfer.TransferKind;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the V7 migration against drifting from the Java that depends on it. */
class RecoverySchemaTest {

    private static String v7() throws IOException {
        try (InputStream in = RecoverySchemaTest.class.getResourceAsStream("/db/migration/V7__chit_recovery_transfer.sql")) {
            assertNotNull(in, "V7 not found on the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Set<String> list(String raw) {
        return Arrays.stream(raw.split(",")).map(s -> s.replace("'", "").trim()).filter(s -> !s.isEmpty()).collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    void transferKindsMatchTheCheck() throws IOException {
        Matcher m = Pattern.compile("kind VARCHAR\\(12\\) NOT NULL DEFAULT 'TRANSFER'\\s+CHECK \\(kind IN \\(([^)]*)\\)").matcher(v7());
        assertTrue(m.find(), "kind check not found");
        Set<String> code = Arrays.stream(TransferKind.values()).map(Enum::name).collect(Collectors.toCollection(TreeSet::new));
        assertEquals(code, list(m.group(1)));
    }

    @Test
    void theNewTablesAreInTheResetList() throws IOException {
        String sql = v7();
        Matcher m = Pattern.compile("CREATE TABLE (chit_[a-z_]+)").matcher(sql);
        int n = 0;
        while (m.find()) {
            n++;
            assertTrue(ResetTables.TABLES.contains(m.group(1)), m.group(1) + " is created by V7 but is not in ResetTables");
        }
        assertEquals(2, n, "V7 creates chit_recovery_action and chit_write_off");
    }

    @Test
    void theRecoveryActionKindsAreTheOnesTheServiceWrites() throws IOException {
        Matcher m = Pattern.compile("kind\\s+VARCHAR\\(20\\)\\s+NOT NULL CHECK \\(kind IN \\(([^)]*)\\)").matcher(v7());
        assertTrue(m.find(), "recovery action kind check not found");
        assertEquals(new TreeSet<>(Set.of("SECURITY_APPLIED", "COVER_REPAID")), list(m.group(1)));
    }

    @Test
    void replacementTransfersCarryNoFeeAndTheMakerCheckerChecksAreGone() throws IOException {
        String sql = v7();
        assertTrue(sql.contains("ck_transfer_fee_kind"), "a replacement exit must carry no fee");
        assertTrue(sql.contains("DROP CONSTRAINT ck_transfer_maker_checker"), "the service, not the database, enforces maker-checker");
        assertTrue(sql.contains("DROP CONSTRAINT ck_default_writeoff_mc"));
    }
}
