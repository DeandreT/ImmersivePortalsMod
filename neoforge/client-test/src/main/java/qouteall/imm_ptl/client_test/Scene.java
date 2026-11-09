package qouteall.imm_ptl.client_test;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.portal.Portal;

/** Scene setup runs only on this new integrated server's server thread. */
final class Scene {
    static void prepare(MinecraftServer server) {
        ArtifactProof.require(!server.isDedicatedServer() && server.getPlayerList().getPlayers().size() == 1,
            "Expected exactly one actual integrated-server player");
        var player = server.getPlayerList().getPlayers().getFirst();
        ArtifactProof.require(player.level() == server.overworld(), "Fresh world did not start in the Overworld");
        for (String command : new String[]{
            "gamerule advance_time false", "gamerule spawn_mobs false", "gamerule fall_damage false", "time set noon",
            "forceload add -32 -32 31 31", "execute in minecraft:the_nether run forceload add -16 -16 15 15",
            "fill -32 -40 -32 32 -40 32 minecraft:lime_concrete",
            "setblock 0 -21 0 minecraft:glass",
            "execute in minecraft:the_nether run fill -10 90 -10 10 120 10 minecraft:red_concrete hollow",
            "execute in minecraft:the_nether run fill -9 99 -9 9 99 9 minecraft:red_concrete",
            "execute in minecraft:the_nether run fill -9 119 -9 9 119 9 minecraft:glowstone"
        }) { checkedCommand(server, command); }
        var nether = server.getLevel(Level.NETHER);
        ArtifactProof.require(nether != null, "Destination dimension is absent");
        int lime = 0;
        for (BlockPos pos : BlockPos.betweenClosed(-32, -40, -32, 32, -40, 32)) {
            if (server.overworld().getBlockState(pos).is(Blocks.CONCRETE.pick(DyeColor.LIME))) { lime++; }
        }
        var markers = java.util.Map.<String, Object>of(
            "lime_count", lime, "expected_lime_count", 4225,
            "glass", server.overworld().getBlockState(new BlockPos(0, -21, 0)).toString(),
            "red_floor", nether.getBlockState(new BlockPos(0, 99, 0)).toString(),
            "red_wall", nether.getBlockState(new BlockPos(-10, 108, 0)).toString());
        BaselineMarkerDiagnostics.serverMarkers(markers);
        ArtifactProof.require(lime == 4225
            && server.overworld().getBlockState(new BlockPos(0, -21, 0)).is(Blocks.GLASS)
            && nether.getBlockState(new BlockPos(0, 99, 0)).is(Blocks.CONCRETE.pick(DyeColor.RED))
            && nether.getBlockState(new BlockPos(-10, 108, 0)).is(Blocks.CONCRETE.pick(DyeColor.RED)),
            "Baseline server marker fixture differs: " + markers);
        portal(server.overworld(), -30, server.getLevel(Level.NETHER), 108);
        portal(server.getLevel(Level.NETHER), 98, server.overworld(), -15);
        // This is the ONLY positioning teleport in the harness, within the source dimension.
        // Both subsequent dimension transitions must come from normal portal prediction.
        player.teleportTo(server.overworld(), 0.5, -20, 0.5, java.util.Set.of(), 0, 90, true);
        player.getAbilities().flying = false;
        player.onUpdateAbilities();
    }

    private static void portal(ServerLevel from, double y, ServerLevel to, double targetY) {
        ArtifactProof.require(to != null, "Destination dimension is absent");
        Portal portal = Portal.ENTITY_TYPE.create(from, EntitySpawnReason.COMMAND);
        ArtifactProof.require(portal != null, "Cannot create actual main-mod portal");
        portal.setPos(0.5, y, 0.5);
        portal.setOrientationAndSize(new Vec3(0, 0, 1), new Vec3(1, 0, 0), 12, 12);
        portal.setDestinationDimension(to.dimension());
        portal.setDestination(new Vec3(0.5, targetY, 0.5));
        ArtifactProof.require(from.addFreshEntity(portal), "Normal portal admission failed");
    }

    static void release(MinecraftServer server, boolean returning) {
        // Remove a support block, never send a teleport or fake client input/ACK.
        if (returning) { checkedCommand(server, "setblock 0 -21 0 minecraft:glass"); }
        checkedCommand(server, returning ? "execute in minecraft:the_nether run setblock 0 99 0 minecraft:air"
            : "setblock 0 -21 0 minecraft:air");
    }

    private static void checkedCommand(MinecraftServer server, String command) {
        var outcome = new BaselineCommandOutcome();
        server.getCommands().performPrefixedCommand(
            server.createCommandSourceStack().withCallback(outcome::accept), command);
        BaselineMarkerDiagnostics.command(command, outcome);
        ArtifactProof.require(outcome.succeeded(), "Baseline command failed or produced no result callback: " + command);
    }

    static void command(MinecraftServer server, String command) {
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
    }
    private Scene() {}
}
