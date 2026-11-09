package qouteall.imm_ptl.client_test;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.peripheral.riftwoven.RealmCoreBlock;
import qouteall.imm_ptl.peripheral.riftwoven.RealmCoreBlockEntity;
import qouteall.imm_ptl.peripheral.riftwoven.RealmFormation;
import qouteall.imm_ptl.peripheral.riftwoven.RealmPortal;
import qouteall.imm_ptl.peripheral.riftwoven.Riftwoven;
import qouteall.imm_ptl.peripheral.riftwoven.environment.RealmEnvironment;
import qouteall.imm_ptl.peripheral.riftwoven.environment.RealmEnvironmentMath;
import qouteall.imm_ptl.peripheral.riftwoven.storage.Departure;
import qouteall.imm_ptl.peripheral.riftwoven.storage.RealmLedger;
import qouteall.imm_ptl.peripheral.riftwoven.storage.RealmRecord;
import qouteall.imm_ptl.peripheral.riftwoven.worldgen.RealmChunkGenerator;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Server-thread fixtures using public packaged APIs, never synthetic peers or test-only dimension transfers. */
final class RealmScene {
    static final BlockPos CORE = new BlockPos(0, 100, 0);
    static final Vec3 SOURCE = new Vec3(0.5, 101, 3.5);
    static final Map<String, Integer> RECIPE = Map.of(
        "minecraft:moss_block", 16, "minecraft:glowstone", 16, "minecraft:echo_shard", 1);

    record Fixture(UUID player, RealmRecord record, ResourceKey<Level> dimension, UUID portal,
                   BlockPos terrainSample, String terrainState,
                   Map<String, Object> evidence) {}

    record Environment(long physicalTicks, double clockTicks, float rate, double overworldClockTicks,
                       float rainLevel, float thunderLevel) {}

