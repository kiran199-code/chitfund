package com.chitfund.portal.common;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Email addresses as the portal compares them: trimmed and lower-cased, and checked for a plausible shape (something@something.tld,
 * no spaces, at most 254 characters). This is deliberately not a full RFC 5322 parser.
 * <p>
 * Google's own rule for consumer Gmail is applied so that one mailbox is one address here, as it is at Google: for gmail.com and
 * googlemail.com the dots in the local part are ignored, a "+tag" suffix is ignored, and googlemail.com is the same as gmail.com.
 * "a.b+x@googlemail.com" therefore becomes "ab@gmail.com". Every other domain (including Google Workspace domains) is left exactly as
 * written, because for those the dots and tags may be significant. VERIFY against Google's current Gmail address documentation.
 */
public final class Emails {

    private static final Pattern SHAPE = Pattern.compile("^[^@\\s,;<>()\\[\\]\"]{1,64}@[^@\\s,;<>()\\[\\]\"]+\\.[^@\\s,;<>()\\[\\]\".]{2,}$");

    private Emails() {
    }

    /** The normalised address, or null if it is not a plausible address. */
    public static String normalise(String raw) {
        if (raw == null) {
            return null;
        }
        String e = raw.trim().toLowerCase(Locale.ROOT);
        if (e.length() > 254 || e.contains("..") || !SHAPE.matcher(e).matches()) {
            return null;
        }
        return googleCanonical(e);
    }

    private static String googleCanonical(String e) {
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
