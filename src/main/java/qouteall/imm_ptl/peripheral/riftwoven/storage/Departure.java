package qouteall.imm_ptl.peripheral.riftwoven.storage;

import java.util.UUID;
import net.minecraft.resources.Identifier;

/** Visit return rights are independent of current membership or a surviving item reference. */
public record Departure(UUID realm, String dimension, double x, double y, double z, float yaw, float pitch) {
    public Departure {
        if (realm == null || dimension == null || dimension.length() > 256 || Identifier.tryParse(dimension) == null ||
            !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) ||
            Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000 || Math.abs(y) > 20_000_000 ||
            !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            throw new IllegalArgumentException("Invalid departure");
        }
    }
}
