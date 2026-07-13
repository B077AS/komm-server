package com.kommserver.security;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class UsedTicketStore {

    // jti -> expiry timestamp. We keep entries until their natural expiry to detect replays.
    private final Map<String, Instant> seen = new ConcurrentHashMap<>();

    /**
     * Returns true if the jti is new and was successfully marked.
     * Returns false if it was already seen (replay).
     */
    public boolean markUsed(String jti) {
        Instant now = Instant.now();
        evictExpired(now);

        // putIfAbsent returns null only if the key was absent — meaning first use
        Instant previous = seen.putIfAbsent(jti, now.plusSeconds(90)); // 90s > 60s ticket TTL
        return previous == null;
    }

    private void evictExpired(Instant now) {
        seen.entrySet().removeIf(e -> e.getValue().isBefore(now));
    }
}