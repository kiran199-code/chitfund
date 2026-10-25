package com.chitfund.portal.mail;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class MailRulesTest {

    @Test
    void amountsUseIndianGrouping() {
        assertEquals("Rs. 0.00", Inr.format(BigDecimal.ZERO));
        assertEquals("Rs. 999.00", Inr.format(new BigDecimal("999")));
        assertEquals("Rs. 1,000.00", Inr.format(new BigDecimal("1000")));
        assertEquals("Rs. 10,000.50", Inr.format(new BigDecimal("10000.5")));
        assertEquals("Rs. 1,00,000.00", Inr.format(new BigDecimal("100000")));
        assertEquals("Rs. 12,34,567.89", Inr.format(new BigDecimal("1234567.89")));
        assertEquals("Rs. 1,00,00,000.00", Inr.format(new BigDecimal("10000000")));
        assertEquals("Rs. -1,500.00", Inr.format(new BigDecimal("-1500")));
        assertEquals("", Inr.format(null));
    }

    @Test
    void theRetryWaitGrowsThenStaysAtSixHours() {
        assertEquals(Duration.ofMinutes(1), RetryPolicy.delayAfter(1));
        assertEquals(Duration.ofMinutes(5), RetryPolicy.delayAfter(2));
        assertEquals(Duration.ofMinutes(30), RetryPolicy.delayAfter(3));
        assertEquals(Duration.ofHours(2), RetryPolicy.delayAfter(4));
        assertEquals(Duration.ofHours(6), RetryPolicy.delayAfter(5));
        assertEquals(Duration.ofHours(6), RetryPolicy.delayAfter(50));
        assertEquals(Duration.ofMinutes(1), RetryPolicy.delayAfter(0));
    }

    @Test
    void theOutboxGivesUpAtTheLimit() {
        assertFalse(RetryPolicy.giveUp(5, 6));
        assertTrue(RetryPolicy.giveUp(6, 6));
        assertTrue(RetryPolicy.giveUp(7, 6));
    }

    private static InviteEmail.Data data(String name, int tickets) {
        return new InviteEmail.Data("Diwali Chit", new BigDecimal("100000"), new BigDecimal("5000"), 20, LocalDate.of(2026, 11, 1),
                name, "member@example.com", tickets, OffsetDateTime.of(2026, 11, 2, 20, 0, 0, 0, ZoneOffset.UTC),
                "https://portal.example/invite/TOKEN", ZoneId.of("Asia/Kolkata"));
    }

    @Test
    void theInviteNamesTheGroupTheTermsAndTheLink() {
        InviteEmail.Rendered m = InviteEmail.render(data("Asha", 1));
        assertEquals("Invitation to join Diwali Chit", m.subject());
        assertTrue(m.text().startsWith("Hello Asha,"));
        assertTrue(m.text().contains("Rs. 1,00,000.00"));
        assertTrue(m.text().contains("Rs. 5,000.00 per instalment"));
        assertTrue(m.text().contains("Cycles:        20"));
        assertTrue(m.text().contains("1 November 2026"));
        assertTrue(m.text().contains("https://portal.example/invite/TOKEN"));
        assertTrue(m.text().contains("member@example.com"));
    }

    @Test
    void theExpiryDateIsShownInTheConfiguredZone() {
        // 20:00 UTC on 2 Nov is 01:30 on 3 Nov in India
        assertTrue(InviteEmail.render(data("Asha", 1)).text().contains("expires on 3 November 2026"));
    }

    @Test
    void severalTicketsAreMentionedAndAMissingNameStillGreets() {
        InviteEmail.Rendered m = InviteEmail.render(data(null, 3));
        assertTrue(m.text().startsWith("Hello,"));
        assertTrue(m.text().contains("with 3 tickets"));
    }

    @Test
    void theInviteNeverAsksForPasswordsOrMoney() {
        String t = InviteEmail.render(data("Asha", 1)).text().toLowerCase();
        assertTrue(t.contains("never ask for a password"));
        assertTrue(t.contains("never ask you to send money"));
    }
}
