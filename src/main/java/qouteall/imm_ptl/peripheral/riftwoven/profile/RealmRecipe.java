package qouteall.imm_ptl.peripheral.riftwoven.profile;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Pure, registry-free version-one offering rules. No random numbers, seeds or deposit history
 * participate. Quantities are provisional balance rules, not ore-yield guarantees.
 */
public final class RealmRecipe {
    /** Maximum escrow item count (including modifiers), checked before weighted arithmetic. */
    public static final int MAX_OFFERINGS = 1_048_576;

    private enum Group { STONE, DEEP, LUSH, ARID, PLATEAU, COLD, WET, SOLAR, LUNAR, ECHO, RARE }

    private record Material(Group group, int units, int rareValue) { }

    private static final Map<String, Material> MATERIALS = materials();

    private RealmRecipe() { }

    /** Only exact, namespaced vanilla identifiers are accepted; null and unknown IDs are false. */
    public static boolean accepts(String itemId) {
        return itemId != null && MATERIALS.containsKey(itemId);
    }

    /** One deliberate use transfers at most sixteen items, or the remaining held count. */
    public static int transferLimit() {
        return 16;
    }

    /**
     * Resolves the current escrow inventory, including an empty growing draft. Rejects unknown
     * IDs even when their count is zero; rejects negative/null counts and oversized inventories.
     * Zero counts for accepted IDs are equivalent to removing the entry entirely.
     */
    public static RealmProfile resolve(Map<String, Integer> offerings) {
        if (offerings == null) {
            throw new IllegalArgumentException("Offerings are required");
        }
        // Validate before canonicalizing so null keys fail with a useful domain error.
        long count = 0;
        for (Map.Entry<String, Integer> entry : offerings.entrySet()) {
            if (!accepts(entry.getKey())) {
                throw new IllegalArgumentException("Unsupported offering: " + entry.getKey());
            }
            Integer amount = entry.getValue();
            if (amount == null || amount < 0 || amount > MAX_OFFERINGS) {
                throw new IllegalArgumentException("Offering counts must be 0.." + MAX_OFFERINGS);
            }
            count += amount;
            if (count > MAX_OFFERINGS) {
                throw new IllegalArgumentException("Total offering limit is " + MAX_OFFERINGS);
            }
        }
        EnumMap<Group, Long> groups = new EnumMap<>(Group.class);
        long total = 0;
        long rare = 0;
        for (Map.Entry<String, Integer> entry : new TreeMap<>(offerings).entrySet()) {
            Material material = MATERIALS.get(entry.getKey());
            long units = (long) entry.getValue() * material.units;
            groups.merge(material.group, units, Long::sum);
            total += units;
            rare += units * material.rareValue;
        }
        long stone = amount(groups, Group.STONE);
        long deep = amount(groups, Group.DEEP);
        long lush = amount(groups, Group.LUSH);
        long arid = amount(groups, Group.ARID) + amount(groups, Group.PLATEAU);
        long plateau = amount(groups, Group.PLATEAU);
        long cold = amount(groups, Group.COLD);
        long wet = amount(groups, Group.WET);
        long solar = amount(groups, Group.SOLAR);
        long lunar = amount(groups, Group.LUNAR) + amount(groups, Group.ECHO);
        long echo = amount(groups, Group.ECHO);
        long bulk = stone + deep + lush + arid + cold + wet;
        long denominator = Math.max(1, total);
        int radius = (int) Math.min(RealmProfile.MAX_RADIUS,
            RealmProfile.MIN_RADIUS + 8 * integerSquareRoot(bulk));

        RealmProfile.Weather weather;
        if (wet >= 64 && wet * 2 >= bulk) {
            weather = RealmProfile.Weather.STORMY;
        }
        else if (wet >= 32 && wet * 4 >= bulk) {
            weather = RealmProfile.Weather.WET;
        }
        else if (wet > 0 || lush > 0 || arid == 0) {
            weather = RealmProfile.Weather.CHANGEABLE;
        }
        else {
            weather = RealmProfile.Weather.CLEAR;
        }

        int cycle = 24000 + (int) Math.min(24000, solar * 96000 / denominator)
            + (int) Math.min(24000, lunar * 96000 / denominator);
        int daylightPermille = 500 + (int) Math.min(350, solar * 2000 / denominator)
            - (int) Math.min(350, lunar * 2000 / denominator);
        int daylight = (int) ((long) cycle * daylightPermille / 1000);
        // Round inward so even a non-integral 15% boundary remains valid.
        daylight = Math.max((cycle * 15 + 99) / 100, Math.min(cycle * 85 / 100, daylight));
        int suns = echo >= 1 && solar >= 16 && solar * 32 >= total ? 2 : 1;
        int moons = echo >= 1 && lunar >= 9 ? 2 : 1;
        double richness = 1.0 + Math.min(3.0, (double) rare / denominator);
        int danger = (int) Math.min(4, rare * 4 / (denominator * 3));
        RealmProfile.Topology topology = deep >= 32 && deep * 2 >= bulk
            ? RealmProfile.Topology.CAVERNS
            : wet >= 32 && wet * 4 >= bulk ? RealmProfile.Topology.ISLANDS
            : plateau >= 32 && plateau * 2 >= bulk ? RealmProfile.Topology.PLATEAUS
            : RealmProfile.Topology.CONTINENT;
        int amplitude = 24 + (int) Math.min(72, (deep + plateau) * 72 / Math.max(1, bulk));
        int waterLevel = 48 + (int) Math.min(48, wet * 48 / Math.max(1, bulk));

        return new RealmProfile(RealmProfile.VERSION, radius, palette(lush, arid, cold, wet),
            weather, cycle, daylight, suns, moons, richness, danger, amplitude, waterLevel, topology);
    }

