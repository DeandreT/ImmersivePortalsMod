package qouteall.imm_ptl.core.teleportation;

import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.util.profiling.Profiler;
import com.mojang.logging.LogUtils;
import qouteall.imm_ptl.core.platform_shared.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.Validate;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import qouteall.dimlib.api.DimensionAPI;
import qouteall.imm_ptl.core.IPMcHelper;
import qouteall.imm_ptl.core.IPPerServerInfo;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.ScaleUtils;
import qouteall.imm_ptl.core.chunk_loading.ImmPtlChunkTracking;
import qouteall.imm_ptl.core.collision.PortalCollisionHandler;
import qouteall.imm_ptl.core.compat.GravityChangerInterface;
import qouteall.imm_ptl.core.ducks.IEEntity;
import qouteall.imm_ptl.core.ducks.IEChunkMap;
import qouteall.imm_ptl.core.ducks.IETrackedEntity;
import qouteall.imm_ptl.core.ducks.IEServerPlayNetworkHandler;
import qouteall.imm_ptl.core.ducks.IEServerPlayerEntity;
import qouteall.imm_ptl.core.mc_utils.ServerTaskList;
import qouteall.imm_ptl.core.network.PacketRedirection;
import qouteall.imm_ptl.core.platform_shared.EntityRegistration;
import qouteall.imm_ptl.core.platform_shared.PlatformHooks;
import qouteall.imm_ptl.core.platform_specific.IPConfig;
import qouteall.imm_ptl.core.platform_specific.O_O;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.global_portals.GlobalPortalStorage;
import qouteall.q_misc_util.MiscHelper;
import qouteall.q_misc_util.api.McRemoteProcedureCall;
import qouteall.q_misc_util.my_util.MyTaskList;
import qouteall.q_misc_util.my_util.WithDim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.BiPredicate;
import java.util.stream.Stream;

