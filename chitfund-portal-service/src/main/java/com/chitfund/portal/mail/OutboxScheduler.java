package com.chitfund.portal.mail;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;

/** Calls the outbox on a fixed delay. The delay is chit.portal.mail.dispatch-interval-millis. */
@Component
public class OutboxScheduler {

    private final OutboxService outbox;
    private final Clock clock;

    public OutboxScheduler(OutboxService outbox, Clock clock) {
        this.outbox = outbox;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${chit.portal.mail.dispatch-interval-millis:15000}")
    public void dispatch() {
        outbox.dispatchDue(OffsetDateTime.now(clock), 50);
    }
}
