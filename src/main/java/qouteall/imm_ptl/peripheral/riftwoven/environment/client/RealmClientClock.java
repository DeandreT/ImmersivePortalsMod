package qouteall.imm_ptl.peripheral.riftwoven.environment.client;

import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.client.ClientClockManager;
import net.minecraft.core.Holder;
import net.minecraft.world.clock.ClockNetworkState;
import net.minecraft.world.clock.WorldClock;
import qouteall.imm_ptl.peripheral.riftwoven.environment.CelestialLayout;
import qouteall.imm_ptl.peripheral.riftwoven.environment.RealmEnvironmentMath;

/** A level-owned clock. Vanilla dimensions still delegate to the connection's vanilla clocks. */
public final class RealmClientClock extends ClientClockManager {
    private final Supplier<ClientClockManager> vanilla;
    private final Optional<Holder<WorldClock>> defaultClock;
    private CelestialLayout layout;
    private long physicalTicks;
    private long lastGameTime;
    private int cycle;
    private int daylight;
    private boolean advancing;

    public RealmClientClock(Supplier<ClientClockManager> vanilla, Optional<Holder<WorldClock>> defaultClock) {
        this.vanilla = vanilla;
        this.defaultClock = defaultClock;
    }

    public CelestialLayout layout() { return layout == null ? CelestialLayout.VANILLA : layout; }
    public boolean isRealm() { return layout != null; }

    public void synchronize(long gameTime, long physicalTicks, int cycle, int daylight,
        CelestialLayout layout, boolean advancing) {
        RealmEnvironmentMath.clockTime(physicalTicks, cycle, daylight); // validate before changing state
        this.layout = layout;
        this.physicalTicks = physicalTicks;
        this.lastGameTime = gameTime;
        this.cycle = cycle;
        this.daylight = daylight;
        this.advancing = advancing;
        updateLocal();
    }

    @Override
    public ClientClockInstance getInstance(Holder<WorldClock> definition) {
        return isRealm() && defaultClock.filter(definition::equals).isPresent()
            ? super.getInstance(definition) : vanilla.get().getInstance(definition);
    }

    @Override
    public void tick(long gameTime) {
        // Preserve the baseline connection clock's game-time anchor, including while living
        // in a realm. Other portal client worlds may call this with the same game tick.
        vanilla.get().tick(gameTime);
        if (isRealm()) {
            if (advancing) { physicalTicks += Math.max(0, gameTime - lastGameTime); }
            lastGameTime = gameTime;
            updateLocal();
        }
    }

    @Override
    public void handleUpdates(long gameTime, Map<Holder<WorldClock>, ClockNetworkState> updates) {
        // Redirected vanilla realm packets must not contaminate the connection-global clock.
        // Realm physical ticks arrive through the explicit dimension-addressed RPC instead.
        if (!isRealm()) { vanilla.get().handleUpdates(gameTime, updates); }
    }

    private void updateLocal() {
        defaultClock.ifPresent(definition -> {
            double value = RealmEnvironmentMath.clockTime(physicalTicks, cycle, daylight);
            long whole = (long) Math.floor(value);
            float rate = advancing ? RealmEnvironmentMath.clockRate(physicalTicks, cycle, daylight) : 0;
            // super.handleUpdates invokes tick virtually; writing via a small transient manager
            // would lose the ClockInstance identity, so load fields through the accessor mixin.
            var instance = (RealmClientClockInstanceAccess) super.getInstance(definition);
            instance.riftwoven$set(whole, (float) (value - whole), rate);
        });
    }
}
