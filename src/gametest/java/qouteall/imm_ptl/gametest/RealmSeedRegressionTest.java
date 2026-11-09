package qouteall.imm_ptl.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.api.PortalAPI;
import qouteall.imm_ptl.core.chunk_loading.ChunkLoader;
import qouteall.imm_ptl.core.ducks.IEServerPlayNetworkHandler;
import qouteall.imm_ptl.core.miscellaneous.IPortalInitialScreen;
import qouteall.imm_ptl.core.platform_shared.EntityRegistration;
import qouteall.imm_ptl.peripheral.riftwoven.RealmCoreBlock;
import qouteall.imm_ptl.peripheral.riftwoven.RealmCoreBlockEntity;
import qouteall.imm_ptl.peripheral.riftwoven.RealmFormation;
import qouteall.imm_ptl.peripheral.riftwoven.RealmPortal;
import qouteall.imm_ptl.peripheral.riftwoven.RealmProtection;
import qouteall.imm_ptl.peripheral.riftwoven.Riftwoven;
import qouteall.imm_ptl.peripheral.riftwoven.profile.RealmRecipe;
import qouteall.imm_ptl.peripheral.riftwoven.storage.Departure;
import qouteall.imm_ptl.peripheral.riftwoven.storage.LedgerStore;
import qouteall.imm_ptl.peripheral.riftwoven.storage.RealmLedger;
import qouteall.imm_ptl.peripheral.riftwoven.storage.RealmRecord;
import qouteall.imm_ptl.peripheral.riftwoven.storage.TransactionJournal;
import qouteall.imm_ptl.peripheral.riftwoven.worldgen.RealmBiomeSource;
import qouteall.imm_ptl.peripheral.riftwoven.worldgen.RealmChunkGenerator;
import qouteall.imm_ptl.peripheral.riftwoven.worldgen.RealmTerrain;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Opt-in connected-client regression: -Pimm_ptl.realmTest=true. No synthetic players or ACKs. */
public final class RealmSeedRegressionTest implements FabricClientGameTest {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(RealmSeedRegressionTest.class);
    private static final BlockPos CORE = new BlockPos(20, 100, 20);
    private static final Vec3 DEPARTURE = new Vec3(20.5, 101, 23.5);
    private static final AABB ENTRANCE = new AABB(16, 99, 16, 25, 106, 26);
    private static final Map<String, Integer> RECIPE = Map.of(
        "minecraft:moss_block", 16, "minecraft:glowstone", 16, "minecraft:echo_shard", 1
    );

    private record Fixture(UUID player, RealmRecord first, RealmRecord second, UUID member) { }
    private record ClientPosition(ResourceKey<Level> dimension, Vec3 position, int entityId) { }
    private record WorldProbePlan(BlockPos grove, List<ChunkPos> inspection, List<ChunkPos> features) { }

