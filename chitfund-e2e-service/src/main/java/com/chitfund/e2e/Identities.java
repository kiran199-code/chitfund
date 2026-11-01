package com.chitfund.e2e;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Mock Gmail customers. The portal (running with the {@code e2e} profile) accepts the token {@code e2e|<subject>|<email>|<name>} in place of a
 * Google ID token. Addresses are made unique per run so repeat runs do not collide. {@link #canonical} repeats Google's Gmail rule that the
 * portal applies (dots and a +tag are ignored, googlemail.com is gmail.com); it is tested, so a drift from the portal shows up as a failed test.
 */
public final class Identities {
    private Identities() {
    }

    /** One mock customer: a stable Google subject, the address typed at sign-in, and the name shown. */
    public record Person(String sub, String signInEmail, String name) {
        public String idToken() {
            return "e2e|" + sub + "|" + signInEmail + "|" + name;
        }

        /** The address as the platform stores it. */
        public String email() {
            return canonical(signInEmail);
        }

        public Person withSignInEmail(String other) {
            return new Person(sub, other, name);
        }
    }

    public static String canonical(String raw) {
        String e = raw.trim().toLowerCase(Locale.ROOT);
        int at = e.lastIndexOf('@');
        String local = e.substring(0, at);
        String domain = e.substring(at + 1);
        if (!domain.equals("gmail.com") && !domain.equals("googlemail.com")) {
            return e;
        }
        int plus = local.indexOf('+');
        if (plus >= 0) local = local.substring(0, plus);
        return local.replace(".", "") + "@gmail.com";
    }

    /** {@code n} customers e2e.<run>.saver01@gmail.com ...; the dotted form is typed at sign-in, the stored form has no dots. */
    public static List<Person> saversFor(String runId, int n) {
        List<Person> out = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            String num = String.format("%02d", i);
            out.add(new Person("e2e-sub-" + runId + "-" + num, "e2e." + runId + ".saver" + num + "@gmail.com", "E2E Saver " + num));
        }
        return out;
    }

    /** How many seats each of {@code customers} asks for so that they add up to {@code tickets}: as even as possible, at most 3 each. */
    public static int[] seatPlan(int tickets, int customers) {
        if (tickets > customers * 3) throw new IllegalArgumentException("at most 3 seats each");
        int[] seats = new int[customers];
        int base = tickets / customers;
        int extra = tickets % customers;
        for (int i = 0; i < customers; i++) {
            seats[i] = base + (i < extra ? 1 : 0);
        }
        return seats;
    }
}
