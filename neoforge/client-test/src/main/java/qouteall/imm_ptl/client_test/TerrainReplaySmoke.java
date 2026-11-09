package qouteall.imm_ptl.client_test;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import qouteall.imm_ptl.client_test.mixin.RealmBiomeSeedObserver;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.peripheral.riftwoven.environment.client.RealmClientEnvironmentAccess;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Runs only after the original realm checks/captures, with separate evidence and no ledger claims. */
final class TerrainReplaySmoke {
    private enum Stage { SETUP, METADATA, ENTER, ARRIVAL, CAPTURE, RETURN }
    private final NativeClientSmoke host;
    private final RealmScene.Fixture fixture;
    private final Map<String, Object> report;
    private final List<Map<String, Object>> landings = new ArrayList<>();
    private CompletableFuture<?> action;
    private CompletableFuture<Boolean> weather;
    private CompletableFuture<Map<String, Object>> diagnostics;
    private CompletableFuture<Void> capture;
    private Stage stage = Stage.SETUP;
    private long started = System.nanoTime();
    private int ticks, stable, clearTicks;
    private boolean serverClear, aimed;
    private int entryRequestsBefore;

    TerrainReplaySmoke(NativeClientSmoke host, RealmScene.Fixture fixture, Map<String, Object> outerReport) {
        this.host = host;
        this.fixture = fixture;
        report = new LinkedHashMap<>();
        outerReport.put("terrain_replay", report);
    }

