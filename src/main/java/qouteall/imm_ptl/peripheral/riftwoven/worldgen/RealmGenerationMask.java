package qouteall.imm_ptl.peripheral.riftwoven.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.ticks.LevelTickAccess;
import net.minecraft.world.ticks.ScheduledTick;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;

/** A generation-only view: never installed on a live level and never restricts player building. */
final class RealmGenerationMask {
    private RealmGenerationMask() {}

    static WorldGenLevel wrap(WorldGenLevel level, RealmTerrain terrain, int chunkX, int chunkZ) {
        RandomSource random = RandomSource.create(RealmTerrain.mix(
            terrain.seed() ^ chunkX * 73428767L ^ chunkZ * 4382893L ^ 0x46454154555245L
        ));
        // WorldGenLevel is the vanilla feature API. Forward every read and unknown extension method,
        // but guard all write entrypoints, including default overloads, before invoking the region.
        // ensureCanWrite also covers OreFeature's direct BulkSectionAccess writes.
        return (WorldGenLevel) Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(),
            new Class<?>[]{WorldGenLevel.class}, (proxy, method, args) -> {
                String name = method.getName();
                if (name.equals("getSeed")) { return terrain.seed(); }
                if (name.equals("getRandom")) { return random; }
                if (name.equals("getBlockTicks")) { return filteredTicks(level.getBlockTicks(), terrain); }
                if (name.equals("getFluidTicks")) { return filteredTicks(level.getFluidTicks(), terrain); }
                boolean blockMutation = name.startsWith("setBlock") || name.equals("removeBlock")
                    || name.equals("destroyBlock") || name.equals("scheduleTick")
                    || name.equals("neighborShapeChanged") || name.equals("updateNeighborsAt")
                    || name.equals("blockEntityChanged");
                if (blockMutation && args != null) {
                    for (Object argument : args) {
                        BlockPos target = argument instanceof BlockPos pos ? pos
                            : argument instanceof BlockEntity entity ? entity.getBlockPos() : null;
                        if (target != null) {
                            if (!allows(terrain, target)) { return method.getReturnType() == boolean.class ? false : null; }
                            break;
                        }
                    }
                }
                if (args != null && args.length > 0 && args[0] instanceof BlockPos pos) {
                    boolean allowed = allows(terrain, pos);
                    if (name.equals("ensureCanWrite") || name.equals("isWithinWriteZone")) {
                        if (!allowed) { return false; }
                    }
                    if (name.equals("getBlockEntity") && !allowed) {
                        return method.getReturnType() == java.util.Optional.class ? java.util.Optional.empty() : null;
                    }
                }
                if (args != null && args.length > 0 && args[0] instanceof Entity entity
                    && (name.equals("addFreshEntity") || name.equals("addFreshEntityWithPassengers"))) {
                    if (!allows(terrain, entity.blockPosition())) {
                        return method.getReturnType() == boolean.class ? false : null;
                    }
                }
                // Invoke write-helper defaults ON THE PROXY, not the backing level. Otherwise a
                // helper such as setBlockAndUpdate internally calls the real level's setBlock and
                // escapes every guard. Preserve concrete read/region overrides (notably getChunk
                // and ensureCanWrite), whose interface defaults have different worldgen semantics.
                if (method.isDefault() && (blockMutation || name.equals("addFreshEntityWithPassengers"))) {
                    return InvocationHandler.invokeDefault(proxy, method, args);
                }
                try {
                    return method.invoke(level, args);
                } catch (InvocationTargetException exception) {
                    throw exception.getCause();
                }
            });
    }

    static boolean allows(RealmTerrain terrain, BlockPos pos) {
        return terrain.inside(pos.getX(), pos.getZ()) && !terrain.terrace(pos.getX(), pos.getZ());
    }

    private static <T> LevelTickAccess<T> filteredTicks(LevelTickAccess<T> ticks, RealmTerrain terrain) {
        return new LevelTickAccess<>() {
            @Override
            public void schedule(ScheduledTick<T> tick) {
                if (allows(terrain, tick.pos())) { ticks.schedule(tick); }
            }

            @Override
            public boolean hasScheduledTick(BlockPos pos, T type) { return ticks.hasScheduledTick(pos, type); }
            @Override
            public boolean willTickThisTick(BlockPos pos, T type) { return ticks.willTickThisTick(pos, type); }
            @Override
            public int count() { return ticks.count(); }
        };
    }
}
