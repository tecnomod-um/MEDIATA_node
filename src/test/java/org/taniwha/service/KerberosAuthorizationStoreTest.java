package org.taniwha.service;

import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KerberosAuthorizationStoreTest {
    private final AtomicLong now = new AtomicLong(1000);

    private KerberosAuthorizationStore store(int capacity) {
        Clock clock = mock(Clock.class);
        when(clock.millis()).thenAnswer(invocation -> now.get());
        return new KerberosAuthorizationStore(capacity, clock);
    }

    @Test
    void consumedAuthorizationCannotBeRecreatedOrConsumedTwice() {
        var store = store(2);
        assertTrue(store.authorize("ticket", "alice", 2000));
        assertFalse(store.consume("ticket", "mallory"));
        assertTrue(store.consume("ticket", "alice"));
        assertFalse(store.authorize("ticket", "alice", 3000));
        assertFalse(store.consume("ticket", "alice"));
    }

    @Test
    void capacityIncludesConsumedEntriesAndDoesNotEvictReplayProtection() {
        var store = store(2);
        assertTrue(store.authorize("one", "alice", 2000));
        assertTrue(store.consume("one", "alice"));
        assertTrue(store.authorize("two", "alice", 3000));
        assertFalse(store.authorize("three", "alice", 3000));
        assertFalse(store.authorize("one", "alice", 3000));
        assertEquals(2, store.size());
        now.set(2000);
        assertTrue(store.authorize("three", "alice", 3000));
        assertEquals(2, store.size());
    }

    @Test
    void expiredOrUnknownAuthorizationCannotBeConsumed() {
        var store = store(2);
        assertFalse(store.authorize("expired", "alice", 1000));
        assertFalse(store.consume("unknown", "alice"));
        assertTrue(store.authorize("ticket", "alice", 2000));
        now.set(2000);
        assertFalse(store.consume("ticket", "alice"));
        assertEquals(0, store.size());
    }

    @Test
    void separateTicketsForTheSameUserRemainIndependent() {
        var store = store(2);
        assertTrue(store.authorize("one", "alice", 2000));
        assertTrue(store.authorize("two", "alice", 2000));
        assertTrue(store.consume("one", "alice"));
        assertTrue(store.consume("two", "alice"));
    }

    @Test
    void concurrentReplayAndConsumptionHaveOneSuccessfulConsumer() throws Exception {
        var store = store(2);
        assertTrue(store.authorize("ticket", "alice", 2000));
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 64; i++) {
                results.add(executor.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    assertFalse(store.authorize("ticket", "alice", 3000));
                    return store.consume("ticket", "alice");
                }));
            }
            start.countDown();
            int successes = 0;
            for (Future<Boolean> result : results) {
                if (result.get(5, TimeUnit.SECONDS)) successes++;
            }
            assertEquals(1, successes);
            assertFalse(store.authorize("ticket", "alice", 3000));
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }
}