    /** Exact commitments and the provisional thresholds, without revealing or sampling a map. */
    public static String preview(RealmProfile profile) {
        Objects.requireNonNull(profile, "profile");
        return String.format(Locale.ROOT,
            "Realm profile v%d: square halfwidth %d blocks (width %d); palette sectors /16 %s "
                + "(guaranteed palette allocation, NOT exact surface block shares). "
                + "Weather %s; cycle %d ticks; daylight %d ticks; visible suns %d, moons %d "
                + "(visual only; no extra gameplay light). Richness tendency %.3f, danger %d/4 "
                + "(no ore quota); topology %s, terrain amplitude %d, water level %d. "
                + "Rules v1: transfer <=16 items/use; total <=1048576 items. "
                + "Bulk radius = min(1024,128+8*floor(sqrt(bulk units))); modifiers add no area. "
                + "Moss/azalea=lush, sand/terracotta=arid, snow/ice=cold, prismarine=wet; "
                + "every supplied family gets >=1/16 palette sector; temperate starts at 16 score. "
                + "Remaining sectors follow relative scores, alphabetical ties. Dry/wet inputs "
                + "make separate regions; cold precipitation is snow-compatible. "
                + "Wet weather needs >=32 wet units and >=1/4 bulk; stormy needs >=64 and >=1/2; "
                + "otherwise changeable, or clear for arid without lush/wet. "
                + "Caverns: >=32 deep units and >=1/2 bulk; else islands: >=32 wet and >=1/4; "
                + "else plateaus: >=32 terracotta and >=1/2; else continent. "
                + "Cycle=24000+min(24000,96000*solar/total)+min(24000,96000*lunar/total); "
                + "daylight fraction=.5+min(.35,2*solar/total)-min(.35,2*lunar/total), "
                + "integer-rounded inward to 15%%..85%%. Glowstone=solar; amethyst/echo=lunar. "
                + "Two suns require >=1 echo, >=16 solar and solar >=1/32 total; "
                + "two moons require >=1 echo and >=9 lunar. "
                + "Richness=1+min(3,rare score/total); danger=min(4,floor(4*rare score/(3*total))). "
                + "Rare scores/unit: lapis 1, gold 2, emerald 4, diamond 8, netherite 16. "
                + "Metal/gem/storage blocks=9 units; snow block=4; other items=1. "
                + "Terrain amplitude=24+min(72,72*(deep+terracotta)/bulk); "
                + "water level=48+min(48,48*wet/bulk). Counts use integer division; "
                + "empty denominators use 1. No seed, map preview, exact yield or free world sampling.",
            profile.version(), profile.radius(), profile.radius() * 2, profile.biomeWeights(),
            profile.weather().name().toLowerCase(Locale.ROOT), profile.cycleTicks(),
            profile.daylightTicks(), profile.suns(), profile.moons(), profile.richness(),
            profile.danger(), profile.topology().name().toLowerCase(Locale.ROOT),
            profile.terrainAmplitude(), profile.waterLevel());
    }

    /** Readable in-world commitments. Full numerical rules remain available in {@link #preview}. */
    public static String summary(RealmProfile profile) {
        Objects.requireNonNull(profile, "profile");
        return String.format(Locale.ROOT,
            "Realm v%d — finite width %d blocks; %s terrain. Habitat sectors /16: %s "
                + "(not exact block shares). Weather: %s. Cycle: %.1f minutes, daylight: %.1f minutes. "
                + "Visible suns: %d; moons: %d (no extra gameplay light). "
                + "Richness tendency: %.2f; danger: %d/4. Locations and mineral yields remain undiscovered.",
            profile.version(), profile.radius() * 2, profile.topology().name().toLowerCase(Locale.ROOT),
            profile.biomeWeights(), profile.weather().name().toLowerCase(Locale.ROOT),
            profile.cycleTicks() / 1200.0, profile.daylightTicks() / 1200.0,
            profile.suns(), profile.moons(), profile.richness(), profile.danger());
    }

