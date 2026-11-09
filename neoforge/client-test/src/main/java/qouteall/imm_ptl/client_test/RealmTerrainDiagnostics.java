package qouteall.imm_ptl.client_test;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.render.PortalClipping;
import qouteall.imm_ptl.core.compat.sodium_compatibility.SodiumInterface;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only evidence: block presence, heightmaps, light, and actual main-view terrain draw state. */
public final class RealmTerrainDiagnostics {
    static volatile boolean enabled;
    private static volatile Map<String, Object> lastMainDraw = Map.of();
    private static Vec3 photoCamera;
    private static String photoDimension;
    private static int readyFrames;
    private static long drawSequence;
    private static final PhotoFrameGuard FRAME_GUARD = new PhotoFrameGuard();
    static final int REQUIRED_READY_FRAMES = 5;

    static void start() {
        lastMainDraw = Map.of();
        photoCamera = null;
        photoDimension = null;
        readyFrames = 0;
        FRAME_GUARD.resetObservation();
        enabled = true;
    }

    static void startPhoto(ResourceKey<Level> dimension, Vec3 feet) {
        start();
        photoCamera = feet;
        photoDimension = dimension.identifier().toString();
    }

    static boolean photoReady() {
        return lastMainDraw.get("ready_consecutive_frames") instanceof Number count
            && count.intValue() >= REQUIRED_READY_FRAMES;
    }

    static void beginFrame() { FRAME_GUARD.beginFrame(); }

    static boolean photoReadyThisFrame() {
        return photoReady() && lastMainDraw.get("draw_sequence") instanceof Number sequence
            && FRAME_GUARD.matches(sequence.longValue());
    }

    static void freezePhoto() {
        ArtifactProof.require(photoReadyThisFrame(), "Photograph has no fresh ready main terrain draw in this render frame");
        var captured = new LinkedHashMap<>(lastMainDraw);
        captured.put("capture_frame_identity", FRAME_GUARD.frame());
        lastMainDraw = captured;
        enabled = false;
    }

    /** Called on the render thread immediately before the ordinary opaque terrain draw. */
    public static void observeMainDraw(LevelRenderer renderer) {
        if (!enabled || PortalRendering.isRendering()) return;
        var client = Minecraft.getInstance();
        var camera = client.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
        var plane = PortalClipping.getCurrentPlane();
        var result = new LinkedHashMap<String, Object>();
        result.put("draw_sequence", ++drawSequence);
        result.put("render_frame_identity", FRAME_GUARD.frame());
        result.put("dimension", client.level == null ? "none" : client.level.dimension().identifier().toString());
        result.put("camera", position(camera.pos));
        result.put("yaw", camera.yRot);
        result.put("pitch", camera.xRot);
        result.put("smart_cull", camera.smartCull);
        result.put("view_determinant", camera.viewRotationMatrix.determinant());
        result.put("view_rotation", camera.viewRotationMatrix.get(new float[16]));
        result.put("projection", camera.projectionMatrix.get(new float[16]));
        result.put("portal_layer", PortalRendering.getPortalLayer());
        result.put("clip_plane", List.of(plane.x(), plane.y(), plane.z(), plane.w()));
        result.put("compile_queue_empty", renderer.hasRenderedAllSections());
        int[] sodium = SodiumInterface.invoker.getViewSectionStats();
        result.put("sodium_geometry", sodium == null ? -1 : sodium[0]);
        result.put("sodium_pending", sodium == null ? -1 : sodium[1]);
        if (sodium != null && photoCamera != null && client.level != null) {
            result.put("sodium_section_evidence", SodiumTerrainEvidence.sample(client.level, BlockPos.containing(photoCamera).below(), camera.cullFrustum));
        }
        var sections = new ArrayList<Map<String, Object>>();
        for (var section : renderer.visibleSections()) {
            var entry = new LinkedHashMap<String, Object>();
            long node = section.getSectionNode();
            entry.put("x", SectionPos.x(node));
            entry.put("y", SectionPos.y(node));
            entry.put("z", SectionPos.z(node));
            var chunk = client.level == null ? null : client.level.getChunk(SectionPos.x(node), SectionPos.z(node), ChunkStatus.FULL, false);
            int sy = SectionPos.y(node);
            entry.put("empty_air", chunk != null && (sy < chunk.getMinSectionY() || sy > chunk.getMaxSectionY()
                || chunk.getSection(chunk.getSectionIndexFromSectionY(sy)).hasOnlyAir()));
            var mesh = section.getSectionMesh();
            entry.put("uncompiled", mesh == CompiledSectionMesh.UNCOMPILED);
            for (var layer : ChunkSectionLayer.values()) {
                var draw = mesh.getSectionDraw(layer);
                entry.put(layer.label() + "_indices", draw == null ? 0 : draw.indexCount());
            }
            sections.add(entry);
        }
        result.put("visible_sections", sections);
        var area = renderer.viewArea();
        if (area != null) {
            result.put("grid_center", area.getCameraSectionPos().toString());
            result.put("grid_radius", area.getViewDistance());
        }
        if (photoCamera != null) {
            var readiness = photoReadiness(client.level, result, photoCamera);
            if (!Boolean.TRUE.equals(readiness.get("ready"))) readyFrames = 0;
            else if (!FRAME_GUARD.hasCurrentObservation()) readyFrames++;
            result.put("photo_readiness", readiness);
            result.put("ready_consecutive_frames", readyFrames);
        }
        FRAME_GUARD.observe(drawSequence);
        lastMainDraw = result;
    }

