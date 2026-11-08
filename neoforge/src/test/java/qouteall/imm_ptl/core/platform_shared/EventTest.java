package qouteall.imm_ptl.core.platform_shared;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EventTest {
    private static Event<Runnable> event() {
        return Event.createArrayBacked(Runnable.class, callbacks -> () -> {
            for (Runnable callback : callbacks) { callback.run(); }
        });
    }

    @Test
    void emptyAndUpdatedInvokersAreSafe() {
        Event<Runnable> event = event();
        event.invoker().run();
        List<Integer> seen = new ArrayList<>();
        event.register(() -> seen.add(1));
        event.register(() -> seen.add(2));
        event.invoker().run();
        assertEquals(List.of(1, 2), seen);
    }

    @Test
    void phasesUseStableTopologicalOrdering() {
        Event<Runnable> event = event();
        Identifier before = Identifier.parse("test:before");
        Identifier after = Identifier.parse("test:after");
        List<String> seen = new ArrayList<>();
        event.register(() -> seen.add("default"));
        event.register(after, () -> seen.add("after"));
        event.register(before, () -> seen.add("before"));
        event.addPhaseOrdering(before, Event.DEFAULT_PHASE);
        event.addPhaseOrdering(Event.DEFAULT_PHASE, after);
        event.invoker().run();
        assertEquals(List.of("before", "default", "after"), seen);
    }

    @Test
    void rejectedCycleDoesNotCorruptLaterRegistrations() {
        Event<Runnable> event = event();
        Identifier other = Identifier.parse("test:other");
        event.addPhaseOrdering(Event.DEFAULT_PHASE, other);
        assertThrows(IllegalArgumentException.class, () -> event.addPhaseOrdering(other, Event.DEFAULT_PHASE));
        assertThrows(IllegalArgumentException.class, () -> event.addPhaseOrdering(other, other));
        List<Integer> seen = new ArrayList<>();
        event.register(other, () -> seen.add(2));
        event.register(() -> seen.add(1));
        event.invoker().run();
        assertEquals(List.of(1, 2), seen);
    }
}
