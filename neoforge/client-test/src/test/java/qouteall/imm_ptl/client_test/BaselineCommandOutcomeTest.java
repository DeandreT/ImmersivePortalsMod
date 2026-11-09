package qouteall.imm_ptl.client_test;

/** Runs with the existing JDK, without Minecraft, a display, or a game. */
public final class BaselineCommandOutcomeTest {
    public static void main(String[] args) {
        var absent = new BaselineCommandOutcome();
        require(!absent.succeeded(), "Missing callback must fail closed");
        var zero = new BaselineCommandOutcome();
        zero.accept(true, 0);
        require(zero.succeeded() && zero.callbacks() == 1 && zero.result() == 0,
            "A successful false-valued gamerule returns zero, not failure");
        var failed = new BaselineCommandOutcome();
        failed.accept(false, 0);
        failed.accept(true, 4225);
        require(!failed.succeeded() && failed.callbacks() == 2,
            "A later successful branch must not hide an earlier failure");
        var fill = new BaselineCommandOutcome();
        fill.accept(true, 4225);
        require(fill.succeeded() && fill.result() == 4225, "Fill result must be retained");
    }

    private static void require(boolean condition, String message) {
        if (!condition) { throw new AssertionError(message); }
    }
}
