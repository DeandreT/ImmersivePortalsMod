package qouteall.dimlib.mixin.common;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.CustomSpawner;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.ServerLevelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.dimlib.api.IndependentDimensionSeed;

import java.util.List;
import java.util.concurrent.Executor;

@Mixin(ServerLevel.class)
public abstract class MixinServerLevel_IndependentSeed {
    @Unique private Long dimlib_independentSeed;

    @ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private static long dimlib_biomeSeed(long original, MinecraftServer server, Executor executor,
        LevelStorageSource.LevelStorageAccess storage, ServerLevelData data, ResourceKey<Level> key,
        LevelStem stem, boolean debug, long zoomSeed, List<CustomSpawner> spawners, boolean tickTime) {
        return stem.generator() instanceof IndependentDimensionSeed independent
            ? BiomeManager.obfuscateSeed(independent.dimensionSeed()) : original;
    }

    // Set before ServerChunkCache is constructed: its constructor calls level.getSeed().
    @Inject(method = "<init>", at = @At(value = "FIELD",
        target = "Lnet/minecraft/server/level/ServerLevel;serverLevelData:Lnet/minecraft/world/level/storage/ServerLevelData;",
        opcode = 181, shift = At.Shift.AFTER))
    private void dimlib_captureSeed(MinecraftServer server, Executor executor,
        LevelStorageSource.LevelStorageAccess storage, ServerLevelData data, ResourceKey<Level> key,
        LevelStem stem, boolean debug, long zoomSeed, List<CustomSpawner> spawners, boolean tickTime,
        CallbackInfo ci) {
        if (stem.generator() instanceof IndependentDimensionSeed independent) {
            dimlib_independentSeed = independent.dimensionSeed();
        }
    }

    @Inject(method = "getSeed", at = @At("HEAD"), cancellable = true)
    private void dimlib_levelSeed(CallbackInfoReturnable<Long> cir) {
        if (dimlib_independentSeed != null) {
            cir.setReturnValue(dimlib_independentSeed);
        }
    }

    @ModifyArg(method = "<init>", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/levelgen/structure/StructureCheck;<init>(Lnet/minecraft/world/level/chunk/storage/ChunkScanAccess;Lnet/minecraft/core/RegistryAccess;Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplateManager;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/LevelHeightAccessor;Lnet/minecraft/world/level/biome/BiomeSource;JLcom/mojang/datafixers/DataFixer;)V"), index = 8)
    private long dimlib_structureSeed(long original) {
        return dimlib_independentSeed == null ? original : dimlib_independentSeed;
    }
}
