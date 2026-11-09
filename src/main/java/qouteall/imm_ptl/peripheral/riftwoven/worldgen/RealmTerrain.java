package qouteall.imm_ptl.peripheral.riftwoven.worldgen;

import qouteall.imm_ptl.peripheral.riftwoven.profile.RealmProfile;

import java.util.ArrayList;
import java.util.List;

/** Version-one, coordinate-only terrain. No ambient world seed or mutable random stream is used. */
public final class RealmTerrain {
    public static final int VERSION = 1;
    public static final int MIN_Y = -64;
    public static final int HEIGHT = 256;
    public static final int GENERATION_MARGIN = 32;
    public static final int ARRIVAL_Y = 80;
    /** Parent-owned return core and standing room must fit inside this square. */
    public static final int TERRACE_RADIUS = 10;

    public enum Material {
        AIR, WATER, LAVA, BEDROCK, STONE, DEEPSLATE, TUFF, CALCITE, GRANITE,
        DIRT, GRASS, SAND, SANDSTONE, SNOW, MUD, MOSS, GRAVEL,
        COAL, IRON, COPPER, GOLD, REDSTONE, LAPIS, DIAMOND, AMETHYST,
        BRICKS, MOSSY_BRICKS, CRACKED_BRICKS
    }

    public record Region(String family, double distanceToBoundary) {}

    /** Open ravine cross-section, with an exposed rock lip and locally contained deep fluids. */
    public record Ravine(int depth, int lavaLevel, boolean flooded, boolean warningRim) {
        private static final Ravine NONE = new Ravine(0, MIN_Y, false, false);
    }

    /** Cached horizontal samples shared by block filling and all height/column queries. */
    public record Column(int x, int z, int surface, String family, boolean inside, boolean terrace, Ravine ravine) {}

    private static final List<String> FAMILIES = List.of("lush", "wet", "temperate", "cold", "arid");
    private final RealmProfile profile;
    private final long seed;
    private final List<String> families;
    private final String[] sectors;
    private final int orientation;

    public RealmTerrain(RealmProfile profile, long seed) {
        if (profile.version() != RealmProfile.VERSION || profile.version() != VERSION) {
            throw new IllegalArgumentException("Unsupported realm terrain/profile version: " + profile.version());
        }
        this.profile = profile;
        this.seed = seed;
        this.orientation = (int) (mix(seed) & 7);
        List<String> active = new ArrayList<>();
        for (String family : FAMILIES) {
            if (profile.biomeWeights().getOrDefault(family, 0) > 0) {
                active.add(family);
            }
        }
        if (active.isEmpty()) {
            throw new IllegalArgumentException("Realm needs at least one biome family");
        }
        // A cyclic offset and reflected/swapped axes change geography without discarding commitments.
        int offset = (int) Math.floorMod(mix(seed ^ 129971L), active.size());
        List<String> rotated = new ArrayList<>();
        for (int i = 0; i < active.size(); i++) {
            rotated.add(active.get((i + offset) % active.size()));
        }
        families = List.copyOf(rotated);
        sectors = new String[RealmProfile.PALETTE_SECTORS];
        int step = 0;
        for (String family : families) {
            for (int i = 0; i < profile.biomeWeights().get(family); i++) {
                // A boustrophedon path keeps each family's allocated sectors connected.
                int row = step / 4;
                int col = row % 2 == 0 ? step % 4 : 3 - step % 4;
                sectors[row * 4 + col] = family;
                step++;
            }
        }
    }

    public RealmProfile profile() { return profile; }
    public long seed() { return seed; }
    public List<String> families() { return families; }
    public int spawnHeight() { return ARRIVAL_Y; }

    /** Playable footprint is [-radius, radius) on both horizontal axes. */
    public boolean inside(int x, int z) {
        return x >= -profile.radius() && x < profile.radius() && z >= -profile.radius() && z < profile.radius();
    }

    public boolean terrace(int x, int z) {
        return Math.abs((long) x) <= TERRACE_RADIUS && Math.abs((long) z) <= TERRACE_RADIUS;
    }