public class ServerTeleportationManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    private final Set<Entity> teleportingEntities = Collections.newSetFromMap(new IdentityHashMap<>());
    private final WeakHashMap<Entity, Long> lastTeleportGameTime = new WeakHashMap<>();
    public boolean isFiringMyChangeDimensionEvent = false;
    public final WeakHashMap<ServerPlayer, WithDim<Vec3>> lastPosition = new WeakHashMap<>();
    private final List<FailedRecovery> failedRecoveries = new ArrayList<>();
    private TransferStatus lastTransferStatus = TransferStatus.REFUSED;
    private List<String> lastTransferDiagnostics = List.of();
    
    public static ServerTeleportationManager of(MinecraftServer server) {
        return IPPerServerInfo.of(server).teleportationManager;
    }
    
    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            of(server).tick(server);
        });
        
        Portal.SERVER_PORTAL_TICK_SIGNAL.register(
            (portal) -> {
                ServerTeleportationManager serverTeleportationManager = of(portal.level().getServer());
                getEntitiesToTeleport(portal).forEach(entity -> {
                    serverTeleportationManager.startTeleportingRegularEntity(portal, entity);
                });
            }
        );
        
        DimensionAPI.SERVER_PRE_REMOVE_DIMENSION_EVENT.register(
            world -> of(world.getServer()).evacuatePlayersFromDimension(world)
        );
    }
    
    public ServerTeleportationManager() {
    
    }
    
    private void tick(MinecraftServer server) {
        teleportingEntities.clear();
        
        manageGlobalPortalTeleportation();
    }
    
    public static boolean shouldEntityTeleport(Portal portal, Entity entity) {
        if (entity.level() != portal.level()) {return false;}
        if (!portal.canTeleportEntity(entity)) {return false;}
        Vec3 lastEyePos = entity.getEyePosition(0);
        Vec3 nextEyePos = entity.getEyePosition(1);
        
        if (entity instanceof Projectile) {
            nextEyePos = nextEyePos.add(McHelper.getWorldVelocity(entity));
        }
        
        boolean movedThroughPortal = portal.isMovedThroughPortal(lastEyePos, nextEyePos);
        return movedThroughPortal;
    }
    
    public void startTeleportingRegularEntity(Portal portal, Entity entity) {
        if (entity instanceof ServerPlayer) {
            return;
        }
        if (entity instanceof Portal) {
            return;
        }
        if (entity.getVehicle() != null || doesEntityClusterContainPlayer(entity)) {
            return;
        }
        if (entity.isRemoved()) {
            return;
        }
        if (!entity.canTeleport(entity.level(), portal.getDestinationWorld())) {
            return;
        }
        if (isJustTeleported(entity, 1)) {
            return;
        }
        //a new born entity may have last tick pos 0 0 0
        if (entity.xo == 0 && entity.yo == 0 && entity.zo == 0) {
            LOGGER.warn("Trying to teleport a fresh new entity {}", entity);
            return;
        }
        
        double motion = McHelper.lastTickPosOf(entity).distanceToSqr(entity.position());
        if (motion > 20) {
            return;
        }
        ServerTaskList.of(portal.level().getServer()).addTask(() -> {
            try {
                teleportRegularEntity(entity, portal);
            }
            catch (Throwable e) {
                LOGGER.error("", e);
            }
            return true;
        });
    }
    
    private static Stream<Entity> getEntitiesToTeleport(Portal portal) {
        return portal.level().getEntitiesOfClass(
            Entity.class,
            portal.getBoundingBox().inflate(2),
            e -> true
        ).stream().filter(
            e -> !(e instanceof Portal)
        ).filter(
            entity -> shouldEntityTeleport(portal, entity)
        );
    }
    
    public void onPlayerTeleportedInClient(
        ServerPlayer player,
        ResourceKey<Level> dimensionBefore,
        Vec3 eyePosBeforeTeleportation,
        UUID portalId
    ) {
        // Packet coordinates must not reach position setters, or even update the
        // teleport cooldown, until both the source and destination are checked.
        if (!isValidTeleportPosition(eyePosBeforeTeleportation)) {
            LOGGER.error("Invalid teleport position from {}", player);
            return;
        }

        if (player.getRemovalReason() != null) {
            LOGGER.error("Trying to teleport a removed player {}", player);
            return;
        }
        
        Portal portal = findPortal(player.level().getServer(), dimensionBefore, portalId);
        
        if (portal == null) {
            LOGGER.error(
                "Unable to find portal {} in {} to teleport {}",
                portalId, dimensionBefore.identifier(), player
            );
            return;
        }
        
        Vec3 oldFeetPos = eyePosBeforeTeleportation.subtract(McHelper.getEyeOffset(player));
        
        String failReason = validatePlayerTeleportationAndGetReason(
            player, dimensionBefore, oldFeetPos, portal
        );
        if (failReason == null) {
            Vec3 newEyePos = portal.transformPoint(eyePosBeforeTeleportation);
            if (!isValidTeleportPosition(newEyePos)
                || !isValidTeleportPosition(newEyePos.subtract(McHelper.getEyeOffset(player)))) {
                LOGGER.error("Invalid teleport destination for {} through {}", player, portal);
                return;
            }

            if (isTeleporting(player)) {
                LOGGER.info("{} is teleporting frequently", player);
            }
            
            ServerLevel oldWorld = (ServerLevel) player.level();
            Vec3 oldServerPos = player.position();
            ResourceKey<Level> dimensionTo = portal.getDestDim();

            TransferResult result = transferEntityTree(player, dimensionTo, newEyePos, true, portal);
            if (result.status() != TransferStatus.COMMITTED) {
                return;
            }

            finishCommittedTransfer(result, player);
            recordLastPosition(player, dimensionBefore, oldFeetPos);

            runPostCommit("notify chasers", player, () -> notifyChasersForPlayer(player, portal, oldWorld, oldServerPos));
            notifyPortalTransfer(result, portal);
            
        }
        else {
            LOGGER.error(
                "Player {} {} {} cannot teleport through portal {}\nReason: {}",
                player, player.level().dimension().identifier(), player.position(),
                portal, failReason
            );
            resynchronizeRejectedTeleport(player);
        }
    }

    private static void resynchronizeRejectedTeleport(ServerPlayer player) {
        if (player.connection == null) return;
        // The position packet carries ImmPtl's authoritative dimension as well
        // as its position, undoing a client's predicted cross-dimension move.
        ((IEServerPlayNetworkHandler) player.connection).ip_sendPositionCorrection();
        player.connection.resetPosition();
        ScaleUtils.setBaseScale(player, ScaleUtils.getBaseScale(player));
        GravityChangerInterface.invoker.setBaseGravityDirectionServer(
            player, GravityChangerInterface.invoker.getGravityDirection(player)
        );
    }
    
    private @Nullable Portal findPortal(
        MinecraftServer server,
        ResourceKey<Level> dimensionBefore, UUID portalId
    ) {
        ServerLevel originalWorld = server.getLevel(dimensionBefore);
        
        if (originalWorld == null) {
            LOGGER.error("Missing world {} when finding portal", dimensionBefore.identifier());
            return null;
        }
        
        Entity portalEntity = originalWorld.getEntity(portalId);
        if (portalEntity == null) {
            portalEntity = GlobalPortalStorage.get(originalWorld).data
                .stream().filter(
                    p -> p.getUUID().equals(portalId)
                ).findFirst().orElse(null);
        }
        if (portalEntity == null) {
            return null;
        }
        if (portalEntity instanceof Portal) {
            return ((Portal) portalEntity);
        }
        return null;
    }
    
    public void recordLastPosition(ServerPlayer player, ResourceKey<Level> dim, Vec3 pos) {
        lastPosition.put(
            player, new WithDim<>(dim, pos)
        );
    }
    
    /**
     * @return null if valid. if invalid, it's the reason
     */
    private @Nullable String validatePlayerTeleportationAndGetReason(
        ServerPlayer player,
        ResourceKey<Level> dimensionBefore,
        Vec3 posBefore,
        Portal portal
    ) {
        if (!isValidTeleportPosition(posBefore)) {
            return "invalid posBefore in packet";
        }
        
        // cannot teleport if having awaiting teleport
        if (((IEServerPlayNetworkHandler) player.connection).ip_hasAwaitingTeleport()) {
            return "has awaiting teleport";
        }
        
        if (!portal.canTeleportEntity(player)) {
            return "portal cannot teleport player";
        }
        
        if (player.level().dimension() != dimensionBefore) {
            return "player is not in the dimensionBefore in packet";
        }
        
        if (!(player.position().distanceToSqr(posBefore) <= 16 * 16)) {
            return "player is too far from the posBefore in packet";
        }
        
        if (!(portal.getDistanceToNearestPointInPortal(posBefore) <= 20)) {
            return "posBefore is too far from portal";
        }
        
        return null;
    }

    private static boolean isValidTeleportPosition(@Nullable Vec3 pos) {
        return pos != null
            && Double.isFinite(pos.x) && Double.isFinite(pos.y) && Double.isFinite(pos.z)
            && pos.x >= -Level.MAX_LEVEL_SIZE && pos.x < Level.MAX_LEVEL_SIZE
            && pos.z >= -Level.MAX_LEVEL_SIZE && pos.z < Level.MAX_LEVEL_SIZE
            && pos.y >= Level.MIN_ENTITY_SPAWN_Y && pos.y < Level.MAX_ENTITY_SPAWN_Y;
    }
    
    public static boolean canPlayerReachPos(
        ServerPlayer player,
        ResourceKey<Level> dimension,
        Vec3 pos
    ) {
        Vec3 playerPos = player.position();
        if (player.level().dimension() == dimension) {
            if (playerPos.distanceToSqr(pos) < 256) {
                return true;
            }
        }
        return IPMcHelper.getNearbyPortals(player, 20)
            .filter(portal -> portal.getDestDim() == dimension)
            .filter(portal -> portal.canTeleportEntity(player))
            .map(portal -> portal.transformPoint(playerPos))
            .anyMatch(mappedPos -> mappedPos.distanceToSqr(pos) < 256);
    }
    
    public static boolean canPlayerReachBlockEntity(
        ServerPlayer player, BlockEntity blockEntity
    ) {
        Level world = blockEntity.getLevel();
        if (world == null) {
            return false;
        }
        return canPlayerReachPos(
            player, world.dimension(),
            Vec3.atCenterOf(blockEntity.getBlockPos())
        );
    }
    
    public void teleportPlayer(
        ServerPlayer player,
        ResourceKey<Level> dimensionTo,
        Vec3 newEyePos
    ) {
        TransferResult result = transferEntityTree(player, dimensionTo, newEyePos, true, null);
        if (result.status() == TransferStatus.COMMITTED) {
            finishCommittedTransfer(result, player);
        }
    }
    
    public void forceTeleportPlayer(
        ServerPlayer player, ResourceKey<Level> dimensionTo, Vec3 newPos
    ) {
        forceTeleportPlayer(
            player, dimensionTo, newPos, true
        );
    }
    
    public void forceTeleportPlayer(
        ServerPlayer player, ResourceKey<Level> dimensionTo, Vec3 newPos,
        boolean sendPacket
    ) {
        if (IPConfig.getConfig().serverTeleportLogging) {
            LOGGER.info(
                "Force teleporting {} to {} {}",
                player, dimensionTo.identifier(), newPos
            );
        }
        
        TransferResult result = transferEntityTree(
            player, dimensionTo, newPos.add(McHelper.getEyeOffset(player)), true, null
        );
        if (result.status() == TransferStatus.COMMITTED) {
            finishCommittedTransfer(result, sendPacket ? null : player);
        }
    }
    
    private void manageGlobalPortalTeleportation() {
        for (ServerLevel world : MiscHelper.getServer().getAllLevels()) {
            for (Entity entity : world.getAllEntities()) {
                if (!(entity instanceof ServerPlayer)) {
                    Portal collidingPortal = ((IEEntity) entity).ip_getCollidingPortal();
                    
                    if (collidingPortal != null && collidingPortal.getIsGlobal()) {
                        if (shouldEntityTeleport(collidingPortal, entity)) {
                            startTeleportingRegularEntity(collidingPortal, entity);
                        }
                    }
                }
            }
        }
    }
    
    public boolean isTeleporting(Entity entity) {
        return teleportingEntities.contains(entity);
    }
    
    private void teleportRegularEntity(Entity entity, Portal portal) {
        Validate.isTrue(!(entity instanceof ServerPlayer));
        if (entity.getRemovalReason() != null) {
            LOGGER.error(
                "Trying to teleport an entity that is already removed {} {}",
                entity, portal
            );
            return;
        }
        
        if (entity.level() != portal.level()) {
            LOGGER.error("Cannot teleport {} from {} through {}", entity, entity.level().dimension(), portal);
            return;
        }
        
        if (portal.getDistanceToNearestPointInPortal(entity.getEyePosition()) > 5) {
            LOGGER.error("Entity is too far to teleport {} {}", entity, portal);
            return;
        }
        
        long currGameTime = McHelper.getServerGameTime();
        Long lastTeleportGameTime = this.lastTeleportGameTime.getOrDefault(entity, 0L);
        if (currGameTime - lastTeleportGameTime <= 0) {
            return;
        }
        if (entity.isPassenger() || doesEntityClusterContainPlayer(entity)) {
            return;
        }
        Vec3 newEyePos = getRegularEntityTeleportedEyePos(entity, portal);
        TransferResult result = transferEntityTree(entity, portal.getDestDim(), newEyePos, true, portal);
        if (result.status() != TransferStatus.COMMITTED) {
            return;
        }
        finishCommittedTransfer(result, null);
        Entity teleported = result.selected();

        // living entities do position interpolation
        // it may interpolate into unloaded chunks and stuck
        // avoid position interpolation
        runPostCommit("synchronize entity position", teleported, () -> McHelper.sendToTrackers(
            teleported,
            McRemoteProcedureCall.createPacketToSendToClient(
                "qouteall.imm_ptl.core.teleportation.ClientTeleportationManager.RemoteCallables.updateEntityPos",
                teleported.level().dimension(),
                teleported.getId(),
                teleported.position()
            )
        ));
        notifyPortalTransfer(result, portal);
    }
    
    private static Vec3 getRegularEntityTeleportedEyePos(Entity entity, Portal portal) {
        // the teleportation is delayed by 1 tick
        // the entity may be behind the portal or in front of the portal at this time
        
        Vec3 eyePosThisTick = McHelper.getEyePos(entity);
        Vec3 eyePosLastTick = McHelper.getLastTickEyePos(entity);
        
        Vec3 deltaMovement = eyePosThisTick.subtract(eyePosLastTick);
        Vec3 deltaMovementDirection = deltaMovement.normalize();
        
        Vec3 collidingPoint = portal.rayTrace(
            eyePosThisTick.subtract(deltaMovementDirection.scale(5)),
            eyePosThisTick.add(deltaMovementDirection)
        );
        
        if (collidingPoint == null) {
            collidingPoint = eyePosLastTick;
        }
        
        Vec3 result = portal.transformPoint(collidingPoint)
            .add(portal.transformLocalVecNonScale(deltaMovementDirection).scale(0.05));
        return result;
    }
    
    /**
     * {@link Entity#changeDimension}
     * Sometimes resuing the same entity object is problematic
     * because entity's AI related things may have world reference inside
     */
    public Entity changeEntityDimension(
        Entity entity,
        ResourceKey<Level> toDimension,
        Vec3 newEyePos,
        boolean recreateEntity
    ) {
        TransferResult result = transferEntityTree(entity, toDimension, newEyePos, recreateEntity, null);
        if (result.status() == TransferStatus.COMMITTED) {
            finishCommittedTransfer(result, null);
            return result.selected();
        }
        return entity;
    }

    private enum TransferStatus { COMMITTED, REFUSED, RECOVERY_FAILED }

    private record EntityTransfer(
        Entity original, Entity replacement, @Nullable Entity vehicle, List<Entity> passengers,
        Vec3 oldPosition, Vec3 eyePos, Vec3 velocity, ServerLevel destination, SourceState capturedState
    ) {}

    private record SourceState(
        Entity entity, @Nullable Entity vehicle, List<Entity> passengers, Vec3 position, Vec3 velocity,
        int globalPlayerCount, @Nullable ServerPlayer globalPlayerOwner
    ) {}

    private record TransferResult(
        TransferStatus status, Entity selected, @Nullable ServerLevel source, List<EntityTransfer> members
    ) {}

    private record FailedRecovery(
        EntityRegistration.Snapshot snapshot, List<EntityRegistration.Attempt> attempts, List<String> failures
    ) {}

    private static final class TransferRejected extends RuntimeException {
        private TransferRejected(String invariant) {
            super(invariant, null, false, false);
        }
    }

    /** Recovery diagnostics remain available even when an external callback also breaks repair. */
    public List<String> getRecoveryFailures() {
        List<String> failures = new ArrayList<>();
        for (FailedRecovery recovery : failedRecoveries) {
            failures.addAll(recovery.failures());
        }
        return List.copyOf(failures);
    }

    private TransferResult transferEntityTree(
        Entity selected, ResourceKey<Level> destinationDimension, Vec3 targetEyePos,
        boolean recreate, @Nullable Portal portal
    ) {
        return transferEntityTree(selected, destinationDimension, targetEyePos, recreate, portal,
            PlatformHooks::canTravelToDimension);
    }

    private TransferResult transferEntityTree(
        Entity selected, ResourceKey<Level> destinationDimension, Vec3 targetEyePos,
        boolean recreate, @Nullable Portal portal, BiPredicate<Entity, ServerLevel> travelPermission
    ) {
        List<EntityTransfer> members = new ArrayList<>();
        List<SourceState> sourceStates = new ArrayList<>();
        List<EntityRegistration.Attempt> attempts = new ArrayList<>();
        List<Entity> newlyMarked = new ArrayList<>();
        boolean profiling = false;
        EntityRegistration.Snapshot snapshot;
        try {
            // capture() includes the entire ordered root tree, including the
            // initiating player. No separate player dismount precedes capture.
            snapshot = EntityRegistration.capture(selected);
        }
        catch (RuntimeException exception) {
            lastTransferStatus = TransferStatus.REFUSED;
            lastTransferDiagnostics = List.of("CAPTURE_FAILED: " + exception);
            LOGGER.error("Portal transfer refused before mutation: {}", selected, exception);
            // Capture may have rejected an inconsistent/cyclic tree. Do not
            // traverse that untrusted graph again to send a correction.
            correctRegisteredPlayers(List.of(selected), false);
            return new TransferResult(TransferStatus.REFUSED, selected, null, List.of());
        }

        try {
            ServerLevel source = snapshot.source();
            Profiler.get().push("portal_teleport");
            profiling = true;
            ServerLevel destination = source.getServer().getLevel(destinationDimension);
            requireTransfer(destination != null, "DESTINATION_MISSING");
            requireTransfer(isValidTeleportPosition(targetEyePos), "INVALID_SELECTED_DESTINATION");
            for (Entity original : snapshot.originals()) {
                sourceStates.add(new SourceState(original, original.getVehicle(), List.copyOf(original.getPassengers()),
                    original.position(), original.getDeltaMovement(),
                    original instanceof ServerPlayer player ? globalPlayerCopies(source.getServer(), player) : 0,
                    original instanceof ServerPlayer player ? source.getServer().getPlayerList().getPlayer(player.getUUID()) : null));
            }
            Map<Entity, Entity> replacements = new IdentityHashMap<>();
            Vec3 selectedEyePos = McHelper.getEyePos(selected);
            Vec3 mappedSelectedEye = portal == null ? selectedEyePos : portal.transformPoint(selectedEyePos);
            Vec3 offset = targetEyePos.subtract(mappedSelectedEye);
            boolean hasPlayers = false;

            // Factories, eligibility checks and all calculations finish before
            // any original is detached, repositioned or removed.
            for (SourceState captured : sourceStates) {
                Entity original = captured.entity();
                requireTransfer(!original.isRemoved() && original.level() == source, "SOURCE_CONTEXT " + original.getUUID());
                requireTransfer(EntityRegistration.inspect(source, original) == EntityRegistration.RegistrationState.REGISTERED,
                    "SOURCE_REGISTRATION " + original.getUUID());
                requireTransfer(original.canTeleport(source, destination), "ENTITY_CANNOT_TRAVEL " + original.getUUID());
                requireTransfer(travelPermission.test(original, destination),
                    "DIMENSION_TRAVEL_REFUSED " + original.getUUID());
                requireTransfer(portal == null || portal.canTeleportEntity(original), "PORTAL_PERMISSION " + original.getUUID());
                if (original instanceof ServerPlayer player) {
                    hasPlayers = true;
                    requireTransfer(globalPlayerMembershipUnchanged(source.getServer(), player, captured), "GLOBAL_PLAYER_LIST " + player.getUUID());
                    requireTransfer(portal == null || player.connection == null
                            || !((IEServerPlayNetworkHandler) player.connection).ip_hasAwaitingTeleport(),
                        "PLAYER_AWAITING_TELEPORT " + player.getUUID());
                }
                Entity replacement = original;
                if (source != destination && recreate && !(original instanceof ServerPlayer)) {
                    replacement = original.getType().create(destination, EntitySpawnReason.DIMENSION_TRAVEL);
                    requireTransfer(replacement != null, "CANNOT_RECREATE " + original.getUUID());
                    replacement.restoreFrom(original);
                    replacement.setId(original.getId());
                    replacement.setYHeadRot(original.getYHeadRot());
                }
                requireTransfer(replacement.getId() == original.getId() && replacement.getUUID().equals(original.getUUID()),
                    "REPLACEMENT_IDENTITY " + original.getUUID());
                EntityRegistration.RegistrationState destinationState = EntityRegistration.inspect(destination, replacement);
                requireTransfer(source == destination
                        ? destinationState == EntityRegistration.RegistrationState.REGISTERED
                        : destinationState == EntityRegistration.RegistrationState.ABSENT,
                    "DESTINATION_ID_CONFLICT " + original.getUUID() + " " + destinationState);
                Vec3 eyePos = (portal == null ? McHelper.getEyePos(original) : portal.transformPoint(McHelper.getEyePos(original)))
                    .add(offset);
                requireTransfer(isValidTeleportPosition(eyePos)
                        && isValidTeleportPosition(eyePos.subtract(McHelper.getEyeOffset(replacement))),
                    "INVALID_MEMBER_DESTINATION " + original.getUUID());
                Vec3 velocity = McHelper.getWorldVelocity(original);
                if (portal != null) {
                    velocity = portal.transformVelocityRelativeToPortal(velocity, original, original.position());
                }
                requireTransfer(isFiniteVector(velocity), "INVALID_MEMBER_VELOCITY " + original.getUUID());
                members.add(new EntityTransfer(original, replacement, captured.vehicle(),
                    captured.passengers(), captured.position(), eyePos, velocity, destination, captured));
                replacements.put(original, replacement);
            }
            requireTransfer(replacements.containsKey(selected), "SELECTED_NOT_IN_CAPTURE");

            if (source != destination) {
                // Journal every slot before admission, including the slot whose
                // callback may throw after it has already registered an entity.
                for (EntityTransfer member : members) {
                    attempts.add(EntityRegistration.beginAttempt(snapshot, member.original(), member.replacement(), destination));
                }
                for (int i = members.size() - 1; i >= 0; i--) {
                    Entity original = members.get(i).original();
                    if (original instanceof ServerPlayer player) {
                        ((IEServerPlayerEntity) player).ip_stopRidingWithoutTeleportRequest();
                    }
                    else {
                        original.stopRiding();
                    }
                    requireTransfer(original.getVehicle() == null, "SOURCE_DISMOUNT_REFUSED " + original.getUUID());
                }
                if (hasPlayers) {
                    for (EntityTransfer member : members) {
                        if (teleportingEntities.add(member.original())) newlyMarked.add(member.original());
                    }
                }
                for (int i = 0; i < members.size(); i++) {
                    EntityTransfer member = members.get(i);
                    Entity original = member.original();
                    // Match vanilla dimension travel, not LivingEntity.remove,
                    // which clears brain memories even for CHANGED_DIMENSION.
                    original.setRemoved(Entity.RemovalReason.CHANGED_DIMENSION);
                    requireTransfer(EntityRegistration.inspect(source, original) == EntityRegistration.RegistrationState.ABSENT,
                        "SOURCE_REMOVAL_INCOMPLETE " + original.getUUID());
                    Entity replacement = member.replacement();
                    if (replacement == original) ((IEEntity) original).ip_unsetRemoved();
                    if (replacement instanceof ServerPlayer player) {
                        // A player has game-mode/world context beyond Entity.level.
                        player.setServerLevel(destination);
                    }
                    else {
                        ((IEEntity) replacement).ip_setWorld(destination);
                    }
                    setPreparedPosition(member);
                    requireTransfer(EntityRegistration.admitNormally(attempts.get(i)), "DESTINATION_ADMISSION_REFUSED " + original.getUUID());
                    requireTransfer(EntityRegistration.inspect(destination, replacement) == EntityRegistration.RegistrationState.REGISTERED,
                        "DESTINATION_REGISTRATION " + original.getUUID());
                }
                for (EntityTransfer member : members) {
                    Entity vehicle = replacements.get(member.vehicle());
                    if (vehicle != null) {
                        Entity passenger = member.replacement();
                        if (passenger instanceof ServerPlayer player) {
                            ((IEServerPlayerEntity) player).ip_startRidingWithoutTeleportRequest(vehicle);
                        }
                        else {
                            requireTransfer(passenger.startRiding(vehicle, true, true), "DESTINATION_MOUNT_REFUSED " + passenger.getUUID());
                        }
                        requireTransfer(passenger.getVehicle() == vehicle, "DESTINATION_MOUNT_REFUSED " + passenger.getUUID());
                    }
                }
                // startRiding may change pose/eye height. Apply the prepared
                // eye positions using the final riding pose, still precommit.
                for (EntityTransfer member : members) setPreparedPosition(member);
            }
            else {
                // No new admission or riding relationship is needed for a
                // same-world move. Existing links stay intact throughout it.
                for (EntityTransfer member : members) setPreparedPosition(member);
            }

            verifyDestination(members, replacements, source, destination);
            EntityRegistration.commit(snapshot);
            lastTransferStatus = TransferStatus.COMMITTED;
            lastTransferDiagnostics = List.of();
            return new TransferResult(TransferStatus.COMMITTED, replacements.get(selected), source, List.copyOf(members));
        }
        catch (RuntimeException exception) {
            return recoverTransfer(snapshot, attempts, members, sourceStates, newlyMarked, selected, exception);
        }
        finally {
            if (profiling) runPostCommit("finish transfer profiling", selected, () -> Profiler.get().pop());
        }
    }

    private static void requireTransfer(boolean condition, String invariant) {
        if (!condition) throw new TransferRejected(invariant);
    }

    private static boolean isFiniteVector(Vec3 vector) {
        return Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }

    private static void setPreparedPosition(EntityTransfer member) {
        McHelper.setEyePos(member.replacement(), member.eyePos(), member.eyePos());
        McHelper.updateBoundingBox(member.replacement());
        McHelper.setWorldVelocity(member.replacement(), member.velocity());
    }

    private static int globalPlayerCopies(MinecraftServer server, ServerPlayer expected) {
        int count = 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player == expected) count++;
        }
        return count;
    }

    private static boolean globalPlayerMembershipUnchanged(MinecraftServer server, ServerPlayer expected, SourceState captured) {
        return (captured.globalPlayerOwner() == null || captured.globalPlayerOwner() == expected)
            && globalPlayerCopies(server, expected) == captured.globalPlayerCount()
            && server.getPlayerList().getPlayer(expected.getUUID()) == captured.globalPlayerOwner();
    }

    private static void verifyDestination(
        List<EntityTransfer> members, Map<Entity, Entity> replacements, ServerLevel source, ServerLevel destination
    ) {
        for (EntityTransfer member : members) {
            Entity target = member.replacement();
            requireTransfer(!target.isRemoved() && target.level() == destination
                    && EntityRegistration.inspect(destination, target) == EntityRegistration.RegistrationState.REGISTERED,
                "DESTINATION_REGISTRATION " + target.getUUID());
            requireTransfer(McHelper.getEyePos(target).distanceToSqr(member.eyePos()) < 1.0e-10,
                "DESTINATION_POSITION " + target.getUUID());
            if (source != destination) {
                requireTransfer(EntityRegistration.inspect(source, member.original()) == EntityRegistration.RegistrationState.ABSENT,
                    "SOURCE_REGISTRATION_REMAINS " + target.getUUID());
            }
            requireTransfer(target.getVehicle() == replacements.get(member.vehicle()), "DESTINATION_PARENT " + target.getUUID());
            List<Entity> passengers = target.getPassengers();
            requireTransfer(passengers.size() == member.passengers().size(), "DESTINATION_PASSENGER_COUNT " + target.getUUID());
            for (int i = 0; i < passengers.size(); i++) {
                requireTransfer(passengers.get(i) == replacements.get(member.passengers().get(i)),
                    "DESTINATION_SEAT_ORDER " + target.getUUID());
            }
            if (target instanceof ServerPlayer player) {
                requireTransfer(globalPlayerMembershipUnchanged(source.getServer(), player, member.capturedState()),
                    "GLOBAL_PLAYER_LIST " + player.getUUID());
            }
        }
    }

    private TransferResult recoverTransfer(
        EntityRegistration.Snapshot snapshot, List<EntityRegistration.Attempt> attempts,
        List<EntityTransfer> members, List<SourceState> sourceStates, List<Entity> newlyMarked,
        Entity selected, RuntimeException cause
    ) {
        List<String> recoveryFailures = new ArrayList<>();
        // A failing cleanup must not prevent cleanup of the remaining slots.
        for (int i = attempts.size() - 1; i >= 0; i--) {
            try {
                EntityRegistration.RecoveryResult result = EntityRegistration.removeAttempt(attempts.get(i));
                if (!result.complete()) recoveryFailures.addAll(namedFailures("REMOVE_ATTEMPT_" + i, result.failures()));
            }
            catch (RuntimeException exception) {
                recoveryFailures.add("REMOVE_ATTEMPT_" + i + ": " + exception);
            }
        }
        try {
            EntityRegistration.RecoveryResult result = EntityRegistration.restore(snapshot);
            if (!result.complete()) recoveryFailures.addAll(namedFailures("RESTORE_SOURCE", result.failures()));
        }
        catch (RuntimeException exception) {
            recoveryFailures.add("RESTORE_SOURCE: " + exception);
        }
        for (Entity original : newlyMarked) teleportingEntities.remove(original);
        // Verify the exact originals, not an equal-ID replacement, after repair.
        for (Entity original : snapshot.originals()) {
            try {
                if (original.isRemoved() || original.level() != snapshot.source()
                    || EntityRegistration.inspect(snapshot.source(), original) != EntityRegistration.RegistrationState.REGISTERED) {
                    recoveryFailures.add("SOURCE_REGISTRATION_NOT_RESTORED " + original.getUUID());
                }
            }
            catch (RuntimeException exception) {
                recoveryFailures.add("VERIFY_SOURCE " + original.getUUID() + ": " + exception);
            }
        }
        for (SourceState state : sourceStates) {
            Entity original = state.entity();
            if (original instanceof ServerPlayer player && !globalPlayerMembershipUnchanged(snapshot.source().getServer(), player, state)) {
                recoveryFailures.add("GLOBAL_PLAYER_LIST_NOT_RESTORED " + player.getUUID());
            }
            if (original.getVehicle() != state.vehicle() || !sameEntityOrder(original.getPassengers(), state.passengers())) {
                recoveryFailures.add("SOURCE_HIERARCHY_NOT_RESTORED " + original.getUUID());
            }
            if (!original.position().equals(state.position()) || !original.getDeltaMovement().equals(state.velocity())) {
                recoveryFailures.add("SOURCE_MOTION_NOT_RESTORED " + original.getUUID());
            }
        }
        for (EntityTransfer member : members) {
            // Reused originals already point back to the source after repair.
            // That pointer cannot prove their old destination entries vanished.
            if (member.destination() != snapshot.source()) {
                try {
                    EntityRegistration.RegistrationState state = EntityRegistration.inspect(member.destination(), member.replacement());
                    if (state == EntityRegistration.RegistrationState.REGISTERED || state == EntityRegistration.RegistrationState.PARTIAL) {
                        recoveryFailures.add("DESTINATION_REGISTRATION_REMAINS " + member.replacement().getUUID());
                    }
                }
                catch (RuntimeException exception) {
                    recoveryFailures.add("VERIFY_DESTINATION " + member.replacement().getUUID() + ": " + exception);
                }
            }
        }
        TransferStatus status = recoveryFailures.isEmpty() ? TransferStatus.REFUSED : TransferStatus.RECOVERY_FAILED;
        lastTransferStatus = status;
        List<String> diagnostics = new ArrayList<>();
        diagnostics.add("TRANSFER_ABORTED: " + cause);
        diagnostics.addAll(recoveryFailures);
        lastTransferDiagnostics = List.copyOf(diagnostics);
        if (status == TransferStatus.RECOVERY_FAILED) {
            failedRecoveries.add(new FailedRecovery(snapshot, List.copyOf(attempts), lastTransferDiagnostics));
            LOGGER.error("RECOVERY_FAILED for portal transfer {}: {}", selected.getUUID(), lastTransferDiagnostics);
        }
        else if (cause instanceof TransferRejected) {
            LOGGER.warn("Portal transfer refused for {}: {}", selected.getUUID(), cause.getMessage());
        }
        else {
            LOGGER.error("Portal transfer callback failed; source registration restored for {}", selected.getUUID(), cause);
        }
        correctRegisteredPlayers(snapshot.originals(), status == TransferStatus.RECOVERY_FAILED);
        // Position corrections must switch every affected client back first.
        // Raw hierarchy repair alone does not invalidate tracker.lastPassengers.
        resendSourcePassengerGraph(snapshot.source(), snapshot.originals());
        return new TransferResult(status, selected, snapshot.source(), List.copyOf(members));
    }

    private static boolean sameEntityOrder(List<?> actual, List<?> expected) {
        if (actual.size() != expected.size()) return false;
        for (int i = 0; i < actual.size(); i++) {
            if (actual.get(i) != expected.get(i)) return false;
        }
        return true;
    }

    private static List<String> namedFailures(String operation, List<String> failures) {
        if (failures.isEmpty()) return List.of(operation + ": incomplete recovery without backend detail");
        List<String> named = new ArrayList<>();
        for (String failure : failures) named.add(operation + ": " + failure);
        return named;
    }

    private static void correctRegisteredPlayers(List<Entity> originals, boolean recoveryFailed) {
        for (Entity original : originals) {
            if (original instanceof ServerPlayer player) {
                if (player.connection == null) continue;
                runPostCommit("correct refused player transfer", player, () -> {
                    if (recoveryFailed) {
                        player.sendSystemMessage(Component.literal("Portal transfer recovery failed. See server diagnostics."));
                    }
                    if (!player.isRemoved() && player.level() instanceof ServerLevel level
                        && EntityRegistration.inspect(level, player) == EntityRegistration.RegistrationState.REGISTERED
                        && isValidTeleportPosition(player.position())) {
                        resynchronizeRejectedTeleport(player);
                    }
                });
            }
        }
    }

    private static void resendSourcePassengerGraph(ServerLevel source, List<Entity> originals) {
        List<ServerPlayer> affectedPlayers = new ArrayList<>();
        for (Entity original : originals) {
            if (original instanceof ServerPlayer player && player.connection != null && !player.isRemoved()) {
                affectedPlayers.add(player);
            }
        }
        for (Entity vehicle : originals) {
            runPostCommit("resend source passenger graph", vehicle, () -> {
                if (!isRegisteredInSource(source, vehicle)) return;
                for (Entity passenger : vehicle.getPassengers()) {
                    // In incomplete recovery never refer to a destination-only
                    // identity from a source-world passenger packet.
                    if (!isRegisteredInSource(source, passenger)) return;
                }
                ClientboundSetPassengersPacket packet = new ClientboundSetPassengersPacket(vehicle);
                runPostCommit("resend source passenger graph to trackers", vehicle, () -> {
                    ChunkMap.TrackedEntity tracker = ((IEChunkMap) source.getChunkSource().chunkMap)
                        .ip_getEntityTrackerMap().get(vehicle.getId());
                    if (tracker != null && ((IETrackedEntity) tracker).ip_getEntity() == vehicle) {
                        PacketRedirection.withForceRedirect(source, () -> tracker.sendToTrackingPlayers(packet));
                    }
                });
                for (ServerPlayer player : affectedPlayers) {
                    // The player may not yet be in the restored tracker's
                    // seenBy set. Send directly as well, including empty seats.
                    runPostCommit("resend source passenger graph to affected player", player,
                        () -> PacketRedirection.sendRedirectedPacket(player.connection, packet, source.dimension()));
                }
            });
        }
    }

    private static boolean isRegisteredInSource(ServerLevel source, Entity entity) {
        return !entity.isRemoved() && entity.level() == source
            && EntityRegistration.inspect(source, entity) == EntityRegistration.RegistrationState.REGISTERED;
    }

    private void finishCommittedTransfer(TransferResult result, @Nullable ServerPlayer predictedPlayer) {
        long time = McHelper.getServerGameTime();
        for (EntityTransfer member : result.members()) {
            lastTeleportGameTime.put(member.replacement(), time);
            if (member.replacement() instanceof ServerPlayer player) {
                recordLastPosition(player, result.source().dimension(), member.oldPosition());
                if (player.connection != null && player != predictedPlayer) {
                    runPostCommit("send committed player position", player,
                        () -> ((IEServerPlayNetworkHandler) player.connection).ip_sendPositionCorrection());
                }
                if (player.connection != null) {
                    runPostCommit("reset player connection position", player, player.connection::resetPosition);
                }
                runPostCommit("update player portal collision", player, () -> {
                    Vec3 eyePos = McHelper.getEyePos(player);
                    PortalCollisionHandler.updateCollidingPortalAfterTeleportation(player, eyePos, eyePos, 1);
                });
                if (player.connection != null) {
                    runPostCommit("update player chunk tracking", player, () -> ImmPtlChunkTracking.immediatelyUpdateForPlayer(player));
                }
                if (result.source() != player.level()) {
                    runPostCommit("player dimension travel event", player, () -> O_O.onPlayerTravelOnServer(
                        player, result.source(), (ServerLevel) player.level()
                    ));
                    runPostCommit("player dimension advancements", player,
                        () -> ((IEServerPlayerEntity) player).portal_worldChanged(result.source(), member.oldPosition()));
                }
            }
        }
    }

    private static void notifyPortalTransfer(TransferResult result, Portal portal) {
        // Business callbacks remain postcommit. A failing callback cannot turn
        // an already published transfer into a rollback of live entities.
        runPostCommit("portal entity callback", result.selected(), () -> portal.onEntityTeleportedOnServer(result.selected()));
        for (EntityTransfer member : result.members()) {
            Entity entity = member.replacement();
            runPostCommit("portal scale update", entity, () -> ScaleUtils.onServerEntityTeleported(entity, portal));
            if (portal.getTeleportChangesGravity()) {
                runPostCommit("portal gravity update", entity, () -> {
                    Direction oldGravity = GravityChangerInterface.invoker.getGravityDirection(entity);
                    GravityChangerInterface.invoker.setBaseGravityDirectionServer(entity, portal.getTransformedGravityDirection(oldGravity));
                });
            }
        }
    }

    private static void runPostCommit(String operation, Entity entity, Runnable action) {
        try {
            action.run();
        }
        catch (RuntimeException exception) {
            LOGGER.error("{} failed for {} (registration outcome unchanged)", operation, entity.getUUID(), exception);
        }
    }
    
    public Entity teleportVehicleAcrossDimensions(
        Entity entity,
        ResourceKey<Level> toDimension,
        Vec3 newEyePos
    ) {
        return changeEntityDimension(entity, toDimension, newEyePos, true);
    }
    
    private boolean doesEntityClusterContainPlayer(Entity entity) {
        if (entity instanceof Player) {
            return true;
        }
        List<Entity> passengerList = entity.getPassengers();
        if (passengerList.isEmpty()) {
            return false;
        }
        return passengerList.stream().anyMatch(this::doesEntityClusterContainPlayer);
    }
    
    public boolean isJustTeleported(Entity entity, long valveTickTime) {
        long currGameTime = McHelper.getServerGameTime();
        Long lastTeleportGameTime = this.lastTeleportGameTime.getOrDefault(entity, -100000L);
        return currGameTime - lastTeleportGameTime < valveTickTime;
    }
    
    public static Entity teleportEntityGeneral(Entity entity, Vec3 targetPos, ServerLevel targetWorld) {
        if (entity instanceof ServerPlayer serverPlayer) {
            of(serverPlayer.level().getServer()).forceTeleportPlayer(
                serverPlayer, targetWorld.dimension(), targetPos
            );
            return entity;
        }
        else {
            return teleportRegularEntityTo(entity, targetWorld.dimension(), targetPos);
        }
    }
    
    @SuppressWarnings("unchecked")
    public static <E extends Entity> E teleportRegularEntityTo(
        E entity, ResourceKey<Level> targetDim, Vec3 targetPos
    ) {
        return (E) of(entity.level().getServer()).changeEntityDimension(
            entity,
            targetDim,
            targetPos.add(McHelper.getEyeOffset(entity)),
            true
        );
    }
    
    // make the mobs chase the player through portal
    // (only works in simple cases)
    private static void notifyChasersForPlayer(
        ServerPlayer player,
        Portal portal,
        ServerLevel originalWorld,
        Vec3 originalPosition
    ) {
        List<Mob> chasers = McHelper.findEntitiesRough(
            Mob.class,
            originalWorld,
            originalPosition,
            1,
            e -> e.getTarget() == player
        );
        
        for (Mob chaser : chasers) {
            chaser.setTarget(null);
            notifyChaser(player, portal, chaser, originalPosition);
        }
    }
    
    private static void notifyChaser(
        ServerPlayer player,
        Portal portal,
        Mob chaser,
        Vec3 originalPosition
    ) {
        Vec3 targetPos = originalPosition.add(portal.getNormal().scale(-0.1));
        
        UUID chaserId = chaser.getUUID();
        ServerLevel destWorld = ((ServerLevel) portal.getDestinationWorld());
        
        ServerTaskList.of(player.level().getServer()).addTask(MyTaskList.withRetryNumberLimit(
            140,
            () -> {
                if (chaser.isRemoved()) {
                    // the chaser teleported
                    Entity newChaser = destWorld.getEntity(chaserId);
                    if (newChaser instanceof Mob) {
                        ((Mob) newChaser).setTarget(player);
                        return true;
                    }
                    else {
                        return false;
                    }
                }
                
                if (chaser.position().distanceTo(targetPos) < 2) {
                    chaser.getMoveControl().setWantedPosition(
                        targetPos.x, targetPos.y, targetPos.z, 1
                    );
                }
                else {
                    @Nullable
                    Path path = chaser.getNavigation().createPath(
                        BlockPos.containing(targetPos), 0
                    );
                    chaser.getNavigation().moveTo(path, 1);
                }
                return false;
            },
            () -> {}
        ));
    }
    
    private void evacuatePlayersFromDimension(ServerLevel world) {
        // teleportation modifies the player list
        List<ServerPlayer> players = new ArrayList<>(
            MiscHelper.getServer().getPlayerList().getPlayers()
        );
        for (ServerPlayer player : players) {
            if (player.level().dimension() == world.dimension()) {
                ServerLevel overWorld = McHelper.getOverWorldOnServer();
                // in 26.x the world spawn can be in any dimension
                LevelData.RespawnData respawnData = overWorld.getRespawnData();
                ResourceKey<Level> spawnDim = respawnData.dimension();
                if (spawnDim == world.dimension()) {
                    spawnDim = Level.OVERWORLD;
                }
                BlockPos spawnPos = respawnData.pos();
                
                forceTeleportPlayer(
                    player, spawnDim, Vec3.atCenterOf(spawnPos)
                );
                
                player.sendSystemMessage(Component.literal(
                    "Teleported to spawn pos because dimension %s had been removed".formatted(world.dimension().identifier())
                ));
            }
        }
    }
}
