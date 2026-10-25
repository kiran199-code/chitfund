package com.chitfund.service.customer;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The e-mail address as the customer record keys it: trimmed, lower-cased, plausible shape, and Google's own rule for consumer Gmail applied
 * (for gmail.com and googlemail.com the dots in the local part and any "+tag" are ignored and googlemail.com is gmail.com), so that one
 * mailbox is one customer. Other domains, Google Workspace included, are kept exactly. This must give the same answer as the portal service's
 * {@code Emails.normalise}; the two are kept in step by hand. VERIFY the Gmail rule against Google's documentation.
 */
public final class CustomerEmails {

    private static final Pattern SHAPE = Pattern.compile("^[^@\\s,;<>()\\[\\]\"]{1,64}@[^@\\s,;<>()\\[\\]\"]+\\.[^@\\s,;<>()\\[\\]\".]{2,}$");

    private CustomerEmails() {
    }

    /** The canonical address, or null if it is not a plausible address. */
    public static String normalise(String raw) {
        if (raw == null) {
            return null;
        }
        String e = raw.trim().toLowerCase(Locale.ROOT);
        if (e.length() > 254 || e.contains("..") || !SHAPE.matcher(e).matches()) {
            return null;
        }
        int at = e.lastIndexOf('@');
        String local = e.substring(0, at);
        String domain = e.substring(at + 1);
        if (!domain.equals("gmail.com") && !domain.equals("googlemail.com")) {
            return e;
        }
        int plus = local.indexOf('+');
        if (plus >= 0) {
            local = local.substring(0, plus);
        }
        local = local.replace(".", "");
        return local.isEmpty() ? null : local + "@gmail.com";
    }
}