    public boolean generatesChunk(int chunkX, int chunkZ) {
        long x = (long) chunkX * 16;
        long z = (long) chunkZ * 16;
        long extent = profile.radius() + GENERATION_MARGIN;
        return x + 15 >= -extent && x < extent && z + 15 >= -extent && z < extent;
    }

    public boolean decoratesChunk(int chunkX, int chunkZ) {
        long x = (long) chunkX * 16;
        long z = (long) chunkZ * 16;
        return x + 15 >= -profile.radius() && x < profile.radius()
            && z + 15 >= -profile.radius() && z < profile.radius();
    }

    public Region region(int x, int z) {
        double u = allocationX(x, z), v = allocationZ(x, z);
        double size = profile.radius() / 2.0;
        int col = Math.clamp((int) Math.floor((u + profile.radius()) / size), 0, 3);
        int row = Math.clamp((int) Math.floor((v + profile.radius()) / size), 0, 3);
        String family = sectors[row * 4 + col];
        double left = u + profile.radius() - col * size;
        double top = v + profile.radius() - row * size;
        double distance = size;
        if (col > 0 && !family.equals(sectors[row * 4 + col - 1])) { distance = Math.min(distance, left); }
        if (col < 3 && !family.equals(sectors[row * 4 + col + 1])) { distance = Math.min(distance, size - left); }
        if (row > 0 && !family.equals(sectors[(row - 1) * 4 + col])) { distance = Math.min(distance, top); }
        if (row < 3 && !family.equals(sectors[(row + 1) * 4 + col])) { distance = Math.min(distance, size - top); }
        return new Region(family, Math.max(0, distance));
    }

    private double allocationX(int x, int z) {
        double u = (orientation & 1) == 0 ? x : z;
        if ((orientation & 2) != 0) { u = -u; }
        return u + noise2(310, x / 95.0, z / 95.0) * 4;
    }

    private double allocationZ(int x, int z) {
        double v = (orientation & 1) == 0 ? z : x;
        if ((orientation & 4) != 0) { v = -v; }
        return v + noise2(311, x / 95.0, z / 95.0) * 4;
    }

    public Column column(int x, int z) {
        if (!inside(x, z)) { return new Column(x, z, MIN_Y - 1, "temperate", false, false, Ravine.NONE); }
        String family = region(x, z).family();
        if (terrace(x, z)) { return new Column(x, z, spawnHeight() - 1, family, true, true, Ravine.NONE); }
        double broad = noise2(11, x / 145.0, z / 145.0);
        double hills = noise2(27, x / 46.0, z / 46.0);
        double ridges = 1 - Math.abs(noise2(89, x / 70.0, z / 70.0));
        double fine = noise2(41, x / 19.0, z / 19.0);
        double amplitude = profile.terrainAmplitude();
        double elevation = profile.waterLevel() + 13 + amplitude * (broad * .62 + hills * .23 + fine * .07);
        switch (profile.topology()) {
            case ISLANDS -> elevation += amplitude * (broad * .65 - .28);
            case PLATEAUS -> elevation = Math.floor(elevation / 14) * 14 + fine * 2 + ridges * amplitude * .16;
            case CAVERNS -> elevation += ridges * amplitude * .3 + 12;
            case CONTINENT -> elevation += ridges * amplitude * .15;
        }
        // Every allocated sector has a substantial dry center, including archipelago sectors.
        // Smooth shoulders allow the surrounding terrain to separate and flood naturally.
        double size = profile.radius() / 2.0;
        double u = allocationX(x, z) + profile.radius(), v = allocationZ(x, z) + profile.radius();
        double localX = u - Math.floor(u / size) * size - size / 2;
        double localZ = v - Math.floor(v / size) * size - size / 2;
        double coreDistance = Math.max(Math.abs(localX), Math.abs(localZ));
        double habitat = 1 - smooth((coreDistance - size * .23) / (size * .16));
        double land = Math.max(elevation, profile.waterLevel() + (family.equals("wet") ? 1 : 3));
        elevation = elevation * (1 - habitat) + land * habitat;
        // A broad access apron blends the permanent arrival terrace into any topology.
        double distance = Math.max(Math.abs(x), Math.abs(z));
        if (distance < 30) {
            double blend = smooth((distance - TERRACE_RADIUS) / (30 - TERRACE_RADIUS));
            elevation = (spawnHeight() - 1) * (1 - blend) + elevation * blend;
        }
        int surface = (int) Math.round(Math.clamp(elevation, MIN_Y + 35, MIN_Y + HEIGHT - 24));
        return new Column(x, z, surface, family, true, false, ravine(x, z, surface, family, coreDistance, size));
    }