    static Map<String, Object> mainDraw() { return lastMainDraw; }

    static Map<String, Object> readiness(Map<String, Object> draw, Vec3 feet) {
        boolean cameraMatches = false, orientationMatches = false, unclipped = false, groundMeshReady = false;
        if (draw.get("camera") instanceof Map<?, ?> camera) {
            cameraMatches = camera.get("x") instanceof Number x && camera.get("y") instanceof Number y
                && camera.get("z") instanceof Number z && Math.abs(x.doubleValue() - feet.x) < 0.01
                && Math.abs(y.doubleValue() - feet.y - 1.62) < 0.01 && Math.abs(z.doubleValue() - feet.z) < 0.01;
        }
        if (draw.get("yaw") instanceof Number yaw && draw.get("pitch") instanceof Number pitch) {
            orientationMatches = Math.abs(yaw.doubleValue() - 135) < 0.01 && Math.abs(pitch.doubleValue() - 12) < 0.01;
        }
        if (draw.get("clip_plane") instanceof List<?> plane && plane.size() == 4) {
            unclipped = Integer.valueOf(0).equals(draw.get("portal_layer"))
                && plane.stream().allMatch(value -> value instanceof Number number && number.doubleValue() == 0);
        }
        if (draw.get("sodium_geometry") instanceof Number count && count.intValue() >= 0) {
            if (draw.get("sodium_section_evidence") instanceof Map<?, ?> evidence) {
                groundMeshReady = Boolean.TRUE.equals(evidence.get("verified")) && Boolean.TRUE.equals(evidence.get("ground_mesh_ready"));
            }
        } else if (draw.get("visible_sections") instanceof List<?> sections) {
            BlockPos floor = BlockPos.containing(feet).below();
            for (Object value : sections) {
                if (!(value instanceof Map<?, ?> section)) continue;
                if (Integer.valueOf(floor.getX() >> 4).equals(section.get("x"))
                    && Integer.valueOf(floor.getY() >> 4).equals(section.get("y"))
                    && Integer.valueOf(floor.getZ() >> 4).equals(section.get("z"))) {
                    groundMeshReady = Boolean.FALSE.equals(section.get("uncompiled"))
                        && section.get("solid_indices") instanceof Number indices && indices.intValue() > 0;
                }
            }
        }
        return Map.of("camera_matches", cameraMatches, "orientation_matches", orientationMatches,
            "unclipped_main_view", unclipped, "ground_mesh_ready", groundMeshReady,
            "ready", cameraMatches && orientationMatches && unclipped && groundMeshReady);
    }

