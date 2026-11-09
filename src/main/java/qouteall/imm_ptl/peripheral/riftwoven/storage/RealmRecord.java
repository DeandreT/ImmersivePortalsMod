package qouteall.imm_ptl.peripheral.riftwoven.storage;

import java.util.List;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import qouteall.imm_ptl.peripheral.riftwoven.profile.RealmProfile;

/** Frozen paid identity. Dimension activation is deliberately separate and retryable. */
public record RealmRecord(UUID id, UUID owner, long seed, RealmProfile profile,
                          List<Contribution> contributions, int recipeVersion, int generatorVersion) {
    public RealmRecord {
        java.util.Objects.requireNonNull(id);
        java.util.Objects.requireNonNull(owner);
        java.util.Objects.requireNonNull(profile);
        contributions = List.copyOf(contributions);
        if (recipeVersion != profile.version() || generatorVersion != 1) {
            throw new IllegalArgumentException("Unsupported frozen recipe or generator version");
        }
    }

    public RealmRecord(UUID id, UUID owner, long seed, RealmProfile profile, List<Contribution> contributions) {
        this(id, owner, seed, profile, contributions, profile.version(), 1);
    }

    public Identifier dimensionId() {
        return Identifier.fromNamespaceAndPath("riftwoven", "realm_" + id.toString().replace("-", ""));
    }
}
