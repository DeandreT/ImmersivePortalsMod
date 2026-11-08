package qouteall.imm_ptl.core.platform_shared;

import com.google.common.collect.ImmutableList;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.core.SectionPos;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.LevelCallback;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.entity.Visibility;
import net.minecraft.world.level.gameevent.DynamicGameEventListener;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.waypoints.WaypointTransmitter;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.ducks.IEChunkMap;
import qouteall.imm_ptl.core.ducks.IEEntity;
import qouteall.imm_ptl.core.ducks.IEServerWorld;
import qouteall.imm_ptl.core.ducks.IETrackedEntity;
import qouteall.imm_ptl.core.network.PacketRedirection;
import qouteall.imm_ptl.core.mixin.common.entity_sync.registration.RegistrationCallbackAccessor;
import qouteall.imm_ptl.core.mixin.common.entity_sync.registration.RegistrationCallbackConstructor;
import qouteall.imm_ptl.core.mixin.common.entity_sync.registration.RegistrationChunkMapAccessor;
import qouteall.imm_ptl.core.mixin.common.entity_sync.registration.RegistrationEntityAccessor;
import qouteall.imm_ptl.core.mixin.common.entity_sync.registration.RegistrationGameModeAccessor;
import qouteall.imm_ptl.core.mixin.common.entity_sync.registration.RegistrationLevelAccessor;
import qouteall.imm_ptl.core.mixin.common.entity_sync.registration.RegistrationLookupAccessor;
import qouteall.imm_ptl.core.mixin.common.entity_sync.registration.RegistrationManagerAccessor;
import qouteall.imm_ptl.core.mixin.common.entity_sync.registration.RegistrationMultiMapAccessor;
import qouteall.imm_ptl.core.mixin.common.entity_sync.registration.RegistrationSectionAccessor;
import qouteall.imm_ptl.core.mixin.common.entity_sync.registration.RegistrationSectionStorageAccessor;
import qouteall.imm_ptl.core.mixin.common.entity_sync.registration.RegistrationTickListAccessor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Pinned 26.3 registration compensation for one captured, server-thread-owned vehicle tree.
 * Destination admission is never bypassed. Only captured originals can be repaired in their
 * captured source; this is deliberately not a general force-add API.
 * Every captured transaction must be sealed by commit or restore, including pure preflight
 * refusals. Recovery verifies the base registration and captured state, not arbitrary external
 * callback side effects. Compensation exceptions remain failures even when structural cleanup
 * can be finished; callers must distinguish that result from an ordinary refused transfer.
 */
public final class EntityRegistration {
    public enum RegistrationState { ABSENT, REGISTERED, PARTIAL, CONFLICT }

    public record RecoveryResult(boolean complete, List<String> failures) {
        public RecoveryResult {
            failures = List.copyOf(failures);
            if (complete && !failures.isEmpty()) {
                throw new IllegalArgumentException("Complete recovery cannot contain failures");
            }
        }
    }

    private enum Phase { ACTIVE, RESTORING, RESTORED, RECOVERY_FAILED, COMMITTED }

    public static final class Snapshot {
        private final MinecraftServer server;
        private final Thread thread;
        private final ServerLevel source;
        private final List<Entity> originals;
        private final IdentityHashMap<Entity, SavedEntity> saved = new IdentityHashMap<>();
        private final List<Attempt> attempts = new ArrayList<>();
        private Phase phase = Phase.ACTIVE;

        private Snapshot(ServerLevel source, List<Entity> originals) {
            this.server = source.getServer();
            this.thread = Thread.currentThread();
            this.source = source;
            this.originals = List.copyOf(originals);
            for (Entity entity : originals) { saved.put(entity, new SavedEntity(entity)); }
        }

        public static Snapshot capture(Entity member) { return EntityRegistration.capture(member); }
        public List<Entity> originals() { checkThread(this); return originals; }
        public ServerLevel source() { checkThread(this); return source; }
    }

    public static final class Attempt {
        private final Snapshot snapshot;
        private final SavedEntity original;
        private final Entity candidate;
        private final ServerLevel destination;
        private final boolean initialConflict;
        private boolean started;
        private boolean reserved;
        private @Nullable UUID reservationUuid;
        private final Set<UUID> foreignReservations = new HashSet<>();
        private boolean cleaning;
        private @Nullable RecoveryResult cleanup;

        private Attempt(Snapshot snapshot, SavedEntity original, Entity candidate, ServerLevel destination) {
            this.snapshot = snapshot;
            this.original = original;
            this.candidate = candidate;
            this.destination = destination;
            this.initialConflict = new View(destination, candidate, original.id, original.uuid).conflict;
        }
    }

    private static final ThreadLocal<Attempt> ADMISSION = new ThreadLocal<>();
    private record CleanupScope(ServerLevel world, Entity entity, Set<UUID> foreignReservations) {}
    private static final ThreadLocal<CleanupScope> CLEANUP = new ThreadLocal<>();
    private static final ThreadLocal<Snapshot> RESTORATION = new ThreadLocal<>();
    private static final Map<Entity, Snapshot> OWNERS = Collections.synchronizedMap(new IdentityHashMap<>());

    /** Only a token-owned compensation may opt out of source-transfer tracking suppression. */
    public static boolean isCompensatingRemoval(ServerLevel level, Entity entity) {
        CleanupScope scope = CLEANUP.get();
        return scope != null && scope.world == level && scope.entity == entity && level.getServer().isSameThread();
    }

    /** Capture the root and the entire ordered tree, before any member is dismounted. */
    public static Snapshot capture(Entity member) {
        Objects.requireNonNull(member, "member");
        if (ADMISSION.get() != null || CLEANUP.get() != null || RESTORATION.get() != null) {
            throw new IllegalStateException("Registration transactions cannot be captured reentrantly");
        }
        if (!(member.level() instanceof ServerLevel source)) {
            throw new IllegalArgumentException("Only server entities can be captured");
        }
        checkServerThread(source);
        Set<Entity> ancestors = identitySet();
        Entity root = member;
        while (root.getVehicle() != null) {
            if (!ancestors.add(root)) { throw new IllegalArgumentException("Cyclic vehicle tree"); }
            root = root.getVehicle();
        }
        List<Entity> originals = new ArrayList<>();
        Set<Entity> descendants = identitySet();
        collect(root, null, source, descendants, originals);
        if (!descendants.contains(member)) { throw new IllegalArgumentException("Member has a nonreciprocal vehicle link"); }
        Snapshot snapshot = new Snapshot(source, originals);
        for (Entity entity : originals) {
            if (inspect(source, entity) != RegistrationState.REGISTERED) {
                throw new IllegalStateException("Cannot capture an incompletely registered entity: " + entity.getUUID());
            }
            if (!snapshot.saved.get(entity).matchesGlobalPlayer()) {
                throw new IllegalStateException("Player global membership changed during capture");
            }
        }
        synchronized (OWNERS) {
            for (Entity entity : originals) {
                if (OWNERS.containsKey(entity)) { throw new IllegalStateException("Original belongs to another active capture"); }
            }
            for (Entity entity : originals) { OWNERS.put(entity, snapshot); }
        }
        return snapshot;
    }

