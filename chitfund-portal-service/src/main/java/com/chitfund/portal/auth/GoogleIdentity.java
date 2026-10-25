package com.chitfund.portal.auth;

/** What a verified Google ID token says about the person: Google's stable account id, the verified email, and a display name (may be null). */
public record GoogleIdentity(String sub, String email, String name) {
}
