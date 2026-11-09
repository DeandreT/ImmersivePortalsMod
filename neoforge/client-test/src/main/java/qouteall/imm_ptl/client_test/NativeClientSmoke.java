package qouteall.imm_ptl.client_test;

import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.i18n.FMLTranslations;
import net.neoforged.neoforge.client.gui.LoadingErrorScreen;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import qouteall.dimlib.ClientDimensionInfo;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;
import qouteall.imm_ptl.core.ducks.IEServerPlayNetworkHandler;
import qouteall.imm_ptl.core.network.ImmPtlNetworkConfig;
import qouteall.imm_ptl.core.platform_shared.EntityRegistration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** One real physical client and integrated server, with actual local FML/network lifecycle. */
final class NativeClientSmoke {
    private static final Logger LOGGER = LogUtils.getLogger();
    private enum Stage { TITLE, JOIN, SETUP, PREVIEW, VIEW_CAPTURE, TO_NETHER, NETHER_CAPTURE,
        AWAY, TO_OVERWORLD, REBUILD, RETURN_CAPTURE, REALM, DONE }
    private final Map<String, Object> report = new LinkedHashMap<>();
    private final List<Map<String, Object>> landings = new ArrayList<>();
    private Stage stage = Stage.TITLE;
    private long stageStarted = System.nanoTime();
    private int ticks, stageTicks, stable, frames, loginEvents;
    private boolean localConnection;
    private Path run, output;
    private int awayTicks;
    private CompletableFuture<?> action;
    private CompletableFuture<ServerState> sample;
    private ServerState serverState;
    private String captureName;
    private CompletableFuture<Void> capture;
    private NativeRealmSmoke realm;

