package qouteall.imm_ptl.peripheral.riftwoven.profile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class RealmRecipeTest {
    @Test
    void emptyDraftHasNoRareBenefitsOrFreeWorldIdentity() {
        RealmProfile profile = RealmRecipe.resolve(Map.of());
        assertEquals(1, profile.version());
        assertEquals(128, profile.radius());
        assertEquals(Map.of("arid", 0, "cold", 0, "lush", 0, "temperate", 16, "wet", 0),
            profile.biomeWeights());
        assertEquals(RealmProfile.Weather.CHANGEABLE, profile.weather());
        assertEquals(24000, profile.cycleTicks());
        assertEquals(12000, profile.daylightTicks());
        assertEquals(1, profile.suns());
        assertEquals(1, profile.moons());
        assertEquals(1.0, profile.richness());
        assertEquals(0, profile.danger());
        assertEquals(RealmProfile.Topology.CONTINENT, profile.topology());
        assertEquals(24, profile.terrainAmplitude());
        assertEquals(48, profile.waterLevel());
    }

    @Test
    void allDepositPermutationsResolveIdentically() {
        Map<String, Integer> recipe = illustrativeRecipe();
        RealmProfile expected = RealmRecipe.resolve(recipe);
        List<String> keys = new ArrayList<>(recipe.keySet());
        Random random = new Random(1092);
        for (int attempt = 0; attempt < 100; attempt++) {
            Collections.shuffle(keys, random);
            Map<String, Integer> reordered = new LinkedHashMap<>();
            for (String key : keys) {
                reordered.put(key, recipe.get(key));
            }
            assertEquals(expected, RealmRecipe.resolve(reordered));
        }
        assertEquals(List.of("arid", "cold", "lush", "temperate", "wet"),
            new ArrayList<>(expected.biomeWeights().keySet()));
    }

    @Test
    void withdrawalHistoryAndZeroEntriesDoNotChangeResolvedCommitments() {
        Map<String, Integer> inventory = new LinkedHashMap<>(illustrativeRecipe());
        RealmProfile initial = RealmRecipe.resolve(inventory);
        inventory.merge("minecraft:diamond", 48, Integer::sum);
        inventory.merge("minecraft:sand", 31, Integer::sum);
        RealmRecipe.resolve(inventory);
        inventory.compute("minecraft:diamond", (key, count) -> count - 48);
        inventory.compute("minecraft:sand", (key, count) -> count - 31);
        assertEquals(initial, RealmRecipe.resolve(inventory));
        assertEquals(initial, RealmRecipe.resolve(illustrativeRecipe()));
    }

    @Test
    void repeatabilityDoesNotDependOnUnrelatedRecipes() {
        RealmProfile expected = RealmRecipe.resolve(illustrativeRecipe());
        for (int index = 1; index <= 30; index++) {
            RealmRecipe.resolve(Map.of("minecraft:diamond", index, "minecraft:ice", index * 7));
            assertEquals(expected, RealmRecipe.resolve(illustrativeRecipe()));
        }
    }

    @Test
    void extentGrowsWithSquareRootDiminishingReturnsAndCaps() {
        assertEquals(128, stone(0).radius());
        assertEquals(136, stone(1).radius());
        assertEquals(248, stone(255).radius());
        assertEquals(256, stone(256).radius());
        assertEquals(384, stone(1024).radius());
        assertEquals(1024, stone(12544).radius());
        assertEquals(1024, stone(RealmRecipe.MAX_OFFERINGS).radius());
        assertTrue(stone(512).radius() - stone(256).radius()
            > stone(1024).radius() - stone(768).radius());
    }

    @Test
    void bulkDilutesRareConcentrationWithoutErasingExtent() {
        RealmProfile concentrated = RealmRecipe.resolve(Map.of("minecraft:diamond", 16,
            "minecraft:stone", 16));
        RealmProfile diluted = RealmRecipe.resolve(Map.of("minecraft:diamond", 16,
            "minecraft:stone", 4096));
        assertTrue(concentrated.richness() > diluted.richness());
        assertTrue(concentrated.danger() > diluted.danger());
        assertTrue(diluted.radius() > concentrated.radius());
        assertEquals(4.0, concentrated.richness());
        assertEquals(4, concentrated.danger());
    }

    @Test
    void rareAndCelestialModifiersDoNotBuyBulkExtent() {
        for (String item : List.of("diamond", "netherite_block", "gold_ingot", "emerald",
            "lapis_lazuli", "amethyst_shard", "glowstone", "echo_shard")) {
            assertEquals(128, RealmRecipe.resolve(Map.of("minecraft:" + item, 500)).radius(), item);
        }
    }

    @Test
    void compressedRareOfferingsHaveExplicitEquivalentUnits() {
        assertEquals(RealmRecipe.resolve(Map.of("minecraft:diamond_block", 1,
                "minecraft:stone", 128)),
            RealmRecipe.resolve(Map.of("minecraft:diamond", 9, "minecraft:stone", 128)));
        assertEquals(RealmRecipe.resolve(Map.of("minecraft:snow_block", 1)),
            RealmRecipe.resolve(Map.of("minecraft:snowball", 4)));
    }

    @Test
    void tinyMixedPaletteIsRealizableAndNeverPromisesMoreThanSixteenSectors() {
        RealmProfile tiny = RealmRecipe.resolve(Map.of("minecraft:moss_block", 1,
            "minecraft:sand", 1, "minecraft:snowball", 1, "minecraft:prismarine", 1));
        assertEquals(144, tiny.radius());
        assertEquals(16, tiny.biomeWeights().values().stream().mapToInt(Integer::intValue).sum());
        assertTrue(tiny.biomeWeights().values().stream().allMatch(weight -> weight >= 1));
        // Five minimum sectors, then eleven proportional sectors at scores 16:1:1:1:1.
        // Largest remainders allocate the final ties alphabetically, not all to temperate.
        assertEquals(10, tiny.biomeWeights().get("temperate"));
        assertEquals(2, tiny.biomeWeights().get("arid"));
        assertEquals(RealmProfile.Weather.CHANGEABLE, tiny.weather());
    }

    @Test
    void opposingClimateInputsRemainSeparateCommittedRegions() {
        RealmProfile mixed = RealmRecipe.resolve(Map.of("minecraft:sand", 128,
            "minecraft:prismarine", 128, "minecraft:ice", 32, "minecraft:moss_block", 32));
        assertTrue(mixed.biomeWeights().get("arid") > 0);
        assertTrue(mixed.biomeWeights().get("wet") > 0);
        assertTrue(mixed.biomeWeights().get("cold") > 0);
        assertTrue(mixed.biomeWeights().get("lush") > 0);
        assertEquals(RealmProfile.Weather.WET, mixed.weather());
        assertTrue(RealmRecipe.preview(mixed).contains("separate regions"));
    }

    @Test
    void paletteRemainderTiesUseAlphabeticalOrder() {
        RealmProfile profile = RealmRecipe.resolve(Map.of("minecraft:sand", 8,
            "minecraft:ice", 8, "minecraft:moss_block", 8));
        assertEquals(Map.of("arid", 4, "cold", 3, "lush", 3, "temperate", 6, "wet", 0),
            profile.biomeWeights());
    }

    @Test
    void mossAndAzaleaShareOneAffinityRatherThanPresetRecipes() {
        assertEquals(RealmRecipe.resolve(Map.of("minecraft:moss_block", 40)),
            RealmRecipe.resolve(Map.of("minecraft:moss_block", 32, "minecraft:azalea", 8)));
        assertEquals(RealmRecipe.resolve(Map.of("minecraft:azalea", 40)),
            RealmRecipe.resolve(Map.of("minecraft:flowering_azalea", 40)));
    }

    @Test
    void wetThresholdsUseBothMinimumAmountAndConcentration() {
        assertEquals(RealmProfile.Weather.CHANGEABLE, wet(31, 0).weather());
        assertEquals(RealmProfile.Weather.WET, wet(32, 96).weather());
        assertEquals(RealmProfile.Weather.CHANGEABLE, wet(32, 97).weather());
        assertEquals(RealmProfile.Weather.WET, wet(63, 0).weather());
        assertEquals(RealmProfile.Weather.STORMY, wet(64, 64).weather());
        assertEquals(RealmProfile.Weather.WET, wet(64, 65).weather());
        assertEquals(RealmProfile.Weather.CLEAR,
            RealmRecipe.resolve(Map.of("minecraft:sand", 16)).weather());
    }

    @Test
    void independentCelestialAxisDoesNotErasePaletteOrWeather() {
        Map<String, Integer> recipe = new LinkedHashMap<>(illustrativeRecipe());
        RealmProfile initial = RealmRecipe.resolve(recipe);
        recipe.put("minecraft:glowstone", 160);
        RealmProfile solar = RealmRecipe.resolve(recipe);
        assertEquals(initial.biomeWeights(), solar.biomeWeights());
        assertEquals(initial.weather(), solar.weather());
        assertEquals(initial.radius(), solar.radius());
        assertTrue(solar.cycleTicks() > initial.cycleTicks());
        assertTrue((double) solar.daylightTicks() / solar.cycleTicks()
            > (double) initial.daylightTicks() / initial.cycleTicks());
        assertEquals(1, solar.suns(), "Glowstone alone never grants a second visible sun");
    }

    @Test
    void lunarInvestmentLengthensCycleAndFavorsNightWithoutExtraSuns() {
        RealmProfile lunar = RealmRecipe.resolve(Map.of("minecraft:amethyst_shard", 32));
        assertEquals(48000, lunar.cycleTicks());
        assertEquals(7200, lunar.daylightTicks());
        assertEquals(1, lunar.suns());
        assertEquals(1, lunar.moons(), "Additional moon needs echo as well as lunar amount");
    }

    @Test
    void secondSunHasExplicitEchoAmountAndConcentrationGates() {
        assertEquals(1, RealmRecipe.resolve(Map.of("minecraft:glowstone", 16)).suns());
        assertEquals(1, RealmRecipe.resolve(Map.of("minecraft:glowstone", 15,
            "minecraft:echo_shard", 1)).suns());
        assertEquals(2, RealmRecipe.resolve(Map.of("minecraft:glowstone", 16,
            "minecraft:echo_shard", 1, "minecraft:stone", 495)).suns());
        assertEquals(1, RealmRecipe.resolve(Map.of("minecraft:glowstone", 16,
            "minecraft:echo_shard", 1, "minecraft:stone", 496)).suns());
        assertEquals(2, RealmRecipe.resolve(withEcho(illustrativeRecipe())).suns());
    }

    @Test
    void secondMoonHasIndependentGate() {
        assertEquals(1, RealmRecipe.resolve(Map.of("minecraft:echo_shard", 1,
            "minecraft:amethyst_shard", 7)).moons());
        RealmProfile profile = RealmRecipe.resolve(Map.of("minecraft:echo_shard", 1,
            "minecraft:amethyst_shard", 8));
        assertEquals(2, profile.moons());
        assertEquals(1, profile.suns());
    }

    @Test
    void topologyIsCompositionNotAWholeWorldPreset() {
        assertEquals(RealmProfile.Topology.CAVERNS,
            RealmRecipe.resolve(Map.of("minecraft:deepslate", 32)).topology());
        assertEquals(RealmProfile.Topology.PLATEAUS,
            RealmRecipe.resolve(Map.of("minecraft:terracotta", 32)).topology());
        assertEquals(RealmProfile.Topology.ISLANDS, wet(32, 96).topology());
        assertEquals(RealmProfile.Topology.CONTINENT, wet(32, 97).topology());
        RealmProfile cavernMix = RealmRecipe.resolve(Map.of("minecraft:deepslate", 128,
            "minecraft:prismarine", 128));
        assertEquals(RealmProfile.Topology.CAVERNS, cavernMix.topology(), "Deep priority is explicit");
        assertTrue(cavernMix.biomeWeights().get("wet") > 0);
    }

    @Test
    void geometryParametersHaveExactPublishedBounds() {
        RealmProfile plateaus = RealmRecipe.resolve(Map.of("minecraft:terracotta", 1024));
        assertEquals(96, plateaus.terrainAmplitude());
        assertEquals(96, wet(1024, 0).waterLevel());
        assertEquals(48, plateaus.waterLevel());
        assertEquals(24, wet(1024, 0).terrainAmplitude());
    }

    @Test
    void acceptsIsStrictAndNullSafe() {
        assertFalse(RealmRecipe.accepts(null));
        for (String invalid : List.of("stone", "Minecraft:stone", "minecraft:STONE", "minecraft:air",
            "other:stone", "minecraft:command_block", " minecraft:stone")) {
            assertFalse(RealmRecipe.accepts(invalid), invalid);
        }
        for (String item : List.of("stone", "cobblestone", "diamond", "netherite_ingot",
            "gold_block", "prismarine", "amethyst_shard", "echo_shard", "black_terracotta")) {
            assertTrue(RealmRecipe.accepts("minecraft:" + item), item);
        }
        assertEquals(16, RealmRecipe.transferLimit());
    }

    @Test
    void invalidOfferingsAreRejectedIncludingUnknownZeroEntries() {
        assertThrows(IllegalArgumentException.class, () -> RealmRecipe.resolve(null));
        assertThrows(IllegalArgumentException.class, () -> RealmRecipe.resolve(Map.of("stone", 1)));
        assertThrows(IllegalArgumentException.class,
            () -> RealmRecipe.resolve(Map.of("minecraft:unknown", 0)));
        Map<String, Integer> invalid = new LinkedHashMap<>();
        invalid.put(null, 1);
        assertThrows(IllegalArgumentException.class, () -> RealmRecipe.resolve(invalid));
        invalid.clear();
        invalid.put("minecraft:stone", null);
        assertThrows(IllegalArgumentException.class, () -> RealmRecipe.resolve(invalid));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, Integer.MIN_VALUE, 1048577, Integer.MAX_VALUE})
    void invalidCountsCannotOverflowOrProduceFreeTraits(int count) {
        assertThrows(IllegalArgumentException.class,
            () -> RealmRecipe.resolve(Map.of("minecraft:stone", count)));
    }

    @Test
    void totalLimitIncludesModifiersAndCannotOverflow() {
        assertThrows(IllegalArgumentException.class, () -> RealmRecipe.resolve(Map.of(
            "minecraft:stone", RealmRecipe.MAX_OFFERINGS, "minecraft:echo_shard", 1)));
        assertThrows(IllegalArgumentException.class, () -> RealmRecipe.resolve(Map.of(
            "minecraft:diamond", Integer.MAX_VALUE, "minecraft:stone", Integer.MAX_VALUE)));
        assertDoesNotThrow(() -> RealmRecipe.resolve(Map.of("minecraft:netherite_block",
            RealmRecipe.MAX_OFFERINGS)));
        assertDoesNotThrow(() -> RealmRecipe.resolve(Map.of("minecraft:snow_block",
            RealmRecipe.MAX_OFFERINGS)));
    }

    @Test
    void allBoundaryCombinationsRemainConstructible() {
        String[] items = {"stone", "moss_block", "terracotta", "snow_block", "prismarine",
            "glowstone", "amethyst_block", "echo_shard", "diamond_block"};
        for (String first : items) {
            for (String second : items) {
                Map<String, Integer> recipe = new LinkedHashMap<>();
                recipe.merge("minecraft:" + first, RealmRecipe.MAX_OFFERINGS / 2, Integer::sum);
                recipe.merge("minecraft:" + second, RealmRecipe.MAX_OFFERINGS / 2, Integer::sum);
                RealmProfile profile = assertDoesNotThrow(() -> RealmRecipe.resolve(recipe));
                assertEquals(16, profile.biomeWeights().values().stream()
                    .mapToInt(Integer::intValue).sum());
                assertTrue(profile.radius() >= 128 && profile.radius() <= 1024);
            }
        }
    }

    @Test
    void previewIsLocaleIndependentHonestAndDeterministic() {
        RealmProfile profile = RealmRecipe.resolve(withEcho(illustrativeRecipe()));
        Locale previous = Locale.getDefault();
        String expected = RealmRecipe.preview(profile);
        try {
            Locale.setDefault(Locale.GERMANY);
            assertEquals(expected, RealmRecipe.preview(profile));
        }
        finally {
            Locale.setDefault(previous);
        }
        assertTrue(expected.contains("NOT exact surface block shares"));
        assertTrue(expected.contains("visual only; no extra gameplay light"));
        assertTrue(expected.contains("No seed, map preview, exact yield or free world sampling"));
        assertTrue(expected.contains("1.000"));
    }

    private static RealmProfile stone(int count) {
        return RealmRecipe.resolve(Map.of("minecraft:stone", count));
    }

    private static RealmProfile wet(int wet, int stone) {
        return RealmRecipe.resolve(Map.of("minecraft:prismarine", wet, "minecraft:stone", stone));
    }

    private static Map<String, Integer> illustrativeRecipe() {
        return Map.of("minecraft:stone", 256, "minecraft:moss_block", 32, "minecraft:azalea", 8,
            "minecraft:prismarine", 32, "minecraft:glowstone", 16, "minecraft:amethyst_shard", 8);
    }

    private static Map<String, Integer> withEcho(Map<String, Integer> source) {
        Map<String, Integer> result = new LinkedHashMap<>(source);
        result.put("minecraft:echo_shard", 1);
        return result;
    }
}
