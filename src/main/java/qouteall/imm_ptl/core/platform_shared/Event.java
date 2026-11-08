package qouteall.imm_ptl.core.platform_shared;

import net.minecraft.resources.Identifier;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Loader-independent, array-backed callbacks with stable phase ordering. */
public final class Event<T> {
    // Keep the established phase identifier for callers of the public portal API.
    public static final Identifier DEFAULT_PHASE = Identifier.fromNamespaceAndPath("fabric", "default");

    private final Class<?> type;
    private final Function<T[], T> factory;
    private final Map<Identifier, List<T>> listeners = new LinkedHashMap<>();
    private final Map<Identifier, Set<Identifier>> successors = new LinkedHashMap<>();
    private volatile T invoker;

    private Event(Class<?> type, Function<T[], T> factory) {
        this.type = type;
        this.factory = factory;
        listeners.put(DEFAULT_PHASE, new ArrayList<>());
        rebuild();
    }

    public static <T> Event<T> createArrayBacked(Class<? super T> type, Function<T[], T> factory) {
        return new Event<>(type, factory);
    }

    public T invoker() {
        return invoker;
    }

    public void register(T listener) {
        register(DEFAULT_PHASE, listener);
    }

    public synchronized void register(Identifier phase, T listener) {
        listeners.computeIfAbsent(phase, ignored -> new ArrayList<>()).add(listener);
        rebuild();
    }

    public synchronized void addPhaseOrdering(Identifier before, Identifier after) {
        if (before.equals(after)) {
            throw new IllegalArgumentException("An event phase cannot precede itself");
        }
        listeners.computeIfAbsent(before, ignored -> new ArrayList<>());
        listeners.computeIfAbsent(after, ignored -> new ArrayList<>());
        Set<Identifier> edges = successors.computeIfAbsent(before, ignored -> new LinkedHashSet<>());
        boolean added = edges.add(after);
        try {
            rebuild();
        }
        catch (IllegalArgumentException exception) {
            if (added) {
                edges.remove(after);
            }
            throw exception;
        }
    }

    @SuppressWarnings("unchecked")
    private void rebuild() {
        List<T> ordered = new ArrayList<>();
        Set<Identifier> remaining = new LinkedHashSet<>(listeners.keySet());
        while (!remaining.isEmpty()) {
            Identifier next = remaining.stream().filter(candidate -> remaining.stream().noneMatch(
                other -> successors.getOrDefault(other, Set.of()).contains(candidate)
            )).findFirst().orElseThrow(() -> new IllegalArgumentException("Cyclic event phase ordering"));
            ordered.addAll(listeners.get(next));
            remaining.remove(next);
        }
        invoker = factory.apply(ordered.toArray(size -> (T[]) Array.newInstance(type, size)));
    }
}
