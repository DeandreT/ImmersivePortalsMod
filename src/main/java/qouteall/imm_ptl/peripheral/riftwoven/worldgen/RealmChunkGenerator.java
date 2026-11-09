package qouteall.imm_ptl.peripheral.riftwoven.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.jetbrains.annotations.Nullable;
import qouteall.dimlib.api.IndependentDimensionSeed;
import qouteall.imm_ptl.peripheral.riftwoven.profile.RealmProfile;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/** Persisted finite compositional terrain, ecosystems and ruins, independently seeded at every stage. */
public final class RealmChunkGenerator extends ChunkGenerator implements IndependentDimensionSeed {
    public static final MapCodec<RealmChunkGenerator> MAP_CODEC = RecordCodecBuilder.<Persisted>mapCodec(instance -> instance.group(
        Codec.intRange(RealmTerrain.VERSION, RealmTerrain.VERSION).fieldOf("generator_version").forGetter(Persisted::version),
        RealmProfile.CODEC.fieldOf("profile").forGetter(Persisted::profile),
        Codec.LONG.fieldOf("realm_seed").forGetter(Persisted::seed),
        RealmBiomeSource.MAP_CODEC.codec().fieldOf("biome_source").forGetter(Persisted::biomes)
    ).apply(instance, Persisted::new)).flatXmap(Persisted::decode, generator -> DataResult.success(
        new Persisted(RealmTerrain.VERSION, generator.profile(), generator.realmSeed(), generator.realmBiomes)));

    private final RealmTerrain terrain;
    private final RealmBiomeSource realmBiomes;

    RealmChunkGenerator(int version, RealmProfile profile, long seed, RealmBiomeSource biomes) {
        super(biomes);
        if (version != RealmTerrain.VERSION || !profile.equals(biomes.profile()) || seed != biomes.realmSeed()) {
            throw new IllegalArgumentException("Inconsistent or unsupported persisted realm generator");
        }
        terrain = new RealmTerrain(profile, seed);
        realmBiomes = biomes;
    }

    public static RealmChunkGenerator create(RegistryAccess registries, RealmProfile profile, long seed) {
        return new RealmChunkGenerator(RealmTerrain.VERSION, profile, seed,
            RealmBiomeSource.create(registries.lookupOrThrow(Registries.BIOME), profile, seed));
    }

    private record Persisted(int version, RealmProfile profile, long seed, RealmBiomeSource biomes) {
        DataResult<RealmChunkGenerator> decode() {
            try {
                return DataResult.success(new RealmChunkGenerator(version, profile, seed, biomes));
            } catch (IllegalArgumentException exception) {
                return DataResult.error(exception::getMessage);
            }
        }
    }

    public RealmProfile profile() { return terrain.profile(); }
    public long realmSeed() { return terrain.seed(); }
    @Override
    public long dimensionSeed() { return realmSeed(); }
    public RealmTerrain terrain() { return terrain; }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() { return MAP_CODEC; }

    @Override
    public CompletableFuture<ChunkAccess> buildTerrain(
        ChunkAccess chunk, Blender blender, RandomState ignored, StructureManager structures,
        BiomeManager biomeManager, @Nullable WorldGenRegion carverRegion, Set<Holder<Biome>> possibleBiomes
    ) {
        if (!terrain.generatesChunk(chunk.getPos().x(), chunk.getPos().z())) {
            return CompletableFuture.completedFuture(chunk);
        }
        return CompletableFuture.supplyAsync(() -> {
            List<LevelChunkSection> acquired = new ArrayList<>();
            try {
                for (LevelChunkSection section : chunk.getSections()) {
                    section.acquire();
                    acquired.add(section);
                }
                fill(chunk);
                return chunk;
            } finally {
                for (LevelChunkSection section : acquired) { section.release(); }
            }
        }, Util.backgroundExecutor().forName("riftwoven_terrain"));
    }