    private static void collect(Entity entity, @Nullable Entity parent, ServerLevel source,
        Set<Entity> seen, List<Entity> result) {
        if (!seen.add(entity) || entity.getVehicle() != parent || entity.level() != source || entity.isRemoved()) {
            throw new IllegalArgumentException("Vehicle tree is not a consistent source tree");
        }
        result.add(entity);
        for (Entity child : entity.getPassengers()) { collect(child, entity, source, seen, result); }
    }

    /** Journal before calling admission, including the candidate whose callback might throw. */
    public static Attempt beginAttempt(Snapshot snapshot, Entity original, Entity candidate, ServerLevel destination) {
        checkActive(snapshot);
        for (Entity member : snapshot.originals) {
            SavedEntity captured = snapshot.saved.get(member);
            if (!identityMatches(member, captured) || inspect(snapshot.source, member) != RegistrationState.REGISTERED
                || member.getVehicle() != captured.vehicle || !sameOrdered(member.getPassengers(), captured.passengers)
                || !captured.matchesGlobalPlayer()) {
                throw new IllegalStateException("Captured tree changed before the destination journal was complete");
            }
        }
        SavedEntity saved = snapshot.saved.get(original);
        if (saved == null) { throw new IllegalArgumentException("Original does not belong to this snapshot"); }
        if (destination == snapshot.source || destination.getServer() != snapshot.server
            || snapshot.server.getLevel(destination.dimension()) != destination) {
            throw new IllegalArgumentException("Destination must be another live level of the captured server");
        }
        if (candidate.getId() != saved.id || !candidate.getUUID().equals(saved.uuid)
            || candidate instanceof ServerPlayer && candidate != original
            || snapshot.saved.containsKey(candidate) && candidate != original) {
            throw new IllegalArgumentException("Candidate does not match the captured original identity");
        }
        // A replacement is borrowed only if it is unregistered. A matching ID/UUID
        // does not grant this transaction ownership of an existing object.
        if (candidate != original
            && ((RegistrationEntityAccessor) candidate).ip_registrationCallback() != EntityInLevelCallback.NULL) {
            throw new IllegalArgumentException("Replacement already has a level callback");
        }
        for (ServerLevel level : snapshot.server.getAllLevels()) {
            if (candidate == original && level == snapshot.source) { continue; }
            if (new View(level, candidate, saved.id, saved.uuid).hasIdentity()) {
                throw new IllegalArgumentException("Candidate was already registered before this attempt");
            }
        }
        for (Attempt previous : snapshot.attempts) {
            if (previous.original.entity == original || previous.candidate == candidate) {
                throw new IllegalStateException("An original or candidate cannot be attempted twice");
            }
        }
        synchronized (OWNERS) {
            Snapshot owner = OWNERS.get(candidate);
            if (owner != null && owner != snapshot) {
                throw new IllegalArgumentException("Candidate belongs to another captured transaction");
            }
            boolean newlyClaimed = owner == null;
            OWNERS.put(candidate, snapshot);
            try {
                Attempt result = new Attempt(snapshot, saved, candidate, destination);
                snapshot.attempts.add(result);
                return result;
            }
            catch (RuntimeException | Error exception) {
                if (newlyClaimed) { OWNERS.remove(candidate); }
                throw exception;
            }
        }
    }

    /**
     * Use the loader's normal cancellable destination gate, also for ServerPlayer.
     * The controller must check canTravelToDimension for every original before source detachment.
     * Runtime callback exceptions intentionally propagate to that controller.
     */
    public static boolean admitNormally(Attempt attempt) {
        checkActive(attempt.snapshot);
        if (attempt.snapshot.server.getLevel(attempt.destination.dimension()) != attempt.destination) {
            throw new IllegalStateException("Attempted destination is no longer the live level");
        }
        if (attempt.started || attempt.cleanup != null || ADMISSION.get() != null) {
            throw new IllegalStateException("Admission attempt was replayed or reentered");
        }
        requireIdentity(attempt.candidate, attempt.original);
        attempt.started = true;
        View before = new View(attempt.destination, attempt.candidate, attempt.original.id, attempt.original.uuid);
        if (before.conflict || before.known || before.hasIdentity()) { return false; }
        for (ServerLevel level : attempt.snapshot.server.getAllLevels()) {
            if (level != attempt.destination && new View(level, attempt.candidate,
                attempt.original.id, attempt.original.uuid).hasIdentity()) {
                throw new IllegalStateException("Candidate still belongs to another entity manager");
            }
        }
        if (attempt.candidate instanceof ServerPlayer player) {
            player.setServerLevel(attempt.destination);
        }
        else if (attempt.candidate.level() != attempt.destination) {
            throw new IllegalStateException("Candidate context is not its attempted destination");
        }
        ADMISSION.set(attempt);
        try {
            return PacketRedirection.withForceRedirectAndGet(attempt.destination, () ->
                attempt.destination.addFreshEntity(attempt.candidate)
                    && inspect(attempt.destination, attempt.candidate) == RegistrationState.REGISTERED);
        }
        finally { ADMISSION.remove(); }
    }

    /** Internal mixin journal. Calls outside an exact active admission have no effect. */
    public static void noteReservation(PersistentEntitySectionManager<?> manager, EntityAccess entity) {
        Attempt attempt = ADMISSION.get();
        if (attempt != null && manager == manager(attempt.destination)) {
            if (entity == attempt.candidate) {
                attempt.reserved = true;
                attempt.reservationUuid = attempt.candidate.getUUID();
                requireIdentity(attempt.candidate, attempt.original);
            }
            else { attempt.foreignReservations.add(entity.getUUID()); }
        }
        CleanupScope cleanup = CLEANUP.get();
        if (cleanup != null && manager == manager(cleanup.world) && entity != cleanup.entity) {
            cleanup.foreignReservations.add(entity.getUUID());
        }
    }

    public static RegistrationState inspect(ServerLevel level, Entity entity) {
        checkServerThread(level);
        return new View(level, entity, entity.getId(), entity.getUUID()).state();
    }

