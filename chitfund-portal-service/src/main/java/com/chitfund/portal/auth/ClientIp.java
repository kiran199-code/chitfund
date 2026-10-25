package com.chitfund.portal.auth;

/**
 * The address to rate-limit and audit by. Behind exactly one trusted proxy that APPENDS to X-Forwarded-For, the last entry is the
 * address that proxy saw; earlier entries are whatever the client claimed and must not be trusted. With no trusted proxy the socket
 * address is used and the header is ignored.
 */
public final class ClientIp {

    private ClientIp() {
    }

    public static String resolve(String remoteAddr, String forwardedFor, boolean trustProxy) {
        if (trustProxy && forwardedFor != null && !forwardedFor.isBlank()) {
            String[] parts = forwardedFor.split(",");
            String last = parts[parts.length - 1].trim();
            if (!last.isEmpty() && last.length() <= 64) {
                return last;
            }
        }
        return remoteAddr == null ? "unknown" : remoteAddr;
    }
}
