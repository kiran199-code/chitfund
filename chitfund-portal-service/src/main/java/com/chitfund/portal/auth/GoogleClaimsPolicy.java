package com.chitfund.portal.auth;

import com.chitfund.portal.common.PortalException;
import com.chitfund.portal.invite.Emails;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Date;
import java.util.Map;
import java.util.Set;

/**
 * The checks the portal makes on the claims of a Google ID token AFTER the signature has been verified by the JWT library: issuer,
 * audience (must be this app's client id), expiry, a subject, and a verified email. Pure, so each rule is tested without Google.
 *
 * <p>The claim names (iss, aud, exp, sub, email, email_verified, name) and the two issuer spellings are as I recall Google's
 * documentation; confirm them against the current Google Identity docs before going live.
 */
public final class GoogleClaimsPolicy {

    private static final Set<String> ISSUERS = Set.of("https://accounts.google.com", "accounts.google.com");

    private GoogleClaimsPolicy() {
    }

    public static GoogleIdentity check(Map<String, Object> claims, String clientId, OffsetDateTime now) {
        if (clientId == null || clientId.isBlank()) {
            throw new PortalException("SIGN_IN_NOT_CONFIGURED", "Google sign-in is not configured (GOOGLE_CLIENT_ID)");
        }
        if (claims == null) {
            throw invalid("no claims");
        }
        Object iss = claims.get("iss");
        if (iss == null || !ISSUERS.contains(iss.toString())) {
            throw invalid("wrong issuer");
        }
        if (!audienceContains(claims.get("aud"), clientId)) {
            throw invalid("wrong audience");
        }
        Instant exp = instant(claims.get("exp"));
        if (exp == null || !exp.isAfter(now.toInstant())) {
            throw invalid("expired");
        }
        Object sub = claims.get("sub");
        if (sub == null || sub.toString().isBlank() || sub.toString().length() > 255) {
            throw invalid("no subject");
        }
        if (!truthy(claims.get("email_verified"))) {
            throw invalid("email not verified");
        }
        Object rawEmail = claims.get("email");
        String email = rawEmail == null ? null : Emails.normalise(rawEmail.toString());
        if (email == null) {
            throw invalid("no usable email");
        }
        Object name = claims.get("name");
        String display = name == null || name.toString().isBlank() ? null : name.toString().trim();
        if (display != null && display.length() > 120) {
            display = display.substring(0, 120);
        }
        return new GoogleIdentity(sub.toString(), email, display);
    }

    private static boolean audienceContains(Object aud, String clientId) {
        if (aud instanceof String s) {
            return s.equals(clientId);
        }
        if (aud instanceof Collection<?> c) {
            return c.stream().anyMatch(a -> clientId.equals(String.valueOf(a)));
        }
        return false;
    }

    private static Instant instant(Object o) {
        if (o instanceof Instant i) {
            return i;
        }
        if (o instanceof Date d) {
            return d.toInstant();
        }
        if (o instanceof Number n) {
            return Instant.ofEpochSecond(n.longValue());
        }
        if (o instanceof String s && s.matches("\\d{1,12}")) {
            return Instant.ofEpochSecond(Long.parseLong(s));
        }
        return null;
    }

    private static boolean truthy(Object o) {
        return o instanceof Boolean b ? b : o != null && "true".equalsIgnoreCase(o.toString());
    }

    private static PortalException invalid(String why) {
        return new PortalException("INVALID_ID_TOKEN", "The Google sign-in could not be verified (" + why + ")");
    }
}