    /** Remove only this attempted identity, continuing known cleanup after interrupted callbacks. */
    public static RecoveryResult removeAttempt(Attempt attempt) {
        checkThread(attempt.snapshot);
        if (attempt.snapshot.phase != Phase.ACTIVE && attempt.snapshot.phase != Phase.RESTORING) {
            throw new IllegalStateException("Snapshot has been sealed");
        }
        if (attempt.cleanup != null) {
            if ((attempt.snapshot.phase == Phase.ACTIVE || attempt.snapshot.phase == Phase.RESTORING)
                && !attemptAbsent(attempt)) {
                List<String> repeated = new ArrayList<>(attempt.cleanup.failures());
                repeated.add("Attempt identity or reservation reappeared after cleanup");
                attempt.cleanup = new RecoveryResult(false, repeated);
            }
            return attempt.cleanup;
        }
        if (ADMISSION.get() != null || CLEANUP.get() != null) {
            throw new IllegalStateException("Cleanup cannot interrupt an active admission or another removal");
        }
        if (attempt.cleaning) { throw new IllegalStateException("Reentrant attempt cleanup"); }
        List<String> failures = new ArrayList<>();
        attempt.cleaning = true;
        try {
        if (!identityMatches(attempt.candidate, attempt.original)) {
            failures.add("Destination candidate ID or UUID changed during admission");
        }
        View before = new View(attempt.destination, attempt.candidate, attempt.original.id, attempt.original.uuid);
        if (before.conflict && !attempt.initialConflict && (attempt.reserved || before.hasIdentity())) {
            failures.add("Destination identity was replaced or conflicted during admission");
        }
        if (before.hasIdentity() || attempt.reserved) {
            removeOwned(attempt.snapshot, attempt.destination, attempt.candidate, attempt.original,
                attempt.reserved || before.ownedCallback, attempt.foreignReservations, failures);
        }
        View after = new View(attempt.destination, attempt.candidate, attempt.original.id, attempt.original.uuid);
        if (!after.identityAbsent()) { failures.add("Attempted destination still retains the exact candidate"); }
        if (attempt.reserved && after.known && !after.foreignUuid) {
            failures.add("Attempted destination still reserves the candidate UUID");
        }
        if (attempt.reservationUuid != null && !attempt.reservationUuid.equals(attempt.original.uuid)
            && managerAccess(attempt.destination).ip_registrationKnownUuids().contains(attempt.reservationUuid)) {
            failures.add("A callback changed the reserved UUID; ownership of that reservation cannot be repaired safely");
        }
        attempt.cleanup = new RecoveryResult(failures.isEmpty(), failures);
        return attempt.cleanup;
        }
        catch (RuntimeException exception) {
            failures.add("Attempt cleanup interrupted: " + exception.getClass().getSimpleName() + ": " + exception.getMessage());
            attempt.cleanup = new RecoveryResult(false, failures);
            return attempt.cleanup;
        }
        finally { attempt.cleaning = false; }
    }

    /** Restore only the captured originals/source. A snapshot is single-use, even on recovery failure. */
    public static RecoveryResult restore(Snapshot snapshot) {
        checkActive(snapshot, false);
        snapshot.phase = Phase.RESTORING;
        RESTORATION.set(snapshot);
        List<String> failures = new ArrayList<>();
        boolean completed = false;
        try {
            for (int i = snapshot.attempts.size() - 1; i >= 0; i--) {
                Attempt attempt = snapshot.attempts.get(i);
                recover(failures, "Destination cleanup", () -> failures.addAll(removeAttempt(attempt).failures()));
            }
            for (Entity entity : snapshot.originals) {
                if (snapshot.server.getLevel(snapshot.source.dimension()) != snapshot.source) {
                    failures.add("Captured source is no longer live; source repair was refused");
                    break;
                }
                recover(failures, "Source registration repair", () -> restoreOriginal(snapshot, snapshot.saved.get(entity), failures));
            }
            recover(failures, "Captured hierarchy repair", () -> restoreHierarchy(snapshot, failures));
            for (Entity entity : snapshot.originals) {
                recover(failures, "Source verification", () -> {
                    if (inspect(snapshot.source, entity) != RegistrationState.REGISTERED) {
                        failures.add("Source registration remains incomplete for " + entity.getUUID());
                    }
                    if (!snapshot.saved.get(entity).matchesGlobalPlayer()) {
                        failures.add("Global player-list identity changed for " + entity.getUUID());
                    }
                    if (!snapshot.saved.get(entity).matchesRestoredState()) {
                        failures.add("Captured entity state was not fully restored for " + entity.getUUID());
                    }
                });
            }
            for (Attempt attempt : snapshot.attempts) {
                recover(failures, "Final destination absence verification", () -> {
                    if (!attemptAbsent(attempt)) {
                        failures.add("Attempt identity or reservation survived compensating callbacks");
                    }
                });
            }
            completed = true;
            return new RecoveryResult(failures.isEmpty(), failures);
        }
        finally {
            snapshot.phase = completed && failures.isEmpty() ? Phase.RESTORED : Phase.RECOVERY_FAILED;
            RESTORATION.remove();
            release(snapshot);
        }
    }

    /** Seal only a fully registered, fully remounted destination tree; notifications follow this. */
    public static void commit(Snapshot snapshot) {
        checkActive(snapshot);
        if (snapshot.attempts.isEmpty()) {
            // In-place portal/command movement changes state, not world admission.
            for (Entity original : snapshot.originals) {
                SavedEntity state = snapshot.saved.get(original);
                requireIdentity(original, state);
                if (inspect(snapshot.source, original) != RegistrationState.REGISTERED
                    || original.getVehicle() != state.vehicle || !sameOrdered(original.getPassengers(), state.passengers)
                    || !state.matchesGlobalPlayer()) {
                    throw new IllegalStateException("Cannot commit an inconsistent in-place vehicle tree");
                }
            }
            snapshot.phase = Phase.COMMITTED;
            release(snapshot);
            return;
        }
        if (snapshot.attempts.size() != snapshot.originals.size()) {
            throw new IllegalStateException("Cannot commit an incomplete destination tree");
        }
        IdentityHashMap<Entity, Entity> mapped = new IdentityHashMap<>();
        ServerLevel destination = snapshot.attempts.getFirst().destination;
        for (Attempt attempt : snapshot.attempts) {
            if (!attempt.started || attempt.cleanup != null || attempt.destination != destination
                || inspect(destination, attempt.candidate) != RegistrationState.REGISTERED) {
                throw new IllegalStateException("Cannot commit incomplete destination registration");
            }
            requireIdentity(attempt.candidate, attempt.original);
            if (!attempt.original.matchesGlobalPlayer()) {
                throw new IllegalStateException("Cannot commit a changed global player-list identity");
            }
            if (new View(snapshot.source, attempt.original.entity, attempt.original.id, attempt.original.uuid).state()
                != RegistrationState.ABSENT) {
                throw new IllegalStateException("Original still registered in the source");
            }
            mapped.put(attempt.original.entity, attempt.candidate);
        }
        for (Entity original : snapshot.originals) {
            SavedEntity state = snapshot.saved.get(original);
            Entity candidate = mapped.get(original);
            if (candidate.getVehicle() != mapped.get(state.vehicle)
                || !sameOrdered(candidate.getPassengers(), mapChildren(state.passengers, mapped))) {
                throw new IllegalStateException("Cannot commit an incomplete destination hierarchy");
            }
        }
        snapshot.phase = Phase.COMMITTED;
        release(snapshot);
    }

