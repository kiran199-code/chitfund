package com.chitfund.e2e;

import java.util.Map;

/**
 * Settings read from environment variables. Passwords never appear in {@link #toString()}. Nothing here has a real default for a
 * password: a service whose password is blank cannot be called.
 */
public final class Config {
    public final String chitUrl;
    public final String chitUser;
    public final String chitPassword;
    public final String portalUrl;
    public final String portalOrigin;
    public final String portalAdminUser;
    public final String portalAdminPassword;
    public final String uiUser;
    public final String uiPassword;
    public final boolean testEnvironment;
    public final int port;

    public Config(Map<String, String> env) {
        chitUrl = trimSlash(env.getOrDefault("CHIT_SERVICE_URL", ""));
        chitUser = env.getOrDefault("CHIT_SERVICE_USERNAME", "chit-service");
        chitPassword = env.getOrDefault("CHIT_SERVICE_PASSWORD", "");
        portalUrl = trimSlash(env.getOrDefault("PORTAL_SERVICE_URL", ""));
        portalOrigin = trimSlash(env.getOrDefault("PORTAL_ORIGIN", portalUrl));
        portalAdminUser = env.getOrDefault("PORTAL_ADMIN_USERNAME", "portal-admin");
        portalAdminPassword = env.getOrDefault("PORTAL_ADMIN_PASSWORD", "");
        uiUser = env.getOrDefault("E2E_UI_USERNAME", "e2e");
        uiPassword = env.getOrDefault("E2E_UI_PASSWORD", "");
        testEnvironment = "true".equalsIgnoreCase(env.getOrDefault("E2E_TEST_ENV", "false"));
        port = Integer.parseInt(env.getOrDefault("PORT", "8090"));
    }

    private static String trimSlash(String s) {
        String t = s == null ? "" : s.trim();
        return t.endsWith("/") ? t.substring(0, t.length() - 1) : t;
    }

    /** What is missing or unsafe, as sentences for the person starting the service; empty when it can run. */
    public java.util.List<String> problems() {
        java.util.List<String> p = new java.util.ArrayList<>();
        if (!testEnvironment) {
            p.add("E2E_TEST_ENV is not 'true'. This service resets data and moves the business date; it refuses to run unless you say this is a test environment.");
        }
        if (chitUrl.isBlank()) p.add("CHIT_SERVICE_URL is not set.");
        if (chitPassword.isBlank()) p.add("CHIT_SERVICE_PASSWORD is not set.");
        if (portalUrl.isBlank()) p.add("PORTAL_SERVICE_URL is not set.");
        if (portalAdminPassword.isBlank()) p.add("PORTAL_ADMIN_PASSWORD is not set.");
        if (uiPassword.isBlank()) p.add("E2E_UI_PASSWORD is not set (the page and API of this service need a password).");
        return p;
    }

    @Override
    public String toString() {
        return "Config{chit=" + chitUrl + ", portal=" + portalUrl + ", portalOrigin=" + portalOrigin + ", testEnvironment=" + testEnvironment + ", port=" + port + "}";
    }
}
