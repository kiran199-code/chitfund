package com.chitfund.portal.mail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Sends through the SMTP server configured in {@code spring.mail.*} (environment SMTP_HOST, SMTP_PORT, SMTP_USERNAME, SMTP_PASSWORD).
 * Plain text only. Any SMTP provider works; the sending limits and the SPF, DKIM and DMARC setup of your sending domain are the
 * provider's and your DNS's, and are not handled here.
 */
@Component
@ConditionalOnProperty(name = "chit.portal.mail.mode", havingValue = "SMTP")
public class SmtpMailGateway implements MailGateway {

    private final JavaMailSender sender;
    private final String from;

    public SmtpMailGateway(JavaMailSender sender, @Value("${chit.portal.mail.from}") String from) {
        this.sender = sender;
        this.from = from;
    }

    @Override
    public String send(OutgoingMail mail) {
        SimpleMailMessage m = new SimpleMailMessage();
        m.setFrom(from);
        m.setTo(mail.to());
        m.setSubject(mail.subject());
        m.setText(mail.text());
        sender.send(m);
        return null;
    }
}