    private static void restoreOriginal(Snapshot snapshot, SavedEntity state, List<String> failures) {
        Entity entity = state.entity;
        for (Attempt attempt : snapshot.attempts) {
            if (attempt.candidate == entity && !attemptAbsent(attempt)) {
                failures.add("Cannot change original context before its recorded destination cleanup is verified");
                return;
            }
        }
        for (ServerLevel level : snapshot.server.getAllLevels()) {
            if (level != snapshot.source && !new View(level, entity, state.id, state.uuid).identityAbsent()) {
                failures.add("Cannot change original context before destination cleanup is verified");
                return;
            }
        }
        View current = new View(snapshot.source, entity, state.id, state.uuid);
        if (current.conflict) {
            failures.add("Captured source identity conflicts with another object: " + state.uuid);
            return;
        }
        boolean stable = current.state() == RegistrationState.REGISTERED
            && current.sectionKey == SectionPos.asLong(BlockPos.containing(state.position));
        if (!stable && current.hasIdentity()) {
            removeOwned(snapshot, snapshot.source, entity, state, current.ownedCallback, new HashSet<>(), failures);
        }
        View ready = new View(snapshot.source, entity, state.id, state.uuid);
        if (!stable && (!ready.identityAbsent() || ready.known)) {
            failures.add("Captured source is not empty enough to repair " + state.uuid);
            return;
        }
        state.restoreContext();
        if (!stable) { restoreRegistration(snapshot, state); }
    }

    /**
     * The only admission-bypassing primitive. It is private, source-fixed and requires an
     * unsealed capture containing this exact original. Mirrors pinned vanilla registration,
     * not addEntity(...), which Fabric and NeoForge both make cancellable.
     */
    private static void restoreRegistration(Snapshot snapshot, SavedEntity state) {
        checkThread(snapshot);
        if (snapshot.phase != Phase.RESTORING || snapshot.saved.get(state.entity) != state) {
            throw new IllegalStateException("Missing captured source restoration capability");
        }
        if (snapshot.server.getLevel(snapshot.source.dimension()) != snapshot.source) {
            throw new IllegalStateException("Captured source is no longer the live source level");
        }
        Entity entity = state.entity;
        requireIdentity(entity, state);
        View before = new View(snapshot.source, entity, state.id, state.uuid);
        if (before.conflict || before.hasIdentity() || before.known) {
            throw new IllegalStateException("Source identity was not released for repair");
        }
        RegistrationManagerAccessor access = managerAccess(snapshot.source);
        if (!access.ip_registrationKnownUuids().add(state.uuid)) {
            throw new IllegalStateException("Captured source UUID was reserved during repair");
        }
        long key = SectionPos.asLong(entity.blockPosition());
        EntitySection<Entity> section = access.ip_registrationSections().getOrCreateSection(key);
        section.add(entity);
        entity.setLevelCallback(RegistrationCallbackConstructor.ip_registrationCreate(manager(snapshot.source), entity, key, section));
        LevelCallback<Entity> callbacks = access.ip_registrationCallbacks();
        PacketRedirection.withForceRedirect(snapshot.source, () -> {
        callbacks.onCreated(entity);
        if (new View(snapshot.source, entity, state.id, state.uuid).conflict) {
            throw new IllegalStateException("Source identity conflict introduced by creation callback");
        }
        Visibility visibility = effective(entity, section);
        if (visibility.isAccessible()) {
            access.ip_registrationVisible().add(entity);
            callbacks.onTrackingStart(entity);
        }
        if (visibility.isTicking()) { callbacks.onTickingStart(entity); }
        PlatformHooks.finishRegistrationRestoration(entity, state.metadataFlag);
        });
        if (inspect(snapshot.source, entity) != RegistrationState.REGISTERED) {
            throw new IllegalStateException("Captured source repair did not complete registration");
        }
    }

    private static void removeOwned(Snapshot snapshot, ServerLevel world, Entity entity, SavedEntity identity,
        boolean ownsReservation, Set<UUID> foreignReservations, List<String> failures) {
        checkThread(snapshot);
        if (snapshot.server.getLevel(world.dimension()) != world) {
            failures.add("Attempted level is no longer live; cleanup into a replacement level was refused");
            return;
        }
        CleanupScope previous = CLEANUP.get();
        CLEANUP.set(new CleanupScope(world, entity, foreignReservations));
        try {
        PacketRedirection.withForceRedirect(world, () -> {
        View initial = new View(world, entity, identity.id, identity.uuid);
        if (initial.hasIdentity() && !initial.ownedCallback) {
            failures.add("Exact registered identity lost its expected manager callback");
        }
        boolean detached = detachOwned(snapshot, entity, failures);
        boolean normal = detached && initial.ownedCallback && !initial.conflict
            && entity.level() == world && identityMatches(entity, identity);
        if (normal) {
            // Vanilla dimension transfer uses the final primitive, not LivingEntity.remove,
            // whose override clears brain memories even for CHANGED_DIMENSION.
            recover(failures, "Removal callback", () -> entity.setRemoved(Entity.RemovalReason.CHANGED_DIMENSION));
        }
        View interrupted = new View(world, entity, identity.id, identity.uuid);
        if (interrupted.hasIdentity()) {
            // Do not catch an entire lifecycle callback and pretend it completed. Finish the
            // known base operations individually and retain every compensation exception.
            recover(failures, "Remaining tracking cleanup", () -> finishTrackingRemoval(world, entity, identity, interrupted, failures));
            recover(failures, "Exact section cleanup", () -> removeSectionIdentity(world, entity));
            recover(failures, "Exact tick-list cleanup", () -> {
                RegistrationTickListAccessor ticks = ticks(world);
                ticks.ip_registrationPrepareMutation();
                removeMapIdentity(ticks.ip_registrationActive(), entity);
            });
            recover(failures, "Exact visible lookup cleanup", () -> {
                RegistrationLookupAccessor lookup = lookup(world);
                removeMapIdentity(lookup.ip_registrationById(), entity);
                lookup.ip_registrationByUuid().entrySet().removeIf(entry -> entry.getValue() == entity);
            });
            recover(failures, "Exact callback cleanup", () -> {
                if (callbackOwnedBy(world, entity)) { entity.setLevelCallback(EntityInLevelCallback.NULL); }
            });
        }
        View after = new View(world, entity, identity.id, identity.uuid);
        if (ownsReservation && !after.foreignUuid && !foreignReservations.contains(identity.uuid) && after.identityAbsent()) {
            managerAccess(world).ip_registrationKnownUuids().remove(identity.uuid);
        }
        if (ownsReservation && entity.level() == world) {
            recover(failures, "Native removal lifecycle", () -> PlatformHooks.finishRegistrationRemoval(entity));
        }
        if (!new View(world, entity, identity.id, identity.uuid).identityAbsent()) {
            failures.add("Exact entity remains in manager after cleanup: " + identity.uuid);
        }
        });
        }
        finally {
            if (previous == null) { CLEANUP.remove(); } else { CLEANUP.set(previous); }
        }
    }

