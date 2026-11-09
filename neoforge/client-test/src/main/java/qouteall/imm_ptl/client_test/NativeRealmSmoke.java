package qouteall.imm_ptl.client_test;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.MixinEnvironment;
import qouteall.dimlib.ClientDimensionInfo;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;
import qouteall.imm_ptl.client_test.mixin.RealmBiomeSeedObserver;
import qouteall.imm_ptl.peripheral.riftwoven.RealmPortal;
import qouteall.imm_ptl.peripheral.riftwoven.environment.client.RealmClientEnvironmentAccess;
import qouteall.imm_ptl.peripheral.riftwoven.environment.client.RealmBiomeSeeds;
import qouteall.imm_ptl.peripheral.riftwoven.environment.client.RealmSkyCompatibility;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Optional real-client realm qualification; never rewrites baseline Nether prediction evidence. */
final class NativeRealmSmoke {
    private static final org.slf4j.Logger LOGGER = LogUtils.getLogger();
    private enum Stage { PREVIEW, VIEW_CAPTURE, ENTER, ARRIVAL, TERRAIN_POSITION, TERRAIN,
        TERRAIN_CAPTURE, SKY, SKY_CAPTURE, AWAY, RETURN, REBUILD, RETURN_CAPTURE, TERRAIN_REPLAY }

    private final NativeClientSmoke host;
    private final Map<String, Object> report;
    private final List<Map<String, Object>> landings;
    private final int awayTicks;
    private CompletableFuture<RealmScene.Fixture> setup;
    private RealmScene.Fixture fixture;
    private CompletableFuture<RealmScene.Environment> environmentSample;
    private RealmScene.Environment environment;
    private CompletableFuture<?> action;
    private CompletableFuture<Void> capture;
    private Stage stage = Stage.PREVIEW;
    private long started = System.nanoTime();
    private int ticks, stable;
    private Vec3 terrainPosition;
    private long firstPhysicalTicks = -1;
    private double firstClientClock = -1;
    private int clearSkyTicks, clearSkyDraws;
    private CompletableFuture<Map<String, Object>> terrainDiagnostics;
    private TerrainReplaySmoke replay;
    private CompletableFuture<Map<String, Object>> previewServerSample;
    private Map<String, Object> previewServerEvidence = Map.of();

    NativeRealmSmoke(NativeClientSmoke host, Map<String, Object> report,
        List<Map<String, Object>> landings, int awayTicks) {
        this.host = host;
        this.report = report;
        this.landings = landings;
        this.awayTicks = awayTicks;
    }

    CompletableFuture<?> prepare(Minecraft client) {
        setup = submitIo(client, () -> RealmScene.prepare(client.getSingleplayerServer()));
        return setup;
    }

    @FunctionalInterface
    private interface IoSupplier<T> {
        T get() throws IOException;
    }

