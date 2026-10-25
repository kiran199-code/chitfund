package com.chitfund.portal.common;

/**
 * A business-rule failure with a stable machine-readable code. Callers switch on {@code code}, not on the message. It carries no
 * HTTP status on purpose; {@code config/ErrorStatus} maps a code to a status in one place.
 */
public class PortalException extends RuntimeException {

    private final String code;

    public PortalException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static PortalException notFound(String what, String ref) {
        return new PortalException("NOT_FOUND", what + " not found: " + ref);
    }

    public static PortalException badRequest(String code, String message) {
        return new PortalException(code, message);
    }

    public static PortalException conflict(String code, String message) {
        return new PortalException(code, message);
    }

    public static PortalException forbidden(String code, String message) {
        return new PortalException(code, message);
    }
}
