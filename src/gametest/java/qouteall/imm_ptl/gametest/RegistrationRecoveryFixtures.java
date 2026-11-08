package qouteall.imm_ptl.gametest;

import net.fabricmc.fabric.api.event.lifecycle.v1.EntityLoadData;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.impl.event.lifecycle.EntityLoadDataSetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import qouteall.imm_ptl.core.ducks.IEEntity;
import qouteall.imm_ptl.core.mixin.common.entity_sync.registration.RegistrationEntityAccessor;
import qouteall.imm_ptl.core.platform_shared.EntityRegistration;
import qouteall.imm_ptl.core.platform_shared.PlatformHooks;

import java.util.concurrent.atomic.AtomicBoolean;

/** Direct facade regressions against real loader metadata, callbacks and entity managers. */
final class RegistrationRecoveryFixtures {
    static void run(ServerLevel source, ServerLevel destination) {
        preexistingCandidateIsNeverBorrowed(source, destination, false);
        preexistingCandidateIsNeverBorrowed(source, destination, true);
        compensationPreservesVillagerBrain(source, destination);
        cancelledReuseRestoresFabricLoadMetadata(source, destination);
    }

    private static void preexistingCandidateIsNeverBorrowed(
        ServerLevel source, ServerLevel destination, boolean hidden
    ) {
        Entity original = createStand(source);
        Entity foreign = createStand(destination);
        foreign.setId(original.getId());
        foreign.setUUID(original.getUUID());
        if (hidden) { foreign.setPos(16032, 100, 16032); }
        check(source.addFreshEntity(original), "Cannot register the original facade fixture");
        check(destination.addFreshEntity(foreign), "Cannot register the preexisting facade candidate");
        try {
            checkRegistered(destination, foreign, "Preexisting candidate is not registered");
            if (hidden) {
                check(destination.getEntity(foreign.getUUID()) == null,
                    "The preexisting-candidate fixture must exercise a hidden section");
            }
            EntityInLevelCallback originalCallback = callback(original);
            EntityInLevelCallback foreignCallback = callback(foreign);
            EntityRegistration.Snapshot capture = EntityRegistration.capture(original);
            try {
                boolean rejected = false;
                try {
                    EntityRegistration.beginAttempt(capture, original, foreign, destination);
                }
                catch (IllegalArgumentException expected) { rejected = true; }
                check(rejected, "The facade borrowed a preexisting registered candidate");
                check(callback(original) == originalCallback && callback(foreign) == foreignCallback,
                    "Candidate rejection changed a preexisting callback");
                checkRegistered(source, original, "Candidate rejection changed the source original");
                checkRegistered(destination, foreign, "Candidate rejection changed the preexisting object");
            }
            finally { restoreOrFail(capture); }
            check(callback(original) == originalCallback && callback(foreign) == foreignCallback,
                "Snapshot recovery removed or replaced a preexisting callback");
            checkRegistered(source, original, "Recovery changed the untouched source original");
            checkRegistered(destination, foreign, "Recovery removed the preexisting candidate");
            // A refused borrow must not grant or leak an ownership lease on the foreign object.
            restoreOrFail(EntityRegistration.capture(foreign));
            restoreOrFail(EntityRegistration.capture(original));
        }
        finally {
            foreign.discard();
            original.discard();
        }
    }

    private static void compensationPreservesVillagerBrain(ServerLevel source, ServerLevel destination) {
        Villager original = EntityTypes.VILLAGER.create(source, EntitySpawnReason.COMMAND);
        check(original != null, "Cannot create the villager-brain facade fixture");
        original.setPos(8, 100, 8);
        check(source.addFreshEntity(original), "Cannot register the villager-brain facade fixture");
        GlobalPos home = GlobalPos.of(source.dimension(), new BlockPos(8, 64, 8));
        original.getBrain().setMemory(MemoryModuleType.HOME, home);
        try {
            EntityRegistration.Snapshot capture = EntityRegistration.capture(original);
            try {
                EntityRegistration.Attempt attempt = EntityRegistration.beginAttempt(capture, original, original, destination);
                prepareReuse(original, destination);
                check(home.equals(original.getBrain().getMemory(MemoryModuleType.HOME).orElse(null)),
                    "Source detachment erased the villager HOME memory");
                check(EntityRegistration.admitNormally(attempt), "Cannot admit the villager-brain candidate");
                EntityRegistration.RecoveryResult removed = EntityRegistration.removeAttempt(attempt);
                check(removed.complete(), "Villager compensation failed: " + removed.failures());
                check(home.equals(original.getBrain().getMemory(MemoryModuleType.HOME).orElse(null)),
                    "Destination compensation erased the original villager HOME memory");
                check(EntityRegistration.inspect(destination, original) == EntityRegistration.RegistrationState.ABSENT,
                    "Compensation retained the original villager at the destination");
            }
            finally { restoreOrFail(capture); }
            checkRegistered(source, original, "Villager source registration was not restored");
            check(home.equals(original.getBrain().getMemory(MemoryModuleType.HOME).orElse(null)),
                "Source restoration changed the original villager HOME memory");
        }
        finally { original.discard(); }
    }

