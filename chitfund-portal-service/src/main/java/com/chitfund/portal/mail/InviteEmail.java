package com.chitfund.portal.mail;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** The invitation email as plain text. Wording lives here, in one place. */
public final class InviteEmail {

    public record Data(String groupName, BigDecimal chitValue, BigDecimal subscription, Integer cycles, LocalDate startDate,
                       String inviteeName, String inviteeEmail, int tickets, OffsetDateTime expiresAt, String link, ZoneId zone) {
    }

    public record Rendered(String subject, String text) {
    }

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.ENGLISH);

    private InviteEmail() {
    }

    public static Rendered render(Data d) {
        StringBuilder t = new StringBuilder();
        t.append(d.inviteeName() == null || d.inviteeName().isBlank() ? "Hello," : "Hello " + d.inviteeName().trim() + ",").append("\n\n");
        t.append("You have been invited to join the chit group \"").append(d.groupName()).append("\"");
        t.append(d.tickets() > 1 ? " with " + d.tickets() + " tickets." : ".").append("\n\n");
        if (d.chitValue() != null) {
            t.append("  Chit value:    ").append(Inr.format(d.chitValue())).append('\n');
        }
        if (d.subscription() != null) {
            t.append("  Subscription:  ").append(Inr.format(d.subscription())).append(" per instalment\n");
        }
        if (d.cycles() != null) {
            t.append("  Cycles:        ").append(d.cycles()).append('\n');
        }
        if (d.startDate() != null) {
            t.append("  Starts:        ").append(DAY.format(d.startDate())).append('\n');
        }
        t.append("\nTo review the invitation and accept or decline it, open this link:\n\n  ").append(d.link()).append("\n\n");
        t.append("You will be asked to sign in with Google. Please use the Google account for ").append(d.inviteeEmail())
                .append(" - the invitation is tied to that address.\n");
        t.append("This invitation expires on ").append(DAY.format(d.expiresAt().atZoneSameInstant(d.zone()).toLocalDate())).append(".\n\n");
        t.append("We never ask for a password, and we will never ask you to send money by replying to this email.\n");
        t.append("If you were not expecting this invitation, you can ignore it.\n");
        return new Rendered("Invitation to join " + d.groupName(), t.toString());
    }
}
