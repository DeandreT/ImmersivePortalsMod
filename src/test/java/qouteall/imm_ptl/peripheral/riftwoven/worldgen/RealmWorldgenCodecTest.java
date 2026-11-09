package qouteall.imm_ptl.peripheral.riftwoven.worldgen;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeSpecialEffects;
import net.minecraft.world.level.levelgen.Heightmap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import qouteall.imm_ptl.peripheral.riftwoven.profile.RealmProfile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RealmWorldgenCodecTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static RealmChunkGenerator fixture(long seed) {
        RealmProfile profile = RealmTerrainTest.profile(128, 2, 2, RealmProfile.Topology.CAVERNS);
        Holder<Biome> biome = Holder.direct(new Biome.BiomeBuilder().temperature(.6f).downfall(.7f)
            .specialEffects(new BiomeSpecialEffects.Builder().waterColor(4159204).build())
            .generationSettings(BiomeGenerationSettings.EMPTY).build());
        Map<String, List<Holder<Biome>>> palette = new LinkedHashMap<>();
        for (String family : profile.biomeWeights().keySet()) { palette.put(family, List.of(biome)); }
        palette.put("transition", List.of(biome));
        palette.put("outside", List.of(biome));
        return new RealmChunkGenerator(1, profile, seed, new RealmBiomeSource(profile, seed, palette));
    }

    @Test
    void generatorRoundTripPreservesProfileSeedVersionAndBiomeHolders() {
        RealmChunkGenerator original = fixture(Long.MIN_VALUE + 173);
        JsonObject json = RealmChunkGenerator.MAP_CODEC.codec().encodeStart(JsonOps.INSTANCE, original).getOrThrow().getAsJsonObject();
        RealmChunkGenerator decoded = RealmChunkGenerator.MAP_CODEC.codec().parse(JsonOps.INSTANCE, json).getOrThrow();
        assertEquals(original.profile(), decoded.profile());
        assertEquals(original.realmSeed(), decoded.realmSeed());
        assertEquals(original.realmSeed(), decoded.dimensionSeed());
        assertEquals(1, json.get("generator_version").getAsInt());
        assertTrue(json.getAsJsonObject("biome_source").has("palette"), "Persist holders; never re-resolve a changed palette on reload");
        assertEquals(json, RealmChunkGenerator.MAP_CODEC.codec().encodeStart(JsonOps.INSTANCE, decoded).getOrThrow());
    }

    @Test
    void missingUnsupportedAndInconsistentSavedGenerationIdentityFailsClosed() {
        JsonObject original = RealmChunkGenerator.MAP_CODEC.codec().encodeStart(JsonOps.INSTANCE, fixture(27)).getOrThrow().getAsJsonObject();
        for (String field : List.of("generator_version", "profile", "realm_seed", "biome_source")) {
            JsonObject missing = original.deepCopy();
            missing.remove(field);
            assertTrue(RealmChunkGenerator.MAP_CODEC.codec().parse(JsonOps.INSTANCE, missing).error().isPresent(), field);
        }
        JsonObject version = original.deepCopy();
        version.addProperty("generator_version", 2);
        assertTrue(RealmChunkGenerator.MAP_CODEC.codec().parse(JsonOps.INSTANCE, version).error().isPresent());
        JsonObject seed = original.deepCopy();
        seed.addProperty("realm_seed", 99);
        assertTrue(assertDoesNotThrow(() -> RealmChunkGenerator.MAP_CODEC.codec().parse(JsonOps.INSTANCE, seed)).error().isPresent());
        JsonObject palette = original.deepCopy();
        palette.getAsJsonObject("biome_source").getAsJsonObject("palette").remove("lush");
        assertTrue(assertDoesNotThrow(() -> RealmChunkGenerator.MAP_CODEC.codec().parse(JsonOps.INSTANCE, palette)).error().isPresent());
    }

    @Test
    void heightAndColumnQueriesUseExactlyTheSameFinalTerrainIncludingRuins() {
        RealmChunkGenerator generator = fixture(719231);
        LevelHeightAccessor accessor = LevelHeightAccessor.create(-64, 384);
        for (int x = -135; x < 140; x += 19) {
            for (int z = -135; z < 140; z += 23) {
                NoiseColumn column = generator.getBaseColumn(x, z, accessor, null);
                RealmTerrain.Column mathColumn = generator.terrain().column(x, z);
                for (int y = -64; y < 192; y++) {
                    assertEquals(RealmChunkGenerator.state(generator.terrain().sample(mathColumn, y), y), column.getBlock(y));
                }
                for (Heightmap.Types type : new Heightmap.Types[]{Heightmap.Types.WORLD_SURFACE_WG, Heightmap.Types.OCEAN_FLOOR_WG}) {
                    int expected = -64;
                    for (int y = 191; y >= -64; y--) {
                        if (type.isOpaque().test(column.getBlock(y))) { expected = y + 1; break; }
                    }
                    assertEquals(expected, generator.getBaseHeight(x, z, type, accessor, null));
                }
            }
        }
        assertEquals(RealmTerrain.ARRIVAL_Y, generator.getBaseHeight(0, 0, Heightmap.Types.WORLD_SURFACE_WG, accessor, null));
    }
}
