package org.taniwha.service;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

/** Single-process exchange state. Consumed entries remain until ticket expiry. */
final class KerberosAuthorizationStore {
    private final Map<String, Authorization> entries = new HashMap<>();
    private final int capacity;
    private final Clock clock;

    KerberosAuthorizationStore(int capacity, Clock clock) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Authorization capacity must be positive");
        }
        this.capacity = capacity;
        this.clock = clock;
    }

    synchronized boolean authorize(String fingerprint, String principal, long expiresAt) {
        long now = clock.millis();
        removeExpired(now);
        if (expiresAt <= now || entries.containsKey(fingerprint) || entries.size() >= capacity) {
            return false;
        }
        entries.put(fingerprint, new Authorization(principal, expiresAt, false));
        return true;
    }

    synchronized boolean consume(String fingerprint, String principal) {
        removeExpired(clock.millis());
        Authorization entry = entries.get(fingerprint);
        if (entry == null || entry.consumed() || !entry.principal().equals(principal)) {
            return false;
        }
        entries.put(fingerprint, new Authorization(entry.principal(), entry.expiresAt(), true));
        return true;
    }

    synchronized int size() {
        removeExpired(clock.millis());
        return entries.size();
    }

    private void removeExpired(long now) {
        entries.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
    }

    private record Authorization(String principal, long expiresAt, boolean consumed) {}
}
