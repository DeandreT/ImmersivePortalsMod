package qouteall.imm_ptl.peripheral.riftwoven;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.nbt.CompoundTag;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.UUID;

/** A private, consented doorway. Ordinary portals keep all their existing behavior. */
public final class RealmPortal extends Portal {
    private UUID realmId;
    private boolean returning;
    private int lifetime = 1200;

    public RealmPortal(EntityType<?> type, Level level) { super(type, level); }

    public UUID realmId() { return realmId; }

    public void configure(UUID realm, UUID viewer, boolean returning) {
        realmId = realm;
        specificPlayerId = viewer;
        this.returning = returning;
        setInteractable(false);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag input) {
        super.readAdditionalSaveData(input);
        try { realmId = UUID.fromString(input.getStringOr("riftwoven_realm", "")); }
        catch (IllegalArgumentException ignored) { realmId = null; }
        returning = input.getBooleanOr("riftwoven_return", false);
        lifetime = Math.max(0, Math.min(1200, input.getIntOr("riftwoven_lifetime", 0)));
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag output) {
        super.addAdditionalSaveData(output);
        if (realmId != null) output.putString("riftwoven_realm", realmId.toString());
        output.putBoolean("riftwoven_return", returning);
        output.putInt("riftwoven_lifetime", lifetime);
    }

    @Override
    public boolean canTeleportEntity(Entity entity) {
        if (!(entity instanceof net.minecraft.world.entity.player.Player) || entity.isPassenger() || entity.isVehicle()) return false;
        if (!super.canTeleportEntity(entity)) return false;
        return !(entity instanceof ServerPlayer player)
            || Riftwoven.portalAdmission(player, realmId, returning);
    }

    @Override
    public boolean broadcastToPlayer(ServerPlayer player) {
        return super.broadcastToPlayer(player) && (returning
            ? Riftwoven.portalAdmission(player, realmId, true) : Riftwoven.portalViewAllowed(player, realmId));
    }

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide()) {
            if (--lifetime <= 0 || realmId == null || specificPlayerId == null) {
                discard();
                return;
            }
            ServerPlayer viewer = level().getServer().getPlayerList().getPlayer(specificPlayerId);
            if (viewer != null) {
                boolean admitted = Riftwoven.portalAdmission(viewer, realmId, returning);
                if (getTeleportable() != admitted) {
                    setTeleportable(admitted);
                    reloadAndSyncToClient();
                }
            }
        }
    }
}