    private static void cancelledReuseRestoresFabricLoadMetadata(ServerLevel source, ServerLevel destination) {
        Entity original = createStand(source);
        check(source.addFreshEntity(original), "Cannot register the Fabric metadata fixture");
        ((EntityLoadDataSetter) original).fabric_setLoadedFromDisk(true);
        check(((EntityLoadData) original).isLoadedFromDisk() && PlatformHooks.registrationMetadataFlag(original),
            "The fixture did not capture actual Fabric loaded-from-disk metadata");
        AtomicBoolean active = new AtomicBoolean(true);
        AtomicBoolean sawResetBeforeCancellation = new AtomicBoolean(false);
        ServerEntityEvents.ALLOW_LOAD.register((entity, world, reason, deserializing) -> {
            if (!active.get() || entity != original) { return true; }
            if (world == destination) {
                sawResetBeforeCancellation.set(!((EntityLoadData) entity).isLoadedFromDisk() && !deserializing);
                return false;
            }
            // A source repair must not depend on a second cancellable normal admission.
            return world != source;
        });
        try {
            EntityRegistration.Snapshot capture = EntityRegistration.capture(original);
            try {
                EntityRegistration.Attempt attempt = EntityRegistration.beginAttempt(capture, original, original, destination);
                prepareReuse(original, destination);
                check(!EntityRegistration.admitNormally(attempt), "The Fabric metadata admission was not cancelled");
                check(sawResetBeforeCancellation.get() && !((EntityLoadData) original).isLoadedFromDisk(),
                    "The real Fabric gate did not reset loaded-from-disk before refusing reuse");
            }
            finally { restoreOrFail(capture); }
            checkRegistered(source, original, "The cancelled Fabric candidate was not restored");
            check(((EntityLoadData) original).isLoadedFromDisk() && PlatformHooks.registrationMetadataFlag(original),
                "Recovery did not restore the captured Fabric load metadata");
            check(EntityRegistration.inspect(destination, original) == EntityRegistration.RegistrationState.ABSENT,
                "The cancelled Fabric candidate remains at the destination");
            restoreOrFail(EntityRegistration.capture(original));
            check(((EntityLoadData) original).isLoadedFromDisk(), "A fresh capture changed the recovered Fabric metadata");
        }
        finally {
            active.set(false);
            original.discard();
        }
    }

    private static Entity createStand(ServerLevel world) {
        Entity result = EntityTypes.ARMOR_STAND.create(world, EntitySpawnReason.COMMAND);
        check(result != null, "Cannot create a facade registration fixture");
        result.setPos(8, 100, 8);
        return result;
    }

    private static void prepareReuse(Entity original, ServerLevel destination) {
        original.setRemoved(Entity.RemovalReason.CHANGED_DIMENSION);
        ((IEEntity) original).ip_setWorld(destination);
        ((IEEntity) original).ip_unsetRemoved();
        original.setPos(8, 120, 8);
    }

    private static EntityInLevelCallback callback(Entity entity) {
        return ((RegistrationEntityAccessor) entity).ip_registrationCallback();
    }

    private static void checkRegistered(ServerLevel level, Entity entity, String message) {
        check(!entity.isRemoved() && entity.level() == level
            && EntityRegistration.inspect(level, entity) == EntityRegistration.RegistrationState.REGISTERED, message);
    }

    private static void restoreOrFail(EntityRegistration.Snapshot capture) {
        EntityRegistration.RecoveryResult recovered = EntityRegistration.restore(capture);
        check(recovered.complete(), "Facade snapshot recovery failed: " + recovered.failures());
    }

    private static void check(boolean condition, String message) {
        if (!condition) { throw new AssertionError(message); }
    }

    private RegistrationRecoveryFixtures() {}
}
