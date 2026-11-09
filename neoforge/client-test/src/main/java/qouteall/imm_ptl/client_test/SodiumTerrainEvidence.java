package qouteall.imm_ptl.client_test;

import net.minecraft.core.BlockPos;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Read-only, fail-closed reflection verified against both pinned native Sodium beta.1/beta.4 jars. */
final class SodiumTerrainEvidence {
    private static final String PREFIX = "net.caffeinemc.mods.sodium.client.render.";
    private static Bindings bindings;

    static Map<String, Object> sample(Level level, BlockPos floor, Frustum drawFrustum) {
        try {
            if (bindings == null) bindings = new Bindings();
            return bindings.sample(level, floor, drawFrustum);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return Map.of("verified", false, "error", failure.toString(), "ground_mesh_ready", false,
                "foreground_meshes_ready", false);
        }
    }

    private static final class Bindings {
        private final Class<?> renderer = Class.forName(PREFIX + "SodiumWorldRenderer");
        private final Class<?> manager = Class.forName(PREFIX + "chunk.RenderSectionManager");
        private final Class<?> section = Class.forName(PREFIX + "chunk.RenderSection");
        private final Class<?> region = Class.forName(PREFIX + "chunk.region.RenderRegion");
        private final Class<?> storage = Class.forName(PREFIX + "chunk.data.SectionRenderDataStorage");
        private final Class<?> pass = Class.forName(PREFIX + "chunk.terrain.TerrainRenderPass");
        private final Class<?> byteIterator = Class.forName("net.caffeinemc.mods.sodium.client.util.iterator.ByteIterator");
        private final Class<?> sectionTree = Class.forName(PREFIX + "chunk.occlusion.SectionTree");
        private final Class<?> forest = Class.forName(PREFIX + "chunk.tree.Forest");
        private final Field managerField = renderer.getDeclaredField("renderSectionManager");
        private final Field managerLevel = manager.getDeclaredField("level");
        private final Field renderTree = manager.getDeclaredField("renderTree");
        private final Field treeForest = sectionTree.getField("tree");
        private final Method sectionPresent = forest.getMethod("isSectionPresent", int.class, int.class, int.class);
        private final Field allocations = storage.getDeclaredField("vertexAllocations");
        private final Method getSection = manager.getDeclaredMethod("getRenderSection", int.class, int.class, int.class);
        private final Object solid = Class.forName(PREFIX + "chunk.terrain.DefaultTerrainRenderPasses").getField("SOLID").get(null);

        private Bindings() throws ReflectiveOperationException {
            managerField.setAccessible(true);
            managerLevel.setAccessible(true);
            renderTree.setAccessible(true);
            allocations.setAccessible(true);
            getSection.setAccessible(true);
        }