    private static void finishTrackingRemoval(ServerLevel world, Entity entity, SavedEntity identity,
        View view, List<String> failures) {
        if (!view.conflict && identityMatches(entity, identity)) {
            if (view.trackerCount != 0) {
                recover(failures, "Chunk tracking removal", () -> world.getChunkSource().removeEntity(entity));
            }
            else if (entity instanceof ServerPlayer player && view.playerMapCount != 0) {
                recover(failures, "Player chunk tracking removal", () ->
                    chunkAccess(world).ip_registrationPlayerStatus(player, false));
            }
        }
        // Any remaining tracker is removed by value identity, never by a possibly reused ID.
        List<ChunkMap.TrackedEntity> ownedTrackers = new ArrayList<>();
        for (ChunkMap.TrackedEntity tracker : trackers(world).values()) {
            if (((IETrackedEntity) tracker).ip_getEntity() == entity) { ownedTrackers.add(tracker); }
        }
        for (ChunkMap.TrackedEntity tracker : ownedTrackers) {
            if (identityMatches(entity, identity) && !view.conflict) {
                recover(failures, "Tracking recipients cleanup", () -> ((IETrackedEntity) tracker).ip_stopTrackingToAllPlayers());
            }
            trackers(world).int2ObjectEntrySet().removeIf(entry -> entry.getValue() == tracker);
        }
        if (entity instanceof ServerPlayer player) {
            removeListIdentity(world.players(), player);
            if (identityCount(chunkAccess(world).ip_registrationPlayerMap().getAllPlayers(), player) != 0) {
                if (view.conflict || !identityMatches(entity, identity)) {
                    failures.add("Cannot safely finish conflicting player distance-manager cleanup");
                }
                else {
                    recover(failures, "Player status cleanup", () -> chunkAccess(world).ip_registrationPlayerStatus(player, false));
                }
            }
            if (!view.conflict && identityMatches(entity, identity)) {
                recover(failures, "Player waypoint cleanup", () -> world.getWaypointManager().removePlayer(player));
            }
            recover(failures, "Sleeping-player accounting", world::updateSleepingPlayerList);
        }
        if (entity instanceof Mob mob) {
            levelAccess(world).ip_registrationNavigatingMobs().removeIf(value -> value == mob);
        }
        for (Entity part : PlatformHooks.registrationParts(entity)) {
            removeMapIdentity(levelAccess(world).ip_registrationParts(), part);
        }
        if (!view.conflict && identityMatches(entity, identity) && entity instanceof WaypointTransmitter waypoint) {
            recover(failures, "Transmitter waypoint cleanup", () -> world.getWaypointManager().untrackWaypoint(waypoint));
        }
        if (entity.level() == world) {
            recover(failures, "Dynamic listener cleanup", () -> entity.updateDynamicGameEventListener(DynamicGameEventListener::remove));
        }
        else { failures.add("Entity context changed before dynamic-listener cleanup"); }
        if (!view.conflict && identityMatches(entity, identity)) {
            recover(failures, "Debug tracking cleanup", () -> world.debugSynchronizers().dropEntity(entity));
        }
    }

    private static void removeSectionIdentity(ServerLevel world, Entity entity) {
        var storage = managerAccess(world).ip_registrationSections();
        Long2ObjectMap<EntitySection<Entity>> sections = sectionMap(world);
        List<Long> empty = new ArrayList<>();
        for (var entry : sections.long2ObjectEntrySet()) {
            EntitySection<Entity> section = entry.getValue();
            if (section.getEntities().noneMatch(value -> value == entity)) { continue; }
            RegistrationSectionAccessor access = (RegistrationSectionAccessor) section;
            RegistrationMultiMapAccessor lists = (RegistrationMultiMapAccessor) access.ip_registrationStorage();
            for (List<Entity> list : lists.ip_registrationLists().values()) { removeListIdentity(list, entity); }
            if (section.isEmpty()) { empty.add(entry.getLongKey()); }
        }
        for (long key : empty) { storage.remove(key); }
    }

    private static boolean detachOwned(Snapshot snapshot, Entity entity, List<String> failures) {
        Entity vehicle = entity.getVehicle();
        if (vehicle != null && !ownedBy(snapshot, vehicle)) {
            failures.add("Candidate acquired a foreign vehicle; its foreign edge was not rewritten");
            return false;
        }
        for (Entity passenger : entity.getPassengers()) {
            if (!ownedBy(snapshot, passenger)) {
                failures.add("Candidate acquired a foreign passenger; its foreign edge was not rewritten");
                return false;
            }
        }
        if (vehicle != null) {
            List<Entity> remaining = new ArrayList<>(vehicle.getPassengers());
            removeListIdentity(remaining, entity);
            ((RegistrationEntityAccessor) vehicle).ip_registrationPassengers(ImmutableList.copyOf(remaining));
        }
        for (Entity passenger : entity.getPassengers()) {
            if (passenger.getVehicle() == entity) { ((RegistrationEntityAccessor) passenger).ip_registrationVehicle(null); }
        }
        ((RegistrationEntityAccessor) entity).ip_registrationVehicle(null);
        ((RegistrationEntityAccessor) entity).ip_registrationPassengers(ImmutableList.of());
        return true;
    }

    private static boolean ownedBy(Snapshot snapshot, Entity entity) {
        if (snapshot.saved.containsKey(entity)) { return true; }
        for (Attempt attempt : snapshot.attempts) { if (attempt.candidate == entity) { return true; } }
        return false;
    }