    NativeClientSmoke() {
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(this::render);
        NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Pre event) -> RealmTerrainDiagnostics.beginFrame());
        NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Pre event) -> BaselineMarkerDiagnostics.beginFrame());
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn event) -> {
            loginEvents++;
            localConnection = event.getConnection().isMemoryConnection();
            LOGGER.info("IP_NATIVE_CLIENT_LOGIN actual_local_connection={}", localConnection);
        });
    }

    private void tick(ClientTickEvent.Post event) {
        if (stage == Stage.DONE) { return; }
        Minecraft client = Minecraft.getInstance();
        try {
            ticks++;
            stageTicks++;
            long stageLimit = stage == Stage.REALM ? awayTicks / 20 + 480L : stage == Stage.AWAY ? awayTicks / 20 + 120L : 180L;
            ArtifactProof.require(System.nanoTime() - stageStarted < TimeUnit.SECONDS.toNanos(stageLimit), "Stage timed out: " + stage);
            if (client.getSingleplayerServer() != null && client.player != null) { sampleServer(client); }
            switch (stage) {
                case TITLE -> {
                    if (client.gui.screen() instanceof LoadingErrorScreen issues) {
                        // NeoForge creates Continue only when there are NO loading
                        // errors. Use its real UI action; never dismiss fatal issues
                        // or suppress their log/qualification failure.
                        String label = FMLTranslations.parseMessage("fml.button.continue.launch");
                        AbstractButton proceed = issues.children().stream()
                            .filter(child -> child instanceof AbstractButton button && button.isActive()
                                && button.getMessage().getString().equals(label))
                            .map(child -> (AbstractButton) child).findFirst().orElseThrow(
                                () -> new IllegalStateException("Native mod-loading errors have no permitted Continue action"));
                        LOGGER.warn("IP_NATIVE_CLIENT_LOAD_WARNINGS acknowledged through actual Continue button");
                        report.put("load_warning_screen_acknowledged", true);
                        proceed.onPress(new KeyEvent(InputConstants.KEY_RETURN, 0, 0));
                        return;
                    }
                    if (!(client.gui.screen() instanceof TitleScreen) || client.gui.overlay() != null) { return; }
                    initialize(client);
                    move(Stage.JOIN);
                    client.createWorldOpenFlows().createFreshLevel("native-smoke",
                        new LevelSettings("Owned native real-client smoke", realmRequested() ? GameType.SURVIVAL : GameType.CREATIVE,
                            new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, true), true, WorldDataConfiguration.DEFAULT),
                        new WorldOptions(0L, false, false),
                        registries -> registries.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
                        client.gui.screen());
                }
                case JOIN -> {
                    if (client.player == null || client.level == null || client.gui.screen() != null) { return; }
                    ArtifactProof.require(localConnection && loginEvents == 1 && client.isLocalServer(), "Not one authentic integrated-server login");
                    ArtifactProof.require(!client.getSingleplayerServer().isPublished(), "The owned integrated server must not be published to LAN");
                    report.put("server_published", false);
                    ArtifactProof.require(Evidence.CONFIG_START.get() > 0 && Evidence.CONFIG_COMPLETE.get() > 0
                        && ImmPtlNetworkConfig.doesServerHaveImmPtl(), "Actual ImmPtl configuration exchange was not observed");
                    ArtifactProof.require(Evidence.DIMENSION_SYNC.get() > 0
                        && ClientDimensionInfo.getDimensionIds().containsAll(List.of(Level.OVERWORLD, Level.NETHER)),
                        "Actual DimLib payload was not consumed");
                    ArtifactProof.require(!IPGlobal.disableTeleportation, "Portal teleportation is disabled");
                    if (!client.gui.hud.isHidden()) { client.gui.hud.toggle(); }
                    client.options.bobView().set(false);
                    if (realmRequested()) {
                        realm = new NativeRealmSmoke(this, report, landings, awayTicks);
                        action = realm.prepare(client);
                    } else {
                        BaselineMarkerDiagnostics.start(output);
                        action = client.getSingleplayerServer().submit(() -> Scene.prepare(client.getSingleplayerServer()));
                    }
                    move(Stage.SETUP);
                }
                case SETUP -> {
                    if (!action.isDone()) { return; }
                    action.join();
                    move(realm == null ? Stage.PREVIEW : Stage.REALM);
                }
                case PREVIEW -> {
                    if (settled(client, Level.OVERWORLD, -20) && stageTicks > 200 && frames > 30) {
                        ArtifactProof.require(Evidence.PORTAL_SYNC.get() > 0 && ClientWorldLoader.getIsInitialized()
                            && ClientWorldLoader.getClientWorlds().stream().anyMatch(world -> world.dimension() == Level.NETHER),
                            "No real portal sync/destination client world");
                        landing(client, "source_ready");
                        screenshot("portal-view.png");
                        move(Stage.VIEW_CAPTURE);
                    }
                }
                case VIEW_CAPTURE -> {
                    if (!capture.isDone()) { return; }
                    capture.join();
                    action = client.getSingleplayerServer().submit(() -> Scene.release(client.getSingleplayerServer(), false));
                    move(Stage.TO_NETHER);
                }
                case TO_NETHER -> {
                    if (!action.isDone()) { return; }
                    action.join();
                    if (settled(client, Level.NETHER, 100)) {
                        ArtifactProof.require(Evidence.PREDICTIONS.contains("minecraft:the_nether")
                            && Evidence.SERVER_TELEPORT_REQUESTS.get() >= 1, "Nether arrival was not an authentic predicted portal crossing");
                        landing(client, "nether_landing");
                        LOGGER.info("IP_NATIVE_CLIENT_CROSSED minecraft:the_nether correct_landing=true awaiting_teleport=false");
                        screenshot("after-crossing.png");
                        move(Stage.NETHER_CAPTURE);
                    }
                }
                case NETHER_CAPTURE -> {
                    if (!capture.isDone()) { return; }
                    capture.join();
                    move(Stage.AWAY);
                }
                case AWAY -> {
                    boolean remainsSettled = settled(client, Level.NETHER, 100);
                    if (stageTicks < awayTicks) { return; }
                    ArtifactProof.require(remainsSettled, "Player did not remain at the Nether landing");
                    report.put("away_ticks_completed", stageTicks);
                    action = client.getSingleplayerServer().submit(() -> Scene.release(client.getSingleplayerServer(), true));
                    move(Stage.TO_OVERWORLD);
                }
                case TO_OVERWORLD -> {
                    if (!action.isDone()) { return; }
                    action.join();
                    if (settled(client, Level.OVERWORLD, -20)) {
                        ArtifactProof.require(Evidence.PREDICTIONS.contains("minecraft:overworld")
                            && Evidence.SERVER_TELEPORT_REQUESTS.get() >= 2, "Return was not an authentic predicted portal crossing");
                        landing(client, "overworld_return");
                        LOGGER.info("IP_NATIVE_CLIENT_RETURNED minecraft:overworld correct_landing=true awaiting_teleport=false");
                        // The ordinary renderer rebuild entrypoint, not a replacement renderer.
                        client.levelExtractor.allChanged();
                        report.put("normal_terrain_rebuild_requested", true);
                        move(Stage.REBUILD);
                    }
                }
                case REBUILD -> {
                    if (settled(client, Level.OVERWORLD, -20) && stageTicks > 200) {
                        screenshot("after-return.png");
                        move(Stage.RETURN_CAPTURE);
                    }
                }
                case RETURN_CAPTURE -> {
                    if (!capture.isDone()) { return; }
                    capture.join();
                    boolean shader = IrisInterface.invoker.isShaderpackInUse();
                    ArtifactProof.require(!Boolean.parseBoolean(ArtifactProof.property("shaderPackExpected")) || shader,
                        "The requested shader pack is not actually active");
                    report.put("shader_pack_active", shader);
                    checkpointBeforeMixinAudit();
                    finish(client, null);
                }
                case REALM -> {
                    if (realm.tick(client, serverState, frames)) { finish(client, null); }
                }
                default -> { }
            }
        }
        catch (Throwable failure) { finish(client, failure); }
    }

    private void initialize(Minecraft client) throws Exception {
        run = client.gameDirectory.toPath().toRealPath();
        output = Path.of(ArtifactProof.property("clientReportDir")).toRealPath();
        String nonce = ArtifactProof.property("clientNonce");
        ArtifactProof.require(FMLEnvironment.getDist() == Dist.CLIENT && nonce.length() >= 32
            && run.equals(Path.of(ArtifactProof.property("clientRunDir")).toRealPath())
            && run.getParent().equals(Path.of("/tmp/opencode").toRealPath())
            && run.getFileName().toString().startsWith("ip-native-client-world-")
            && output.getParent().equals(run.getParent()) && output.getFileName().toString().startsWith("ip-native-client-report-")
            && Files.readString(run.resolve(".ip-native-client-owned")).trim().equals(nonce)
            && Files.readString(output.resolve(".ip-native-client-owned")).trim().equals(nonce)
            && Files.readString(run.resolve("eula.txt")).trim().equals("eula=true")
            && !Files.exists(run.resolve("saves/native-smoke")), "Not a fresh approved owned client world");
        awayTicks = Integer.parseInt(ArtifactProof.property("awayTicks"));
        ArtifactProof.require(awayTicks >= 200 && awayTicks <= 4000, "Unsafe away tick budget");
        report.put("artifacts", ArtifactProof.verify(output));
        report.put("physical_side", "CLIENT");
        report.put("profile", ArtifactProof.property("profile"));
        if (realmRequested()) { report.put("scenario", "riftwoven_realm"); }
        report.put("coverage", "one real client + integrated server, native FML and actual local in-memory transport; not a remote or two-player test");
        LOGGER.info("IP_NATIVE_CLIENT_BEGIN actual_packaged_parent=true physical_side=CLIENT");
    }

    private void sampleServer(Minecraft client) {
        if (sample != null && sample.isDone()) { serverState = sample.join(); sample = null; }
        if (sample != null || ticks % 5 != 0) { return; }
        var server = client.getSingleplayerServer();
        var id = client.player.getUUID();
        sample = server.submit(() -> {
            var player = server.getPlayerList().getPlayer(id);
            ArtifactProof.require(player != null && server.getPlayerList().getPlayers().size() == 1, "Actual logged-in player is missing/duplicated");
            return new ServerState(player.level().dimension().identifier().toString(), player.getX(), player.getY(), player.getZ(),
                ((IEServerPlayNetworkHandler) player.connection).ip_hasAwaitingTeleport(),
                EntityRegistration.inspect((net.minecraft.server.level.ServerLevel) player.level(), player)
                    == EntityRegistration.RegistrationState.REGISTERED);
        });
    }

    private boolean settled(Minecraft client, net.minecraft.resources.ResourceKey<Level> dimension, double y) {
        boolean ready = client.player != null && client.level != null && client.player.level() == client.level
            && client.level.dimension() == dimension && client.gui.screen() == null && serverState != null
            && serverState.dimension.equals(dimension.identifier().toString()) && !serverState.awaiting && serverState.registered
            && Math.abs(client.player.getX() - 0.5) < 1 && Math.abs(client.player.getZ() - 0.5) < 1
            && Math.abs(client.player.getY() - y) < 0.15 && Math.abs(serverState.y - y) < 0.15
            && client.player.position().distanceToSqr(new net.minecraft.world.phys.Vec3(serverState.x, serverState.y, serverState.z)) < 0.25;
        stable = ready ? stable + 1 : 0;
        return stable >= 40;
    }

    private void landing(Minecraft client, String name) {
        landings.add(Map.of("stage", name, "dimension", serverState.dimension, "client_dimension", client.level.dimension().identifier().toString(),
            "server_x", serverState.x, "server_y", serverState.y, "server_z", serverState.z,
            "client_y", client.player.getY(), "awaiting_teleport", serverState.awaiting, "registered", serverState.registered));
    }
    private void move(Stage next) {
        LOGGER.info("IP_NATIVE_CLIENT_STAGE {} -> {}", stage, next);
        stage = next; stageTicks = 0; stable = 0; stageStarted = System.nanoTime();
    }
    CompletableFuture<Void> screenshot(String name) {
        capture = new CompletableFuture<>();
        captureName = name;
        return capture;
    }
    private void render(RenderFrameEvent.Post event) {
        frames++;
        if (captureName == null || stage == Stage.DONE) { return; }
        if (captureName.equals("realm-terrain.png") || captureName.equals("realm-terrain-replay.png")) {
            // Recheck the actual frame, not an earlier tick's mesh/queue state. Never rebuild
            // terrain or change render options to satisfy the photographic fixture.
            if (!RealmTerrainDiagnostics.photoReadyThisFrame()) { return; }
            RealmTerrainDiagnostics.freezePhoto();
        }
        String name = captureName;
        captureName = null;
        var future = capture;
        try {
            if (name.equals("portal-view.png")) { BaselineMarkerDiagnostics.freezeAndWrite(); }
            Screenshot.takeScreenshot(Minecraft.getInstance().gameRenderer.mainRenderTarget(), image -> {
                try (image) {
                    ArtifactProof.require(image.getWidth() >= 640 && image.getHeight() >= 400, "Invalid actual framebuffer size");
                    image.writeToFile(output.resolve(name));
                    LOGGER.info("IP_NATIVE_CLIENT_SCREENSHOT {}", name);
                    future.complete(null);
                }
                catch (Throwable failure) { future.completeExceptionally(failure); }
            });
        }
        catch (Throwable failure) { future.completeExceptionally(failure); }
    }

    void checkpointBeforeMixinAudit() {
        var checkpoint = new LinkedHashMap<String, Object>(report);
        checkpoint.put("status", "PARTIAL_DIAGNOSTIC");
        checkpoint.put("checkpoint", "BEFORE_MIXIN_AUDIT");
        checkpoint.put("audit_scheduled", realm != null);
        checkpoint.put("checkpoint_scope", realm == null
            ? "Actual loaded method/config evidence before baseline finish; baseline does not invoke audit"
            : "Partial realm evidence before the existing required audit; not a final runtime result");
        checkpoint.put("landings", landings.stream().map(LinkedHashMap::new).toList());
        checkpoint.put("rendered_frames", frames);
        checkpoint.put("login_events", loginEvents);
        checkpoint.put("actual_local_connection", localConnection);
        checkpoint.put("config_start", Evidence.CONFIG_START.get());
        checkpoint.put("config_complete", Evidence.CONFIG_COMPLETE.get());
        checkpoint.put("dimension_sync", Evidence.DIMENSION_SYNC.get());
        checkpoint.put("portal_sync", Evidence.PORTAL_SYNC.get());
        checkpoint.put("server_teleport_requests", Evidence.SERVER_TELEPORT_REQUESTS.get());
        checkpoint.put("prediction_dimensions", List.copyOf(Evidence.PREDICTIONS));
        checkpoint.put("mixin_audit_evidence", MixinAuditEvidence.sample());
        try {
            AtomicDiagnosticWriter.write(output.resolve(realm == null ? "baseline-pre-audit-diagnostics.json"
                : "realm-pre-audit-diagnostics.json"), new GsonBuilder().setPrettyPrinting().create().toJson(checkpoint));
        } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException("Cannot publish pre-audit diagnostic", failure);
        }
    }

    private void finish(Minecraft client, Throwable failure) {
        if (realm != null) { realm.releaseInput(client); }
        stage = Stage.DONE;
        report.put("status", failure == null ? "RUNTIME_PASS" : "FAIL");
        report.put("landings", landings);
        report.put("rendered_frames", frames);
        report.put("login_events", loginEvents);
        report.put("actual_local_connection", localConnection);
        report.put("config_start", Evidence.CONFIG_START.get());
        report.put("config_complete", Evidence.CONFIG_COMPLETE.get());
        report.put("dimension_sync", Evidence.DIMENSION_SYNC.get());
        report.put("portal_sync", Evidence.PORTAL_SYNC.get());
        report.put("server_teleport_requests", Evidence.SERVER_TELEPORT_REQUESTS.get());
        report.put("prediction_dimensions", List.copyOf(Evidence.PREDICTIONS));
        if (failure != null) {
            report.put("failure", failure.toString());
            LOGGER.error("IP_NATIVE_CLIENT_FAIL", failure);
        }
        try {
            if (realm == null) { BaselineMarkerDiagnostics.terminal(failure); }
            if (output == null) { output = Path.of(ArtifactProof.property("clientReportDir")); }
            Files.writeString(output.resolve("runtime-results.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report));
        }
        catch (Throwable writeFailure) { LOGGER.error("IP_NATIVE_CLIENT_REPORT_FAIL", writeFailure); }
        LOGGER.info("IP_NATIVE_CLIENT_DONE status={}", report.get("status"));
        client.stop();
    }
    private static boolean realmRequested() { return Boolean.parseBoolean(ArtifactProof.property("realm")); }
    record ServerState(String dimension, double x, double y, double z, boolean awaiting, boolean registered) {}
}
