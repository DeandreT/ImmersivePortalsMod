package qouteall.imm_ptl.client_test;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.phys.Vec3;
import qouteall.dimlib.api.DimensionAPI;
import qouteall.imm_ptl.core.api.PortalAPI;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.peripheral.riftwoven.RealmProtection;
import qouteall.imm_ptl.peripheral.riftwoven.environment.RealmEnvironment;
import qouteall.imm_ptl.peripheral.riftwoven.profile.RealmRecipe;
import qouteall.imm_ptl.peripheral.riftwoven.worldgen.RealmChunkGenerator;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Supplemental admin-created terrain reproduction, never a paid realm or ledger admission. */
final class TerrainReplayScene {
    static final long SEED = -8484714376220950612L;
    static final Vec3 CAMERA = new Vec3(24.5, 68, 24.5);
    static final BlockPos FLOOR = new BlockPos(24, 67, 24);
    static final ResourceKey<Level> DIMENSION = ResourceKey.create(Registries.DIMENSION,
        Identifier.fromNamespaceAndPath("ip_native_client_test", "terrain_replay"));

    static Map<String, Object> prepare(MinecraftServer server, RealmScene.Fixture fixture) {
        ServerPlayer player = player(server, fixture);
        ArtifactProof.require(player.level() == server.overworld() && server.getLevel(DIMENSION) == null,
            "Terrain replay must start only after the legitimate realm return in a fresh diagnostic dimension");
        var type = server.registryAccess().lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(BuiltinDimensionTypes.OVERWORLD);
        var profile = RealmRecipe.resolve(RealmScene.RECIPE);
        DimensionAPI.addDimensionDynamically(server, DIMENSION.identifier(),
            new LevelStem(type, RealmChunkGenerator.create(server.registryAccess(), profile, SEED)));
        ServerLevel level = server.getLevel(DIMENSION);
        ArtifactProof.require(level != null && level.getSeed() == SEED
            && level.getChunkSource().getGenerator() instanceof RealmChunkGenerator generator
            && generator.realmSeed() == SEED && generator.profile().equals(profile), "Wrong diagnostic terrain generator/seed/profile");
        level.getWorldBorder().setCenter(0, 0);
        level.getWorldBorder().setSize(profile.radius() * 2.0);
        // Publish dimension-addressed obfuscated seed/environment BEFORE requesting replay chunks.
        RealmEnvironment.send(player, level);
        // Preserve the real protected-arrival enclosure's block geometry, including its lights
        // and unbound core block, without inventing or copying a paid identity or block-entity data.
        ServerLevel paid = server.getLevel(fixture.dimension());
        RealmProtection.withArrivalEdits(() -> {
            for (BlockPos pos : BlockPos.betweenClosed(-8, 79, -8, 8, 85, 8)) {
                level.setBlock(pos, paid.getBlockState(pos), 3);
            }
        });
        level.getChunk(1, 1);
        ArtifactProof.require(level.getBlockState(FLOOR).is(Blocks.GRASS_BLOCK)
            && level.getBlockState(FLOOR.above()).isAir() && level.getBlockState(FLOOR.above(2)).isAir(),
            "Original seed no longer reproduces its recorded natural grass floor/standing space");
        var environment = RealmEnvironment.state(level);
        long before = environment.timeTicks();
        environment.sleep(profile.cycleTicks(), false, true);
        ArtifactProof.require(environment.timeTicks() == before && environment.weatherSuppressed(),
            "Diagnostic normal sleep weather clearing altered physical clock");
        RealmEnvironment.prepareWeather(level);
        RealmEnvironment.send(player, level);
        // Original source/entry/return evidence is already complete. Replace only that fixture's
        // expiring doorway, so overlapping source apertures cannot send this diagnostic elsewhere.
        var originalDoorway = server.overworld().getEntity(fixture.portal());
        if (originalDoorway != null) originalDoorway.discard();
        Portal portal = Portal.ENTITY_TYPE.create(server.overworld(), EntitySpawnReason.COMMAND);
        ArtifactProof.require(portal != null, "Cannot create ordinary diagnostic portal");
        portal.specificPlayerId = player.getUUID();
        portal.setOriginPos(new Vec3(0.5, 102.5, 0.5));
        portal.setOrientationAndSize(new Vec3(1, 0, 0), new Vec3(0, 1, 0), 3, 3);
        portal.setDestinationDimension(DIMENSION);
        portal.setDestination(new Vec3(0.5, 81.5, 0.5));
        ArtifactProof.require(server.overworld().addFreshEntity(portal), "Normal diagnostic portal admission failed");
        player.teleportTo(server.overworld(), RealmScene.SOURCE.x, RealmScene.SOURCE.y, RealmScene.SOURCE.z,
            Set.of(), 180, 0, true);
        var result = new LinkedHashMap<String, Object>();
        result.put("coverage", "supplemental terrain-only admin dimension; ordinary public Portal with real movement/predicted entry and the original same-dimension camera reposition; general PortalAPI safe return; NOT paid identity or private RealmPortal/ledger admission, and later in the JVM than the original first entry");
        result.put("dimension", DIMENSION.identifier().toString());
        result.put("seed", SEED);
        result.put("actual_level_seed", level.getSeed());
        result.put("expected_obfuscated_seed", BiomeManager.obfuscateSeed(SEED));
        result.put("camera", RealmTerrainDiagnostics.position(CAMERA));
        result.put("yaw", 135);
        result.put("pitch", 12);
        result.put("recipe", RealmScene.RECIPE);
        result.put("metadata_sent_before_chunk_requests", true);
        result.put("arrival_geometry_copied_without_identity", true);
        result.put("portal_uuid", portal.getUUID().toString());
        result.put("natural_floor", level.getBlockState(FLOOR).toString());
        return result;
    }

    static void camera(MinecraftServer server, RealmScene.Fixture fixture) {
        ServerPlayer player = player(server, fixture);
        ArtifactProof.require(player.level().dimension().equals(DIMENSION), "Diagnostic camera positioned before real ordinary portal entry");
        player.teleportTo(server.getLevel(DIMENSION), CAMERA.x, CAMERA.y, CAMERA.z, Set.of(), 135, 12, true);
    }

    static Map<String, Object> diagnostics(MinecraftServer server, RealmScene.Fixture fixture) {
        ServerPlayer player = player(server, fixture);
        ArtifactProof.require(player.level().dimension().equals(DIMENSION), "Diagnostic server player is not in the replay dimension");
        var result = new LinkedHashMap<>(RealmTerrainDiagnostics.sample(player.level(), player.position()));
        result.put("rain", player.level().getRainLevel(1));
        result.put("thunder", player.level().getThunderLevel(1));
        return result;
    }

    static boolean weatherClear(MinecraftServer server) {
        ServerLevel level = server.getLevel(DIMENSION);
        return level.getRainLevel(1) == 0 && level.getThunderLevel(1) == 0;
    }

    static void returnSafe(MinecraftServer server, RealmScene.Fixture fixture) {
        ServerPlayer player = player(server, fixture);
        PortalAPI.teleportEntity(player, server.overworld(), RealmScene.SOURCE);
    }

    private static ServerPlayer player(MinecraftServer server, RealmScene.Fixture fixture) {
        ServerPlayer player = server.getPlayerList().getPlayer(fixture.player());
        ArtifactProof.require(player != null, "Actual diagnostic player disappeared");
        return player;
    }

    private TerrainReplayScene() {}
}
