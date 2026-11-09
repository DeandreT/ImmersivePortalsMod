package qouteall.imm_ptl.peripheral.riftwoven;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import qouteall.imm_ptl.peripheral.riftwoven.worldgen.RealmChunkGenerator;
import qouteall.imm_ptl.peripheral.riftwoven.worldgen.RealmTerrain;

/** Target-world permission checks shared by ordinary and cross-portal interactions. */
public final class RealmProtection {
    private static final ThreadLocal<Boolean> ARRIVAL_EDITS = new ThreadLocal<>();

    private RealmProtection() {}

    public static boolean isRealm(Level level) {
        return level instanceof ServerLevel serverLevel
            && serverLevel.getChunkSource().getGenerator() instanceof RealmChunkGenerator;
    }

    /** Only the shelter is immutable; the same columns remain mineable beneath it. */
    public static boolean terrace(int x, int y, int z) {
        return footprint(x, z) && y >= RealmTerrain.ARRIVAL_Y - 2 && y <= RealmTerrain.ARRIVAL_Y + 6;
    }

    public static boolean standing(int x, int y, int z) {
        return footprint(x, z) && y >= RealmTerrain.ARRIVAL_Y && y <= RealmTerrain.ARRIVAL_Y + 4;
    }

    private static boolean footprint(int x, int z) {
        return x >= -RealmTerrain.TERRACE_RADIUS && x <= RealmTerrain.TERRACE_RADIUS
            && z >= -RealmTerrain.TERRACE_RADIUS && z <= RealmTerrain.TERRACE_RADIUS;
    }

    public static boolean protectedBlock(Level level, BlockPos pos) {
        return terrace(pos.getX(), pos.getY(), pos.getZ()) && isRealm(level);
    }

    public static boolean protectedArrival(Level level, BlockPos pos) {
        return standing(pos.getX(), pos.getY(), pos.getZ()) && isRealm(level);
    }

    public static boolean canModify(ServerPlayer player, BlockPos pos) {
        return canModify(player, player.level(), pos);
    }

    /** The level is explicit: a player outside a realm may still reach into it through a portal. */
    public static boolean canModify(ServerPlayer player, Level target, BlockPos pos) {
        if (!isRealm(target)) return true;
        return !protectedBlock(target, pos) && canBuild(player, target);
    }

    public static boolean canBuild(ServerPlayer player, Level target) {
        if (!isRealm(target)) return true;
        return Riftwoven.canBuild(player, (ServerLevel) target);
    }

    public static boolean arrivalEditsAllowed() {
        return Boolean.TRUE.equals(ARRIVAL_EDITS.get());
    }

    /** For synchronous server initialization only, never for a player interaction. */
    public static void withArrivalEdits(Runnable action) {
        Boolean previous = ARRIVAL_EDITS.get();
        ARRIVAL_EDITS.set(true);
        try {
            action.run();
        }
        finally {
            if (previous == null) ARRIVAL_EDITS.remove();
            else ARRIVAL_EDITS.set(previous);
        }
    }
}