    private static long amount(Map<Group, Long> groups, Group group) {
        return groups.getOrDefault(group, 0L);
    }

    private static long integerSquareRoot(long value) {
        long root = (long) Math.sqrt(value);
        while ((root + 1) * (root + 1) <= value) {
            root++;
        }
        while (root * root > value) {
            root--;
        }
        return root;
    }

    private static Map<String, Integer> palette(long lush, long arid, long cold, long wet) {
        Map<String, Long> scores = Map.of("arid", arid, "cold", cold, "lush", lush,
            "temperate", 16L, "wet", wet);
        LinkedHashMap<String, Integer> weights = new LinkedHashMap<>();
        int active = 0;
        long total = 0;
        for (String family : RealmProfile.BIOME_FAMILIES) {
            long score = scores.get(family);
            weights.put(family, score > 0 ? 1 : 0);
            active += score > 0 ? 1 : 0;
            total += score;
        }
        int remaining = RealmProfile.PALETTE_SECTORS - active;
        Map<String, Long> remainders = new LinkedHashMap<>();
        int assigned = active;
        for (String family : RealmProfile.BIOME_FAMILIES) {
            long numerator = scores.get(family) * remaining;
            int share = (int) (numerator / total);
            weights.merge(family, share, Integer::sum);
            assigned += share;
            remainders.put(family, numerator % total);
        }
        // Largest remainder allocation, alphabetical ties, each remainder used once.
        while (assigned < RealmProfile.PALETTE_SECTORS) {
            String best = null;
            for (String family : RealmProfile.BIOME_FAMILIES) {
                if (scores.get(family) > 0
                    && (best == null || remainders.get(family) > remainders.get(best))) {
                    best = family;
                }
            }
            weights.merge(best, 1, Integer::sum);
            remainders.put(best, -1L);
            assigned++;
        }
        return weights;
    }

    private static Map<String, Material> materials() {
        Map<String, Material> result = new LinkedHashMap<>();
        add(result, Group.STONE, 1, 0, "stone", "cobblestone", "andesite", "diorite", "granite",
            "tuff", "calcite", "dirt", "gravel", "basalt", "smooth_basalt", "blackstone",
            "obsidian", "crying_obsidian", "netherrack", "end_stone");
        add(result, Group.DEEP, 1, 0, "deepslate", "cobbled_deepslate");
        add(result, Group.LUSH, 1, 0, "moss_block", "azalea", "flowering_azalea");
        add(result, Group.ARID, 1, 0, "sand", "red_sand");
        add(result, Group.PLATEAU, 1, 0, "terracotta", "white_terracotta", "orange_terracotta",
            "magenta_terracotta", "light_blue_terracotta", "yellow_terracotta", "lime_terracotta",
            "pink_terracotta", "gray_terracotta", "light_gray_terracotta", "cyan_terracotta",
            "purple_terracotta", "blue_terracotta", "brown_terracotta", "green_terracotta",
            "red_terracotta", "black_terracotta");
        add(result, Group.COLD, 1, 0, "snowball", "ice", "packed_ice", "blue_ice");
        add(result, Group.COLD, 4, 0, "snow_block");
        add(result, Group.WET, 1, 0, "prismarine", "prismarine_bricks", "dark_prismarine",
            "prismarine_shard", "prismarine_crystals");
        add(result, Group.SOLAR, 1, 0, "glowstone", "glowstone_dust");
        add(result, Group.LUNAR, 1, 0, "amethyst_shard");
        add(result, Group.LUNAR, 9, 0, "amethyst_block");
        add(result, Group.ECHO, 1, 0, "echo_shard");
        addRare(result, "lapis_lazuli", "lapis_block", 1);
        addRare(result, "gold_ingot", "gold_block", 2);
        addRare(result, "emerald", "emerald_block", 4);
        addRare(result, "diamond", "diamond_block", 8);
        addRare(result, "netherite_ingot", "netherite_block", 16);
        return Collections.unmodifiableMap(result);
    }

    private static void addRare(Map<String, Material> result, String item, String block, int value) {
        add(result, Group.RARE, 1, value, item);
        add(result, Group.RARE, 9, value, block);
    }

    private static void add(Map<String, Material> result, Group group, int units, int rare,
                            String... names) {
        for (String name : names) {
            result.put("minecraft:" + name, new Material(group, units, rare));
        }
    }
}