    private static void restoreHierarchy(Snapshot snapshot, List<String> failures) {
        for (Attempt attempt : snapshot.attempts) {
            if (!snapshot.saved.containsKey(attempt.candidate)) { detachOwned(snapshot, attempt.candidate, failures); }
        }
        for (Entity entity : snapshot.originals) {
            if (inspect(snapshot.source, entity) != RegistrationState.REGISTERED) {
                failures.add("Cannot restore hierarchy before all captured originals are registered");
                return;
            }
            if (entity.getVehicle() != null && !ownedBy(snapshot, entity.getVehicle())) {
                failures.add("Source original acquired a foreign vehicle"); return;
            }
            for (Entity passenger : entity.getPassengers()) {
                if (!ownedBy(snapshot, passenger)) { failures.add("Source original acquired a foreign passenger"); return; }
            }
        }
        for (Entity entity : snapshot.originals) {
            ((RegistrationEntityAccessor) entity).ip_registrationVehicle(null);
            ((RegistrationEntityAccessor) entity).ip_registrationPassengers(ImmutableList.of());
        }
        for (Entity entity : snapshot.originals) {
            SavedEntity state = snapshot.saved.get(entity);
            ((RegistrationEntityAccessor) entity).ip_registrationPassengers(ImmutableList.copyOf(state.passengers));
            ((RegistrationEntityAccessor) entity).ip_registrationVehicle(state.vehicle);
        }
        for (Entity entity : snapshot.originals) {
            SavedEntity state = snapshot.saved.get(entity);
            if (entity.getVehicle() != state.vehicle || !sameOrdered(entity.getPassengers(), state.passengers)) {
                failures.add("Captured ordered hierarchy was not restored");
            }
        }
    }

    private static final class SavedEntity {
        final Entity entity;
        final UUID uuid;
        final int id;
        final Vec3 position;
        final Vec3 velocity;
        final AABB box;
        final float xRot, yRot, xRotO, yRotO, headRot, headRotO, bodyRot, bodyRotO;
        final double xo, yo, zo, xOld, yOld, zOld, fallDistance;
        final int boardingCooldown, portalCooldown;
        final boolean needsSync, syncPosition, syncVelocity;
        final boolean metadataFlag;
        final Pose pose;
        final @Nullable Entity.RemovalReason removal;
        final @Nullable Entity vehicle;
        final List<Entity> passengers;
        final @Nullable SectionPos lastPlayerSection;
        final @Nullable ChunkTrackingView playerTrackingView;
        final boolean globalPlayerMember;
        final ServerLevel level;

        SavedEntity(Entity entity) {
            this.entity = entity;
            this.uuid = entity.getUUID(); this.id = entity.getId();
            this.level = (ServerLevel) entity.level();
            this.position = entity.position(); this.velocity = entity.getDeltaMovement(); this.box = entity.getBoundingBox();
            this.xRot = entity.getXRot(); this.yRot = entity.getYRot();
            this.xRotO = entity.xRotO; this.yRotO = entity.yRotO;
            this.headRot = entity.getYHeadRot();
            this.headRotO = entity instanceof LivingEntity living ? living.yHeadRotO : 0;
            this.bodyRot = entity instanceof LivingEntity living ? living.yBodyRot : 0;
            this.bodyRotO = entity instanceof LivingEntity living ? living.yBodyRotO : 0;
            this.xo = entity.xo; this.yo = entity.yo; this.zo = entity.zo;
            this.xOld = entity.xOld; this.yOld = entity.yOld; this.zOld = entity.zOld;
            this.fallDistance = entity.fallDistance;
            RegistrationEntityAccessor access = (RegistrationEntityAccessor) entity;
            this.boardingCooldown = access.ip_registrationBoardingCooldown();
            this.portalCooldown = access.ip_registrationPortalCooldown();
            this.needsSync = entity.needsSync; this.syncPosition = entity.syncPosition; this.syncVelocity = entity.syncVelocity;
            this.metadataFlag = PlatformHooks.registrationMetadataFlag(entity);
            this.pose = entity.getPose(); this.removal = entity.getRemovalReason();
            this.vehicle = entity.getVehicle(); this.passengers = List.copyOf(entity.getPassengers());
            this.lastPlayerSection = entity instanceof ServerPlayer player ? player.getLastSectionPos() : null;
            this.playerTrackingView = entity instanceof ServerPlayer player ? player.getChunkTrackingView() : null;
            if (entity instanceof ServerPlayer player) {
                var players = level.getServer().getPlayerList();
                int count = identityCount(players.getPlayers(), player);
                ServerPlayer owner = players.getPlayer(uuid);
                if (!(count == 0 && owner == null || count == 1 && owner == player)) {
                    throw new IllegalStateException("Player global UUID/list membership is inconsistent or foreign");
                }
                this.globalPlayerMember = count == 1;
            }
            else { this.globalPlayerMember = false; }
        }

        void restoreContext() {
            try {
            if (entity instanceof ServerPlayer player) { player.setServerLevel(level); }
            else { ((IEEntity) entity).ip_setWorld(level); }
            entity.setId(id); entity.setUUID(uuid);
            ((IEEntity) entity).ip_setPositionWithoutTriggeringCallback(position);
            // The existing callback-free position helper compares doubles with !=;
            // preserve the captured signed-zero representation too.
            ((RegistrationEntityAccessor) entity).ip_registrationExactPosition(position);
            // Keep a detached original marked removed while pose dimensions are
            // repaired; do not force-load a source chunk at its old destination position.
            if (entity.getPose() != pose) { entity.setPose(pose); }
            ((IEEntity) entity).ip_setPositionWithoutTriggeringCallback(position);
            ((RegistrationEntityAccessor) entity).ip_registrationExactPosition(position);
            ((RegistrationEntityAccessor) entity).ip_registrationExactXRot(xRot);
            ((RegistrationEntityAccessor) entity).ip_registrationExactYRot(yRot);
            entity.xRotO = xRotO; entity.yRotO = yRotO;
            if (entity instanceof LivingEntity living) {
                living.yHeadRot = headRot; living.yHeadRotO = headRotO;
                living.yBodyRot = bodyRot; living.yBodyRotO = bodyRotO;
            }
            else { entity.setYHeadRot(headRot); }
            entity.xo = xo; entity.yo = yo; entity.zo = zo;
            entity.xOld = xOld; entity.yOld = yOld; entity.zOld = zOld;
            entity.setDeltaMovement(velocity); entity.setBoundingBox(box); entity.fallDistance = fallDistance;
            ((RegistrationEntityAccessor) entity).ip_registrationBoardingCooldown(boardingCooldown);
            ((RegistrationEntityAccessor) entity).ip_registrationPortalCooldown(portalCooldown);
            entity.needsSync = needsSync; entity.syncPosition = syncPosition; entity.syncVelocity = syncVelocity;
            PlatformHooks.restoreRegistrationMetadataFlag(entity, metadataFlag);
            if (entity instanceof ServerPlayer player) {
                player.setLastSectionPos(Objects.requireNonNull(lastPlayerSection));
                player.setChunkTrackingView(Objects.requireNonNull(playerTrackingView));
            }
            }
            finally { ((RegistrationEntityAccessor) entity).ip_registrationRemovalReason(removal); }
        }

