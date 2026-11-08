package qouteall.imm_ptl.client_test;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/** Observation only: no callbacks, protocol responses, movement, or acknowledgements. */
public final class Evidence {
    public static final AtomicInteger CONFIG_START = new AtomicInteger();
    public static final AtomicInteger CONFIG_COMPLETE = new AtomicInteger();
    public static final AtomicInteger DIMENSION_SYNC = new AtomicInteger();
    public static final AtomicInteger PORTAL_SYNC = new AtomicInteger();
    public static final AtomicInteger SERVER_TELEPORT_REQUESTS = new AtomicInteger();
    public static final ConcurrentLinkedQueue<String> PREDICTIONS = new ConcurrentLinkedQueue<>();
    private Evidence() {}
}
