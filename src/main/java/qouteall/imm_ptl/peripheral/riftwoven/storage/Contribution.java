package qouteall.imm_ptl.peripheral.riftwoven.storage;

/** Vanilla, component-free offering stack. */
public record Contribution(String item, int count) {
    public Contribution {
        if (item == null || !item.matches("minecraft:[a-z0-9_]+") || count < 1 || count > 64) {
            throw new IllegalArgumentException("Invalid contribution");
        }
    }
}
