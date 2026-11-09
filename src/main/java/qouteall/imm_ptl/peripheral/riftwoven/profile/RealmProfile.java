package qouteall.imm_ptl.peripheral.riftwoven.profile;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Immutable version-one generation commitments, independent of the landscape seed.
 * Biome weights divide a palette into sixteen sectors; they are not exact surface block shares.
 * The generator must assign every positive family at least its stated number of palette sectors.
 */
public record RealmProfile(
    int version,
    int radius,
    Map<String, Integer> biomeWeights,
    Weather weather,
    int cycleTicks,
    int daylightTicks,
    int suns,
    int moons,
    double richness,
    int danger,
    int terrainAmplitude,
    int waterLevel,
    Topology topology
) {
    public static final int VERSION = 1;
    public static final int MIN_RADIUS = 128;
    public static final int MAX_RADIUS = 1024;
    public static final int PALETTE_SECTORS = 16;
    public static final List<String> BIOME_FAMILIES = List.of(
        "arid", "cold", "lush", "temperate", "wet"
    );

    public enum Weather { CLEAR, CHANGEABLE, WET, STORMY }

    public enum Topology { CONTINENT, ISLANDS, PLATEAUS, CAVERNS }

    private static final Codec<Weather> WEATHER_CODEC = enumCodec(Weather.class);
    private static final Codec<Topology> TOPOLOGY_CODEC = enumCodec(Topology.class);

    // Decode into an unchecked carrier first: malformed cross-field combinations must produce
    // DataResult errors, not escape the codec as constructor exceptions.
    public static final Codec<RealmProfile> CODEC = RecordCodecBuilder.<Fields>create(instance ->
        instance.group(
            Codec.INT.fieldOf("version").forGetter(Fields::version),
            Codec.INT.fieldOf("radius").forGetter(Fields::radius),
            Codec.unboundedMap(Codec.STRING, Codec.INT).fieldOf("biome_weights")
                .forGetter(Fields::biomeWeights),
            WEATHER_CODEC.fieldOf("weather").forGetter(Fields::weather),
            Codec.INT.fieldOf("cycle_ticks").forGetter(Fields::cycleTicks),
            Codec.INT.fieldOf("daylight_ticks").forGetter(Fields::daylightTicks),
            Codec.INT.fieldOf("suns").forGetter(Fields::suns),
            Codec.INT.fieldOf("moons").forGetter(Fields::moons),
            Codec.DOUBLE.fieldOf("richness").forGetter(Fields::richness),
            Codec.INT.fieldOf("danger").forGetter(Fields::danger),
            Codec.INT.fieldOf("terrain_amplitude").forGetter(Fields::terrainAmplitude),
            Codec.INT.fieldOf("water_level").forGetter(Fields::waterLevel),
            TOPOLOGY_CODEC.fieldOf("topology").forGetter(Fields::topology)
        ).apply(instance, Fields::new)
    ).flatXmap(Fields::validated, profile -> DataResult.success(new Fields(profile)));

    public RealmProfile {
        require(version == VERSION, "Unsupported RealmProfile version: " + version);
        require(radius >= MIN_RADIUS && radius <= MAX_RADIUS, "Radius must be 128..1024");
        require(biomeWeights != null, "Biome weights are required");
        require(biomeWeights.size() == BIOME_FAMILIES.size()
            && biomeWeights.keySet().containsAll(BIOME_FAMILIES),
            "Biome weights must contain exactly arid, cold, lush, temperate and wet");
        LinkedHashMap<String, Integer> canonicalWeights = new LinkedHashMap<>();
        int sum = 0;
        for (String family : BIOME_FAMILIES) {
            Integer weight = biomeWeights.get(family);
            require(weight != null && weight >= 0 && weight <= PALETTE_SECTORS,
                "Each biome weight must be 0..16");
            sum += weight;
            canonicalWeights.put(family, weight);
        }
        require(sum == PALETTE_SECTORS, "Biome weights must sum to sixteen palette sectors");
        biomeWeights = Collections.unmodifiableMap(canonicalWeights);
        require(weather != null && topology != null, "Weather and topology are required");
        require(cycleTicks >= 24000 && cycleTicks <= 72000, "Cycle must be 24000..72000 ticks");
        require(daylightTicks >= (cycleTicks * 15 + 99) / 100
            && daylightTicks <= cycleTicks * 85 / 100, "Daylight must be 15%..85% of cycle");
        require(suns >= 1 && suns <= 2 && moons >= 1 && moons <= 2,
            "One or two visible suns and moons are supported");
        require(Double.isFinite(richness) && richness >= 1.0 && richness <= 4.0,
            "Richness must be finite and 1..4");
        require(danger >= 0 && danger <= 4, "Danger must be 0..4");
        require(terrainAmplitude >= 16 && terrainAmplitude <= 96,
            "Terrain amplitude must be 16..96");
        require(waterLevel >= 32 && waterLevel <= 96, "Water level must be 32..96");
        // Even the smallest 256x256 footprint has room for sixteen 64x64 palette sectors.
        // Dry and wet sectors coexist; global weather never erases a palette commitment.
        require((weather != Weather.WET && weather != Weather.STORMY)
            || biomeWeights.get("wet") > 0, "Wet weather requires wet palette sectors");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private static <E extends Enum<E>> Codec<E> enumCodec(Class<E> type) {
        return Codec.STRING.comapFlatMap(value -> {
            for (E candidate : type.getEnumConstants()) {
                if (candidate.name().toLowerCase(Locale.ROOT).equals(value)) {
                    return DataResult.success(candidate);
                }
            }
            return DataResult.error(() -> "Unknown " + type.getSimpleName() + ": " + value);
        }, value -> value.name().toLowerCase(Locale.ROOT));
    }

    private record Fields(
        int version, int radius, Map<String, Integer> biomeWeights, Weather weather,
        int cycleTicks, int daylightTicks, int suns, int moons, double richness, int danger,
        int terrainAmplitude, int waterLevel, Topology topology
    ) {
        private Fields(RealmProfile profile) {
            this(profile.version, profile.radius, profile.biomeWeights, profile.weather,
                profile.cycleTicks, profile.daylightTicks, profile.suns, profile.moons,
                profile.richness, profile.danger, profile.terrainAmplitude, profile.waterLevel,
                profile.topology);
        }

        private DataResult<RealmProfile> validated() {
            try {
                return DataResult.success(new RealmProfile(version, radius, biomeWeights, weather,
                    cycleTicks, daylightTicks, suns, moons, richness, danger, terrainAmplitude,
                    waterLevel, topology));
            }
            catch (IllegalArgumentException exception) {
                return DataResult.error(exception::getMessage);
            }
        }
    }
}