    @Override
    public void runTest(ClientGameTestContext context) {
        // Return before opening a world or changing options: ordinary test runs pay no realm-test cost.
        if (!Boolean.getBoolean("imm_ptl.realmTest")) return;
        context.runOnClient(client -> {
            if (client.gui.screen() instanceof IPortalInitialScreen screen) screen.onClose();
            client.options.bobView().set(false);
        });
        context.waitForScreen(TitleScreen.class);
        Path output = Path.of(System.getProperty("imm_ptl.visualTest.out", "visual-test")).resolve("realm");
        Runnable restoreShaders = saveRequestedShaders(context);
        try {
            if (Boolean.getBoolean("imm_ptl.visualTest.shaders")) {
                // Realm-only runs skip PortalVisualTest, which normally enables the requested pack.
                context.runOnClient(client -> IrisTestPack.enable());
            }
            Files.createDirectories(output);
            // Obtain a public, reopenable save handle. createServer writes its actual dedicated world
            // into this test-owned save, so save.open() later tests a real shutdown/reload, not a copy
            // of an in-memory ledger. Fabric has no dedicated-server reopen API.
            TestWorldSave save;
            try (TestSingleplayerContext initial = context.worldBuilder().create()) {
                save = initial.getWorldSave();
            }
            context.waitForScreen(TitleScreen.class);
            // Same accepted local test-server EULA as FeatureSmokeTest; never bind a public address.
            Files.writeString(Path.of("eula.txt"), "eula=true\n");
            var properties = new java.util.Properties();
            properties.setProperty("server-ip", "127.0.0.1");
            properties.setProperty("level-name", save.getSaveDirectory().toAbsolutePath().toString());
            properties.setProperty("online-mode", "false");
            properties.setProperty("spawn-protection", "0");
            properties.setProperty("view-distance", "3");
            properties.setProperty("simulation-distance", "3");
            Fixture fixture;
            try (TestDedicatedServerContext server = context.worldBuilder().createServer(properties)) {
                try (TestDedicatedServerConnection connection = server.connect()) {
                    connection.waitForChunksRender();
                    if (Boolean.getBoolean("imm_ptl.visualTest.shaders")) {
                        context.waitFor(client -> WithShaders.active(), 1200);
                        context.runOnClient(client -> WithShaders.assertActive());
                    }
                    check(context.computeOnClient(client -> {
                        var network = client.getConnection().getConnection();
                        return !network.isMemoryConnection() && network.isConnected()
                            && network.getRemoteAddress() instanceof InetSocketAddress address
                            && address.getAddress().isLoopbackAddress();
                    }), "Realm regression requires a real loopback TCP connection, not an integrated/synthetic peer");
                    UUID playerId = server.computeOnServer(s -> s.getPlayerList().getPlayers().getFirst().getUUID());
                    server.runCommand("gamemode creative @a");
                    server.runCommand("time set noon");
                    server.runOnServer(s -> buildFormation(s, playerId));
                    server.runCommand("tp @a 20.5 101.0 23.5 180 35");
                    awaitSettled(context, server, playerId, Level.OVERWORLD, DEPARTURE, "fixture placement");
                    verifyCoreInteractionBoundary(context, server, playerId);
                    UUID firstId = server.computeOnServer(s -> prepareOfferings(s, playerId));

                    // The client actually looks at the planted block and holds sneak + use. This
                    // exercises block interaction, the held-input payload, sixty real server ticks,
                    // durable payment, dimension creation and private portal creation together.
                    context.getInput().holdKey(options -> options.keyShift);
                    context.waitTicks(3);
                    float[] aim = context.computeOnClient(client -> {
                        // The walkway is level with the core's top. Aiming at its buried center
                        // hits the walkway, and sneaking changes eye height; aim after crouching.
                        Vec3 delta = new Vec3(CORE.getX() + .5, CORE.getY() + .99, CORE.getZ() + .5)
                            .subtract(client.player.getEyePosition());
                        return new float[]{(float) Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90,
                            (float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z)))};
                    });
                    context.getInput().lookAt(aim[0], aim[1]);
                    context.waitTicks(2);
                    try {
                        context.getInput().holdKey(options -> options.keyUse);
                        server.waitFor(s -> !s.overworld().getEntitiesOfClass(RealmPortal.class, ENTRANCE).isEmpty(), 600);
                    } finally {
                        context.getInput().releaseKey(options -> options.keyUse);
                        context.getInput().releaseKey(options -> options.keyShift);
                    }
                    context.waitTicks(3);
                    fixture = server.computeOnServer(s -> commitPaidFixtures(s, playerId, firstId));
                    context.waitTick();
                    long firstGeography = verifyWorldCooperatively(context, server, fixture.first());
                    long secondGeography = verifyWorldCooperatively(context, server, fixture.second());
                    check(firstGeography != secondGeography, "Different paid seeds produced identical sampled physical geography");
                    UUID ownPortal = server.computeOnServer(s -> s.overworld()
                        .getEntitiesOfClass(RealmPortal.class, ENTRANCE).getFirst().getUUID());
                    UUID foreignPortal = server.computeOnServer(s -> spawnForeignPrivatePortal(s, fixture));
                    context.waitFor(client -> client.level != null && client.level.getEntitiesOfClass(
                        RealmPortal.class, ENTRANCE).stream().anyMatch(p -> p.getUUID().equals(ownPortal)), 600);
                    // Wait through multiple tracker updates, then inspect the actual received entities.
                    context.waitTicks(40);
                    check(context.computeOnClient(client -> client.level.getEntitiesOfClass(RealmPortal.class, ENTRANCE)
                        .stream().noneMatch(p -> p.getUUID().equals(foreignPortal))),
                        "Client received a private realm portal addressed to another UUID");
                    ResourceKey<Level> realmDimension = dimension(fixture.first());
                    context.waitFor(client -> {
                        var remote = ClientWorldLoader.getOptionalWorld(realmDimension);
                        return remote != null && remote.getChunkSource().hasChunk(0, 0);
                    }, 1200);
                    context.waitFor(client -> {
                        var remote = ClientWorldLoader.getOptionalWorld(realmDimension);
                        return remote != null && ((qouteall.imm_ptl.peripheral.riftwoven.environment.client.RealmClientEnvironmentAccess) remote)
                            .riftwoven$clock().layout().suns() == fixture.first().profile().suns();
                    }, 600);
                    check(context.computeOnClient(client ->
                        ((qouteall.imm_ptl.peripheral.riftwoven.environment.client.RealmClientEnvironmentAccess) client.level)
                            .riftwoven$clock().layout().suns() == 1), "Remote celestial state leaked into the Overworld view");
                    long ordinaryZoom = server.computeOnServer(s -> net.minecraft.world.level.biome.BiomeManager.obfuscateSeed(s.overworld().getSeed()));
                    context.waitFor(client -> ClientWorldLoader.getWorld(realmDimension).getBiomeManager().biomeZoomSeed
                        == net.minecraft.world.level.biome.BiomeManager.obfuscateSeed(fixture.first().seed()), 600);
                    check(context.computeOnClient(client -> client.level.getBiomeManager().biomeZoomSeed == ordinaryZoom),
                        "Remote realm biome seed leaked into the ordinary dimension");
                    verifyClientBiomeSeeds(context, server);
                    context.getInput().lookAt(180, 0);
                    context.waitTicks(40);
                    screenshot(context, output, "private_entrance_view");

                    // Feet are inside the three-by-three aperture; use the real client's crossing
                    // detector and server packet validator, not a test-only teleport into the realm.
                    server.runCommand("tp @a 20.5 101.0 21.8 180 0");
                    awaitSettled(context, server, playerId, Level.OVERWORLD, new Vec3(20.5, 101, 21.8), "before crossing");
                    for (int tick = 0; tick < 60 && !context.computeOnClient(client ->
                        client.level != null && client.level.dimension().equals(realmDimension)); tick++) {
                        context.runOnClient(client -> client.player.setPos(
                            client.player.getX(), client.player.getY(), client.player.getZ() - .08));
                        context.waitTick();
                    }
                    awaitSettled(context, server, playerId, realmDimension, null, "physical realm entry");
                    server.runOnServer(s -> check(RealmLedger.open(s).canReturn(playerId, firstId),
                        "Physical entry lost its durable return record"));
                    context.waitTicks(20);
                    screenshot(context, output, "arrival_terrace");
                    server.runOnServer(s -> {
                        ServerPlayer visitor = player(s, playerId);
                        visitor.getAbilities().flying = true;
                        visitor.onUpdateAbilities();
                        ServerLevel realm = (ServerLevel) visitor.level();
                        // Exercise the normal sleep weather-clearing state for readable sky
                        // photographs; retain the recipe regime and normal rain fade/rendering.
                        qouteall.imm_ptl.peripheral.riftwoven.environment.RealmEnvironment.state(realm)
                            .sleep(fixture.first().profile().cycleTicks(), false, true);
                        qouteall.imm_ptl.peripheral.riftwoven.environment.RealmEnvironment.prepareWeather(realm);
                        qouteall.imm_ptl.peripheral.riftwoven.environment.RealmEnvironment.send(visitor, realm);
                    });
                    server.runCommand("execute in " + realmDimension.identifier() + " run tp @a 32.5 150 32.5 0 -30");
                    awaitSettled(context, server, playerId, realmDimension, new Vec3(32.5, 150, 32.5), "realm sky viewpoint");
                    context.waitFor(client -> client.level != null && client.level.getRainLevel(1) == 0, 600);
                    for (int direction = 0; direction < 4; direction++) {
                        context.getInput().lookAt(direction * 90, -30);
                        context.waitTicks(20);
                        screenshot(context, output, "realm_sky_" + direction);
                    }
                }

                // A second genuine network login of the same client verifies saved player/visit
                // recovery. The framework exposes one Minecraft instance, not two concurrent clients.
                try (TestDedicatedServerConnection reconnected = server.connect()) {
                    context.waitFor(client -> client.level != null
                        && client.level.dimension().equals(dimension(fixture.first())), 1200);
                    awaitSettled(context, server, fixture.player(), dimension(fixture.first()), null, "reconnected visitor");
                    verifyClientBiomeSeeds(context, server);
                    server.runOnServer(s -> {
                        RealmLedger.close(s);
                        RealmLedger ledger = RealmLedger.open(s);
                        check(ledger.realm(fixture.first().id()).equals(fixture.first()), "Paid identity changed after ledger reopen");
                        check(ledger.canReturn(fixture.player(), fixture.first().id()), "Disconnect/reopen lost return rights");
                        check(count(player(s, fixture.player()), Items.DIAMOND) == 0, "Reconnect restored spent diamonds");
                        Riftwoven.returnPlayer(player(s, fixture.player()));
                        check(ledger.departure(fixture.player()) != null,
                            "Direct return consumed its visit before the real client's ACK");
                    });
                    awaitSettled(context, server, fixture.player(), Level.OVERWORLD, DEPARTURE, "direct return after real ACK");
                    verifyClientBiomeSeeds(context, server);
                    server.waitFor(s -> departure(s, fixture.player()) == null, 600);
                    screenshot(context, output, "returned_after_ack");
                }
            }
            context.waitForScreen(TitleScreen.class);
            try (TestSingleplayerContext reopened = save.open()) {
                reopened.getServer().runOnServer(s -> verifyReopened(s, fixture));
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } finally {
            context.runOnClient(client -> restoreShaders.run());
        }
    }

    private static Runnable saveRequestedShaders(ClientGameTestContext context) {
        if (!Boolean.getBoolean("imm_ptl.visualTest.shaders")) return () -> { };
        check(FabricLoader.getInstance().isModLoaded("iris"),
            "Realm shader qualification requires Iris and an actual enabled shaderpack");
        // Like IrisDepthRegressionTest, keep Iris-specific resolution out of the vanilla path.
        return context.computeOnClient(client -> WithShaders.save());
    }

    private static final class WithShaders {
        private record SavedFile(Path path, byte[] contents) {
            static SavedFile read(Path path) throws IOException {
                return new SavedFile(path, Files.exists(path) ? Files.readAllBytes(path) : null);
            }

            void restore() throws IOException {
                if (contents == null) Files.deleteIfExists(path);
                else Files.write(path, contents);
            }
        }

        private static String requestedPack() {
            String zip = System.getProperty("imm_ptl.visualTest.shaderpack", "");
            return zip.isEmpty() ? IrisTestPack.NAME : Path.of(zip).getFileName().toString();
        }

        static boolean active() {
            return net.irisshaders.iris.Iris.getIrisConfig().areShadersEnabled()
                && net.irisshaders.iris.Iris.getCurrentPack().isPresent()
                && requestedPack().equals(net.irisshaders.iris.Iris.getCurrentPackName())
                && net.irisshaders.iris.api.v0.IrisApi.getInstance().isShaderPackInUse()
                && net.irisshaders.iris.Iris.getPipelineManager().getPipelineNullable()
                    instanceof net.irisshaders.iris.pipeline.IrisRenderingPipeline;
        }

        static void assertActive() {
            check(active(), "Requested realm shaderpack is not actively rendering: " + requestedPack());
        }

        static Runnable save() {
            var config = net.irisshaders.iris.Iris.getIrisConfig();
            try {
                // Preserve the exact config bytes, including the original absence of the file.
                // This is the pinned Iris config field also used by IrisDepthRegressionTest.
                var field = config.getClass().getDeclaredField("propertiesPath");
                field.setAccessible(true);
                SavedFile configuration = SavedFile.read((Path) field.get(config));
                String name = config.getShaderPackName().orElse(null);
                boolean enabled = config.areShadersEnabled();
                // IrisTestPack overwrites <pack>.txt for an explicitly requested zip.
                SavedFile options = System.getProperty("imm_ptl.visualTest.shaderpack", "").isEmpty()
                    ? null : SavedFile.read(net.irisshaders.iris.Iris.getShaderpacksDirectory()
                        .resolve(requestedPack() + ".txt"));
                return () -> {
                    try {
                        if (options != null) options.restore();
                        config.setShaderPackName(name);
                        config.setShadersEnabled(enabled);
                        config.save();
                        net.irisshaders.iris.Iris.reload();
                    } catch (IOException exception) {
                        throw new UncheckedIOException(exception);
                    } finally {
                        try { configuration.restore(); }
                        catch (IOException exception) { throw new UncheckedIOException(exception); }
                    }
                };
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError("Cannot preserve pinned Iris shader configuration", exception);
            }
        }
    }

    private static void verifyClientBiomeSeeds(ClientGameTestContext context, TestServerContext server) {
        Map<ResourceKey<Level>, Long> expected = server.computeOnServer(s -> {
            Map<ResourceKey<Level>, Long> seeds = new java.util.HashMap<>();
            for (ServerLevel level : s.getAllLevels()) {
                seeds.put(level.dimension(), level.getBiomeManager().biomeZoomSeed);
            }
            return seeds;
        });
        for (var entry : expected.entrySet()) {
            context.waitFor(client -> {
                var cached = qouteall.imm_ptl.peripheral.riftwoven.environment.client.RealmBiomeSeeds.cachedSeed(entry.getKey());
                return cached.isPresent() && cached.getAsLong() == entry.getValue();
            }, 600);
            check(context.computeOnClient(client -> ClientWorldLoader.getWorld(entry.getKey())
                .getBiomeManager().biomeZoomSeed == entry.getValue()),
                "Client biome seed mismatch for " + entry.getKey().identifier());
        }
    }

    private static void verifyCoreInteractionBoundary(ClientGameTestContext context,
        TestDedicatedServerContext server, UUID playerId) throws IOException {
        // Independent test-owned saved realm: never rewrite either paid identity or impersonate its
        // owner. Only the genuinely connected player performs the game-mode interactions below.
        RealmRecord record = new RealmRecord(UUID.randomUUID(), UUID.randomUUID(), 0x52494654L,
            RealmRecipe.resolve(Map.of()), List.of());
        server.runOnServer(s -> {
            writeCoreBoundaryMembership(s, record, playerId, 5); // ENTER | VIEW, no BUILD.
            ServerLevel world = Riftwoven.ensureWorld(s, record);
            Riftwoven.prepareArrival(world, record);
            for (BlockPos pos : BlockPos.betweenClosed(18, 100, 18, 23, 104, 25)) {
                world.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            }
            for (BlockPos pos : BlockPos.betweenClosed(18, 100, 18, 23, 100, 25)) {
                world.setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
            }
            world.setBlock(CORE, Riftwoven.CORE.defaultBlockState(), 3);
            ((RealmCoreBlockEntity) world.getBlockEntity(CORE)).bind(record.id());
        });
        server.runCommand("execute in " + record.dimensionId() + " run tp @a 20.5 101 23.5 180 35");
        awaitSettled(context, server, playerId, dimension(record), DEPARTURE, "core boundary visitor");
        server.runOnServer(s -> {
            ServerPlayer visitor = player(s, playerId);
            ServerLevel world = (ServerLevel) visitor.level();
            RealmLedger ledger = RealmLedger.open(s);
            ledger.recordDeparture(playerId, new Departure(record.id(), Level.OVERWORLD.identifier().toString(),
                DEPARTURE.x, DEPARTURE.y, DEPARTURE.z, 180, 35));
            check(ledger.canEnter(record.id(), playerId) && ledger.canView(record.id(), playerId)
                && !ledger.canBuild(record.id(), playerId), "Core boundary fixture is not an ENTER/VIEW-only visitor");
            check(!RealmProtection.protectedBlock(world, CORE)
                && !RealmProtection.protectedBlock(world, CORE.above()), "Offhand probe accidentally tests shelter protection");
            visitor.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            visitor.setShiftKeyDown(true);
            try {
                for (Item item : List.of(Items.STONE, Items.FLINT_AND_STEEL)) {
                    ItemStack held = new ItemStack(item);
                    visitor.setItemInHand(InteractionHand.OFF_HAND, held);
                    var beforeCore = world.getBlockState(CORE);
                    var beforeAdjacent = world.getBlockState(CORE.above());
                    int beforeCount = held.getCount(), beforeDamage = held.getDamageValue();
                    InteractionResult result = visitor.gameMode.useItemOn(visitor, world, held,
                        InteractionHand.OFF_HAND, coreTopHit(CORE));
                    check(result == InteractionResult.FAIL, "ENTER/VIEW visitor's sneaking offhand core use was not rejected: " + item);
                    check(world.getBlockState(CORE).equals(beforeCore)
                        && world.getBlockState(CORE.above()).equals(beforeAdjacent),
                        "Rejected offhand core use mutated a block: " + item);
                    check(held.getCount() == beforeCount && held.getDamageValue() == beforeDamage,
                        "Rejected offhand core use consumed or damaged the held item: " + item);
                }
                writeCoreBoundaryMembership(s, record, playerId, 7); // Add BUILD, same player/core.
                check(RealmProtection.canModify(visitor, world, CORE)
                    && RealmProtection.canModify(visitor, world, CORE.above()), "Builder fixture lacks modification rights");
                for (Item item : List.of(Items.STONE, Items.FLINT_AND_STEEL)) {
                    ItemStack held = new ItemStack(item);
                    visitor.setItemInHand(InteractionHand.OFF_HAND, held);
                    InteractionResult result = visitor.gameMode.useItemOn(visitor, world, held,
                        InteractionHand.OFF_HAND, coreTopHit(CORE));
                    check(result.consumesAction() && (item == Items.STONE
                        ? world.getBlockState(CORE.above()).is(Blocks.STONE)
                        : world.getBlockState(CORE.above()).is(Blocks.FIRE)),
                        "Authorized builder's legitimate sneaking offhand item use was blocked: " + item);
                    world.setBlock(CORE.above(), Blocks.AIR.defaultBlockState(), 3);
                }
            } finally {
                visitor.setShiftKeyDown(false);
                visitor.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
                visitor.inventoryMenu.broadcastChanges();
            }
        });
        server.runCommand("execute in " + record.dimensionId() + " run tp @a 0.5 80 2.5 0 0");
        awaitSettled(context, server, playerId, dimension(record), new Vec3(.5, 80, 2.5), "revoked guest at return core");
        server.runOnServer(s -> {
            writeCoreBoundaryMembership(s, record, playerId, 0);
            ServerPlayer visitor = player(s, playerId);
            ServerLevel world = (ServerLevel) visitor.level();
            RealmLedger ledger = RealmLedger.open(s);
            BlockPos returnCore = new BlockPos(0, 80, 4);
            check(!ledger.canEnter(record.id(), playerId) && !ledger.canView(record.id(), playerId)
                && !ledger.canBuild(record.id(), playerId) && ledger.canReturn(playerId, record.id())
                && !RealmProtection.canModify(visitor, world, returnCore), "Revoked guest fixture retained modification rights");
            visitor.setShiftKeyDown(true);
            try {
                check(visitor.gameMode.useItemOn(visitor, world, visitor.getMainHandItem(),
                    InteractionHand.MAIN_HAND, coreTopHit(returnCore)).consumesAction(),
                    "Revoked guest's main-hand return core interaction was blocked");
                check(visitor.level() == s.overworld() && ledger.canReturn(playerId, record.id()),
                    "Return core did not transfer the revoked guest while retaining the pending real ACK");
            } finally {
                visitor.setShiftKeyDown(false);
            }
        });
        awaitSettled(context, server, playerId, Level.OVERWORLD, DEPARTURE, "revoked guest core return after real ACK");
        server.waitFor(s -> departure(s, playerId) == null, 600);
    }

    private static BlockHitResult coreTopHit(BlockPos pos) {
        return new BlockHitResult(Vec3.atLowerCornerOf(pos).add(.5, 1, .5), Direction.UP, pos, false);
    }

    private static void writeCoreBoundaryMembership(MinecraftServer server, RealmRecord record,
        UUID visitor, int permissions) throws IOException {
        // Publish a valid saved fixture through the real journal, then reopen through normal ledger
        // validation. No synthetic owner, reflected private mutation, or paid-record edits.
        RealmLedger.close(server);
        JsonObject state = new JsonObject();
        state.addProperty("schema", 1);
        state.addProperty("id", record.id().toString());
        state.addProperty("owner", record.owner().toString());
        state.add("contributions", new JsonArray());
        JsonObject realm = new JsonObject();
        realm.addProperty("seed", record.seed());
        realm.addProperty("generator_version", record.generatorVersion());
        realm.addProperty("recipe_version", record.recipeVersion());
        realm.add("profile", qouteall.imm_ptl.peripheral.riftwoven.profile.RealmProfile.CODEC
            .encodeStart(JsonOps.INSTANCE, record.profile()).getOrThrow());
        state.add("realm", realm);
        JsonObject members = new JsonObject();
        if (permissions != 0) members.addProperty(visitor.toString(), permissions);
        state.add("members", members);
        TransactionJournal journal = new TransactionJournal(new LedgerStore(server.getWorldPath(LevelResource.ROOT)
            .resolve("data/riftwoven/realms")));
        journal.complete(journal.prepare(record.id(), null, state.toString().getBytes(StandardCharsets.UTF_8),
            "[]".getBytes(StandardCharsets.UTF_8)), null);
        check(RealmLedger.open(server).realm(record.id()).equals(record), "Boundary fixture failed normal ledger validation");
    }

    private static void buildFormation(MinecraftServer server, UUID id) {
        ServerLevel level = server.overworld();
        for (BlockPos pos : BlockPos.betweenClosed(15, 99, 15, 25, 106, 26)) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        }
        for (BlockPos pos : BlockPos.betweenClosed(15, 100, 21, 25, 100, 26)) {
            level.setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
        }
        for (int x = -2; x <= 2; x++) level.setBlock(CORE.offset(x, -1, 0), Blocks.STONE.defaultBlockState(), 3);
        for (int x : new int[]{-2, 2}) {
            for (int y = 0; y < 3; y++) level.setBlock(CORE.offset(x, y, 0), Blocks.CRYING_OBSIDIAN.defaultBlockState(), 3);
            level.setBlock(CORE.offset(x, 3, 0), Blocks.AMETHYST_BLOCK.defaultBlockState(), 3);
        }
        level.setBlock(CORE, Riftwoven.CORE.defaultBlockState().setValue(RealmCoreBlock.FACING, Direction.NORTH), 3);
        Riftwoven.placed(player(server, id), CORE, new ItemStack(Riftwoven.SEED));
        check(RealmFormation.problem(level, CORE, Direction.NORTH) == null, "Physical seed arch is invalid");
    }

    private static UUID prepareOfferings(MinecraftServer server, UUID id) throws IOException {
        ServerPlayer player = player(server, id);
        RealmLedger ledger = RealmLedger.open(server);
        UUID realm = ((RealmCoreBlockEntity) server.overworld().getBlockEntity(CORE)).realmId();
        check(realm != null && ledger.draft(realm) != null, "Placing the real core did not create/bind a draft");
        player.getInventory().clearContent();
        player.getInventory().setSelectedSlot(0);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.MOSS_BLOCK, 16));
        Riftwoven.useCore(player, CORE, InteractionHand.MAIN_HAND);
        check(count(player, Items.MOSS_BLOCK) == 0 && ledger.draft(realm).contributions().size() == 1,
            "Core offering did not remove sixteen real inventory items into durable escrow");
        player.setShiftKeyDown(true);
        Riftwoven.useCore(player, CORE, InteractionHand.MAIN_HAND);
        player.setShiftKeyDown(false);
        check(count(player, Items.MOSS_BLOCK) == 16 && ledger.draft(realm).contributions().isEmpty(),
            "Sneak-empty-hand withdrawal did not refund the actual offering exactly once");
        Riftwoven.useCore(player, CORE, InteractionHand.MAIN_HAND);
        for (var ingredient : List.of(new ItemStack(Items.GLOWSTONE, 16), new ItemStack(Items.ECHO_SHARD))) {
            player.setItemInHand(InteractionHand.MAIN_HAND, ingredient);
            Riftwoven.useCore(player, CORE, InteractionHand.MAIN_HAND);
            check(player.getMainHandItem().isEmpty(), "Physical offering did not consume " + ingredient);
        }
        check(ledger.draft(realm).recipe().equals(RECIPE), "Physical offerings disagree with the intended recipe");
        check(ledger.realm(realm) == null && server.getLevel(ResourceKey.create(Registries.DIMENSION,
            Identifier.parse("riftwoven:realm_" + realm.toString().replace("-", "")))) == null,
            "Unpaid preview created a realm");
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND, 8));
        player.inventoryMenu.broadcastChanges();
        return realm;
    }

    private static Fixture commitPaidFixtures(MinecraftServer server, UUID id, UUID firstId) throws IOException {
        ServerPlayer player = player(server, id);
        RealmLedger ledger = RealmLedger.open(server);
        RealmRecord first = ledger.realm(firstId);
        check(first != null, "Physical sneak-use ritual did not awaken the planted seed");
        check(count(player, Items.DIAMOND) == 4, "Awakening did not charge exactly four real diamonds");
        check(ledger.awaken(player, firstId).equals(first) && count(player, Items.DIAMOND) == 4,
            "Awakening retry charged again or changed paid identity");
        // A second paid realm uses exactly the same recipe, but a separately committed seed.
        UUID secondId = ledger.createDraft(player);
        player.getInventory().setItem(10, player.getMainHandItem().copy());
        for (var ingredient : List.of(new ItemStack(Items.MOSS_BLOCK, 16),
            new ItemStack(Items.GLOWSTONE, 16), new ItemStack(Items.ECHO_SHARD))) {
            player.setItemInHand(InteractionHand.MAIN_HAND, ingredient);
            ledger.offer(player, secondId, InteractionHand.MAIN_HAND, ingredient.getCount());
            check(player.getMainHandItem().isEmpty(), "Second realm offering did not debit real inventory");
        }
        RealmRecord second = ledger.awaken(player, secondId);
        check(count(player, Items.DIAMOND) == 0, "Second realm did not charge the remaining four diamonds");
        check(ledger.awaken(player, secondId).equals(second) && count(player, Items.DIAMOND) == 0,
            "Zero-diamond retry failed or charged again");
        check(first.profile().equals(second.profile()) && first.profile().equals(RealmRecipe.resolve(RECIPE)),
            "Repeated recipe did not freeze the deterministic profile");
        check(first.seed() != second.seed(), "Independent paid realms reused a world seed");
        UUID member = UUID.randomUUID();
        ledger.grant(player, firstId, member, Set.of(RealmLedger.Permission.ENTER));
        check(ledger.canEnter(firstId, member) && !ledger.canView(firstId, member), "Entry incorrectly implied viewing permission");
        ledger.grant(player, firstId, member, Set.of(RealmLedger.Permission.ENTER, RealmLedger.Permission.VIEW));
        Departure visit = new Departure(firstId, Level.OVERWORLD.identifier().toString(), 20.5, 101, 23.5, 180, 0);
        ledger.recordDeparture(member, visit);
        ledger.revoke(player, firstId, member);
        check(!ledger.canEnter(firstId, member) && !ledger.canView(firstId, member) && ledger.canReturn(member, firstId),
            "Revocation did not block entry/viewing while preserving an existing visitor's return rights");
        check(ledger.departure(id) != null, "Opening the physical rift did not persist a departure before crossing");
        player.inventoryMenu.broadcastChanges();
        return new Fixture(id, first, second, member);
    }

    private static long verifyWorldCooperatively(ClientGameTestContext context, TestServerContext server,
        RealmRecord record) throws IOException {
        long started = System.nanoTime();
        WorldProbePlan plan = server.computeOnServer(s -> {
            ServerLevel world = Riftwoven.ensureWorld(s, record);
            RealmChunkGenerator generator = (RealmChunkGenerator) world.getChunkSource().getGenerator();
            BlockPos grove = probeGrove(generator, record.profile().radius());
            int radius = ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.FEATURES).directDependencies().size() - 1;
            return new WorldProbePlan(grove, inspectionChunks(record.profile().radius()),
                featureChunks(grove.getX() >> 4, grove.getZ() >> 4, radius));
        });
        context.waitTick();
        // computeOnServer synchronously parks the test thread; it does not pump client keepalives.
        // Yield a normal client/server tick after every individual FULL-chunk request instead.
        // UNKNOWN tickets from getChunk do not retain earlier chunks across these yielded ticks.
        // Global loaders retain only server data, not player visibility or client mesh readiness.
        List<ChunkLoader> inspectionHolds = probeChunkLoaders(record, plan.inspection());
        long geography;
        try {
            server.runOnServer(s -> {
                for (ChunkLoader loader : inspectionHolds) PortalAPI.addGlobalChunkLoader(s, loader);
            });
            warmChunks(context, server, record, "inspection", plan.inspection());
            geography = server.computeOnServer(s -> {
                ServerLevel world = s.getLevel(dimension(record));
                requireLoadedChunks(world, plan.inspection());
                verifyWorld(world, record);
                return geography(world);
            });
        } finally {
            server.runOnServer(s -> {
                for (ChunkLoader loader : inspectionHolds) PortalAPI.removeGlobalChunkLoader(s, loader);
            });
        }
        context.waitTick();
        // Each phase owns strong references to exactly its loaders, including during partial setup.
        // Release them through the reference-based API even when warming or an assertion throws.
        List<ChunkLoader> featureHolds = probeChunkLoaders(record, plan.features());
        try {
            server.runOnServer(s -> {
                for (ChunkLoader loader : featureHolds) PortalAPI.addGlobalChunkLoader(s, loader);
            });
            warmChunks(context, server, record, "features", plan.features());
            server.runOnServer(s -> {
                ServerLevel world = s.getLevel(dimension(record));
                requireLoadedChunks(world, plan.features());
                probeFeatureSeedAndTerrace(world, (RealmChunkGenerator) world.getChunkSource().getGenerator(),
                    record.seed(), plan.grove());
            });
        } finally {
            server.runOnServer(s -> {
                for (ChunkLoader loader : featureHolds) PortalAPI.removeGlobalChunkLoader(s, loader);
            });
        }
        context.waitTick();
        LOGGER.info("Realm probe {} verified in {} ms", record.dimensionId(), (System.nanoTime() - started) / 1_000_000);
        return geography;
    }

    private static List<ChunkPos> inspectionChunks(int radius) {
        Set<ChunkPos> chunks = new LinkedHashSet<>();
        chunks.add(new ChunkPos((radius + 31) >> 4, 0));
        chunks.add(new ChunkPos((radius + 20) >> 4, 0));
        for (int x = -1; x <= 0; x++) for (int z = -1; z <= 0; z++) chunks.add(new ChunkPos(x, z));
        for (int x : new int[]{-48, -32, 32, 48}) for (int z : new int[]{-32, 32}) {
            chunks.add(new ChunkPos(x >> 4, z >> 4));
        }
        return List.copyOf(chunks);
    }

    private static List<ChunkPos> featureChunks(int cx, int cz, int radius) {
        Set<ChunkPos> chunks = new LinkedHashSet<>();
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) chunks.add(new ChunkPos(x, z));
        }
        // The physical outcrop and its scan can straddle a chunk edge, including the fallback grove.
        for (int x = cx - 1; x <= cx + 1; x++) {
            for (int z = cz - 1; z <= cz + 1; z++) chunks.add(new ChunkPos(x, z));
        }
        // Include the shelter in this phase too: its hash/mask assertions follow feature placement.
        for (int x = -1; x <= 0; x++) for (int z = -1; z <= 0; z++) {
            ChunkPos pos = new ChunkPos(x, z);
            chunks.remove(pos);
            chunks.add(pos);
        }
        return List.copyOf(chunks);
    }

    private static void warmChunks(ClientGameTestContext context, TestServerContext server,
        RealmRecord record, String stage, List<ChunkPos> chunks) {
        check(chunks.size() <= 293, "Chunk probe phase exceeds its bounded coordinate budget");
        long started = System.nanoTime();
        LOGGER.info("Realm probe {} warming {}: {} chunks", record.dimensionId(), stage, chunks.size());
        for (ChunkPos chunk : chunks) {
            server.runOnServer(s -> s.getLevel(dimension(record)).getChunk(chunk.x(), chunk.z()));
            context.waitTick();
        }
        // Global-loader ticket dispatch is normally throttled. Wait cooperatively for actual FULL
        // residency rather than assuming that marking a loader installed its ticket immediately.
        server.waitFor(s -> chunks.stream().allMatch(chunk -> s.getLevel(dimension(record))
            .getChunkSource().getChunkNow(chunk.x(), chunk.z()) != null), 600);
        LOGGER.info("Realm probe {} warmed {} in {} ms", record.dimensionId(), stage,
            (System.nanoTime() - started) / 1_000_000);
    }

    private static List<ChunkLoader> probeChunkLoaders(RealmRecord record, List<ChunkPos> chunks) {
        check(chunks.size() <= 293, "Chunk probe phase exceeds its bounded coordinate budget");
        return chunks.stream().map(chunk -> new ChunkLoader(dimension(record), chunk.x(), chunk.z(), 0)).toList();
    }

    private static void requireLoadedChunks(ServerLevel world, List<ChunkPos> chunks) {
        for (ChunkPos chunk : chunks) loadedChunk(world, chunk.x(), chunk.z());
    }

    private static LevelChunk loadedChunk(ServerLevel world, int x, int z) {
        LevelChunk chunk = world.getChunkSource().getChunkNow(x, z);
        check(chunk != null, "Probe chunk must already be FULL and loaded: " + x + "," + z);
        return chunk;
    }

    private static void verifyWorld(ServerLevel world, RealmRecord record) throws IOException {
        check(world.getSeed() == record.seed(), "ServerLevel inherited the ordinary world's seed");
        RealmChunkGenerator generator = (RealmChunkGenerator) world.getChunkSource().getGenerator();
        check(generator.dimensionSeed() == record.seed(), "Independent generator seed differs from paid identity");
        int radius = record.profile().radius();
        check(world.getWorldBorder().getSize() == radius * 2.0
            && !world.getWorldBorder().isWithinBounds(new BlockPos(radius + 16, 80, 0)), "Finite border is wrong");
        loadedChunk(world, (radius + 31) >> 4, 0);
        for (int y = world.getMinY(); y <= world.getMaxY(); y++) {
            check(world.getBlockState(new BlockPos(radius + 20, y, 0)).isAir(), "Generated terrain escaped the finite realm");
        }
        // Inspect the biome's completed features before preparing the protected arrival envelope.
        loadedChunk(world, 0, 0);
        loadedChunk(world, -1, -1);
        if (world.getBlockState(new BlockPos(0, 79, 0)).is(Blocks.STONE)) {
            // The second world has not been repaired/prepared by Riftwoven yet. Inspect vanilla's
            // completed feature stage directly so prepareArrival cannot conceal decoration damage.
            for (BlockPos pos : BlockPos.betweenClosed(-8, 80, -8, 8, 84, 8)) {
                check(world.getBlockState(pos).isAir(), "Natural biome decoration invaded the unprepared arrival terrace");
            }
        }
        Riftwoven.prepareArrival(world, record);
        check(world.getBlockState(new BlockPos(0, 79, 0)).is(Blocks.BEDROCK)
            && world.getBlockState(new BlockPos(0, 80, 0)).isAir()
            && world.getBlockState(new BlockPos(0, 81, 0)).isAir()
            && world.getBlockState(new BlockPos(0, 80, 4)).is(Riftwoven.CORE), "Arrival terrace is not safe");
        RealmBiomeSource biomes = (RealmBiomeSource) generator.getBiomeSource();
        check(biomes.possibleBiomes().stream().anyMatch(b -> b.is(RealmBiomeSource.CRYSTALLINE_GROVE)),
            "The new crystalline biome is missing from the active lush palette");
    }

    private static BlockPos probeGrove(RealmChunkGenerator generator, int radius) {
        RealmBiomeSource biomes = (RealmBiomeSource) generator.getBiomeSource();
        BlockPos grove = null;
        for (int x = -radius + 24; x < radius - 24 && grove == null; x += 8) {
            for (int z = -radius + 24; z < radius - 24; z += 8) {
                if (!generator.terrain().terrace(x, z) && biomes.biomeAt(x, 80, z).is(RealmBiomeSource.CRYSTALLINE_GROVE)) {
                    grove = new BlockPos(x, 80, z);
                    break;
                }
            }
        }
        // A family allocation guarantees lush sectors, not any particular sub-biome in every seed.
        // The actual datapack feature is exercised below even if this small seed chooses other lush biomes.
        if (grove == null) grove = new BlockPos(40, 80, 40);
        return grove;
    }

    private static void probeFeatureSeedAndTerrace(ServerLevel world, RealmChunkGenerator generator, long seed, BlockPos grove) {
        int cx = grove.getX() >> 4, cz = grove.getZ() >> 4;
        // Use real loaded holders and the actual vanilla FEATURES step, never a mocked region.
        var step = ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.FEATURES);
        int radius = step.directDependencies().size() - 1;
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) loadedChunk(world, x, z);
        }
        StaticCache2D<GenerationChunkHolder> cache = StaticCache2D.create(cx, cz, radius,
            (x, z) -> world.getChunkSource().chunkMap.getUpdatingChunkIfPresent(ChunkPos.pack(x, z)));
        WorldGenRegion region = new WorldGenRegion(world, cache, step, loadedChunk(world, cx, cz));
        Feature seedProbe = new Feature() {
            @Override public MapCodec<? extends Feature> codec() { return MapCodec.unit(this); }
            @Override public boolean place(WorldGenLevel level, ChunkGenerator chunkGenerator, RandomSource random, BlockPos pos) {
                check(level instanceof WorldGenRegion && level.getSeed() == seed,
                    "A real feature observed the wrong WorldGenRegion seed");
                return true;
            }
        };
        check(seedProbe.place(region, generator, region.getRandom(), grove), "Worldgen feature seed probe did not execute");
        // Exercise the same generation-only write mask used by applyBiomeDecoration with the actual
        // datapack feature. It may decorate natural terrain but must not modify the reserved terrace.
        var feature = world.registryAccess().lookupOrThrow(Registries.FEATURE)
            .getOrThrow(ResourceKey.create(Registries.FEATURE, Identifier.parse("riftwoven:crystal_outcrop"))).value();
        // Completed holders expose read-only ImposterProtoChunks to a FEATURES region. Use
        // the live level writer for this post-generation physical-placement probe; the separate
        // real-region probe above still verifies the seed observed during feature execution.
        WorldGenLevel masked = generationView(world, generator.terrain(), cx, cz);
        BlockPos surface = new BlockPos(grove.getX(), world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            grove.getX(), grove.getZ()), grove.getZ());
        // Isolate feature execution from its probabilistic natural substrate (some grove columns
        // are moss, stone or water). This is real dirt in the real region, not a mocked writer.
        world.setBlock(surface.below(), Blocks.DIRT.defaultBlockState(), 3);
        check(feature.place(masked, generator, RandomSource.create(seed), surface), "Crystalline biome's real outcrop feature could not place");
        boolean outcrop = false;
        for (BlockPos pos : BlockPos.betweenClosed(surface.offset(-3, -3, -3), surface.offset(3, 3, 3))) {
            if (world.getBlockState(pos).is(Blocks.AMETHYST_BLOCK)) outcrop = true;
        }
        check(outcrop, "Crystalline feature reported success without placing its physical amethyst outcrop");
        // Permit the fixture's underlying writer so the assertion tests the generation mask,
        // not the independent live-shelter protection or read-only completed-chunk wrappers.
        long before = terraceHash(world);
        qouteall.imm_ptl.peripheral.riftwoven.RealmProtection.withArrivalEdits(() ->
            feature.place(generationView(world, generator.terrain(), 0, 0), generator,
                RandomSource.create(seed), new BlockPos(0, 81, 0)));
        check(terraceHash(world) == before, "A biome feature damaged the reserved safe arrival terrace");
    }

    private static WorldGenLevel generationView(WorldGenLevel region, RealmTerrain terrain, int x, int z) {
        try {
            var mask = Class.forName("qouteall.imm_ptl.peripheral.riftwoven.worldgen.RealmGenerationMask");
            var wrap = mask.getDeclaredMethod("wrap", WorldGenLevel.class, RealmTerrain.class, int.class, int.class);
            wrap.setAccessible(true);
            return (WorldGenLevel) wrap.invoke(null, region, terrain, x, z);
        } catch (InvocationTargetException exception) {
            throw new AssertionError("Realm generation mask failed", exception.getCause());
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Cannot exercise the actual realm generation mask", exception);
        }
    }

    private static long terraceHash(ServerLevel world) {
        long hash = 1;
        for (BlockPos pos : BlockPos.betweenClosed(-8, 79, -8, 8, 85, 8)) {
            hash = hash * 31 + world.getBlockState(pos).hashCode();
        }
        return hash;
    }

    private static long geography(ServerLevel level) {
        long hash = 1;
        for (int x : new int[]{-48, -32, 32, 48}) for (int z : new int[]{-32, 32}) {
            loadedChunk(level, x >> 4, z >> 4);
            for (int y = -32; y < 128; y += 3) hash = hash * 31 + level.getBlockState(new BlockPos(x, y, z)).hashCode();
        }
        return hash;
    }

    private static UUID spawnForeignPrivatePortal(MinecraftServer server, Fixture fixture) {
        RealmPortal portal = Riftwoven.REALM_PORTAL.create(server.overworld(), EntitySpawnReason.COMMAND);
        check(portal != null, "Cannot create the private tracking counterexample");
        portal.configure(fixture.first().id(), fixture.member(), false);
        portal.setOriginPos(new Vec3(23.5, 102.5, 20.5));
        portal.setOrientationAndSize(new Vec3(1, 0, 0), new Vec3(0, 1, 0), 3, 3);
        portal.setDestinationDimension(dimension(fixture.first()));
        portal.setDestination(new Vec3(.5, 81.5, .5));
        check(server.overworld().addFreshEntity(portal), "Cannot register foreign private tracking fixture");
        return portal.getUUID();
    }

    private static void verifyReopened(MinecraftServer server, Fixture fixture) throws IOException {
        RealmLedger ledger = RealmLedger.open(server);
        for (RealmRecord expected : List.of(fixture.first(), fixture.second())) {
            check(expected.equals(ledger.realm(expected.id())), "Shutdown/reopen changed a paid realm or frozen profile");
            ServerLevel world = server.getLevel(dimension(expected));
            check(world != null && world.getSeed() == expected.seed(), "Saved realm failed to reopen with its independent seed");
            check(world.getWorldBorder().getSize() == expected.profile().radius() * 2.0, "Reopen lost finite border");
        }
        check(((RealmCoreBlockEntity) server.overworld().getBlockEntity(CORE)).realmId().equals(fixture.first().id()),
            "Saved physical core lost its paid reference");
        check(ledger.departure(fixture.player()) == null, "Completed real-client return reappeared after shutdown");
        check(!ledger.canEnter(fixture.first().id(), fixture.member()) && !ledger.canView(fixture.first().id(), fixture.member())
            && ledger.canReturn(fixture.member(), fixture.first().id()), "Reopen lost revocation or preserved visitor return rights");
    }

    private static void awaitSettled(ClientGameTestContext context, TestServerContext server, UUID id,
        ResourceKey<Level> dimension, Vec3 target, String stage) {
        // Always yield from the test thread, never hold the server thread waiting for client ACK.
        int stable = 0;
        for (int tick = 0; tick < 1200; tick++) {
            context.waitTick();
            ClientPosition client = context.computeOnClient(mc -> mc.player == null || mc.level == null ? null
                : new ClientPosition(mc.level.dimension(), mc.player.position(), mc.player.getId()));
            boolean settled = server.computeOnServer(s -> {
                ServerPlayer player = player(s, id);
                return client != null && client.dimension().equals(dimension) && player.level().dimension().equals(dimension)
                    && !((IEServerPlayNetworkHandler) player.connection).ip_hasAwaitingTeleport()
                    && EntityRegistration.inspect((ServerLevel) player.level(), player) == EntityRegistration.RegistrationState.REGISTERED
                    && client.entityId() == player.getId() && client.position().distanceToSqr(player.position()) < .04
                    && (target == null || player.position().distanceToSqr(target) < .1);
            });
            stable = settled ? stable + 1 : 0;
            if (stable >= 3) return;
        }
        throw new AssertionError("Connected client/server position and real teleport ACK did not settle: " + stage);
    }

    private static ResourceKey<Level> dimension(RealmRecord record) {
        return ResourceKey.create(Registries.DIMENSION, record.dimensionId());
    }

    private static ServerPlayer player(MinecraftServer server, UUID id) {
        ServerPlayer player = server.getPlayerList().getPlayer(id);
        check(player != null, "The actual connected realm test player disconnected");
        return player;
    }

    private static int count(ServerPlayer player, Item item) {
        int count = 0;
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(item)) count += stack.getCount();
        }
        if (player.getOffhandItem().is(item)) count += player.getOffhandItem().getCount();
        return count;
    }

    private static Departure departure(MinecraftServer server, UUID player) {
        try { return RealmLedger.open(server).departure(player); }
        catch (IOException exception) { throw new UncheckedIOException(exception); }
    }

    private static void screenshot(ClientGameTestContext context, Path output, String name) {
        context.runOnClient(client -> {
            if (Boolean.getBoolean("imm_ptl.visualTest.shaders")) WithShaders.assertActive();
            client.gui.toastManager().clear();
            client.gui.hud.getChat().clearMessages(false);
        });
        context.takeScreenshot(TestScreenshotOptions.of(name).disableCounterPrefix().withDestinationDir(output));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
