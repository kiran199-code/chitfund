package com.chitfund.batch.core;

import com.chitfund.batch.chit.ChitServiceClient;
import com.chitfund.batch.chit.ChitServiceSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Spring-free: where "today" comes from, and that the job context never falls back to the clock. */
class BusinessDateProviderTest {

    private static ChitServiceClient chitReporting(String date) {
        return new ChitServiceClient(new ChitServiceSettings("http://unused", null, null, null, null, 1,
                Duration.ofMillis(1)), new ObjectMapper()) {
            @Override
            public BusinessDateInfo businessDate() {
                return new BusinessDateInfo(date, null, null, "OPEN", true, null, "MANAGED");
            }
        };
    }

    @Test
    void byDefaultTheBusinessDateComesFromTheChitService() {
        BusinessDateProvider p = new BusinessDateProvider(chitReporting("2031-02-03"), "CHIT", "Asia/Kolkata");
        assertEquals(LocalDate.of(2031, 2, 3), p.current());
    }

    @Test
    void systemSourceUsesTheWallClockAndNeverCallsTheChitService() {
        ChitServiceClient mustNotBeCalled = new ChitServiceClient(
                new ChitServiceSettings("http://unused", null, null, null, null, 1, Duration.ofMillis(1)),
                new ObjectMapper()) {
            @Override
            public BusinessDateInfo businessDate() {
                throw new AssertionError("the chit service must not be called for source=SYSTEM");
            }
        };
        BusinessDateProvider p = new BusinessDateProvider(mustNotBeCalled, "system", "Asia/Kolkata");
        assertEquals(LocalDate.now(ZoneId.of("Asia/Kolkata")), p.current());
    }

    @Test
    void theJobContextHasNoWallClockFallback() {
        BatchJobContext.clear();
        assertThrows(IllegalStateException.class, BatchJobContext::businessDate);
        BatchJobContext.set(LocalDate.of(2026, 10, 12), "{}");
        assertEquals(LocalDate.of(2026, 10, 12), BatchJobContext.businessDate());
        BatchJobContext.clear();
    }
}
