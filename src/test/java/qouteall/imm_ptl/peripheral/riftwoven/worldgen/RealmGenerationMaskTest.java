package qouteall.imm_ptl.peripheral.riftwoven.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.ticks.LevelTickAccess;
import net.minecraft.world.ticks.ScheduledTick;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import qouteall.imm_ptl.peripheral.riftwoven.profile.RealmProfile;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RealmGenerationMaskTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void onlyGenerationIsMaskedAtTheTerraceAndFiniteBoundary() {
        RealmTerrain terrain = new RealmTerrain(
            RealmTerrainTest.profile(128, 1, 0, RealmProfile.Topology.CONTINENT), 9);
        for (int y : new int[]{-64, 30, 79, 80, 84, 191}) {
            assertFalse(RealmGenerationMask.allows(terrain, new BlockPos(0, y, 0)));
            assertFalse(RealmGenerationMask.allows(terrain, new BlockPos(10, y, -10)));
            assertTrue(RealmGenerationMask.allows(terrain, new BlockPos(11, y, -10)));
            assertTrue(RealmGenerationMask.allows(terrain, new BlockPos(-128, y, 127)));
            assertFalse(RealmGenerationMask.allows(terrain, new BlockPos(128, y, 0)));
            assertFalse(RealmGenerationMask.allows(terrain, new BlockPos(-129, y, 0)));
        }
    }

    @Test
    void realInterfaceDefaultWriteHelpersCannotEscapeTheMask() {
        BackingWriter backing = new BackingWriter();
        RealmTerrain terrain = new RealmTerrain(
            RealmTerrainTest.profile(128, 1, 0, RealmProfile.Topology.CONTINENT), 9);
        WorldGenLevel masked = RealmGenerationMask.wrap(backing.level(), terrain, 0, 0);
        BlockState crystal = Blocks.AMETHYST_BLOCK.defaultBlockState();
        for (BlockPos protectedPos : new BlockPos[]{new BlockPos(0, 80, 0), new BlockPos(128, 80, 0)}) {
            backing.blocks.put(protectedPos, Blocks.STONE.defaultBlockState());
            assertFalse(masked.setBlockAndUpdate(protectedPos, crystal));
            assertFalse(masked.setBlock(protectedPos, crystal, 3));
            assertFalse(masked.setBlock(protectedPos, crystal, 3, 512));
            assertFalse(masked.destroyBlock(protectedPos, false));
            assertFalse(masked.destroyBlock(protectedPos, false, null));
            assertFalse(masked.removeBlock(protectedPos, false));
            assertEquals(Blocks.STONE.defaultBlockState(), backing.blocks.get(protectedPos),
                "Default helpers must not invoke their nested writes on the unmasked level");
            assertFalse(masked.ensureCanWrite(protectedPos));
        }
        BlockPos allowed = new BlockPos(32, 80, 32);
        assertTrue(masked.setBlockAndUpdate(allowed, crystal));
        assertEquals(crystal, backing.blocks.get(allowed), "Allowed default-helper placement must remain physical");
        assertTrue(masked.destroyBlock(allowed, false));
        assertEquals(Blocks.AIR.defaultBlockState(), backing.blocks.get(allowed));
        assertEquals(0, backing.escapedDefaultWrites,
            "Write-helper default bodies must run on the outer mask, never dispatch on the backing level");
        // The backing implementation deliberately refuses this otherwise allowed coordinate.
        assertFalse(masked.ensureCanWrite(new BlockPos(33, 80, 32)), "Do not replace the region's write-zone override with the interface's true default");
    }

    @Test
    void bothDefaultSchedulingAndDirectTickAccessRespectProtectedCoordinates() {
        BackingWriter backing = new BackingWriter();
        RealmTerrain terrain = new RealmTerrain(
            RealmTerrainTest.profile(128, 1, 0, RealmProfile.Topology.CONTINENT), 9);
        WorldGenLevel masked = RealmGenerationMask.wrap(backing.level(), terrain, 0, 0);
        BlockPos terrace = new BlockPos(0, 80, 0), outside = new BlockPos(128, 80, 0), allowed = new BlockPos(32, 80, 32);
        for (BlockPos pos : new BlockPos[]{terrace, outside}) {
            masked.scheduleTick(pos, Blocks.STONE, 1);
            masked.scheduleTick(pos, Fluids.WATER, 1);
            masked.getBlockTicks().schedule(new ScheduledTick<>(Blocks.STONE, pos, 1, 0));
            masked.getFluidTicks().schedule(new ScheduledTick<>(Fluids.WATER, pos, 1, 0));
        }
        assertEquals(0, backing.blockTicks.count());
        assertEquals(0, backing.fluidTicks.count());
        masked.scheduleTick(allowed, Blocks.STONE, 1);
        masked.scheduleTick(allowed, Fluids.WATER, 1);
        assertTrue(backing.blockTicks.hasScheduledTick(allowed, Blocks.STONE));
        assertTrue(backing.fluidTicks.hasScheduledTick(allowed, Fluids.WATER));
    }

    /** Minimal backing storage, but actual vanilla interface default bodies execute unchanged. */
    private static final class BackingWriter {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();
        final RecordingTicks<Block> blockTicks = new RecordingTicks<>();
        final RecordingTicks<Fluid> fluidTicks = new RecordingTicks<>();
        int escapedDefaultWrites;

        WorldGenLevel level() {
            return (WorldGenLevel) Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(),
                new Class<?>[]{WorldGenLevel.class}, (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("ensureCanWrite")) { return ((BlockPos) args[0]).getX() != 33; }
                    if (name.equals("setBlock") && args.length == 4) {
                        blocks.put(((BlockPos) args[0]).immutable(), (BlockState) args[1]);
                        return true;
                    }
                    if (name.equals("removeBlock") || name.equals("destroyBlock") && args.length == 4) {
                        blocks.put(((BlockPos) args[0]).immutable(), Blocks.AIR.defaultBlockState());
                        return true;
                    }
                    if (name.equals("getBlockTicks")) { return blockTicks; }
                    if (name.equals("getFluidTicks")) { return fluidTicks; }
                    if (name.equals("createTick")) {
                        return args.length == 4
                            ? new ScheduledTick<>(args[1], (BlockPos) args[0], (int) args[2], (net.minecraft.world.ticks.TickPriority) args[3], 0)
                            : new ScheduledTick<>(args[1], (BlockPos) args[0], (int) args[2], 0);
                    }
                    if (method.isDefault()) {
                        if (name.startsWith("setBlock") || name.equals("destroyBlock") || name.equals("scheduleTick")) {
                            escapedDefaultWrites++;
                        }
                        return InvocationHandler.invokeDefault(proxy, method, args);
                    }
                    throw new UnsupportedOperationException("Unexpected backing call: " + name);
                });
        }
    }

    private static final class RecordingTicks<T> implements LevelTickAccess<T> {
        final Set<ScheduledTick<T>> ticks = new HashSet<>();
        @Override
        public void schedule(ScheduledTick<T> tick) { ticks.add(tick); }
        @Override
        public boolean hasScheduledTick(BlockPos pos, T type) { return ticks.stream().anyMatch(tick -> tick.pos().equals(pos) && tick.type() == type); }
        @Override
        public boolean willTickThisTick(BlockPos pos, T type) { return hasScheduledTick(pos, type); }
        @Override
        public int count() { return ticks.size(); }
    }
}
