package com.chitfund.portal.mail;

import com.chitfund.portal.mail.OutboxRepository.Due;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Sends the mails waiting in the outbox. A failure is recorded and retried later (see {@link RetryPolicy}); it never touches the
 * business change that queued the mail. Delivery is at least once: if the database commit fails after a send, the mail is sent again.
 */
@Service
public class OutboxService {

    private static final Logger log = LoggerFactory.getLogger(OutboxService.class);

    private final OutboxRepository repo;
    private final MailGateway gateway;
    private final int maxAttempts;

    public OutboxService(OutboxRepository repo, MailGateway gateway, @Value("${chit.portal.mail.max-attempts:6}") int maxAttempts) {
        this.repo = repo;
        this.gateway = gateway;
        this.maxAttempts = maxAttempts;
    }

    /** Queues a mail inside the caller's transaction. */
    public boolean queue(String kind, String to, String subject, String text, String dedupeKey, Long invitationId, OffsetDateTime now) {
        return repo.enqueue(kind, to, subject, text, dedupeKey, invitationId, now);
    }

    /** Sends up to {@code limit} due mails; returns how many were sent. */
    @Transactional
    public int dispatchDue(OffsetDateTime now, int limit) {
        List<Due> due = repo.lockDue(now, limit);
        int sent = 0;
        for (Due m : due) {
            try {
                String providerId = gateway.send(new OutgoingMail(m.to(), m.subject(), m.text()));
                repo.markSent(m.id(), providerId, now);
                if (m.invitationId() != null) {
                    repo.touchInvitationSent(m.invitationId(), now);
                }
                sent++;
            } catch (RuntimeException e) {
                int failed = m.attempts() + 1;
                boolean giveUp = RetryPolicy.giveUp(failed, maxAttempts);
                repo.markFailed(m.id(), e.getClass().getSimpleName() + ": " + e.getMessage(), now.plus(RetryPolicy.delayAfter(failed)), giveUp);
                log.warn("Mail {} ({}) failed, attempt {}{}: {}", m.id(), m.kind(), failed, giveUp ? ", giving up" : "", e.getMessage());
            }
        }
        return sent;
    }
}
