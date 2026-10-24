package com.chitfund.batch.core;

import com.chitfund.batch.chit.ChitServiceClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Where the batch service gets "today" from. By default that is the chit service's business date (source
 * {@code CHIT}), so jobs run for the business date and not for the calendar date. Source {@code SYSTEM} uses the wall
 * clock and exists only for running the batch service on its own with no chit service (never in a shared environment).
 */
@Component
public class BusinessDateProvider {

    private final ChitServiceClient chit;
    private final boolean useSystemClock;
    private final ZoneId systemZone;

    public BusinessDateProvider(ChitServiceClient chit,
                                @Value("${batch.business-date.source:CHIT}") String source,
                                @Value("${batch.business-date.system-timezone:Asia/Kolkata}") String systemTimezone) {
        this.chit = chit;
        this.useSystemClock = "SYSTEM".equalsIgnoreCase(source);
        this.systemZone = ZoneId.of(systemTimezone);
    }

    public LocalDate current() {
        return useSystemClock ? LocalDate.now(systemZone) : chit.businessDate().date();
    }
}