    private Ravine ravine(int x, int z, int surface, String family, double coreDistance, double sectorSize) {
        int danger = profile.danger();
        // Keep a broad arrival apron, a substantial center in every promised habitat, and the
        // boundary approach quiet. Danger is country to discover and prepare for, not an arrival trap.
        if (danger == 0 || Math.max(Math.abs(x), Math.abs(z)) <= 42
            || coreDistance < sectorSize * .23 || Math.max(Math.abs(x), Math.abs(z)) >= profile.radius() - 8) {
            return Ravine.NONE;
        }
        int cellX = Math.floorDiv(x, 64), cellZ = Math.floorDiv(z, 64);
        long district = mix(seed ^ cellX * 729871L ^ cellZ * 1299709L ^ 0x524156494e45L);
        // Nested activation and widening make increasing danger produce more exposed country,
        // while even danger four leaves most columns undisturbed and whole districts quiet.
        if (Math.floorMod(district, 8) >= danger + 1) { return Ravine.NONE; }
        double along = x - cellX * 64;
        double across = z - cellZ * 64;
        if ((district & 8) != 0) { double swap = along; along = across; across = swap; }
        along -= 29 + ((district >>> 8) & 7);
        // Offset the chasm from the cell center. At the minimum realm size cells and habitat
        // sectors coincide; centering both would cut every severe ravine out of its quiet core.
        across -= (district & 16) == 0 ? 13 : 51;
        double halfLength = 19 + ((district >>> 24) & 7);
        double end = Math.max(0, 1 - along * along / (halfLength * halfLength));
        if (end == 0) { return Ravine.NONE; }
        across += Math.sin(along / 9 + ((district >>> 32) & 7)) * 2;
        double halfWidth = (2.5 + danger * 2) * Math.sqrt(end);
        double cross = Math.abs(across) / halfWidth;
        if (cross >= 1.35) { return Ravine.NONE; }
        if (cross >= 1) { return new Ravine(0, MIN_Y, false, true); }
        int maximumDepth = 5 + danger * danger * 5 + (int) ((district >>> 40) & 7);
        int depth = Math.max(1, (int) Math.round(maximumDepth * Math.sqrt(1 - cross * cross) * end));
        depth = Math.min(depth, surface - (MIN_Y + 8));
        // High-danger geothermal districts have deep, visibly glowing pools, not hidden surface
        // lava. Wet districts instead expose flooded depths. These are ordinary mineable blocks
        // and fluids: bridging, draining, lighting and fortification work normally.
        boolean hot = danger >= 3 && ((district >>> 48) & 3) < danger - 2
            && !family.equals("wet") && surface > profile.waterLevel() + 4;
        int lavaLevel = hot ? Math.max(MIN_Y + 10, surface - maximumDepth + 4) : MIN_Y;
        return new Ravine(depth, lavaLevel, family.equals("wet"), true);
    }

