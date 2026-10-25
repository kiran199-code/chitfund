package com.chitfund.portal.auth;

import com.chitfund.portal.common.PortalException;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A fixed-window limit per key (an address or a member). In memory, so it counts per instance: with several instances the real limit is
 * the number of instances times the setting, and a restart clears it. Enough to slow guessing; not a substitute for a gateway-level limit.
 */
public final class RateLimiter {

    private record Window(long startMillis, int count) {
    }

    private final int perMinute;
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimiter(int perMinute, Clock clock) {
        this.perMinute = perMinute;
        this.clock = clock;
    }

    /** Counts one call for the key; throws TOO_MANY_REQUESTS if the key is over the limit for the current minute. */
    public void hit(String key) {
        long now = clock.millis();
        if (windows.size() > 10_000) {
            windows.entrySet().removeIf(e -> now - e.getValue().startMillis() >= 60_000);
        }
        Window w = windows.merge(key, new Window(now, 1), (old, fresh) ->
                now - old.startMillis() >= 60_000 ? fresh : new Window(old.startMillis(), old.count() + 1));
        if (w.count() > perMinute) {
            throw new PortalException("TOO_MANY_REQUESTS", "Too many requests; wait a minute and try again");
        }
    }
}