    private static <T> CompletableFuture<T> submitIo(Minecraft client, IoSupplier<T> supplier) {
        return client.getSingleplayerServer().submit(() -> {
            try {
                return supplier.get();
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        });
    }

    boolean tick(Minecraft client, NativeClientSmoke.ServerState server, int frames) {
        ticks++;
        long limit = stage == Stage.AWAY ? awayTicks / 20 + 120L : 180L;
        if (stage == Stage.PREVIEW && System.nanoTime() - started >= TimeUnit.SECONDS.toNanos(limit)) {
            report.put("realm_preview_failure", previewEvidence(client, server, frames, stable >= 40));
        }
        ArtifactProof.require(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(limit), "Realm stage timed out: " + stage);
        if (fixture == null) {
            fixture = setup.join();
            report.put("realm", fixture.evidence());
            report.put("coverage", "one genuine survival client and integrated server; public ledger/core APIs and manually configured private RealmPortal; not physical awakening/open hold ritual or TCP/multiplayer");
            Evidence.expectedRealm = fixture.dimension().identifier().toString();
        }
        sampleEnvironment(client);
        switch (stage) {
            case PREVIEW -> {
                if (previewServerSample != null && previewServerSample.isDone()) {
                    previewServerEvidence = previewServerSample.join();
                    previewServerSample = null;
                }
                if (previewServerSample == null && ticks % 20 == 0) {
                    previewServerSample = client.getSingleplayerServer().submit(() -> RealmScene.previewDiagnostics(client.getSingleplayerServer(), fixture));
                }
                boolean sourceSettled = settled(client, server, Level.OVERWORLD, RealmScene.SOURCE);
                if (!report.containsKey("realm_preview_first") || ticks % 10 == 0) {
                    var evidence = previewEvidence(client, server, frames, sourceSettled);
                    report.putIfAbsent("realm_preview_first", evidence);
                    report.put("realm_preview_latest", evidence);
                }
                if (!sourceSettled || ticks < 200 || frames < 30) return false;
                if (!privateViewReady(client) || !environmentReady(client, false)) return false;
                snapshot(client, server, "realm_source_ready");
                report.put("realm_private_portal_received", true);
                report.put("realm_generated_chunk_received", true);
                report.put("realm_remote_sky_extractions", Evidence.REALM_SKY_EXTRACTIONS.get());
                report.put("realm_environment_before_entry", true);
                report.put("realm_preview_clock", clockEvidence(client));
                capture = host.screenshot("realm-portal-view.png");
                move(Stage.VIEW_CAPTURE);
            }
            case VIEW_CAPTURE -> {
                if (!captured()) return false;
                client.options.keyUp.setDown(true);
                move(Stage.ENTER);
            }
            case ENTER -> {
                if (client.level == null || !client.level.dimension().equals(fixture.dimension())) return false;
                releaseInput(client);
                move(Stage.ARRIVAL);
            }
            case ARRIVAL -> {
                if (!settled(client, server, fixture.dimension(), null)) return false;
                ArtifactProof.require(Evidence.PREDICTIONS.contains(fixture.dimension().identifier().toString())
                    && Evidence.SERVER_TELEPORT_REQUESTS.get() >= 1, "Realm entry was not a genuine predicted portal crossing");
                ArtifactProof.require(environmentReady(client, true), "Realm environment was lost on actual entry");
                snapshot(client, server, "realm_landing");
                report.put("realm_entry_predicted", true);
                report.put("realm_entry_ack_settled", true);
                LOGGER.info("IP_NATIVE_REALM_CROSSED {} awaiting_teleport=false", fixture.dimension().identifier());
                action = submitIo(client, () -> RealmScene.terrainCamera(client.getSingleplayerServer(), fixture));
                move(Stage.TERRAIN_POSITION);
            }
            case TERRAIN_POSITION -> {
                if (!action.isDone()) return false;
                terrainPosition = (Vec3) action.join();
                report.put("realm_camera_same_dimension_reposition", true);
                report.put("realm_photo_weather_control", "normal realm sleep weather clearing after admitted arrival; recipe and physical clock unchanged");
                report.put("realm_selected_camera", RealmTerrainDiagnostics.position(terrainPosition));
                RealmTerrainDiagnostics.startPhoto(fixture.dimension(), terrainPosition);
                move(Stage.TERRAIN);
            }
            case TERRAIN -> {
                if (!settled(client, server, fixture.dimension(), terrainPosition) || ticks < 100) return false;
                ArtifactProof.require(client.level.getChunkSource().hasChunk(1, 1)
                    && client.level.getBlockState(fixture.terrainSample()).toString().equals(fixture.terrainState()),
                     "Actual realm terrain differs from generated server chunk");
                Map<String, Object> draw = RealmTerrainDiagnostics.mainDraw();
                if (!report.containsKey("realm_terrain_initial_main_draw") && !draw.isEmpty()) {
                    report.put("realm_terrain_initial_main_draw", draw);
                }
                report.put("realm_terrain_latest_readiness", draw.get("photo_readiness"));
                report.put("realm_terrain_readiness_wait_ticks", ticks - 100);
                if (!RealmTerrainDiagnostics.photoReady()) return false;
                if (terrainDiagnostics == null) {
                    terrainDiagnostics = client.getSingleplayerServer().submit(() ->
                        RealmScene.terrainDiagnostics(client.getSingleplayerServer(), fixture));
                    return false;
                }
                if (!terrainDiagnostics.isDone()) return false;
                snapshot(client, server, "realm_terrain_camera");
                report.put("realm_terrain_server", terrainDiagnostics.join());
                report.put("realm_terrain_client", RealmTerrainDiagnostics.sample(client.level, client.player.position()));
                report.put("realm_terrain_main_draw", RealmTerrainDiagnostics.mainDraw());
                capture = host.screenshot("realm-terrain.png");
                move(Stage.TERRAIN_CAPTURE);
            }
            case TERRAIN_CAPTURE -> {
                if (!captured()) return false;
                Map<String, Object> draw = RealmTerrainDiagnostics.mainDraw();
                report.put("realm_terrain_main_draw", draw);
                report.put("realm_terrain_readiness", draw.get("photo_readiness"));
                report.put("realm_terrain_ready_consecutive_frames", draw.get("ready_consecutive_frames"));
                client.player.setYRot(-90);
                client.player.setXRot(-35);
                move(Stage.SKY);
            }
            case SKY -> {
                if (!settled(client, server, fixture.dimension(), terrainPosition) || environment == null
                    || environment.rainLevel() != 0 || environment.thunderLevel() != 0
                    || client.level.getRainLevel(1) != 0 || client.level.getThunderLevel(1) != 0) {
                    clearSkyTicks = 0;
                    return false;
                }
                if (clearSkyTicks++ == 0) clearSkyDraws = Evidence.REALM_TWO_SUN_DRAWS.get();
                if (clearSkyTicks < 60) return false;
                ArtifactProof.require(environmentReady(client, true) && Evidence.REALM_SKY_EXTRACTIONS.get() > 0
                    && Evidence.REALM_TWO_SUN_DRAWS.get() > clearSkyDraws,
                    "Clear packaged realm sky did not extract and freshly draw two ordinary sun bodies");
                report.put("realm_sky_extractions", Evidence.REALM_SKY_EXTRACTIONS.get());
                report.put("realm_two_sun_draws", Evidence.REALM_TWO_SUN_DRAWS.get() - clearSkyDraws);
                report.put("realm_photo_weather_clear", true);
                report.put("realm_photo_weather", Map.of("server_rain", environment.rainLevel(),
                    "server_thunder", environment.thunderLevel(), "client_rain", client.level.getRainLevel(1),
                    "client_thunder", client.level.getThunderLevel(1), "clear_ticks", clearSkyTicks));
                report.put("realm_inside_clock", clockEvidence(client));
                capture = host.screenshot("realm-sky.png");
                move(Stage.SKY_CAPTURE);
            }
            case SKY_CAPTURE -> {
                if (!captured()) return false;
                move(Stage.AWAY);
            }
            case AWAY -> {
                boolean remainsSettled = settled(client, server, fixture.dimension(), terrainPosition);
                if (ticks < awayTicks) return false;
                ArtifactProof.require(remainsSettled && environmentReady(client, true)
                    && environment.physicalTicks() > firstPhysicalTicks && realmClock(client).totalTicks() > firstClientClock,
                    "Realm clock did not advance independently during the actual away interval");
                report.put("away_ticks_completed", ticks);
                report.put("realm_clock_advanced", true);
                report.put("realm_final_clock", clockEvidence(client));
                action = submitIo(client, () -> {
                    RealmScene.returnCore(client.getSingleplayerServer(), fixture);
                    return null;
                });
                move(Stage.RETURN);
            }
            case RETURN -> {
                if (!action.isDone()) return false;
                action.join();
                if (!settled(client, server, Level.OVERWORLD, RealmScene.SOURCE)) return false;
                snapshot(client, server, "realm_overworld_return");
                report.put("realm_return_mode", "public permanent core useCore -> maintained returnPlayer admission/rollback");
                report.put("realm_return_ack_settled", true);
                LOGGER.info("IP_NATIVE_REALM_RETURNED minecraft:overworld awaiting_teleport=false");
                report.put("realm_departure_retained_until_ack", true);
                client.player.setYRot(180);
                client.player.setXRot(0);
                client.levelExtractor.allChanged();
                report.put("normal_terrain_rebuild_requested", true);
                action = submitIo(client, () -> RealmScene.departureCleared(client.getSingleplayerServer(), fixture));
                move(Stage.REBUILD);
            }
            case REBUILD -> {
                if (!settled(client, server, Level.OVERWORLD, RealmScene.SOURCE) || ticks < 200 || !action.isDone()) return false;
                ArtifactProof.require(Boolean.TRUE.equals(action.join()), "Acknowledged realm return did not clear its durable departure");
                ArtifactProof.require(!((RealmClientEnvironmentAccess) client.level).riftwoven$clock().isRealm(),
                    "Realm environment leaked into the returned Overworld client world");
                report.put("realm_departure_cleared_after_ack", true);
                report.put("realm_overworld_environment_restored", true);
                capture = host.screenshot("realm-return.png");
                move(Stage.RETURN_CAPTURE);
            }
            case RETURN_CAPTURE -> {
                if (!captured()) return false;
                boolean shader = IrisInterface.invoker.isShaderpackInUse();
                ArtifactProof.require(!Boolean.parseBoolean(ArtifactProof.property("shaderPackExpected")) || shader,
                    "Requested shader pack is not actually active in the realm scenario");
                report.put("shader_pack_active", shader);
                // Force-load remaining applicable mixin targets in this fresh physical-client JVM.
                // Audit diagnoses application, not unexercised gameplay paths or optional absent mods.
                host.checkpointBeforeMixinAudit();
                MixinEnvironment.getCurrentEnvironment().audit();
                report.put("realm_mixin_audit_invoked", true);
                report.put("realm_original_prediction_dimensions", List.copyOf(Evidence.PREDICTIONS));
                replay = new TerrainReplaySmoke(host, fixture, report);
                move(Stage.TERRAIN_REPLAY);
            }
            case TERRAIN_REPLAY -> {
                return replay.tick(client, server);
            }
        }
        return false;
    }

    /** Observe every conjunct without changing the real short-circuit gates or their counters. */
    private Map<String, Object> previewEvidence(Minecraft client, NativeClientSmoke.ServerState server,
        int frames, boolean sourceSettled) {
        if (fixture == null) return Map.of("fixture_ready", false, "stage_ticks", ticks);
        var result = new LinkedHashMap<String, Object>();
        var conditions = new LinkedHashMap<String, Boolean>();
        result.put("stage_ticks", ticks);
        result.put("elapsed_seconds", (System.nanoTime() - started) / 1_000_000_000.0);
        result.put("rendered_frames", frames);
        result.put("stable_ticks", stable);
        result.put("expected_dimension", fixture.dimension().identifier().toString());
        result.put("expected_player_uuid", fixture.player().toString());
        result.put("expected_portal_uuid", fixture.portal().toString());
        result.put("expected_terrain_position", fixture.terrainSample().toShortString());
        result.put("expected_terrain_state", fixture.terrainState());
        result.put("latest_sampled_server", previewServerEvidence);
        result.put("server_terrain_still_matches_fixture", fixture.terrainState().equals(previewServerEvidence.get("terrain_state")));
        conditions.put("player_present", client.player != null);
        conditions.put("client_level_present", client.level != null);
        conditions.put("player_uses_current_level", client.player != null && client.level != null && client.player.level() == client.level);
        conditions.put("client_in_overworld", client.level != null && client.level.dimension().equals(Level.OVERWORLD));
        conditions.put("no_gui_screen", client.gui.screen() == null);
        conditions.put("server_sample_present", server != null);
        conditions.put("server_in_overworld", server != null && server.dimension().equals(Level.OVERWORLD.identifier().toString()));
        conditions.put("no_pending_teleport", server != null && !server.awaiting());
        conditions.put("server_player_registered", server != null && server.registered());
        conditions.put("client_server_position_match", client.player != null && server != null
            && client.player.position().distanceToSqr(new Vec3(server.x(), server.y(), server.z())) < 0.25);
        conditions.put("client_at_source", client.player != null && client.player.position().distanceToSqr(RealmScene.SOURCE) < 0.25);
        conditions.put("server_at_source", server != null && new Vec3(server.x(), server.y(), server.z()).distanceToSqr(RealmScene.SOURCE) < 0.25);
        conditions.put("settled_40_ticks", sourceSettled);
        conditions.put("minimum_200_ticks", ticks >= 200);
        conditions.put("minimum_30_frames", frames >= 30);
        if (client.player != null) {
            result.put("client_position", RealmTerrainDiagnostics.position(client.player.position()));
            result.put("actual_client_player_uuid", client.player.getUUID().toString());
        }
        if (client.level != null) result.put("client_dimension", client.level.dimension().identifier().toString());
        result.put("client_gui_screen", client.gui.screen() == null ? "none" : client.gui.screen().getClass().getName());
        if (server != null) {
            result.put("server_position", RealmTerrainDiagnostics.position(new Vec3(server.x(), server.y(), server.z())));
            result.put("server_state", Map.of("dimension", server.dimension(), "awaiting_teleport", server.awaiting(), "registered", server.registered()));
        }
        conditions.put("dimension_registered", ClientDimensionInfo.getDimensionIds().contains(fixture.dimension()));
        conditions.put("portal_sync_received", Evidence.PORTAL_SYNC.get() >= 1);
        conditions.put("client_world_loader_initialized", ClientWorldLoader.getIsInitialized());
        ClientLevel remote = ClientWorldLoader.getIsInitialized() ? ClientWorldLoader.getOptionalWorld(fixture.dimension()) : null;
        conditions.put("remote_level_present", remote != null);
        conditions.put("remote_sky_extraction_original_counter_positive", Evidence.REALM_SKY_EXTRACTIONS.get() > 0);
        conditions.put("remote_chunk_0_0_present", remote != null && remote.getChunkSource().hasChunk(0, 0));
        conditions.put("remote_chunk_1_1_present", remote != null && remote.getChunkSource().hasChunk(1, 1));
        String terrain = remote == null ? "no remote level" : remote.getBlockState(fixture.terrainSample()).toString();
        result.put("current_remote_terrain_state", terrain);
        result.put("remote_terrain_matches_latest_server", terrain.equals(previewServerEvidence.get("terrain_state")));
        conditions.put("remote_terrain_matches_frozen_fixture", terrain.equals(fixture.terrainState()));
        var portals = client.level == null ? List.<RealmPortal>of() : client.level.getEntitiesOfClass(RealmPortal.class, new AABB(-4, 99, -4, 5, 106, 6));
        result.put("received_private_portals", portals.stream().map(portal -> Map.of("uuid", portal.getUUID().toString(),
            "specific_player", String.valueOf(portal.specificPlayerId), "destination", String.valueOf(portal.getDestDim()))).toList());
        conditions.put("matching_private_portal_received", portals.stream().anyMatch(portal -> portal.getUUID().equals(fixture.portal())
            && fixture.player().equals(portal.specificPlayerId) && fixture.dimension().equals(portal.getDestDim())));
        result.put("portal_sync_count", Evidence.PORTAL_SYNC.get());
        result.put("original_realm_sky_extractions", Evidence.REALM_SKY_EXTRACTIONS.get());
        result.put("original_two_sun_draws", Evidence.REALM_TWO_SUN_DRAWS.get());
        result.put("sky_observer_actual_realm", SkyExtractionEvidence.snapshot(fixture.dimension().identifier().toString()));
        result.put("sky_observer_actual_overworld", SkyExtractionEvidence.snapshot(Level.OVERWORLD.identifier().toString()));
        result.put("observer_expected_realm", String.valueOf(Evidence.expectedRealm));
        long expectedSeed = BiomeManager.obfuscateSeed(fixture.record().seed());
        var cached = RealmBiomeSeeds.cachedSeed(fixture.dimension());
        result.put("expected_obfuscated_seed", expectedSeed);
        result.put("cached_biome_seed_present", cached.isPresent());
        if (cached.isPresent()) result.put("cached_biome_seed", cached.getAsLong());
        var attached = remote == null ? null : ((RealmClientEnvironmentAccess) remote).riftwoven$clock();
        var layout = attached == null ? null : attached.layout();
        conditions.put("realm_environment_attached", attached != null && attached.isRealm());
        conditions.put("layout_two_suns_one_moon", layout != null && layout.suns() == 2 && layout.moons() == 1);
        conditions.put("layout_obfuscated_seed_matches", layout != null && layout.seed() == expectedSeed);
        conditions.put("actual_remote_biome_seed_matches", remote != null
            && ((RealmBiomeSeedObserver) remote.getBiomeManager()).ip_nativeClient$biomeZoomSeed() == expectedSeed);
        conditions.put("server_environment_sample_present", environment != null);
        conditions.put("overworld_environment_not_realm", client.level != null
            && !((RealmClientEnvironmentAccess) client.level).riftwoven$clock().isRealm());
        if (layout != null) result.put("actual_layout", Map.of("suns", layout.suns(), "moons", layout.moons(), "cosmetic_seed", layout.seed()));
        if (remote != null) result.put("actual_remote_biome_seed", ((RealmBiomeSeedObserver) remote.getBiomeManager()).ip_nativeClient$biomeZoomSeed());
        boolean hasClock = remote != null && remote.dimensionType().defaultClock().isPresent();
        conditions.put("remote_default_clock_present", hasClock);
        if (hasClock) {
            var clock = remote.clockManager().getInstance(remote.dimensionType().defaultClock().orElseThrow());
            double time = clock.totalTicks() + clock.partialTick();
            result.put("actual_client_clock", Map.of("ticks", time, "rate", clock.rate()));
            conditions.put("clock_time_within_80", environment != null && Math.abs(time - environment.clockTicks()) <= 80);
            conditions.put("clock_rate_matches", environment != null && Math.abs(clock.rate() - environment.rate()) <= 0.0001);
            if (environment != null) {
                result.put("clock_time_difference", time - environment.clockTicks());
                result.put("clock_rate_difference", clock.rate() - environment.rate());
            }
        } else {
            conditions.put("clock_time_within_80", false);
            conditions.put("clock_rate_matches", false);
        }
        conditions.put("realm_clock_distinct_from_overworld", environment != null && Math.abs(environment.clockTicks() - environment.overworldClockTicks()) >= 100);
        if (environment != null) result.put("sampled_server_environment", Map.of("physical_ticks", environment.physicalTicks(),
            "clock_ticks", environment.clockTicks(), "rate", environment.rate(), "overworld_clock_ticks", environment.overworldClockTicks()));
        result.put("shader_pack_active", IrisInterface.invoker.isShaderpackInUse());
        result.put("shader_pack_name", String.valueOf(IrisInterface.invoker.getShaderpackName()));
        result.put("boundary_warning_expected", String.valueOf(RealmSkyCompatibility.previewWarning(2, 1)));
        result.put("conditions", conditions);
        result.put("false_conditions", conditions.entrySet().stream().filter(entry -> !entry.getValue()).map(Map.Entry::getKey).toList());
        return result;
    }

    private boolean privateViewReady(Minecraft client) {
        if (!ClientDimensionInfo.getDimensionIds().contains(fixture.dimension()) || Evidence.PORTAL_SYNC.get() < 1
            || !ClientWorldLoader.getIsInitialized()) return false;
        ClientLevel remote = ClientWorldLoader.getOptionalWorld(fixture.dimension());
        return remote != null && Evidence.REALM_SKY_EXTRACTIONS.get() > 0
            && remote.getChunkSource().hasChunk(0, 0) && remote.getChunkSource().hasChunk(1, 1)
            && remote.getBlockState(fixture.terrainSample()).toString().equals(fixture.terrainState())
            && client.level.getEntitiesOfClass(RealmPortal.class, new AABB(-4, 99, -4, 5, 106, 6)).stream().anyMatch(portal ->
                portal.getUUID().equals(fixture.portal()) && fixture.player().equals(portal.specificPlayerId)
                    && fixture.dimension().equals(portal.getDestDim()));
    }

    private void sampleEnvironment(Minecraft client) {
        if (environmentSample != null && environmentSample.isDone()) {
            environment = environmentSample.join();
            environmentSample = null;
        }
        if (environmentSample == null && ticks % 10 == 0) {
            environmentSample = client.getSingleplayerServer().submit(() -> RealmScene.environment(client.getSingleplayerServer(), fixture));
        }
    }

    private net.minecraft.world.clock.ClockInstance realmClock(Minecraft client) {
        ClientLevel remote = ClientWorldLoader.getOptionalWorld(fixture.dimension());
        return remote.clockManager().getInstance(remote.dimensionType().defaultClock().orElseThrow());
    }

    private boolean environmentReady(Minecraft client, boolean inside) {
        ClientLevel remote = ClientWorldLoader.getOptionalWorld(fixture.dimension());
        if (remote == null || environment == null) return false;
        var attached = ((RealmClientEnvironmentAccess) remote).riftwoven$clock();
        var layout = attached.layout();
        long expectedBiomeSeed = BiomeManager.obfuscateSeed(fixture.record().seed());
        if (!attached.isRealm() || layout.suns() != 2 || layout.moons() != 1
            || layout.seed() != expectedBiomeSeed
            || ((RealmBiomeSeedObserver) remote.getBiomeManager()).ip_nativeClient$biomeZoomSeed() != expectedBiomeSeed
            || inside && ((RealmBiomeSeedObserver) client.level.getBiomeManager()).ip_nativeClient$biomeZoomSeed() != expectedBiomeSeed) return false;
        var clock = realmClock(client);
        if (Math.abs(clock.totalTicks() + clock.partialTick() - environment.clockTicks()) > 80
            || Math.abs(clock.rate() - environment.rate()) > 0.0001) return false;
        if (!inside && ((RealmClientEnvironmentAccess) client.level).riftwoven$clock().isRealm()) return false;
        if (Math.abs(environment.clockTicks() - environment.overworldClockTicks()) < 100) return false;
        if (firstPhysicalTicks < 0) {
            firstPhysicalTicks = environment.physicalTicks();
            firstClientClock = clock.totalTicks();
        }
        return true;
    }

    private Map<String, Object> clockEvidence(Minecraft client) {
        var clock = realmClock(client);
        return Map.of("server_physical_ticks", environment.physicalTicks(), "server_clock_ticks", environment.clockTicks(),
            "client_clock_ticks", clock.totalTicks() + clock.partialTick(), "server_rate", environment.rate(),
            "client_rate", clock.rate(), "overworld_clock_ticks", environment.overworldClockTicks(),
            "suns", 2, "moons", 1);
    }

    private boolean settled(Minecraft client, NativeClientSmoke.ServerState server, ResourceKey<Level> dimension, Vec3 position) {
        boolean ready = client.player != null && client.level != null && client.player.level() == client.level
            && client.level.dimension().equals(dimension) && client.gui.screen() == null && server != null
            && server.dimension().equals(dimension.identifier().toString()) && !server.awaiting() && server.registered()
            && client.player.position().distanceToSqr(new Vec3(server.x(), server.y(), server.z())) < 0.25
            && (position == null || client.player.position().distanceToSqr(position) < 0.25
                && new Vec3(server.x(), server.y(), server.z()).distanceToSqr(position) < 0.25);
        stable = ready ? stable + 1 : 0;
        return stable >= 40;
    }

    private void snapshot(Minecraft client, NativeClientSmoke.ServerState server, String name) {
        landings.add(Map.ofEntries(Map.entry("stage", name), Map.entry("dimension", server.dimension()),
            Map.entry("client_dimension", client.level.dimension().identifier().toString()), Map.entry("server_x", server.x()),
            Map.entry("server_y", server.y()), Map.entry("server_z", server.z()), Map.entry("client_x", client.player.getX()),
            Map.entry("client_y", client.player.getY()), Map.entry("client_z", client.player.getZ()),
            Map.entry("awaiting_teleport", server.awaiting()), Map.entry("registered", server.registered())));
    }

    private boolean captured() {
        if (!capture.isDone()) return false;
        capture.join();
        return true;
    }

    private void move(Stage next) {
        LOGGER.info("IP_NATIVE_REALM_STAGE {} -> {}", stage, next);
        stage = next;
        ticks = 0;
        stable = 0;
        started = System.nanoTime();
    }

    void releaseInput(Minecraft client) { client.options.keyUp.setDown(false); }
}