        boolean matchesRestoredState() {
            if (entity.level() != level || !identityMatches(entity, this)
                || entity.getRemovalReason() != removal || entity.getPose() != pose
                || !entity.position().equals(position) || !entity.getDeltaMovement().equals(velocity)
                || !entity.getBoundingBox().equals(box) || entity.getXRot() != xRot || entity.getYRot() != yRot
                || entity.xRotO != xRotO || entity.yRotO != yRotO || entity.getYHeadRot() != headRot
                || entity.xo != xo || entity.yo != yo || entity.zo != zo
                || entity.xOld != xOld || entity.yOld != yOld || entity.zOld != zOld
                || entity.fallDistance != fallDistance || entity.needsSync != needsSync
                || entity.syncPosition != syncPosition || entity.syncVelocity != syncVelocity
                || PlatformHooks.registrationMetadataFlag(entity) != metadataFlag) { return false; }
            RegistrationEntityAccessor access = (RegistrationEntityAccessor) entity;
            if (access.ip_registrationBoardingCooldown() != boardingCooldown
                || access.ip_registrationPortalCooldown() != portalCooldown) { return false; }
            return !(entity instanceof LivingEntity living) || living.yHeadRotO == headRotO
                && living.yBodyRot == bodyRot && living.yBodyRotO == bodyRotO;
        }

        boolean matchesGlobalPlayer() {
            if (!(entity instanceof ServerPlayer player)) { return true; }
            MinecraftServer server = level.getServer();
            return globalPlayerMember ? globalPlayerIdentity(server, player)
                : server.getPlayerList().getPlayer(uuid) == null
                    && identityCount(server.getPlayerList().getPlayers(), player) == 0;
        }
    }

    /** Inspect every section, including HIDDEN sections; lookup/tick contains() are ID-based. */
    private static final class View {
        final ServerLevel level;
        final Entity entity;
        final int id;
        final UUID uuid;
        final boolean known;
        final boolean ownedCallback;
        boolean conflict;
        boolean foreignUuid;
        int sectionCount, lookupIdCount, lookupUuidCount, tickCount, trackerCount, playerCount, playerMapCount, navCount, partCount;
        long sectionKey;
        @Nullable EntitySection<Entity> section;

        View(ServerLevel level, Entity entity, int id, UUID uuid) {
            this.level = level; this.entity = entity; this.id = id; this.uuid = uuid;
            this.known = managerAccess(level).ip_registrationKnownUuids().contains(uuid);
            this.ownedCallback = callbackOwnedBy(level, entity);
            for (var entry : sectionMap(level).long2ObjectEntrySet()) {
                for (Entity member : entry.getValue().getEntities().toList()) {
                    if (member == entity) { sectionCount++; section = entry.getValue(); sectionKey = entry.getLongKey(); }
                    else { observeForeign(member); }
                }
            }
            RegistrationLookupAccessor lookup = lookup(level);
            for (Entity value : lookup.ip_registrationById().values()) { if (value == entity) { lookupIdCount++; } }
            for (Entity value : lookup.ip_registrationByUuid().values()) { if (value == entity) { lookupUuidCount++; } }
            Entity idOwner = lookup.ip_registrationById().get(id);
            Entity uuidOwner = lookup.ip_registrationByUuid().get(uuid);
            if (idOwner != null && idOwner != entity) { conflict = true; }
            if (uuidOwner != null && uuidOwner != entity) { conflict = true; foreignUuid = true; }
            observeForeign(idOwner); observeForeign(uuidOwner);
            Entity tickOwner = ticks(level).ip_registrationActive().get(id);
            if (tickOwner != null && tickOwner != entity) { conflict = true; }
            for (Entity value : ticks(level).ip_registrationActive().values()) {
                if (value == entity) { tickCount++; } else { observeForeign(value); }
            }
            for (ChunkMap.TrackedEntity tracker : trackers(level).values()) {
                Entity tracked = ((IETrackedEntity) tracker).ip_getEntity();
                if (tracked == entity) { trackerCount++; } else { observeForeign(tracked); }
            }
            ChunkMap.TrackedEntity idTracker = trackers(level).get(id);
            if (idTracker != null && ((IETrackedEntity) idTracker).ip_getEntity() != entity) { conflict = true; }
            for (ServerPlayer player : level.players()) {
                if (player == entity) { playerCount++; } else { observeForeign(player); }
            }
            for (ServerPlayer player : chunkAccess(level).ip_registrationPlayerMap().getAllPlayers()) {
                if (player == entity) { playerMapCount++; } else { observeForeign(player); }
            }
            for (Mob mob : levelAccess(level).ip_registrationNavigatingMobs()) {
                if (mob == entity) { navCount++; } else { observeForeign(mob); }
            }
            for (Entity part : PlatformHooks.registrationParts(entity)) {
                Entity present = levelAccess(level).ip_registrationParts().get(part.getId());
                if (present == part) { partCount++; }
                else if (present != null) { conflict = true; }
            }
        }

        void observeForeign(@Nullable Entity other) {
            if (other != null && other != entity) {
                if (other.getUUID().equals(uuid)) { foreignUuid = true; conflict = true; }
                if (other.getId() == id) { conflict = true; }
            }
        }

        boolean hasIdentity() {
            return sectionCount != 0 || lookupIdCount != 0 || lookupUuidCount != 0 || tickCount != 0
                || trackerCount != 0 || playerCount != 0 || playerMapCount != 0 || navCount != 0 || partCount != 0 || ownedCallback;
        }

        boolean identityAbsent() { return !hasIdentity(); }

        RegistrationState state() {
            if (conflict) { return RegistrationState.CONFLICT; }
            if (!hasIdentity() && !known) { return RegistrationState.ABSENT; }
            if (sectionCount != 1 || section == null || !known || !ownedCallback || entity.isRemoved()
                || entity.level() != level || entity.getId() != id || !entity.getUUID().equals(uuid)
                || sectionKey != SectionPos.asLong(entity.blockPosition())) {
                return RegistrationState.PARTIAL;
            }
            RegistrationCallbackAccessor callback = (RegistrationCallbackAccessor) ((RegistrationEntityAccessor) entity).ip_registrationCallback();
            if (callback.ip_registrationSectionKey() != sectionKey || callback.ip_registrationSection() != section
                || managerAccess(level).ip_registrationSections().getSection(sectionKey) != section) {
                return RegistrationState.PARTIAL;
            }
            Visibility visibility = effective(entity, section);
            int visible = visibility.isAccessible() ? 1 : 0;
            int tracking = visibility.isAccessible() && PlatformHooks.needsEntityTracker(entity) ? 1 : 0;
            RegistrationLookupAccessor lookup = lookup(level);
            if (lookupIdCount != visible || lookupUuidCount != visible || tickCount != (visibility.isTicking() ? 1 : 0)
                || trackerCount != tracking || navCount != (entity instanceof Mob ? visible : 0)
                || partCount != (visibility.isAccessible() ? PlatformHooks.registrationParts(entity).size() : 0)) {
                return RegistrationState.PARTIAL;
            }
            ChunkMap.TrackedEntity tracker = trackers(level).get(id);
            if (visible == 1 && (lookup.ip_registrationById().get(id) != entity || lookup.ip_registrationByUuid().get(uuid) != entity)
                || tickCount == 1 && ticks(level).ip_registrationActive().get(id) != entity
                || tracking == 1 && (tracker == null || ((IETrackedEntity) tracker).ip_getEntity() != entity)) {
                return RegistrationState.PARTIAL;
            }
            if (entity instanceof ServerPlayer player && (playerCount != visible || playerMapCount != tracking
                || ((RegistrationGameModeAccessor) player.gameMode).ip_registrationLevel() != level)) {
                return RegistrationState.PARTIAL;
            }
            return RegistrationState.REGISTERED;
        }
    }