    private void fill(ChunkAccess chunk) {
        Heightmap floor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap surface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        int minY = Math.max(getMinY(), chunk.getMinY());
        int maxY = Math.min(getMinY() + getGenDepth() - 1, chunk.getMaxY());
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                RealmTerrain.Column column = terrain.column(chunk.getPos().getMinBlockX() + x, chunk.getPos().getMinBlockZ() + z);
                if (!column.inside()) { continue; }
                for (int y = minY; y <= maxY; y++) {
                    BlockState block = state(terrain.sample(column, y), y);
                    if (block.isAir()) { continue; }
                    LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
                    section.setBlockState(x, y & 15, z, block, false);
                    floor.update(x, y, z, block);
                    surface.update(x, y, z, block);
                }
            }
        }
    }

    @Override
    public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager structures) {
        ChunkPos pos = chunk.getPos();
        if (terrain.decoratesChunk(pos.x(), pos.z())) {
            super.applyBiomeDecoration(RealmGenerationMask.wrap(level, terrain, pos.x(), pos.z()), chunk, structures);
        }
    }

    @Override
    public void spawnOriginalMobs(WorldGenRegion region) {
        ChunkPos pos = region.getCenter();
        if (!terrain.decoratesChunk(pos.x(), pos.z())) { return; }
        WorldGenLevel masked = RealmGenerationMask.wrap(region, terrain, pos.x(), pos.z());
        RandomSource random = RandomSource.create(RealmTerrain.mix(realmSeed() ^ pos.x() * 912931L ^ pos.z() * 73428767L));
        NaturalSpawner.spawnMobsForChunkGeneration(masked, pos.getWorldPosition().atY(region.getMaxY()), pos, random);
    }

    @Override
    public ChunkGeneratorStructureState createState(HolderLookup<StructureSet> structures, RandomState randomState, long ignoredSeed) {
        // The version-one vocabulary uses its own bounded grammar, not unbounded vanilla jigsaw/ring starts.
        // Persisted realmSeed still owns structure state; no server-world seed is inherited here.
        return ChunkGeneratorStructureState.createForFlat(randomState, realmSeed(), new ChunkPos(0, 0), realmBiomes, Stream.empty());
    }

    @Override
    public void createStructures(RegistryAccess registries, ChunkGeneratorStructureState state, StructureManager structures,
                                 ChunkAccess chunk, StructureTemplateManager templates, ResourceKey<Level> level) {
        // Authored ruin modules are composed in the same pure terrain field as height queries.
    }

    @Override
    public void createReferences(WorldGenLevel level, StructureManager structures, ChunkAccess chunk) {
        // No vanilla starts: importantly, do not request vanilla's eight-chunk reference neighborhood.
    }

    @Override
    public int getSpawnHeight(LevelHeightAccessor accessor) { return RealmTerrain.ARRIVAL_Y; }
    @Override
    public int getGenDepth() { return RealmTerrain.HEIGHT; }
    @Override
    public int getMinY() { return RealmTerrain.MIN_Y; }
    @Override
    public int getSeaLevel() { return profile().waterLevel(); }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor accessor, RandomState ignored) {
        RealmTerrain.Column column = terrain.column(x, z);
        int minY = Math.max(getMinY(), accessor.getMinY());
        int maxY = Math.min(getMinY() + getGenDepth() - 1, accessor.getMaxY());
        for (int y = maxY; y >= minY; y--) {
            if (type.isOpaque().test(state(terrain.sample(column, y), y))) { return y + 1; }
        }
        return accessor.getMinY();
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor accessor, RandomState ignored) {
        int minY = Math.max(getMinY(), accessor.getMinY());
        int maxY = Math.min(getMinY() + getGenDepth() - 1, accessor.getMaxY());
        BlockState[] blocks = new BlockState[Math.max(0, maxY - minY + 1)];
        RealmTerrain.Column column = terrain.column(x, z);
        for (int i = 0; i < blocks.length; i++) { blocks[i] = state(terrain.sample(column, minY + i), minY + i); }
        return new NoiseColumn(minY, blocks);
    }

    @Override
    public void addDebugScreenInfo(List<String> lines, RandomState ignored, BlockPos pos, SamplerContext context) {
        lines.add("Riftwoven v" + RealmTerrain.VERSION + " " + profile().topology() + " radius=" + profile().radius());
        lines.add("Habitat: " + terrain.region(pos.getX(), pos.getZ()).family());
    }

    static BlockState state(RealmTerrain.Material material, int y) {
        return (switch (material) {
            case AIR -> Blocks.AIR;
            case WATER -> Blocks.WATER;
            case LAVA -> Blocks.LAVA;
            case BEDROCK -> Blocks.BEDROCK;
            case STONE -> Blocks.STONE;
            case DEEPSLATE -> Blocks.DEEPSLATE;
            case TUFF -> Blocks.TUFF;
            case CALCITE -> Blocks.CALCITE;
            case GRANITE -> Blocks.GRANITE;
            case DIRT -> Blocks.DIRT;
            case GRASS -> Blocks.GRASS_BLOCK;
            case SAND -> Blocks.SAND;
            case SANDSTONE -> Blocks.SANDSTONE;
            case SNOW -> Blocks.SNOW_BLOCK;
            case MUD -> Blocks.MUD;
            case MOSS -> Blocks.MOSS_BLOCK;
            case GRAVEL -> Blocks.GRAVEL;
            case COAL -> y < 0 ? Blocks.DEEPSLATE_COAL_ORE : Blocks.COAL_ORE;
            case IRON -> y < 0 ? Blocks.DEEPSLATE_IRON_ORE : Blocks.IRON_ORE;
            case COPPER -> y < 0 ? Blocks.DEEPSLATE_COPPER_ORE : Blocks.COPPER_ORE;
            case GOLD -> y < 0 ? Blocks.DEEPSLATE_GOLD_ORE : Blocks.GOLD_ORE;
            case REDSTONE -> y < 0 ? Blocks.DEEPSLATE_REDSTONE_ORE : Blocks.REDSTONE_ORE;
            case LAPIS -> y < 0 ? Blocks.DEEPSLATE_LAPIS_ORE : Blocks.LAPIS_ORE;
            case DIAMOND -> y < 0 ? Blocks.DEEPSLATE_DIAMOND_ORE : Blocks.DIAMOND_ORE;
            case AMETHYST -> Blocks.AMETHYST_BLOCK;
            case BRICKS -> Blocks.STONE_BRICKS;
            case MOSSY_BRICKS -> Blocks.MOSSY_STONE_BRICKS;
            case CRACKED_BRICKS -> Blocks.CRACKED_STONE_BRICKS;
        }).defaultBlockState();
    }
}
