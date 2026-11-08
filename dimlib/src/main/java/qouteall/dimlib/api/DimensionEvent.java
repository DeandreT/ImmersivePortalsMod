package qouteall.dimlib.api;

import net.minecraft.resources.Identifier;
import qouteall.dimlib.impl.PhasedEvent;

import java.util.function.Function;

/** Loader-neutral dimension callbacks with stable registration order and explicit phases. */
public final class DimensionEvent<T> extends PhasedEvent<Identifier, T> {
    // Retain the existing phase identifier so Fabric callers can keep their phase ordering.
    public static final Identifier DEFAULT_PHASE = Identifier.parse("fabric:default");

    private DimensionEvent(Class<T> listenerType, Function<T[], T> invokerFactory) {
        super(DEFAULT_PHASE, listenerType, invokerFactory);
    }

    public static <T> DimensionEvent<T> createArrayBacked(
        Class<T> listenerType, Function<T[], T> invokerFactory
    ) {
        return new DimensionEvent<>(listenerType, invokerFactory);
    }
}
