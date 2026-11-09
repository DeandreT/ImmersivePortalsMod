package qouteall.imm_ptl.client_test;

/** Keeps zero-valued successful commands distinct from failures or absent callbacks. */
final class BaselineCommandOutcome {
    private int callbacks;
    private boolean failed;
    private int result;

    void accept(boolean success, int value) {
        callbacks++;
        failed |= !success;
        result = value;
    }

    boolean succeeded() { return callbacks > 0 && !failed; }
    int callbacks() { return callbacks; }
    int result() { return result; }
}
