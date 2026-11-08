package qouteall.dimlib.impl;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** An ordered callback snapshot, independent of Minecraft and either loader. */
public class PhasedEvent<P, T> {
    private final P defaultPhase;
    private final Class<T> listenerType;
    private final Function<T[], T> invokerFactory;
    private final Map<P, List<T>> listeners = new LinkedHashMap<>();
    private final Map<P, Set<P>> successors = new LinkedHashMap<>();
    private volatile T invoker;

    protected PhasedEvent(P defaultPhase, Class<T> listenerType, Function<T[], T> invokerFactory) {
        this.defaultPhase = Objects.requireNonNull(defaultPhase);
        this.listenerType = Objects.requireNonNull(listenerType);
        this.invokerFactory = Objects.requireNonNull(invokerFactory);
        ensurePhase(defaultPhase);
        rebuildInvoker();
    }

    public final T invoker() {
        return invoker;
    }

    public final void register(T listener) {
        register(defaultPhase, listener);
    }

    public final synchronized void register(P phase, T listener) {
        Objects.requireNonNull(listener);
        ensurePhase(Objects.requireNonNull(phase));
        listeners.get(phase).add(listener);
        rebuildInvoker();
    }

    /** Orders all callbacks in the first phase before all callbacks in the second phase. */
    public final synchronized void addPhaseOrdering(P first, P second) {
        Objects.requireNonNull(first);
        Objects.requireNonNull(second);
        if (first.equals(second)) {
            throw new IllegalArgumentException("A phase cannot precede itself");
        }
        ensurePhase(first);
        ensurePhase(second);
        if (!successors.get(first).add(second)) {
            return;
        }
        try {
            rebuildInvoker();
        }
        catch (IllegalArgumentException exception) {
            successors.get(first).remove(second);
            throw exception;
        }
    }

    private void ensurePhase(P phase) {
        listeners.computeIfAbsent(phase, ignored -> new ArrayList<>());
        successors.computeIfAbsent(phase, ignored -> new LinkedHashSet<>());
    }

    private void rebuildInvoker() {
        Map<P, Integer> incoming = new LinkedHashMap<>();
        listeners.keySet().forEach(phase -> incoming.put(phase, 0));
        successors.values().forEach(next -> next.forEach(phase -> incoming.merge(phase, 1, Integer::sum)));
        List<T> ordered = new ArrayList<>();
        Set<P> remaining = new LinkedHashSet<>(listeners.keySet());
        while (!remaining.isEmpty()) {
            P ready = null;
            for (P phase : remaining) {
                if (incoming.get(phase) == 0) {
                    ready = phase;
                    break;
                }
            }
            if (ready == null) {
                throw new IllegalArgumentException("Cyclic dimension-event phase ordering");
            }
            remaining.remove(ready);
            ordered.addAll(listeners.get(ready));
            for (P next : successors.get(ready)) {
                incoming.merge(next, -1, Integer::sum);
            }
        }
        @SuppressWarnings("unchecked")
        T[] snapshot = ordered.toArray(size -> (T[]) Array.newInstance(listenerType, size));
        invoker = invokerFactory.apply(snapshot);
    }
}