    static Fixture prepare(MinecraftServer server) throws IOException {
        ArtifactProof.require(!server.isDedicatedServer() && server.getPlayerList().getPlayers().size() == 1,
            "Realm scenario requires exactly one actual integrated-server player");
        ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
        ArtifactProof.require(player.level() == server.overworld() && player.gameMode.getGameModeForPlayer() == GameType.SURVIVAL,
            "Realm fixture must start with the genuine survival player");
        Scene.command(server, "gamerule spawn_mobs false");
        Scene.command(server, "gamerule fall_damage false");
        Scene.command(server, "time set noon");
        Scene.command(server, "forceload add -32 -32 31 31");
        Scene.command(server, "fill -8 99 -8 8 106 8 minecraft:air");
        Scene.command(server, "fill -8 100 1 8 100 8 minecraft:stone");
        ServerLevel source = server.overworld();
        for (int x = -2; x <= 2; x++) source.setBlock(CORE.offset(x, -1, 0), Blocks.STONE.defaultBlockState(), 3);
        for (int x : new int[]{-2, 2}) {
            for (int y = 0; y < 3; y++) source.setBlock(CORE.offset(x, y, 0), Blocks.CRYING_OBSIDIAN.defaultBlockState(), 3);
            source.setBlock(CORE.offset(x, 3, 0), Blocks.AMETHYST_BLOCK.defaultBlockState(), 3);
        }
        source.setBlock(CORE, Riftwoven.CORE.defaultBlockState().setValue(RealmCoreBlock.FACING, Direction.NORTH), 3);
        Riftwoven.placed(player, CORE, new ItemStack(Riftwoven.SEED));
        ArtifactProof.require(RealmFormation.problem(source, CORE, Direction.NORTH) == null, "Invalid survival seed formation");
        var core = (RealmCoreBlockEntity) source.getBlockEntity(CORE);
        ArtifactProof.require(core != null && core.realmId() != null, "Real planted core did not bind a ledger draft");
        UUID id = core.realmId();
        RealmLedger ledger = RealmLedger.open(server);
        player.getInventory().clearContent();
        player.getInventory().setSelectedSlot(0);
        offer(player, Items.MOSS_BLOCK, 16);
        ArtifactProof.require(count(player, Items.MOSS_BLOCK) == 0 && ledger.draft(id).contributions().size() == 1,
            "Offering did not debit the real survival inventory");
        player.setShiftKeyDown(true);
        Riftwoven.useCore(player, CORE, InteractionHand.MAIN_HAND);
        player.setShiftKeyDown(false);
        ArtifactProof.require(count(player, Items.MOSS_BLOCK) == 16 && ledger.draft(id).contributions().isEmpty(),
            "Core withdrawal did not refund exactly sixteen real items");
        // Withdrawal puts these items back in the selected empty slot; offer the actual refund.
        ArtifactProof.require(player.getMainHandItem().is(Items.MOSS_BLOCK), "Refund did not occupy the selected empty slot");
        Riftwoven.useCore(player, CORE, InteractionHand.MAIN_HAND);
        ArtifactProof.require(count(player, Items.MOSS_BLOCK) == 0, "Re-offering the refund did not debit it");
        offer(player, Items.GLOWSTONE, 16);
        offer(player, Items.ECHO_SHARD, 1);
        ArtifactProof.require(ledger.draft(id).recipe().equals(RECIPE) && ledger.realm(id) == null,
            "Unpaid draft differs from deliberate offerings or was awakened prematurely");
        int worldsBefore = server.levelKeys().size();
        ArtifactProof.require(worldsBefore == 3, "Unpaid preview created an extra dimension");
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND, 8));
        RealmRecord paid = ledger.awaken(player, id);
        ArtifactProof.require(count(player, Items.DIAMOND) == 4 && paid.profile().suns() == 2 && paid.profile().moons() == 1,
            "Actual payment or committed two-sun recipe differs");
        ServerLevel realm = Riftwoven.ensureWorld(server, paid);
        Riftwoven.prepareArrival(realm, paid);
        ArtifactProof.require(realm.getSeed() == paid.seed() && realm.getSeed() != source.getSeed(),
            "Actual ServerLevel.getSeed() is not the independent committed realm seed");
        ArtifactProof.require(realm.getChunkSource().getGenerator() instanceof RealmChunkGenerator generator
            && generator.realmSeed() == paid.seed() && generator.profile().equals(paid.profile()), "Wrong actual realm generator");
        ArtifactProof.require(realm.getWorldBorder().getSize() == paid.profile().radius() * 2.0,
            "Actual realm border is not finite and profile-sized");
        ArtifactProof.require(server.saveEverything(false, true, true), "Normal integrated-server world save did not complete");
        // Reopen the real durable journal, not an in-memory record or copied test save.
        RealmLedger.close(server);
        ledger = RealmLedger.open(server);
        RealmRecord retry = ledger.awaken(player, id);
        ArtifactProof.require(paid.equals(retry) && count(player, Items.DIAMOND) == 4
            && ledger.realms().size() == 1 && Riftwoven.ensureWorld(server, retry) == realm
            && realm.getSeed() == retry.seed() && server.levelKeys().size() == worldsBefore + 1,
            "Saved idempotent retry changed identity, world seed, dimension count or charged twice");
        // The ritual itself is qualified by the Fabric TCP test. Here we deliberately construct
        // a normal private RealmPortal using its public API; all view/travel admission stays live.
        player.teleportTo(source, SOURCE.x, SOURCE.y, SOURCE.z, Set.of(), 180, 0, true);
        player.getAbilities().flying = false;
        player.onUpdateAbilities();
        ledger.recordDeparture(player.getUUID(), new Departure(id, Level.OVERWORLD.identifier().toString(),
            SOURCE.x, SOURCE.y, SOURCE.z, 180, 0));
        ArtifactProof.require(Riftwoven.portalViewAllowed(player, id) && Riftwoven.portalAdmission(player, id, false),
            "Normal private portal view/entry admission refused the owner");
        RealmPortal portal = Riftwoven.REALM_PORTAL.create(source, EntitySpawnReason.COMMAND);
        ArtifactProof.require(portal != null, "Cannot construct packaged RealmPortal");
        portal.configure(id, player.getUUID(), false);
        portal.setOriginPos(new Vec3(0.5, 102.5, 0.5));
        portal.setOrientationAndSize(new Vec3(1, 0, 0), new Vec3(0, 1, 0), 3, 3);
        portal.setDestinationDimension(realm.dimension());
        portal.setDestination(new Vec3(0.5, 81.5, 0.5));
        ArtifactProof.require(source.addFreshEntity(portal), "Normal RealmPortal entity admission failed");
        // Move the actual remainder, never fabricate replacement payment evidence.
        player.getInventory().setItem(10, player.getMainHandItem());
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        player.inventoryMenu.broadcastChanges();
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("formation_valid", true);
        evidence.put("survival_player", true);
        evidence.put("offering_refund_reoffering", true);
        evidence.put("unpaid_worlds", worldsBefore);
        evidence.put("diamonds_before", 8);
        evidence.put("diamonds_after", count(player, Items.DIAMOND));
        evidence.put("saved_idempotent_retry", true);
        evidence.put("world_saved_before_retry", true);
        evidence.put("realm_count", ledger.realms().size());
        evidence.put("realm_seed", paid.seed());
        evidence.put("actual_level_seed", realm.getSeed());
        evidence.put("overworld_seed", source.getSeed());
        evidence.put("border_size", realm.getWorldBorder().getSize());
        evidence.put("radius", paid.profile().radius());
        evidence.put("cycle_ticks", paid.profile().cycleTicks());
        evidence.put("daylight_ticks", paid.profile().daylightTicks());
        evidence.put("suns", paid.profile().suns());
        evidence.put("moons", paid.profile().moons());
        evidence.put("dimension", realm.dimension().identifier().toString());
        evidence.put("portal_uuid", portal.getUUID().toString());
        evidence.put("doorway_setup", "public RealmPortal.configure + durable ledger departure; not physical hold ritual");
        realm.getChunk(1, 1);
        BlockPos terrainSample = StableTerrainSample.choose(realm, 24, 24);
        ArtifactProof.require(!realm.getBlockState(terrainSample).isAir(), "Generated terrain sample is empty");
        String terrainState = realm.getBlockState(terrainSample).toString();
        evidence.put("terrain_sample", Map.of("x", 24, "y", terrainSample.getY(), "z", 24, "state", terrainState));
        evidence.put("terrain_sample_selection", "unmodified natural full-solid non-ticking geology; bounded downward scan at x=24,z=24; exact full state comparison retained");
        evidence.put("terrain_sample_max_scan_depth", StableTerrainSamplePolicy.MAX_SCAN_DEPTH);
        return new Fixture(player.getUUID(), paid, realm.dimension(), portal.getUUID(), terrainSample, terrainState, evidence);
    }

    private static void offer(ServerPlayer player, Item item, int count) {
        ArtifactProof.require(player.getMainHandItem().isEmpty(), "Fixture would overwrite a held contribution");
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(item, count));
        Riftwoven.useCore(player, CORE, InteractionHand.MAIN_HAND);
        ArtifactProof.require(player.getMainHandItem().isEmpty(), "Core did not consume the real offered inventory");
    }

    private static int count(ServerPlayer player, Item item) {
        int result = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            var stack = player.getInventory().getItem(slot);
            if (stack.is(item)) result += stack.getCount();
        }
        return result;
    }

    /** A same-dimension camera fixture on unmodified generated terrain, only AFTER real portal entry. */
    static Vec3 terrainCamera(MinecraftServer server, Fixture fixture) throws IOException {
        ServerPlayer player = player(server, fixture);
        ServerLevel level = server.getLevel(fixture.dimension());
        ArtifactProof.require(player.level() == level && RealmLedger.open(server).canReturn(player.getUUID(), fixture.record().id()),
            "Cannot position terrain camera before genuine admitted realm arrival");
        // Use the normal realm sleep weather policy after genuine arrival, without changing
        // its committed weather recipe, physical clock or the Overworld weather. Normal ticks
        // still have to fade the actual server/client rain before the photographic stage.
        var environment = RealmEnvironment.state(level);
        long physicalTicks = environment.timeTicks();
        environment.sleep(fixture.record().profile().cycleTicks(), false, true);
        ArtifactProof.require(environment.timeTicks() == physicalTicks && environment.weatherSuppressed(),
            "Normal sleep weather clearing changed the realm clock or failed to suppress weather");
        RealmEnvironment.prepareWeather(level);
        RealmEnvironment.send(player, level);
        for (int x = 24; x <= 64; x += 8) for (int z = 24; z <= 64; z += 8) {
            level.getChunk(x >> 4, z >> 4);
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos feet = new BlockPos(x, y, z);
            if (!level.getBlockState(feet).isAir() || !level.getBlockState(feet.above()).isAir()
                || !level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), Direction.UP)
                || !level.getFluidState(feet.below()).isEmpty()) continue;
            Vec3 position = Vec3.atBottomCenterOf(feet);
            player.teleportTo(level, position.x, position.y, position.z, Set.of(), 135, 12, true);
            return position;
        }
        throw new IllegalStateException("No unmodified dry generated terrain camera site");
    }

    static void returnCore(MinecraftServer server, Fixture fixture) throws IOException {
        ServerPlayer player = player(server, fixture);
        ServerLevel level = server.getLevel(fixture.dimension());
        ArtifactProof.require(player.level() == level && level.getBlockEntity(new BlockPos(0, 80, 4)) instanceof RealmCoreBlockEntity core
            && fixture.record().id().equals(core.realmId()), "Permanent arrival return core is missing or unbound");
        Riftwoven.useCore(player, new BlockPos(0, 80, 4), InteractionHand.MAIN_HAND);
        ArtifactProof.require(RealmLedger.open(server).departure(player.getUUID()) != null,
            "Return consumed durable departure before the actual client ACK");
    }

    static Map<String, Object> terrainDiagnostics(MinecraftServer server, Fixture fixture) {
        ServerPlayer player = player(server, fixture);
        var result = new LinkedHashMap<>(RealmTerrainDiagnostics.sample(player.level(), player.position()));
        result.put("yaw", player.getYRot());
        result.put("pitch", player.getXRot());
        return result;
    }

    static Map<String, Object> previewDiagnostics(MinecraftServer server, Fixture fixture) {
        ServerLevel realm = server.getLevel(fixture.dimension());
        return Map.of("terrain_state", realm.getBlockState(fixture.terrainSample()).toString(),
            "server_tick", server.getTickCount(), "realm_game_time", realm.getGameTime(),
            "realm_physical_ticks", RealmEnvironment.state(realm).timeTicks());
    }

    static boolean departureCleared(MinecraftServer server, Fixture fixture) throws IOException {
        return RealmLedger.open(server).departure(fixture.player()) == null;
    }

    static Environment environment(MinecraftServer server, Fixture fixture) {
        ServerLevel realm = server.getLevel(fixture.dimension());
        var state = RealmEnvironment.state(realm);
        var clock = realm.clockManager().getInstance(realm.dimensionType().defaultClock().orElseThrow());
        var sourceClock = server.overworld().clockManager().getInstance(server.overworld().dimensionType().defaultClock().orElseThrow());
        double expected = RealmEnvironmentMath.clockTime(state.timeTicks(), fixture.record().profile().cycleTicks(),
            fixture.record().profile().daylightTicks());
        double actual = clock.totalTicks() + clock.partialTick();
        ArtifactProof.require(Math.abs(actual - expected) < 2, "Actual server realm clock differs from its saved physical state");
        ArtifactProof.require(count(player(server, fixture), Items.DIAMOND) == 4, "Unspent real diamonds changed after payment/retry");
        return new Environment(state.timeTicks(), actual, clock.rate(), sourceClock.totalTicks() + sourceClock.partialTick(),
            realm.getRainLevel(1), realm.getThunderLevel(1));
    }

    private static ServerPlayer player(MinecraftServer server, Fixture fixture) {
        ServerPlayer player = server.getPlayerList().getPlayer(fixture.player());
        ArtifactProof.require(player != null, "Actual integrated player disappeared");
        return player;
    }

    private RealmScene() {}
}
