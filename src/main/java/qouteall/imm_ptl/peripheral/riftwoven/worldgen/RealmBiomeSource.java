package qouteall.imm_ptl.peripheral.riftwoven.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import qouteall.imm_ptl.peripheral.riftwoven.profile.RealmProfile;

import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/** Finite-scale allocated habitats, with seeded sub-biomes and soft transition belts. */
public final class RealmBiomeSource extends BiomeSource {
    public static final ResourceKey<Biome> CRYSTALLINE_GROVE = ResourceKey.create(
        Registries.BIOME, Identifier.fromNamespaceAndPath("riftwoven", "crystalline_grove")
    );
    public static final MapCodec<RealmBiomeSource> MAP_CODEC = RecordCodecBuilder.<Persisted>mapCodec(instance -> instance.group(
        RealmProfile.CODEC.fieldOf("profile").forGetter(Persisted::profile),
        Codec.LONG.fieldOf("realm_seed").forGetter(Persisted::seed),
        Codec.unboundedMap(Codec.STRING, Biome.CODEC.listOf()).fieldOf("palette").forGetter(Persisted::palette)
    ).apply(instance, Persisted::new)).flatXmap(Persisted::decode,
        source -> DataResult.success(new Persisted(source.profile(), source.realmSeed(), source.palette)));

    private final RealmTerrain terrain;
    private final Map<String, List<Holder<Biome>>> palette;

    private record Persisted(RealmProfile profile, long seed, Map<String, List<Holder<Biome>>> palette) {
        DataResult<RealmBiomeSource> decode() {
            try {
                return DataResult.success(new RealmBiomeSource(profile, seed, palette));
            } catch (IllegalArgumentException exception) {
                return DataResult.error(exception::getMessage);
            }
        }
    }

    public RealmBiomeSource(RealmProfile profile, long seed, Map<String, List<Holder<Biome>>> palette) {
        this.terrain = new RealmTerrain(profile, seed);
        Map<String, List<Holder<Biome>>> copied = new LinkedHashMap<>();
        palette.forEach((family, biomes) -> copied.put(family, List.copyOf(biomes)));
        for (String family : terrain.families()) {
            if (!copied.containsKey(family) || copied.get(family).isEmpty()) {
                throw new IllegalArgumentException("Missing persisted realm biome palette for " + family);
            }
        }
        if (!copied.containsKey("transition") || copied.get("transition").isEmpty()
            || !copied.containsKey("outside") || copied.get("outside").isEmpty()) {
            throw new IllegalArgumentException("Missing persisted transition/outside biomes");
        }
        // FeatureSorter consumes possibleBiomes in encounter order. Map.copyOf iteration is
        // JVM-randomized and would otherwise change independent decoration seeds on restart.
        this.palette = Collections.unmodifiableMap(new TreeMap<>(copied));
    }

    public static RealmBiomeSource create(HolderGetter<Biome> biomes, RealmProfile profile, long seed) {
        Map<String, List<Holder<Biome>>> palette = new LinkedHashMap<>();
        palette.put("lush", List.of(biomes.getOrThrow(Biomes.FOREST), biomes.getOrThrow(Biomes.FLOWER_FOREST),
            biomes.getOrThrow(CRYSTALLINE_GROVE)));
        palette.put("arid", List.of(biomes.getOrThrow(Biomes.DESERT), biomes.getOrThrow(Biomes.SAVANNA),
            biomes.getOrThrow(Biomes.BADLANDS)));
        palette.put("cold", List.of(biomes.getOrThrow(Biomes.SNOWY_PLAINS), biomes.getOrThrow(Biomes.SNOWY_TAIGA),
            biomes.getOrThrow(Biomes.GROVE)));
        palette.put("wet", List.of(biomes.getOrThrow(Biomes.SWAMP), biomes.getOrThrow(Biomes.MANGROVE_SWAMP),
            biomes.getOrThrow(Biomes.RIVER)));
        palette.put("temperate", List.of(biomes.getOrThrow(Biomes.PLAINS), biomes.getOrThrow(Biomes.MEADOW),
            biomes.getOrThrow(Biomes.BIRCH_FOREST)));
        palette.put("transition", List.of(biomes.getOrThrow(Biomes.WINDSWEPT_HILLS)));
        palette.put("outside", List.of(biomes.getOrThrow(Biomes.THE_VOID)));
        // Only active families belong to possibleBiomes, avoiding unnecessary/cyclic feature sorting.
        palette.keySet().removeIf(key -> !key.equals("transition") && !key.equals("outside") && !terrainFamily(profile, key));
        return new RealmBiomeSource(profile, seed, palette);
    }

    private static boolean terrainFamily(RealmProfile profile, String key) {
        return profile.biomeWeights().getOrDefault(key, 0) > 0;
    }

    public RealmProfile profile() { return terrain.profile(); }
    public long realmSeed() { return terrain.seed(); }

    @Override
    protected MapCodec<? extends BiomeSource> codec() { return MAP_CODEC; }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        return palette.values().stream().flatMap(List::stream);
    }

    public Holder<Biome> biomeAt(int x, int y, int z) {
        if (!terrain.inside(x, z)) { return palette.get("outside").getFirst(); }
        RealmTerrain.Region region = terrain.region(x, z);
        if (region.distanceToBoundary() < 4 && terrain.families().size() > 1) {
            return palette.get("transition").getFirst();
        }
        List<Holder<Biome>> choices = palette.get(region.family());
        double variation = terrain.noise2(811, x / 52.0, z / 52.0);
        int index = variation < -.17 ? 0 : variation > .22 ? 2 : 1;
        return choices.get(Math.min(index, choices.size() - 1));
    }

    @Override
    public BiomeResolver createResolver(Climate.Sampler ignored) {
        return (quartX, quartY, quartZ) -> biomeAt(quartX * 4, quartY * 4, quartZ * 4);
    }
}
