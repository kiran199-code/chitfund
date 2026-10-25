package com.chitfund.portal.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Development sender: writes the mail to the log instead of sending it. The default mode. */
@Component
@ConditionalOnProperty(name = "chit.portal.mail.mode", havingValue = "CONSOLE", matchIfMissing = true)
public class ConsoleMailGateway implements MailGateway {

    private static final Logger log = LoggerFactory.getLogger(ConsoleMailGateway.class);

    @Override
    public String send(OutgoingMail mail) {
        log.info("MAIL (console mode, not sent) to={} subject={}\n{}", mail.to(), mail.subject(), mail.text());
        return "console";
    }
}