    private static Map<String, Object> photoReadiness(Level level, Map<String, Object> draw, Vec3 feet) {
        var result = new LinkedHashMap<>(readiness(draw, feet));
        BlockPos floor = BlockPos.containing(feet).below();
        boolean neighborsReady = level != null;
        if (level != null) {
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                int x = (floor.getX() >> 4) + dx, z = (floor.getZ() >> 4) + dz;
                neighborsReady &= level.getChunk(x, z, ChunkStatus.FULL, false) != null
                    && level.getLightEngine().lightOnInColumn(SectionPos.getZeroNode(x, z));
            }
        }
        boolean supported = level != null && level.getBlockState(floor.above()).isAir()
            && level.getBlockState(floor.above(2)).isAir()
            && level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)
            && level.getFluidState(floor).isEmpty();
        int nearbyVisible = 0, nearbyUncompiled = 0;
        if (draw.get("visible_sections") instanceof List<?> sections) {
            for (Object value : sections) {
                if (!(value instanceof Map<?, ?> section)) continue;
                if (section.get("x") instanceof Number x && section.get("y") instanceof Number y
                    && section.get("z") instanceof Number z
                    && Math.abs(x.intValue() - (floor.getX() >> 4)) <= 1
                    && Math.abs(y.intValue() - (floor.getY() >> 4)) <= 1
                    && Math.abs(z.intValue() - (floor.getZ() >> 4)) <= 1) {
                    nearbyVisible++;
                    if (!Boolean.TRUE.equals(section.get("empty_air")) && !Boolean.FALSE.equals(section.get("uncompiled"))) nearbyUncompiled++;
                }
            }
        }
        boolean sodium = draw.get("sodium_geometry") instanceof Number geometry && geometry.intValue() >= 0;
        boolean queueEmpty = sodium ? draw.get("sodium_pending") instanceof Number pending && pending.intValue() == 0
            : Boolean.TRUE.equals(draw.get("compile_queue_empty"));
        boolean foregroundReady = sodium ? draw.get("sodium_section_evidence") instanceof Map<?, ?> evidence
            && Boolean.TRUE.equals(evidence.get("verified")) && Boolean.TRUE.equals(evidence.get("foreground_meshes_ready"))
            : nearbyVisible > 0 && nearbyUncompiled == 0;
        boolean dimensionMatches = photoDimension != null && photoDimension.equals(draw.get("dimension"));
        result.put("dimension_matches", dimensionMatches);
        result.put("neighbors_loaded_and_lit", neighborsReady);
        result.put("ground_supported", supported);
        result.put("nearby_visible_sections", nearbyVisible);
        result.put("nearby_uncompiled_sections", nearbyUncompiled);
        result.put("foreground_meshes_ready", foregroundReady);
        result.put("compile_queue_empty", queueEmpty);
        result.put("ready", Boolean.TRUE.equals(result.get("ready")) && dimensionMatches && neighborsReady && supported && foregroundReady && queueEmpty);
        return result;
    }

    static Map<String, Object> position(Vec3 position) {
        return Map.of("x", position.x, "y", position.y, "z", position.z);
    }

    static Map<String, Object> sample(Level level, Vec3 position) {
        var result = new LinkedHashMap<String, Object>();
        result.put("dimension", level.dimension().identifier().toString());
        result.put("position", position(position));
        BlockPos feet = BlockPos.containing(position);
        result.put("feet", level.getBlockState(feet).toString());
        result.put("head", level.getBlockState(feet.above()).toString());
        result.put("floor", level.getBlockState(feet.below()).toString());
        result.put("floor_sturdy", level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), Direction.UP));
        result.put("sky_at_feet", level.canSeeSky(feet));
        result.put("sky_light", level.getBrightness(LightLayer.SKY, feet));
        result.put("block_light", level.getBrightness(LightLayer.BLOCK, feet));
        var columns = new ArrayList<Map<String, Object>>();
        for (int dx = -8; dx <= 8; dx += 4) for (int dz = -8; dz <= 8; dz += 4) {
            int x = feet.getX() + dx, z = feet.getZ() + dz;
            var column = new LinkedHashMap<String, Object>();
            column.put("x", x);
            column.put("z", z);
            var chunk = level.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false);
            column.put("chunk_present", chunk != null);
            if (chunk != null) {
                column.put("light_correct", chunk.isLightCorrect());
                column.put("light_column_enabled", level.getLightEngine().lightOnInColumn(SectionPos.getZeroNode(x >> 4, z >> 4)));
                int scanned = level.getMinY();
                for (int y = level.getMaxY(); y >= level.getMinY(); y--) {
                    if (Heightmap.Types.MOTION_BLOCKING_NO_LEAVES.isOpaque().test(level.getBlockState(new BlockPos(x, y, z)))) {
                        scanned = y + 1;
                        break;
                    }
                }
                column.put("scanned_motion_height", scanned);
                column.put("motion_height", level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z));
                column.put("surface_height", level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z));
                var vertical = new ArrayList<Map<String, Object>>();
                for (int y = feet.getY() - 3; y <= feet.getY() + 3; y++) {
                    vertical.add(Map.of("y", y, "state", level.getBlockState(new BlockPos(x, y, z)).toString()));
                }
                column.put("near_camera_blocks", vertical);
            }
            columns.add(column);
        }
        result.put("columns", columns);
        var sections = new ArrayList<Map<String, Object>>();
        for (int cx = (feet.getX() >> 4) - 1; cx <= (feet.getX() >> 4) + 1; cx++) {
            for (int cz = (feet.getZ() >> 4) - 1; cz <= (feet.getZ() >> 4) + 1; cz++) {
                var chunk = level.getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null) continue;
                for (int sy = chunk.getMinSectionY(); sy <= chunk.getMaxSectionY(); sy++) {
                    var section = chunk.getSection(chunk.getSectionIndexFromSectionY(sy));
                    int actual = 0;
                    for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) {
                        if (!section.getBlockState(x, y, z).isEmpty()) actual++;
                    }
                    if (actual == 0 && section.hasOnlyAir()) continue;
                    sections.add(Map.of("x", cx, "y", sy, "z", cz,
                        "actual_nonempty", actual, "reported_empty", section.hasOnlyAir()));
                }
            }
        }
        result.put("sections", sections);
        return result;
    }

    private RealmTerrainDiagnostics() {}
}