    public Material material(Column c, int y) {
        if (!c.inside() || y < MIN_Y || y >= MIN_Y + HEIGHT) { return Material.AIR; }
        if (c.terrace()) {
            // No valuable harvest, cavities, water or feature roots under the protected arrival footprint.
            if (y <= MIN_Y + 1) { return Material.BEDROCK; }
            return y <= c.surface() ? Material.STONE : Material.AIR;
        }
        if (y <= MIN_Y + 1) { return Material.BEDROCK; }
        if (y > c.surface()) { return y <= profile.waterLevel() ? Material.WATER : Material.AIR; }
        int depth = c.surface() - y;
        Ravine ravine = c.ravine();
        if (depth < ravine.depth()) {
            if (y <= ravine.lavaLevel()) { return Material.LAVA; }
            if (ravine.flooded() && y <= profile.waterLevel() - 8) { return Material.WATER; }
            return Material.AIR;
        }
        if (ravine.warningRim() && (depth <= 2 || depth <= ravine.depth() + 2)) {
            // Exposed stone edges and walls advertise the drop before a traveler reaches it.
            return ravine.lavaLevel() > MIN_Y ? Material.TUFF : Material.STONE;
        }
        if (depth > 5 && y > MIN_Y + 5 && cave(c.x(), y, c.z(), depth)) {
            if (y < MIN_Y + 12 && profile.danger() >= 2) { return Material.LAVA; }
            if (c.family().equals("wet") && y < profile.waterLevel() - 22) { return Material.WATER; }
            return Material.AIR;
        }
        if (depth == 0) {
            if (c.surface() <= profile.waterLevel()) { return c.family().equals("wet") ? Material.MUD : Material.GRAVEL; }
            return switch (c.family()) {
                case "arid" -> Material.SAND;
                case "cold" -> Material.SNOW;
                case "wet" -> Material.GRASS;
                case "lush" -> noise2(733, c.x() / 22.0, c.z() / 22.0) > .45 ? Material.MOSS : Material.GRASS;
                default -> Material.GRASS;
            };
        }
        if (depth < 4) {
            return c.family().equals("arid") ? (depth < 3 ? Material.SAND : Material.SANDSTONE) : Material.DIRT;
        }
        // Broad mixed mineral districts and small seams vary with richness, never offering-specific ores.
        double district = noise3(220, c.x() / 38.0, y / 28.0, c.z() / 38.0);
        double seam = noise3(221, c.x() / 5.0, y / 4.0, c.z() / 5.0);
        double richness = Math.clamp(profile.richness(), 0, 4);
        if (district > .30 && seam > .80 - richness * .07) {
            long mineral = mix(seed ^ Math.floorDiv(c.x(), 7) * 73428767L ^ Math.floorDiv(y, 6) * 912931L ^ Math.floorDiv(c.z(), 7) * 4382893L);
            int choice = (int) Math.floorMod(mineral, 100);
            if (choice < 30) { return Material.IRON; }
            if (choice < 50) { return y > 0 ? Material.COPPER : Material.REDSTONE; }
            if (choice < 70) { return Material.COAL; }
            if (choice < 83) { return y < 32 ? Material.GOLD : Material.COPPER; }
            if (choice < 94) { return Material.LAPIS; }
            return y < 0 ? Material.DIAMOND : Material.AMETHYST;
        }
        if (district > .38) { return y < 0 ? Material.TUFF : Material.CALCITE; }
        if (district < -.4) { return Material.GRANITE; }
        return y < 0 ? Material.DEEPSLATE : Material.STONE;
    }

    /** Final pre-decoration block, including the terrain-adaptive ruin grammar. */
    public Material sample(Column column, int y) {
        if (column.inside() && !column.terrace() && y > column.surface() - 10 && y <= column.surface() + 18) {
            Material module = ruin(column.x(), y, column.z());
            if (module != Material.AIR) { return module; }
        }
        return material(column, y);
    }

    private boolean cave(int x, int y, int z, int depth) {
        double tunnels = Math.abs(noise3(97, x / 33.0, y / 23.0, z / 33.0));
        double cross = Math.abs(noise3(103, x / 29.0, y / 31.0, z / 29.0));
        boolean tunnel = tunnels < .105 && cross < .17;
        double chambers = noise3(113, x / 56.0, y / 27.0, z / 56.0);
        double threshold = profile.topology() == RealmProfile.Topology.CAVERNS ? .29 : .59;
        // These subsurface fissures can meet the genuinely open ravines; quiet ground retains
        // its surface skin instead of acquiring scattered, hard-to-read single-block holes.
        boolean fissure = depth < 20 && profile.danger() > 0 && tunnels < .035 * profile.danger() && cross < .12;
        return tunnel || chambers > threshold || fissure;
    }