    private static Visibility effective(Entity entity, EntitySection<?> section) {
        return entity.isAlwaysTicking() ? Visibility.TICKING : section.getStatus();
    }

    private static PersistentEntitySectionManager<Entity> manager(ServerLevel level) {
        return ((IEServerWorld) level).ip_getEntityManager();
    }
    private static RegistrationManagerAccessor managerAccess(ServerLevel level) { return (RegistrationManagerAccessor) manager(level); }
    private static RegistrationLevelAccessor levelAccess(ServerLevel level) { return (RegistrationLevelAccessor) level; }
    private static RegistrationLookupAccessor lookup(ServerLevel level) { return (RegistrationLookupAccessor) managerAccess(level).ip_registrationVisible(); }
    private static RegistrationTickListAccessor ticks(ServerLevel level) { return (RegistrationTickListAccessor) levelAccess(level).ip_registrationTickList(); }
    private static RegistrationChunkMapAccessor chunkAccess(ServerLevel level) { return (RegistrationChunkMapAccessor) level.getChunkSource().chunkMap; }
    private static Int2ObjectMap<ChunkMap.TrackedEntity> trackers(ServerLevel level) {
        return ((IEChunkMap) level.getChunkSource().chunkMap).ip_getEntityTrackerMap();
    }
    private static Long2ObjectMap<EntitySection<Entity>> sectionMap(ServerLevel level) {
        return ((RegistrationSectionStorageAccessor) managerAccess(level).ip_registrationSections()).ip_registrationSections();
    }
    private static boolean callbackOwnedBy(ServerLevel level, Entity entity) {
        EntityInLevelCallback callback = ((RegistrationEntityAccessor) entity).ip_registrationCallback();
        return callback instanceof RegistrationCallbackAccessor access
            && access.ip_registrationOwner() == manager(level) && access.ip_registrationEntity() == entity;
    }
    private static boolean identityMatches(Entity entity, SavedEntity saved) { return entity.getId() == saved.id && entity.getUUID().equals(saved.uuid); }
    private static boolean attemptAbsent(Attempt attempt) {
        View view = new View(attempt.destination, attempt.candidate, attempt.original.id, attempt.original.uuid);
        return view.identityAbsent() && (!attempt.reserved || !view.known || view.foreignUuid);
    }
    private static void requireIdentity(Entity entity, SavedEntity saved) {
        if (!identityMatches(entity, saved)) { throw new IllegalStateException("Captured entity identity changed"); }
    }
    private static boolean globalPlayerIdentity(MinecraftServer server, ServerPlayer player) {
        return server.getPlayerList().getPlayer(player.getUUID()) == player
            && identityCount(server.getPlayerList().getPlayers(), player) == 1;
    }
    private static void checkServerThread(ServerLevel level) {
        if (!level.getServer().isSameThread() || level.getServer().getLevel(level.dimension()) != level) {
            throw new IllegalStateException("Registration access requires the live level's server thread");
        }
    }
    private static void checkThread(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (Thread.currentThread() != snapshot.thread || !snapshot.server.isSameThread()) {
            throw new IllegalStateException("Wrong captured thread or server");
        }
    }
    private static void checkActive(Snapshot snapshot) {
        checkActive(snapshot, true);
    }
    private static void checkActive(Snapshot snapshot, boolean requireLiveSource) {
        checkThread(snapshot);
        if (snapshot.phase != Phase.ACTIVE) { throw new IllegalStateException("Snapshot has been sealed"); }
        if (requireLiveSource && snapshot.server.getLevel(snapshot.source.dimension()) != snapshot.source) {
            throw new IllegalStateException("Captured source is no longer live");
        }
        if (ADMISSION.get() != null || CLEANUP.get() != null || RESTORATION.get() != null) {
            throw new IllegalStateException("Registration transaction was reentered");
        }
        synchronized (OWNERS) {
            for (Entity entity : snapshot.originals) {
                if (OWNERS.get(entity) != snapshot) { throw new IllegalStateException("Wrong captured registration owner"); }
            }
            for (Attempt attempt : snapshot.attempts) {
                if (OWNERS.get(attempt.candidate) != snapshot) { throw new IllegalStateException("Wrong attempted registration owner"); }
            }
        }
    }
    private static void release(Snapshot snapshot) {
        synchronized (OWNERS) {
            for (Entity entity : snapshot.originals) {
                if (OWNERS.get(entity) == snapshot) { OWNERS.remove(entity); }
            }
            for (Attempt attempt : snapshot.attempts) {
                if (OWNERS.get(attempt.candidate) == snapshot) { OWNERS.remove(attempt.candidate); }
            }
        }
    }
    private static Set<Entity> identitySet() { return Collections.newSetFromMap(new IdentityHashMap<>()); }
    private static int identityCount(Iterable<? extends Entity> values, Entity entity) {
        int count = 0;
        for (Entity value : values) { if (value == entity) { count++; } }
        return count;
    }
    private static <T> void removeListIdentity(List<T> list, T object) { list.removeIf(value -> value == object); }
    private static <T> void removeMapIdentity(Int2ObjectMap<T> map, T object) { map.int2ObjectEntrySet().removeIf(entry -> entry.getValue() == object); }
    private static boolean sameOrdered(List<Entity> first, List<Entity> second) {
        if (first.size() != second.size()) { return false; }
        for (int i = 0; i < first.size(); i++) { if (first.get(i) != second.get(i)) { return false; } }
        return true;
    }
    private static List<Entity> mapChildren(List<Entity> children, Map<Entity, Entity> mapped) {
        List<Entity> result = new ArrayList<>();
        for (Entity child : children) { result.add(mapped.get(child)); }
        return result;
    }
    private static void recover(List<String> failures, String operation, Runnable work) {
        try { work.run(); }
        catch (RuntimeException exception) {
            failures.add(operation + ": " + exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
    }

    private EntityRegistration() {}
}
