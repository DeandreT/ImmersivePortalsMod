package qouteall.imm_ptl.peripheral.riftwoven.profile;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class RealmProfileTest {
    @Test
    void paletteIsDefensivelyCopiedAndCanonicallyOrdered() {
        Map<String, Integer> input = new LinkedHashMap<>();
        input.put("wet", 0);
        input.put("temperate", 16);
        input.put("lush", 0);
        input.put("cold", 0);
        input.put("arid", 0);
        Draft draft = new Draft();
        draft.weights = input;
        RealmProfile profile = draft.build();
        input.put("temperate", 0);
        assertEquals(16, profile.biomeWeights().get("temperate"));
        assertEquals(RealmProfile.BIOME_FAMILIES, List.copyOf(profile.biomeWeights().keySet()));
        assertThrows(UnsupportedOperationException.class,
            () -> profile.biomeWeights().put("lush", 1));
        assertThrows(UnsupportedOperationException.class,
            () -> RealmProfile.BIOME_FAMILIES.add("other"));
    }

    @Test
    void constructorRejectsEveryInvalidScalarAndCrossFieldValue() {
        List<Consumer<Draft>> invalid = List.of(
            draft -> draft.version = 0,
            draft -> draft.version = 2,
            draft -> draft.radius = 127,
            draft -> draft.radius = 1025,
            draft -> draft.weather = null,
            draft -> draft.topology = null,
            draft -> draft.cycle = 23999,
            draft -> draft.cycle = 72001,
            draft -> draft.daylight = 3599,
            draft -> draft.daylight = 20401,
            draft -> draft.daylight = -1,
            draft -> draft.daylight = Integer.MAX_VALUE,
            draft -> draft.suns = 0,
            draft -> draft.suns = 3,
            draft -> draft.moons = 0,
            draft -> draft.moons = 3,
            draft -> draft.richness = Double.NaN,
            draft -> draft.richness = Double.POSITIVE_INFINITY,
            draft -> draft.richness = Double.NEGATIVE_INFINITY,
            draft -> draft.richness = .999,
            draft -> draft.richness = 4.001,
            draft -> draft.danger = -1,
            draft -> draft.danger = 5,
            draft -> draft.amplitude = 15,
            draft -> draft.amplitude = 97,
            draft -> draft.water = 31,
            draft -> draft.water = 97,
            draft -> draft.weather = RealmProfile.Weather.WET,
            draft -> draft.weather = RealmProfile.Weather.STORMY
        );
        for (int index = 0; index < invalid.size(); index++) {
            Draft draft = new Draft();
            invalid.get(index).accept(draft);
            assertThrows(IllegalArgumentException.class, draft::build, "Invalid case " + index);
        }
    }

    @Test
    void constructorRejectsMalformedPaletteWeights() {
        Draft draft = new Draft();
        draft.weights = null;
        assertThrows(IllegalArgumentException.class, draft::build);
        draft.weights = Map.of("temperate", 16);
        assertThrows(IllegalArgumentException.class, draft::build);
        for (Integer badWeight : new Integer[]{-1, 17, Integer.MAX_VALUE, null}) {
            draft.weights = new LinkedHashMap<>(RealmRecipe.resolve(Map.of()).biomeWeights());
            draft.weights.put("lush", badWeight);
            assertThrows(IllegalArgumentException.class, draft::build);
        }
        draft.weights = Map.of("arid", 0, "cold", 0, "lush", 0, "temperate", 15, "wet", 0);
        assertThrows(IllegalArgumentException.class, draft::build);
        draft.weights = Map.of("arid", 0, "cold", 0, "lush", 0, "temperate", 16, "unknown", 0);
        assertThrows(IllegalArgumentException.class, draft::build);
    }

    @Test
    void constructorAcceptsInclusiveBoundsAndContrastingSectors() {
        Draft draft = new Draft();
        draft.radius = 1024;
        draft.cycle = 72000;
        draft.daylight = 10800;
        draft.suns = 2;
        draft.moons = 2;
        draft.richness = 4;
        draft.danger = 4;
        draft.amplitude = 96;
        draft.water = 96;
        draft.weights = Map.of("arid", 8, "cold", 0, "lush", 0, "temperate", 0, "wet", 8);
        draft.weather = RealmProfile.Weather.STORMY;
        assertDoesNotThrow(draft::build);
        draft.daylight = 61200;
        assertDoesNotThrow(draft::build);
        draft.amplitude = 16;
        draft.water = 32;
        assertDoesNotThrow(draft::build);
    }

    @Test
    void jsonCodecRoundTripsWithoutMinecraftBootstrap() {
        List<RealmProfile> profiles = List.of(
            RealmRecipe.resolve(Map.of()),
            RealmRecipe.resolve(Map.of("minecraft:prismarine", 256, "minecraft:sand", 128,
                "minecraft:echo_shard", 1, "minecraft:glowstone", 16)),
            RealmRecipe.resolve(Map.of("minecraft:deepslate", 512, "minecraft:diamond_block", 64)),
            RealmRecipe.resolve(Map.of("minecraft:amethyst_shard", 256)),
            RealmRecipe.resolve(Map.of("minecraft:terracotta", 512, "minecraft:ice", 128))
        );
        for (RealmProfile profile : profiles) {
            JsonElement json = RealmProfile.CODEC.encodeStart(JsonOps.INSTANCE, profile).getOrThrow();
            assertEquals(profile, RealmProfile.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
            assertEquals(json, RealmProfile.CODEC.encodeStart(JsonOps.INSTANCE,
                RealmProfile.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow()).getOrThrow());
        }
    }

    @Test
    void schemaUsesStableExplicitNamesAndNoDefaultedVersion() {
        JsonObject json = defaultJson();
        assertEquals(13, json.size());
        for (String name : List.of("version", "radius", "biome_weights", "weather", "cycle_ticks",
            "daylight_ticks", "suns", "moons", "richness", "danger", "terrain_amplitude",
            "water_level", "topology")) {
            assertTrue(json.has(name), name);
            JsonObject missing = json.deepCopy();
            missing.remove(name);
            assertTrue(RealmProfile.CODEC.parse(JsonOps.INSTANCE, missing).error().isPresent(), name);
        }
        assertEquals("changeable", json.get("weather").getAsString());
        assertEquals("continent", json.get("topology").getAsString());
    }

    @Test
    void codecReportsInvalidScalarsAsErrorsRatherThanThrowing() {
        Map<String, Number> invalid = Map.ofEntries(
            Map.entry("version", 2), Map.entry("radius", 127), Map.entry("cycle_ticks", 0),
            Map.entry("daylight_ticks", 24000), Map.entry("suns", 3), Map.entry("moons", 0),
            Map.entry("richness", -1.0), Map.entry("danger", 5),
            Map.entry("terrain_amplitude", 97), Map.entry("water_level", 31)
        );
        invalid.forEach((name, value) -> {
            JsonObject json = defaultJson();
            json.addProperty(name, value);
            assertTrue(assertDoesNotThrow(() -> RealmProfile.CODEC.parse(JsonOps.INSTANCE, json))
                .error().isPresent(), name);
        });
    }

    @Test
    void codecRejectsBadEnumsAndCrossFieldPromises() {
        for (String name : List.of("weather", "topology")) {
            for (String value : List.of("unknown", "CLEAR", "")) {
                JsonObject json = defaultJson();
                json.addProperty(name, value);
                assertTrue(RealmProfile.CODEC.parse(JsonOps.INSTANCE, json).error().isPresent());
            }
        }
        JsonObject wetWithoutWetPalette = defaultJson();
        wetWithoutWetPalette.addProperty("weather", "stormy");
        assertTrue(RealmProfile.CODEC.parse(JsonOps.INSTANCE, wetWithoutWetPalette).error().isPresent());
        JsonObject wrongDaylight = defaultJson();
        wrongDaylight.addProperty("cycle_ticks", 72000);
        wrongDaylight.addProperty("daylight_ticks", 10000);
        assertTrue(RealmProfile.CODEC.parse(JsonOps.INSTANCE, wrongDaylight).error().isPresent());
    }

    @Test
    void codecRejectsBadPaletteIncludingOverflowAndUnknownFamilies() {
        for (String family : List.of("arid", "temperate", "wet")) {
            JsonObject json = defaultJson();
            json.getAsJsonObject("biome_weights").addProperty(family, Integer.MAX_VALUE);
            assertTrue(RealmProfile.CODEC.parse(JsonOps.INSTANCE, json).error().isPresent());
        }
        JsonObject unknown = defaultJson();
        unknown.getAsJsonObject("biome_weights").addProperty("unknown", 0);
        assertTrue(RealmProfile.CODEC.parse(JsonOps.INSTANCE, unknown).error().isPresent());
        JsonObject missing = defaultJson();
        missing.getAsJsonObject("biome_weights").remove("wet");
        assertTrue(RealmProfile.CODEC.parse(JsonOps.INSTANCE, missing).error().isPresent());
    }

    private static JsonObject defaultJson() {
        return RealmProfile.CODEC.encodeStart(JsonOps.INSTANCE, RealmRecipe.resolve(Map.of()))
            .getOrThrow().getAsJsonObject();
    }

    private static final class Draft {
        int version = 1;
        int radius = 128;
        Map<String, Integer> weights = RealmRecipe.resolve(Map.of()).biomeWeights();
        RealmProfile.Weather weather = RealmProfile.Weather.CHANGEABLE;
        int cycle = 24000;
        int daylight = 12000;
        int suns = 1;
        int moons = 1;
        double richness = 1;
        int danger = 0;
        int amplitude = 24;
        int water = 48;
        RealmProfile.Topology topology = RealmProfile.Topology.CONTINENT;

        RealmProfile build() {
            return new RealmProfile(version, radius, weights, weather, cycle, daylight, suns, moons,
                richness, danger, amplitude, water, topology);
        }
    }
}
