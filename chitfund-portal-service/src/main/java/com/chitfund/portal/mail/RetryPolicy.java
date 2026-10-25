package com.chitfund.portal.mail;

import java.time.Duration;

/** When to try a failed mail again: 1 minute, 5 minutes, 30 minutes, 2 hours, then every 6 hours, up to the attempt limit. */
public final class RetryPolicy {

    private static final long[] MINUTES = {1, 5, 30, 120, 360};

    private RetryPolicy() {
    }

    /** The wait after the {@code failedAttempts}-th failure (1 = first failure). */
    public static Duration delayAfter(int failedAttempts) {
        int i = Math.max(1, failedAttempts) - 1;
        return Duration.ofMinutes(MINUTES[Math.min(i, MINUTES.length - 1)]);
    }

    public static boolean giveUp(int failedAttempts, int maxAttempts) {
        return failedAttempts >= maxAttempts;
    }
}
