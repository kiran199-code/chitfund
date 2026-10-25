package com.chitfund.portal.invite;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;

@Component
public class InviteSweeper {

    private final InvitationService invitations;
    private final Clock clock;

    public InviteSweeper(InvitationService invitations, Clock clock) {
        this.invitations = invitations;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${chit.portal.invite.sweep-interval-millis:300000}")
    public void sweep() {
        invitations.expireDue(OffsetDateTime.now(clock));
    }
}
