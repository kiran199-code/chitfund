package com.chitfund.e2e;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IdentitiesAndMoneyTest {

    @Test
    void gmailVariantsAreOneAddress() {
        assertEquals("kiran199@gmail.com", Identities.canonical("Kiran.199+chit@GoogleMail.com"));
        assertEquals("a.b@example.com", Identities.canonical(" A.B@Example.com "));
    }

    @Test
    void saversAreUniquePerRunAndStoredWithoutDots() {
        var a = Identities.saversFor("r1", 5);
        var b = Identities.saversFor("r2", 5);
        assertEquals(5, a.size());
        assertEquals(5, new HashSet<>(a.stream().map(Identities.Person::email).toList()).size());
        for (int i = 0; i < 5; i++) {
            assertNotEquals(a.get(i).email(), b.get(i).email());
            assertNotEquals(a.get(i).signInEmail(), a.get(i).email());
            assertTrue(a.get(i).idToken().startsWith("e2e|e2e-sub-r1-"));
        }
    }

    @Test
    void seatPlanAddsUpAndRespectsTheCap() {
        int[] p = Identities.seatPlan(10, 6);
        int sum = 0;
        for (int s : p) {
            sum += s;
            assertTrue(s >= 1 && s <= 3);
        }
        assertEquals(10, sum);
        assertThrows(IllegalArgumentException.class, () -> Identities.seatPlan(10, 3));
    }

    @Test
    void bidsAscendStrictlyInsideTheBand() {
        List<BigDecimal> l = Money.ascendingBids(new BigDecimal("5000"), new BigDecimal("40000"), 4);
        assertEquals(4, l.size());
        for (int i = 0; i < l.size(); i++) {
            assertTrue(l.get(i).compareTo(new BigDecimal("5000")) > 0 && l.get(i).compareTo(new BigDecimal("40000")) < 0);
            if (i > 0) assertTrue(l.get(i).compareTo(l.get(i - 1)) > 0);
        }
        assertTrue(Money.ascendingBids(new BigDecimal("5"), new BigDecimal("6"), 3).isEmpty());
    }

    @Test
    void moneyComparisons() {
        assertTrue(Money.same(new BigDecimal("10.0"), new BigDecimal("10.00")));
        assertTrue(Money.near(new BigDecimal("10.00"), new BigDecimal("10.01")));
        assertFalse(Money.near(new BigDecimal("10.00"), new BigDecimal("10.02")));
        assertEquals(new BigDecimal("5000.00"), Money.pct(new BigDecimal("100000"), new BigDecimal("5")));
        assertEquals(BigDecimal.ZERO, Money.zeroIfNull(null));
    }
}