    boolean tick(Minecraft client, NativeClientSmoke.ServerState server) {
        ticks++;
        ArtifactProof.require(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(180), "Terrain replay timed out: " + stage);
        switch (stage) {
            case SETUP -> {
                if (action == null) {
                    action = client.getSingleplayerServer().submit(() -> TerrainReplayScene.prepare(client.getSingleplayerServer(), fixture));
                    return false;
                }
                if (!action.isDone()) return false;
                @SuppressWarnings("unchecked") var prepared = (Map<String, Object>) action.join();
                report.putAll(prepared);
                move(Stage.METADATA);
            }
            case METADATA -> {
                var remote = ClientWorldLoader.getOptionalWorld(TerrainReplayScene.DIMENSION);
                if (remote == null || ticks < 200
                    || !settled(client, server, Level.OVERWORLD, RealmScene.SOURCE)) return false;
                long expected = BiomeManager.obfuscateSeed(TerrainReplayScene.SEED);
                var environment = ((RealmClientEnvironmentAccess) remote).riftwoven$clock();
                if (((RealmBiomeSeedObserver) remote.getBiomeManager()).ip_nativeClient$biomeZoomSeed() != expected
                    || !environment.isRealm() || environment.layout().seed() != expected
                    || environment.layout().suns() != 2 || environment.layout().moons() != 1) return false;
                if (!remote.getChunkSource().hasChunk(0, 0) || !remote.getChunkSource().hasChunk(1, 1)
                    || !remote.getBlockState(TerrainReplayScene.FLOOR).is(Blocks.GRASS_BLOCK)
                    || client.level.getEntitiesOfClass(Portal.class, new AABB(-4, 99, -4, 5, 106, 6)).stream().noneMatch(portal ->
                        portal.getUUID().toString().equals(report.get("portal_uuid")) && portal.getDestDim().equals(TerrainReplayScene.DIMENSION))) return false;
                report.put("client_metadata_before_predicted_entry", true);
                entryRequestsBefore = Evidence.SERVER_TELEPORT_REQUESTS.get();
                client.options.keyUp.setDown(true);
                move(Stage.ENTER);
            }
            case ENTER -> {
                if (client.level == null || !client.level.dimension().equals(TerrainReplayScene.DIMENSION)) return false;
                client.options.keyUp.setDown(false);
                if (!settled(client, server, TerrainReplayScene.DIMENSION, null)) return false;
                ArtifactProof.require(Evidence.PREDICTIONS.contains(TerrainReplayScene.DIMENSION.identifier().toString())
                    && Evidence.SERVER_TELEPORT_REQUESTS.get() > entryRequestsBefore, "Supplemental ordinary portal entry was not genuinely predicted/requested");
                report.put("ordinary_entry_predicted", true);
                report.put("entry_server_requests", Evidence.SERVER_TELEPORT_REQUESTS.get() - entryRequestsBefore);
                landings.add(landing(client, server, "diagnostic_portal_arrival"));
                action = client.getSingleplayerServer().submit(() -> TerrainReplayScene.camera(client.getSingleplayerServer(), fixture));
                RealmTerrainDiagnostics.startPhoto(TerrainReplayScene.DIMENSION, TerrainReplayScene.CAMERA);
                move(Stage.ARRIVAL);
            }
            case ARRIVAL -> {
                if (!action.isDone()) return false;
                action.join();
                if (client.level != null && client.level.dimension().equals(TerrainReplayScene.DIMENSION) && !aimed) {
                    client.player.setYRot(135);
                    client.player.setXRot(12);
                    aimed = true;
                }
                if (weather != null && weather.isDone()) { serverClear = weather.join(); weather = null; }
                if (weather == null && ticks % 10 == 0) {
                    weather = client.getSingleplayerServer().submit(() -> TerrainReplayScene.weatherClear(client.getSingleplayerServer()));
                }
                if (!settled(client, server, TerrainReplayScene.DIMENSION, TerrainReplayScene.CAMERA)
                    || !serverClear || client.level.getRainLevel(1) != 0 || client.level.getThunderLevel(1) != 0) {
                    clearTicks = 0;
                    return false;
                }
                if (++clearTicks < 60 || ticks < 100) return false;
                var draw = RealmTerrainDiagnostics.mainDraw();
                if (!report.containsKey("initial_main_draw") && !draw.isEmpty()) report.put("initial_main_draw", draw);
                report.put("latest_readiness", draw.get("photo_readiness"));
                report.put("latest_main_draw", draw);
                if (!RealmTerrainDiagnostics.photoReady()) return false;
                if (diagnostics == null) {
                    diagnostics = client.getSingleplayerServer().submit(() -> TerrainReplayScene.diagnostics(client.getSingleplayerServer(), fixture));
                    return false;
                }
                if (!diagnostics.isDone() || RealmTerrainDiagnostics.mainDraw().isEmpty()) return false;
                report.put("server", diagnostics.join());
                report.put("client", RealmTerrainDiagnostics.sample(client.level, client.player.position()));
                report.put("main_draw", RealmTerrainDiagnostics.mainDraw());
                report.put("render_readiness", draw.get("photo_readiness"));
                report.put("clear_ticks", clearTicks);
                report.put("arrival_ticks", ticks);
                report.put("natural_floor_matches", client.level.getBlockState(TerrainReplayScene.FLOOR).is(Blocks.GRASS_BLOCK));
                landings.add(landing(client, server, "diagnostic_arrival"));
                report.put("landings", landings);
                capture = host.screenshot("realm-terrain-replay.png");
                move(Stage.CAPTURE);
            }
            case CAPTURE -> {
                if (!capture.isDone()) return false;
                capture.join();
                var draw = RealmTerrainDiagnostics.mainDraw();
                report.put("main_draw", draw);
                report.put("render_readiness", draw.get("photo_readiness"));
                report.put("ready_consecutive_frames", draw.get("ready_consecutive_frames"));
                ArtifactProof.require(Boolean.TRUE.equals(report.get("natural_floor_matches")), "Replay client natural grass floor differs from original server evidence");
                ArtifactProof.require(((Map<?, ?>) report.get("render_readiness")).get("ready").equals(Boolean.TRUE),
                    "Replay main camera, clipping or nearby ground mesh is not ready; actual photograph retained");
                action = client.getSingleplayerServer().submit(() -> TerrainReplayScene.returnSafe(client.getSingleplayerServer(), fixture));
                move(Stage.RETURN);
            }
            case RETURN -> {
                if (!action.isDone()) return false;
                action.join();
                if (!settled(client, server, Level.OVERWORLD, RealmScene.SOURCE)) return false;
                landings.add(landing(client, server, "diagnostic_safe_return"));
                report.put("safe_return_ack_settled", true);
                report.put("status", "DIAGNOSTIC_CAPTURED");
                return true;
            }
        }
        return false;
    }

    private boolean settled(Minecraft client, NativeClientSmoke.ServerState server,
        net.minecraft.resources.ResourceKey<Level> dimension, Vec3 position) {
        boolean ready = client.level != null && client.player != null && client.player.level() == client.level
            && client.level.dimension().equals(dimension) && client.gui.screen() == null && server != null
            && server.dimension().equals(dimension.identifier().toString()) && !server.awaiting() && server.registered()
            && client.player.position().distanceToSqr(new Vec3(server.x(), server.y(), server.z())) < 0.25
            && (position == null || client.player.position().distanceToSqr(position) < 0.25
                && new Vec3(server.x(), server.y(), server.z()).distanceToSqr(position) < 0.25);
        stable = ready ? stable + 1 : 0;
        return stable >= 40;
    }

    private static Map<String, Object> landing(Minecraft client, NativeClientSmoke.ServerState server, String stage) {
        return Map.of("stage", stage, "server_dimension", server.dimension(),
            "client_dimension", client.level.dimension().identifier().toString(),
            "server_position", RealmTerrainDiagnostics.position(new Vec3(server.x(), server.y(), server.z())),
            "client_position", RealmTerrainDiagnostics.position(client.player.position()),
            "awaiting_teleport", server.awaiting(), "registered", server.registered());
    }

    private void move(Stage next) {
        stage = next;
        ticks = 0;
        stable = 0;
        started = System.nanoTime();
    }
}
