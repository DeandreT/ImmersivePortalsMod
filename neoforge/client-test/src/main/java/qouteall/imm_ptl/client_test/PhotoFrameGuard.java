package qouteall.imm_ptl.client_test;

/** Associates a read-only terrain observation with its actual render pre/post frame. */
final class PhotoFrameGuard {
    private long frame;
    private long observedFrame = -1;
    private long observedDraw = -1;

    void beginFrame() {
        frame++;
        resetObservation();
    }

    void resetObservation() {
        observedFrame = -1;
        observedDraw = -1;
    }

    void observe(long drawSequence) {
        observedFrame = frame;
        observedDraw = drawSequence;
    }

    boolean hasCurrentObservation() { return observedFrame == frame; }

    boolean matches(long drawSequence) {
        return drawSequence > 0 && hasCurrentObservation() && observedDraw == drawSequence;
    }

    long frame() { return frame; }
}
