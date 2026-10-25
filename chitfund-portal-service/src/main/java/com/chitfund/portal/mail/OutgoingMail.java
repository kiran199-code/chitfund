package com.chitfund.portal.mail;

/** One message handed to a {@link MailGateway}. Plain text only in this step. */
public record OutgoingMail(String to, String subject, String text) {
}
