package qouteall.imm_ptl.peripheral.mixin.common.riftwoven;

import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.clock.ServerClockManager;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.saveddata.WeatherData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.peripheral.riftwoven.environment.RealmEnvironment;
import qouteall.imm_ptl.peripheral.riftwoven.environment.RealmEnvironmentAccess;
import qouteall.imm_ptl.peripheral.riftwoven.environment.RealmEnvironmentState;

@Mixin(ServerLevel.class)
public abstract class RealmServerLevelMixin implements RealmEnvironmentAccess {
    @Unique private RealmEnvironmentState riftwoven$state;
    @Unique private ServerClockManager riftwoven$clocks;
    @Unique private WeatherData riftwoven$weather;

    @Override
    public RealmEnvironmentState riftwoven$environment() {
        if (riftwoven$state == null) {
            riftwoven$state = ((ServerLevel) (Object) this).getDataStorage().computeIfAbsent(RealmEnvironmentState.TYPE);
        }
        return riftwoven$state;
    }

    @Inject(method = "clockManager()Lnet/minecraft/world/clock/ServerClockManager;", at = @At("HEAD"), cancellable = true)
    private void riftwoven$clocks(CallbackInfoReturnable<ServerClockManager> ci) {
        var level = (ServerLevel) (Object) this;
        if (RealmEnvironment.isRealm(level)) {
            if (riftwoven$clocks == null) { riftwoven$clocks = RealmEnvironment.createClocks(level); }
            ci.setReturnValue(riftwoven$clocks);
        }
    }

    @Inject(method = "getWeatherData", at = @At("HEAD"), cancellable = true)
    private void riftwoven$weather(CallbackInfoReturnable<WeatherData> ci) {
        var level = (ServerLevel) (Object) this;
        if (RealmEnvironment.isRealm(level)) {
            if (riftwoven$weather == null) { riftwoven$weather = level.getDataStorage().computeIfAbsent(WeatherData.TYPE); }
            ci.setReturnValue(riftwoven$weather);
        }
    }

    @Redirect(method = "<init>", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/MinecraftServer;getWeatherData()Lnet/minecraft/world/level/saveddata/WeatherData;"))
    private WeatherData riftwoven$initialWeather(net.minecraft.server.MinecraftServer server) {
        var level = (ServerLevel) (Object) this;
        if (!RealmEnvironment.isRealm(level)) { return server.getWeatherData(); }
        RealmEnvironment.prepareWeather(level);
        return level.getWeatherData();
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void riftwoven$advance(java.util.function.BooleanSupplier haveTime, CallbackInfo ci) {
        var level = (ServerLevel) (Object) this;
        if (RealmEnvironment.isRealm(level)) { RealmEnvironment.advance(level); }
    }

    @Inject(method = "advanceWeatherCycle", at = @At("HEAD"))
    private void riftwoven$chooseWeather(CallbackInfo ci) {
        var level = (ServerLevel) (Object) this;
        if (RealmEnvironment.isRealm(level)) { RealmEnvironment.prepareWeather(level); }
    }

    // Vanilla (and NeoForge's sleep adjustment) targets the server-global manager. A realm
    // handles its dawn below instead; normal dimensions retain the original operation/hooks.
    @Redirect(method = "tick", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/dimension/DimensionType;defaultClock()Ljava/util/Optional;"))
    private Optional<Holder<WorldClock>> riftwoven$localSleep(DimensionType type) {
        return RealmEnvironment.isRealm((ServerLevel) (Object) this) ? Optional.empty() : type.defaultClock();
    }

    @Inject(method = "wakeUpAllPlayers", at = @At("HEAD"))
    private void riftwoven$wakeAtDawn(CallbackInfo ci) {
        var level = (ServerLevel) (Object) this;
        if (RealmEnvironment.isRealm(level)) {
            riftwoven$environment().sleep(RealmEnvironment.generator(level).profile().cycleTicks(),
                level.getGameRules().get(GameRules.ADVANCE_TIME), false);
            RealmEnvironment.updateClocks(level, level.clockManager());
            level.environmentAttributes().invalidateTickCache();
            for (var player : level.players()) { RealmEnvironment.send(player, level); }
        }
    }

    @Inject(method = "resetWeatherCycle", at = @At("HEAD"))
    private void riftwoven$clearAfterSleep(CallbackInfo ci) {
        var level = (ServerLevel) (Object) this;
        if (RealmEnvironment.isRealm(level)) {
            riftwoven$environment().sleep(RealmEnvironment.generator(level).profile().cycleTicks(), false, true);
        }
    }
}