    /** Deterministic authored module grammar; each cell is independent and writes only its own footprint. */
    public Material ruin(int x, int y, int z) {
        if (!inside(x, z) || Math.max(Math.abs(x), Math.abs(z)) < 34) { return Material.AIR; }
        int cellX = Math.floorDiv(x, 64);
        int cellZ = Math.floorDiv(z, 64);
        long grammar = mix(seed ^ cellX * 728117L ^ cellZ * 912931L ^ 0x5255494eL);
        if (Math.floorMod(grammar, 9) > 1 + Math.min(2, (int) profile.richness())) { return Material.AIR; }
        int anchorX = cellX * 64 + 20 + (int) ((grammar >>> 8) & 15);
        int anchorZ = cellZ * 64 + 20 + (int) ((grammar >>> 16) & 15);
        if (!inside(anchorX - 15, anchorZ - 15) || !inside(anchorX + 15, anchorZ + 15)) { return Material.AIR; }
        Column anchor = column(anchorX, anchorZ);
        if (anchor.surface() <= profile.waterLevel() + 1 || anchor.ravine().warningRim()) { return Material.AIR; }
        int dx = x - anchorX;
        int dz = z - anchorZ;
        if ((grammar & 4) != 0) { int swap = dx; dx = dz; dz = swap; }
        Column groundColumn = column(x, z);
        if (groundColumn.ravine().warningRim()) { return Material.AIR; }
        int ground = groundColumn.surface();
        int base = anchor.surface() + 1;
        if (Math.abs(ground - anchor.surface()) > 10) { return Material.AIR; }
        int dy = y - base;
        // Courtyard foundation, two broken arcades, a terrain-following approach and optional tower.
        boolean court = Math.abs(dx) <= 7 && Math.abs(dz) <= 7 && y > ground && dy <= 0 && dy >= -10;
        boolean pillar = Math.abs(dx) == 7 && Math.abs(dz) <= 6 && dz % 4 == 0 && dy >= 0 && dy <= 4;
        boolean arch = Math.abs(dx) == 7 && Math.abs(dz) <= 6 && dy == 4;
        boolean path = Math.abs(dx) <= 2 && dz >= 8 && dz <= 14 && y == ground + 1;
        boolean tower = (grammar & 2) != 0 && dx >= -4 && dx <= 0 && dz >= -4 && dz <= 0
            && (dx == -4 || dx == 0 || dz == -4 || dz == 0) && dy >= 0 && dy < 7;
        if (!(court || pillar || arch || path || tower)) { return Material.AIR; }
        long wear = mix(seed ^ x * 92837111L ^ y * 689287L ^ z * 287117L);
        if (dy > 0 && (wear & 7) == 0) { return Material.AIR; }
        if ((wear & 3) == 0) { return Material.CRACKED_BRICKS; }
        return anchor.family().equals("lush") || anchor.family().equals("wet") || (wear & 3) == 1
            ? Material.MOSSY_BRICKS : Material.BRICKS;
    }

    public double noise2(long salt, double x, double z) { return noise3(salt, x, 0, z); }

    public double noise3(long salt, double x, double y, double z) {
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y), iz = (int) Math.floor(z);
        double fx = smooth(x - ix), fy = smooth(y - iy), fz = smooth(z - iz);
        double a = lerp(value(salt, ix, iy, iz), value(salt, ix + 1, iy, iz), fx);
        double b = lerp(value(salt, ix, iy + 1, iz), value(salt, ix + 1, iy + 1, iz), fx);
        double c = lerp(value(salt, ix, iy, iz + 1), value(salt, ix + 1, iy, iz + 1), fx);
        double d = lerp(value(salt, ix, iy + 1, iz + 1), value(salt, ix + 1, iy + 1, iz + 1), fx);
        return lerp(lerp(a, b, fy), lerp(c, d, fy), fz);
    }

    private double value(long salt, int x, int y, int z) {
        return (mix(seed ^ salt * 1299709L ^ x * 73428767L ^ y * 912931L ^ z * 4382893L) >>> 11) * 0x1.0p-52 - 1;
    }

    private static double smooth(double v) { v = Math.clamp(v, 0, 1); return v * v * (3 - 2 * v); }
    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }
    public static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }
}
