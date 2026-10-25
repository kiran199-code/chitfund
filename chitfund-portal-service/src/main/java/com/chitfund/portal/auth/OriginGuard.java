package com.chitfund.portal.auth;

import com.chitfund.portal.common.PortalException;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * CSRF defence for the cookie-authenticated member API, on every state-changing call: the request must carry the custom header
 * {@code X-Portal-Client: web} (a cross-site form cannot set it) and, when the browser sends an {@code Origin}, it must be one of the
 * allowed origins. Together with a SameSite cookie this is the CSRF approach; it assumes the portal web app is the only browser client.
 */
public final class OriginGuard {

    public static final String HEADER = "X-Portal-Client";
    public static final String HEADER_VALUE = "web";

    private final Set<String> allowed;

    public OriginGuard(String commaSeparatedOrigins) {
        this.allowed = Arrays.stream(commaSeparatedOrigins == null ? new String[0] : commaSeparatedOrigins.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).map(OriginGuard::stripSlash).collect(Collectors.toSet());
    }

    public void check(String origin, String clientHeader) {
        if (!HEADER_VALUE.equals(clientHeader)) {
            throw PortalException.forbidden("CLIENT_HEADER_REQUIRED", "This call must come from the portal web app");
        }
        if (origin != null && !origin.isBlank() && !allowed.contains(stripSlash(origin.trim()))) {
            throw PortalException.forbidden("FORBIDDEN_ORIGIN", "This origin may not use the portal API");
        }
    }

    private static String stripSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
