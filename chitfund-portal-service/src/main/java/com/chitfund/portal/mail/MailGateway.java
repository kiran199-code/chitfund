package com.chitfund.portal.mail;

/** Sends one mail. The provider sits behind this interface; the outbox decides when to call it and what to do on failure. */
public interface MailGateway {

    /** Returns a provider message id (may be null). Throws on any failure, so the outbox can retry. */
    String send(OutgoingMail mail);
}
