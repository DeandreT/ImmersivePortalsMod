package qouteall.imm_ptl.core.platform_shared;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EventTest {
    private static Event<Runnable> event() {
        return Event.createArrayBacked(Runnable.class, listeners -> () -> {
            for (Runnable listener : listeners) {
                listener.run();
            }
        });
    }

    private static Identifier phase(String name) {
        return Identifier.fromNamespaceAndPath("immersive_portals_test", name);
    }

    @Test
    void keepsRegistrationOrderAndAnEmptyInvoker() {
        Event<Runnable> event = event();
        List<Integer> calls = new ArrayList<>();
        event.invoker().run();
        event.register(() -> calls.add(1));
        event.register(() -> calls.add(2));
        event.invoker().run();
        assertEquals(List.of(1, 2), calls);
    }

    @Test
    void ordersPhasesEvenWhenRegisteredInReverse() {
        Event<Runnable> event = event();
        List<String> calls = new ArrayList<>();
        event.register(phase("last"), () -> calls.add("last"));
        event.register(phase("first"), () -> calls.add("first"));
        event.register(() -> calls.add("default"));
        event.addPhaseOrdering(phase("first"), Event.DEFAULT_PHASE);
        event.addPhaseOrdering(Event.DEFAULT_PHASE, phase("last"));
        event.invoker().run();
        assertEquals(List.of("first", "default", "last"), calls);
    }

    @Test
    void aRejectedCycleDoesNotCorruptThePreviousInvoker() {
        Event<Runnable> event = event();
        List<Integer> calls = new ArrayList<>();
        event.register(phase("first"), () -> calls.add(1));
        event.register(phase("last"), () -> calls.add(2));
        event.addPhaseOrdering(phase("first"), phase("last"));
        assertThrows(IllegalArgumentException.class,
            () -> event.addPhaseOrdering(phase("last"), phase("first")));
        assertThrows(IllegalArgumentException.class,
            () -> event.addPhaseOrdering(phase("first"), phase("first")));
        event.register(phase("last"), () -> calls.add(3));
        event.invoker().run();
        assertEquals(List.of(1, 2, 3), calls);
    }

    @Test
    void registrationDuringInvocationUsesANewSnapshot() {
        Event<Runnable> event = event();
        List<Integer> calls = new ArrayList<>();
        event.register(() -> {
            calls.add(1);
            event.register(() -> calls.add(2));
        });
        event.invoker().run();
        assertEquals(List.of(1), calls);
        calls.clear();
        event.invoker().run();
        assertEquals(List.of(1, 2), calls);
    }

    @Test
    void supportsParameterizedCallbackInterfaces() {
        Event<Consumer<String>> event = Event.createArrayBacked(Consumer.class, listeners -> value -> {
            for (Consumer<String> listener : listeners) {
                listener.accept(value);
            }
        });
        List<String> calls = new ArrayList<>();
        event.register(calls::add);
        event.invoker().accept("value");
        assertEquals(List.of("value"), calls);
    }
}