        private Map<String, Object> sample(Level level, BlockPos floor, Frustum drawFrustum) throws ReflectiveOperationException {
            Object worldRenderer = renderer.getMethod("instanceNullable").invoke(null);
            if (worldRenderer == null) return Map.of("verified", false, "ground_mesh_ready", false, "foreground_meshes_ready", false);
            Object sectionManager = managerField.get(worldRenderer);
            if (sectionManager == null) return Map.of("verified", false, "ground_mesh_ready", false, "foreground_meshes_ready", false);
            if (managerLevel.get(sectionManager) != level) {
                return Map.of("verified", false, "error", "Sodium section manager belongs to a different ClientLevel",
                    "ground_mesh_ready", false, "foreground_meshes_ready", false);
            }
            Object currentTree = renderTree.get(sectionManager);
            if (currentTree == null || drawFrustum == null) {
                return Map.of("verified", false, "error", "No current Sodium section tree or draw frustum",
                    "ground_mesh_ready", false, "foreground_meshes_ready", false);
            }
            Object currentForest = treeForest.get(currentTree);
            Object lists = manager.getMethod("getRenderLists").invoke(sectionManager);
            var visible = new IdentityHashMap<Object, Set<Integer>>();
            Iterator<?> iterator = (Iterator<?>) lists.getClass().getMethod("iterator", boolean.class).invoke(lists, false);
            while (iterator.hasNext()) {
                Object list = iterator.next();
                Object listRegion = list.getClass().getMethod("getRegion").invoke(list);
                Object indices = list.getClass().getMethod("sectionsWithGeometryIterator", boolean.class).invoke(list, false);
                var entries = new HashSet<Integer>();
                if (indices != null) {
                    while ((boolean) byteIterator.getMethod("hasNext").invoke(indices)) {
                        entries.add((int) byteIterator.getMethod("nextByteAsInt").invoke(indices));
                    }
                }
                visible.put(listRegion, entries);
            }
            int cx = floor.getX() >> 4, cy = floor.getY() >> 4, cz = floor.getZ() >> 4;
            boolean groundReady = false, foregroundReady = true;
            var sections = new ArrayList<Map<String, Object>>();
            for (int x = cx - 1; x <= cx + 1; x++) for (int y = cy - 1; y <= cy + 1; y++) for (int z = cz - 1; z <= cz + 1; z++) {
                var entry = new LinkedHashMap<String, Object>();
                entry.put("x", x); entry.put("y", y); entry.put("z", z);
                var chunk = level.getChunk(x, z, ChunkStatus.FULL, false);
                boolean empty = chunk != null && (y < chunk.getMinSectionY() || y > chunk.getMaxSectionY()
                    || chunk.getSection(chunk.getSectionIndexFromSectionY(y)).hasOnlyAir());
                boolean boxVisible = (boolean) renderer.getMethod("isBoxVisible", double.class, double.class,
                    double.class, double.class, double.class, double.class).invoke(worldRenderer,
                    x * 16.0, y * 16.0, z * 16.0, x * 16.0 + 16, y * 16.0 + 16, z * 16.0 + 16);
                // isBoxVisible is only a broad entity query: it expands by half a block and
                // accepts neighboring/empty sections. Terrain readiness needs this exact cell.
                boolean treePresent = (boolean) sectionPresent.invoke(currentForest, x, y, z);
                boolean inFrustum = drawFrustum.isVisible(new AABB(x * 16.0, y * 16.0, z * 16.0,
                    x * 16.0 + 16, y * 16.0 + 16, z * 16.0 + 16));
                Object renderSection = getSection.invoke(sectionManager, x, y, z);
                boolean built = renderSection != null && (boolean) section.getMethod("isBuilt").invoke(renderSection);
                boolean drawn = false;
                long solidLength = 0;
                if (renderSection != null) {
                    Object renderRegion = section.getMethod("getRegion").invoke(renderSection);
                    int index = (int) section.getMethod("getSectionIndex").invoke(renderSection);
                    drawn = visible.getOrDefault(renderRegion, Set.of()).contains(index);
                    Object data = region.getMethod("getStorage", pass).invoke(renderRegion, solid);
                    Object allocation = data == null ? null : Array.get(allocations.get(data), index);
                    if (allocation != null) solidLength = ((Number) allocation.getClass().getMethod("getLength").invoke(allocation)).longValue();
                }
                entry.put("empty_air", empty); entry.put("box_visible", boxVisible); entry.put("built", built);
                entry.put("tree_present", treePresent); entry.put("in_frustum", inFrustum);
                entry.put("drawn_geometry", drawn); entry.put("solid_allocation_length", solidLength);
                sections.add(entry);
                if (!empty && treePresent && inFrustum && !built) foregroundReady = false;
                if (x == cx && y == cy && z == cz) groundReady = built && drawn && solidLength > 0;
            }
            return Map.of("verified", true, "api", "pinned Sodium RenderSection.isBuilt + current render-list region/index membership + SOLID vertex allocation; exact SectionTree.tree.isSectionPresent AND actual draw frustum for foreground; broad isBoxVisible diagnostic only",
                "ground_mesh_ready", groundReady, "foreground_meshes_ready", foregroundReady, "sections", sections);
        }
    }

    private SodiumTerrainEvidence() {}
}
