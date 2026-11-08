package qouteall.dimlib.impl;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PhasedEventTest {
    private static PhasedEvent<String, Runnable> event() {
        return new PhasedEvent<>("default", Runnable.class, callbacks -> () -> {
            for (Runnable callback : callbacks) {
                callback.run();
            }
        });
    }

    @Test
    void emptyInvokerIsSafeDuringInitialization() {
        event().invoker().run();
    }

    @Test
    void preservesRegistrationOrderWithinEachPhase() {
        var event = event();
        List<Integer> calls = new ArrayList<>();
        event.register(() -> calls.add(1));
        event.register(() -> calls.add(2));
        event.invoker().run();
        assertEquals(List.of(1, 2), calls);
    }

    @Test
    void earlyDimensionIdsPrecedeDefaultPortalCallbacks() {
        var event = event();
        List<String> calls = new ArrayList<>();
        event.register(() -> calls.add("portal-storage"));
        event.addPhaseOrdering("dimension-ids", "default");
        event.addPhaseOrdering("dimension-ids", "default");
        event.register("dimension-ids", () -> calls.add("dimension-ids"));
        event.invoker().run();
        assertEquals(List.of("dimension-ids", "portal-storage"), calls);
    }

    @Test
    void transitiveOrderingDoesNotDependOnRegistrationOrder() {
        var event = event();
        List<String> calls = new ArrayList<>();
        event.register("last", () -> calls.add("last"));
        event.register("middle", () -> calls.add("middle"));
        event.register("first", () -> calls.add("first"));
        event.addPhaseOrdering("first", "middle");
        event.addPhaseOrdering("middle", "last");
        event.invoker().run();
        assertEquals(List.of("first", "middle", "last"), calls);
    }

    @Test
    void rejectsCyclesWithoutPoisoningFutureRegistrations() {
        var event = event();
        List<String> calls = new ArrayList<>();
        event.addPhaseOrdering("first", "default");
        assertThrows(IllegalArgumentException.class,
            () -> event.addPhaseOrdering("default", "first"));
        assertThrows(IllegalArgumentException.class,
            () -> event.addPhaseOrdering("default", "default"));
        event.register(() -> calls.add("default"));
        event.register("first", () -> calls.add("first"));
        event.invoker().run();
        assertEquals(List.of("first", "default"), calls);
    }

    @Test
    void callbacksRegisteredWhileDispatchingWaitForTheNextSnapshot() {
        var event = event();
        List<String> calls = new ArrayList<>();
        event.register(() -> {
            calls.add("original");
            event.register(() -> calls.add("new"));
        });
        event.invoker().run();
        assertEquals(List.of("original"), calls);
        calls.clear();
        event.invoker().run();
        assertEquals(List.of("original", "new"), calls);
    }
}
