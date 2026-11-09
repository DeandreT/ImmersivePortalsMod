package qouteall.imm_ptl.client_test;

import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import qouteall.imm_ptl.core.compat.sodium_compatibility.SodiumInterface;
import qouteall.imm_ptl.core.render.PortalClipping;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Diagnostic only: never delays the baseline photo or changes terrain, options or visibility. */
public final class BaselineMarkerDiagnostics {
    private static final PhotoFrameGuard FRAME = new PhotoFrameGuard();
    private static volatile List<Map<String, Object>> commands = List.of();
    private static volatile Map<String, Object> serverMarkers = Map.of();
    private static Path output;
    private static boolean observing;
    private static long sequence;
    private static long remoteFrame = -1;
    private static Map<String, Object> main = Map.of(), remote = Map.of();
    private static final Map<String, Object> captured = new LinkedHashMap<>();

    static void start(Path directory) {
        output = directory;
        observing = true;
        commands = List.of();
        serverMarkers = Map.of();
        main = Map.of();
        remote = Map.of();
        remoteFrame = -1;
        captured.clear();
        FRAME.resetObservation();
    }

    static void beginFrame() { if (observing) { FRAME.beginFrame(); } }

    static void command(String command, BaselineCommandOutcome outcome) {
        var next = new ArrayList<>(commands);
        next.add(Map.of("command", command, "success", outcome.succeeded(),
            "callbacks", outcome.callbacks(), "result", outcome.result()));
        commands = List.copyOf(next);
    }

    static void serverMarkers(Map<String, Object> markers) { serverMarkers = Map.copyOf(markers); }

    /** Runs at the existing ordinary solid-draw hook, for the actual current view. */
    public static void observeDraw(LevelRenderer renderer) {
        if (!observing) { return; }
        var client = Minecraft.getInstance();
        if (client.level == null) { return; }
        boolean portal = PortalRendering.isRendering();
        if ((!portal && client.level.dimension() != Level.OVERWORLD)
            || (portal && client.level.dimension() != Level.NETHER)) { return; }
        var camera = client.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
        var plane = PortalClipping.getCurrentPlane();
        var draw = new LinkedHashMap<String, Object>();
        long drawSequence = ++sequence;
        draw.put("draw_sequence", drawSequence);
        draw.put("render_frame_identity", FRAME.frame());
        draw.put("dimension", client.level.dimension().identifier().toString());
        draw.put("portal_layer", PortalRendering.getPortalLayer());
        draw.put("camera", List.of(camera.pos.x, camera.pos.y, camera.pos.z));
        draw.put("yaw", camera.yRot);
        draw.put("pitch", camera.xRot);
        draw.put("projection", camera.projectionMatrix.get(new float[16]));
        draw.put("view_rotation", camera.viewRotationMatrix.get(new float[16]));
        draw.put("clip_plane", List.of(plane.x(), plane.y(), plane.z(), plane.w()));
        var fog = camera.fogData;
        draw.put("fog", Map.of("color", List.of(fog.color.x, fog.color.y, fog.color.z, fog.color.w),
            "environmental_start", fog.environmentalStart, "environmental_end", fog.environmentalEnd,
            "render_distance_start", fog.renderDistanceStart, "render_distance_end", fog.renderDistanceEnd));
        draw.put("compile_queue_empty", renderer.hasRenderedAllSections());
        int[] sodium = SodiumInterface.invoker.getViewSectionStats();
        draw.put("vanilla_mesh_authoritative", sodium == null);
        draw.put("sodium_geometry", sodium == null ? -1 : sodium[0]);
        draw.put("sodium_pending", sodium == null ? -1 : sodium[1]);
        var markers = new ArrayList<Map<String, Object>>();
        if (!portal) {
            // Every section containing the unchanged floor, including the +32 edge.
            for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
                markers.add(marker(renderer, new BlockPos(Math.min(x * 16 + 8, 32), -40,
                    Math.min(z * 16 + 8, 32)), "lime", ChunkSectionLayer.SOLID));
            }
            markers.add(marker(renderer, new BlockPos(0, -21, 0), "glass", ChunkSectionLayer.TRANSLUCENT));
        } else {
            markers.add(marker(renderer, new BlockPos(0, 99, 0), "red_floor", ChunkSectionLayer.SOLID));
            markers.add(marker(renderer, new BlockPos(-10, 108, 0), "red_wall", ChunkSectionLayer.SOLID));
        }
        draw.put("markers", markers);
        if (portal) {
            remote = draw;
            remoteFrame = FRAME.frame();
        } else {
            main = draw;
            FRAME.observe(drawSequence);
        }
    }

    private static Map<String, Object> marker(LevelRenderer renderer, BlockPos pos, String name,
                                               ChunkSectionLayer expectedLayer) {
        var level = Minecraft.getInstance().level;
        var entry = new LinkedHashMap<String, Object>();
        entry.put("marker", name);
        entry.put("position", List.of(pos.getX(), pos.getY(), pos.getZ()));
        entry.put("section", List.of(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4));
        entry.put("expected_layer", expectedLayer.label());
        var chunk = level.getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL, false);
        entry.put("chunk_present", chunk != null);
        if (chunk != null) {
            entry.put("state", chunk.getBlockState(pos).toString());
            entry.put("light_correct", chunk.isLightCorrect());
            entry.put("light_column_enabled", level.getLightEngine().lightOnInColumn(
                SectionPos.getZeroNode(pos.getX() >> 4, pos.getZ() >> 4)));
            entry.put("sky_light_above", level.getBrightness(LightLayer.SKY, pos.above()));
            entry.put("block_light_above", level.getBrightness(LightLayer.BLOCK, pos.above()));
            entry.put("section_empty", chunk.getSection(chunk.getSectionIndex(pos.getY())).hasOnlyAir());
        }
        var area = renderer.viewArea();
        var section = area == null ? null : area.getRenderSectionAt(pos);
        entry.put("render_section_present", section != null);
        if (section != null) {
            entry.put("section_node_matches", section.getSectionNode() == SectionPos.asLong(pos));
            entry.put("visible", renderer.visibleSections().contains(section));
            var mesh = section.getSectionMesh();
            entry.put("uncompiled", mesh == CompiledSectionMesh.UNCOMPILED);
            for (var layer : ChunkSectionLayer.values()) {
                var draw = mesh.getSectionDraw(layer);
                entry.put(layer.label() + "_indices", draw == null ? 0 : draw.indexCount());
            }
        }
        return entry;
    }

    static void freezeAndWrite() throws Exception {
        captured.put("capture_frame_identity", FRAME.frame());
        captured.put("fresh_main_draw", main.get("draw_sequence") instanceof Number draw && FRAME.matches(draw.longValue()));
        captured.put("fresh_remote_draw", remoteFrame == FRAME.frame());
        captured.put("main_draw", main);
        captured.put("remote_draw", remote);
        observing = false;
        write();
    }

    static void terminal(Throwable failure) throws Exception {
        if (output == null) { return; }
        if (failure != null) { captured.put("terminal_failure", failure.toString()); }
        captured.putIfAbsent("main_draw", main);
        captured.putIfAbsent("remote_draw", remote);
        write();
        observing = false;
    }

    private static void write() throws Exception {
        captured.put("commands", commands);
        captured.put("server_markers", serverMarkers);
        captured.put("purpose", "Read-only first-photo diagnostic; original fixed-tick capture and strict crops unchanged");
        Files.writeString(output.resolve("baseline-marker-diagnostics.json"),
            new GsonBuilder().setPrettyPrinting().create().toJson(captured));
    }

    private BaselineMarkerDiagnostics() {}
}
