package com.chitfund.portal.auth;

/** Builds the Set-Cookie header value for the session cookie: HttpOnly always, Secure and SameSite from configuration. */
public final class SessionCookies {

    public static final String NAME = "portal_session";

    private SessionCookies() {
    }

    public static String issue(String value, long maxAgeSeconds, boolean secure, String sameSite) {
        return NAME + "=" + value + "; Path=/; Max-Age=" + maxAgeSeconds + "; HttpOnly" + (secure ? "; Secure" : "") + "; SameSite=" + sameSite;
    }

    public static String clear(boolean secure, String sameSite) {
        return NAME + "=; Path=/; Max-Age=0; HttpOnly" + (secure ? "; Secure" : "") + "; SameSite=" + sameSite;
    }
}
