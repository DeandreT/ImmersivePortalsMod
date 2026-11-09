package qouteall.imm_ptl.gametest;

import com.mojang.authlib.GameProfile;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.chunk_loading.ImmPtlChunkTracking;
import qouteall.imm_ptl.core.ducks.IEServerPlayNetworkHandler;
import qouteall.imm_ptl.core.ducks.IEServerPlayerEntity;
import qouteall.imm_ptl.core.ducks.IEChunkMap;
import qouteall.imm_ptl.core.ducks.IETrackedEntity;
import qouteall.imm_ptl.core.miscellaneous.IPortalInitialScreen;
import qouteall.imm_ptl.core.platform_shared.EntityRegistration;
import qouteall.imm_ptl.core.network.PacketRedirection;
import qouteall.imm_ptl.core.api.PortalAPI;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.PortalExtension;
import qouteall.imm_ptl.core.portal.PortalManipulation;
import qouteall.imm_ptl.core.portal.shape.BoxPortalShape;
import qouteall.imm_ptl.core.teleportation.ServerTeleportationManager;
import qouteall.imm_ptl.peripheral.wand.PortalWandInteraction;
import qouteall.q_misc_util.my_util.DQuaternion;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.Queue;
import java.util.function.BiPredicate;

/** Exercises server-side regressions against real entities, worlds and applied mixins. */
public class PortalRegressionTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        if (Boolean.getBoolean("imm_ptl.realmTest.only")) return;
        context.runOnClient(client -> {
            if (client.gui.screen() instanceof IPortalInitialScreen screen) {
                screen.onClose();
            }
        });
        context.waitForScreen(TitleScreen.class);
        try (TestSingleplayerContext singleplayer = context.worldBuilder()
            .adjustSettings(settings -> settings.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE))
            .create()) {
            TestServerContext serverContext = singleplayer.getServer();
            serverContext.runCommand("forceload add 0 0 31 31");
            serverContext.runCommand("execute in minecraft:the_nether run forceload add 0 0 31 31");
            UUID realPlayerId = serverContext.computeOnServer(server -> server.getPlayerList().getPlayers().getFirst().getUUID());
            ResourceKey<Level> sourceDimension = serverContext.computeOnServer(server -> realPlayer(server, realPlayerId).level().dimension());
            awaitRealPlayerSettled(context, serverContext, realPlayerId, sourceDimension, "initial login");
            serverContext.waitFor(server -> forcedFixtureChunksReady(server, sourceDimension), 600);

            // These fixtures do not correct/move the connected real client.
            // Synthetic packet peers are disposed without inventing ACKs.
            serverContext.runOnServer(server -> {
                ServerPlayer player = realPlayer(server, realPlayerId);
                ServerLevel source = (ServerLevel) player.level();
                ServerLevel destination = server.getLevel(Level.NETHER);
                check(destination != null, "Nether must exist in the regression world");
                ServerTeleportationManager manager = ServerTeleportationManager.of(server);
                RegistrationRecoveryFixtures.run(source, destination);
                nonFinitePacketsHaveNoSideEffects(manager, player);
                copyAndCutPreserveVolume(player);
                pasteLoadCallbackFailureCanBeRetried(player);
                cutClusterKeepsDestinationWorld(player, destination);
                transformedExitOffset(source);
                privatePortalTracking(player, destination);
                passengerTreeTransfer(manager, source, destination, true);
                passengerTreeTransfer(manager, source, destination, false);
                for (boolean recreate : new boolean[]{true, false}) {
                    for (int failingMember : new int[]{0, 2, 3}) {
                        refusedAdmissionRestoresTree(manager, source, destination, recreate, failingMember, false);
                        refusedAdmissionRestoresTree(manager, source, destination, recreate, failingMember, true);
                    }
                    hiddenIdConflictPreservesForeignEntity(manager, source, destination, recreate);
                    cleanupCallbackFailureIsReported(manager, source, destination, recreate);
                }
                sameWorldMoveAfterRestoreKeepsNativeCallbacks(manager, source);
                sameWorldTravelPermissionChecksEveryMember(manager, source);
                for (boolean recreate : new boolean[]{true, false}) {
                    cancelledVillagerKeepsMemories(manager, source, destination, recreate);
                }
                preflightRefusalReleasesCapture(manager, source, destination);
                nonglobalPlayerMembershipIsPreserved(manager, source, destination);
                cancelledRootResendsSourcePassengerPackets(manager, source, destination);
            });
            awaitRealPlayerSettled(context, serverContext, realPlayerId, sourceDimension, "after non-client fixtures");

            // Each packet scenario gets fresh synthetic companions. They have
            // no real client, so no synthetic ACK is used to admit a later packet.
            for (int scenario = 0; scenario < 3; scenario++) {
                int selectedScenario = scenario;
                serverContext.runOnServer(server -> {
                    ServerPlayer player = realPlayer(server, realPlayerId);
                    multiplePlayerTreeIsOneTransaction(ServerTeleportationManager.of(server), player,
                        server.getLevel(Level.NETHER), selectedScenario);
                });
                awaitRealPlayerSettled(context, serverContext, realPlayerId, sourceDimension,
                    "after multiplayer scenario " + scenario);
            }

            mountedPlayerValidationAndCrossing(context, serverContext, realPlayerId);
        }
    }

    private static ServerPlayer realPlayer(MinecraftServer server, UUID id) {
        ServerPlayer player = server.getPlayerList().getPlayer(id);
        check(player != null, "The real game-test player disconnected");
        return player;
    }

    private static boolean forcedFixtureChunksReady(MinecraftServer server, ResourceKey<Level> sourceDimension) {
        ServerLevel source = server.getLevel(sourceDimension);
        ServerLevel destination = server.getLevel(Level.NETHER);
        if (source == null || destination == null) return false;
        for (ServerLevel world : List.of(source, destination)) {
            for (int x = 0; x < 2; x++) {
                for (int z = 0; z < 2; z++) {
                    if (!world.areEntitiesActuallyLoadedAndTicking(new ChunkPos(x, z))) return false;
                }
            }
        }
        return true;
    }

    private record ClientPlayerState(ResourceKey<Level> dimension, int id, int vehicleId, int rootId, Vec3 position) {}

    private static void awaitRealPlayerSettled(
        ClientGameTestContext context, TestServerContext serverContext, UUID id,
        ResourceKey<Level> expectedDimension, String stage
    ) {
        // This runs on the test thread, never from inside runOnServer. The
        // framework yields actual client/server ticks so the real client can
        // receive its correction and return ServerboundAcceptTeleportationPacket.
        context.waitTick();
        serverContext.waitFor(server -> {
            ServerPlayer player = realPlayer(server, id);
            return player.level().dimension() == expectedDimension && !player.isRemoved()
                && !((IEServerPlayNetworkHandler) player.connection).ip_hasAwaitingTeleport();
        }, 600);
        int stableTicks = 0;
        for (int tick = 0; tick < 600; tick++) {
            ClientPlayerState client = context.computeOnClient(minecraft -> {
                if (minecraft.player == null || minecraft.level == null || minecraft.player.level() != minecraft.level) return null;
                Entity vehicle = minecraft.player.getVehicle();
                return new ClientPlayerState(minecraft.level.dimension(), minecraft.player.getId(),
                    vehicle == null ? -1 : vehicle.getId(), minecraft.player.getRootVehicle().getId(), minecraft.player.position());
            });
            boolean settled = serverContext.computeOnServer(server -> {
                ServerPlayer player = realPlayer(server, id);
                Entity vehicle = player.getVehicle();
                return client != null && client.dimension() == expectedDimension && player.level().dimension() == expectedDimension
                    && !((IEServerPlayNetworkHandler) player.connection).ip_hasAwaitingTeleport()
                    && EntityRegistration.inspect((ServerLevel) player.level(), player) == EntityRegistration.RegistrationState.REGISTERED
                    && client.id() == player.getId() && client.vehicleId() == (vehicle == null ? -1 : vehicle.getId())
                    && client.rootId() == player.getRootVehicle().getId() && client.position().distanceToSqr(player.position()) <= 1;
            });
            stableTicks = settled ? stableTicks + 1 : 0;
            if (stableTicks >= 2) return;
            context.waitTick();
        }
        throw new AssertionError("Real client position/ACK/riding graph did not settle " + stage);
    }

    private static void nonFinitePacketsHaveNoSideEffects(ServerTeleportationManager manager, ServerPlayer player) {
        Vec3 before = player.position();
        Level world = player.level();
        Entity vehicle = player.getVehicle();
        Portal portal = portal((ServerLevel) world, player.getEyePosition(), (ServerLevel) world, player.getEyePosition().add(10, 0, 0));
        check(world.addFreshEntity(portal), "Cannot spawn packet validation fixture");
        Map<?, ?> cooldowns = field(manager, "lastTeleportGameTime");
        Object oldCooldown = cooldowns.get(player);
        Object oldLastPosition = manager.lastPosition.get(player);
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.MAX_VALUE}) {
            for (Vec3 invalid : List.of(new Vec3(value, 64, 0), new Vec3(0, value, 0), new Vec3(0, 64, value))) {
                manager.onPlayerTeleportedInClient(player, world.dimension(), invalid, portal.getUUID());
                check(player.position().equals(before) && player.level() == world, "Invalid packet moved the player");
                check(player.getVehicle() == vehicle, "Invalid packet changed the vehicle");
                check(cooldowns.get(player) == oldCooldown, "Invalid packet changed the cooldown");
                check(manager.lastPosition.get(player) == oldLastPosition, "Invalid packet changed recorded position");
            }
        }
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.MAX_VALUE}) {
            portal.setDestination(new Vec3(value, 100, 0));
            manager.onPlayerTeleportedInClient(player, world.dimension(), player.getEyePosition(), portal.getUUID());
            check(player.position().equals(before) && player.level() == world, "Invalid transformed destination moved player");
            check(player.getVehicle() == vehicle, "Invalid transformed destination changed vehicle");
            check(cooldowns.get(player) == oldCooldown, "Invalid transformed destination changed cooldown");
            check(manager.lastPosition.get(player) == oldLastPosition, "Invalid destination changed recorded position");
        }
        portal.discard();
    }

    private static void copyAndCutPreserveVolume(ServerPlayer player) {
        ServerLevel world = (ServerLevel) player.level();
        Portal original = portal(world, player.getEyePosition().add(2, 0, 0), world, new Vec3(20, 100, 20));
        original.setThickness(3.5);
        original.setScaling(2);
        original.setPortalShape(BoxPortalShape.FACING_OUTWARDS);
        original.portalTag = "regression-cut-" + UUID.randomUUID();
        Portal copy = PortalManipulation.copyPortal(original, Portal.ENTITY_TYPE);
        check(copy.getThickness() == 3.5, "copyPortal lost box thickness");
        check(copy.getPortalShape() instanceof BoxPortalShape, "copyPortal lost box shape");
        check(world.addFreshEntity(original), "Cannot spawn clipboard fixture");
        PortalWandInteraction.handleCopyCutPortal(player, original.getUUID(), true);
        check(original.isRemoved(), "Cut did not remove original");
        Map<?, ?> clipboard = staticField(PortalWandInteraction.class, "copyingSessionMap");
        Object saved = clipboard.get(player);
        check(saved != null, "Cut did not populate clipboard");
        confirmPaste(player, player.position().add(65, 0, 0));
        check(clipboard.get(player) == saved, "Rejected paste consumed cut clipboard");
        confirmPaste(player, new Vec3(Double.NaN, 64, 0));
        check(clipboard.get(player) == saved, "NaN paste consumed cut clipboard");
        confirmPaste(player, player.getEyePosition().add(3, 0, 0));
        check(!clipboard.containsKey(player), "Successful paste did not consume clipboard");
        Portal pasted = null;
        for (Entity entity : world.getAllEntities()) {
            if (entity instanceof Portal candidate && !candidate.isRemoved()
                && original.portalTag.equals(candidate.portalTag)) {
                check(pasted == null, "Paste duplicated the portal");
                pasted = candidate;
            }
        }
        check(pasted != null, "Paste did not create a live portal");
        check(pasted.getThickness() == 3.5 && pasted.getOtherSideState().thickness() == 7,
            "Paste changed source or destination box thickness");
        check(pasted.getPortalShape() instanceof BoxPortalShape, "Paste lost box shape");
        pasted.discard();
    }

    private static void confirmPaste(ServerPlayer player, Vec3 origin) {
        invoke(method(PortalWandInteraction.class, "handleConfirmCopyCut", ServerPlayer.class, Vec3.class, DQuaternion.class),
            null, player, origin, DQuaternion.identity);
    }

    private static void pasteLoadCallbackFailureCanBeRetried(ServerPlayer player) {
        ServerLevel world = (ServerLevel) player.level();
        Portal original = portal(world, player.getEyePosition().add(2, 0, 0), world, new Vec3(20, 100, 20));
        String tag = "regression-paste-callback-" + UUID.randomUUID();
        original.portalTag = tag;
        check(world.addFreshEntity(original), "Cannot spawn paste callback fixture");
        PortalWandInteraction.handleCopyCutPortal(player, original.getUUID(), true);
        Map<?, ?> clipboard = staticField(PortalWandInteraction.class, "copyingSessionMap");
        Object saved = clipboard.get(player);
        AtomicBoolean failOnce = new AtomicBoolean(true);
        AtomicReference<Entity> attempted = new AtomicReference<>();
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof Portal candidate && tag.equals(candidate.portalTag) && failOnce.getAndSet(false)) {
                attempted.set(entity);
                throw new IllegalStateException("Injected post-registration paste callback failure");
            }
        });
        try {
            confirmPaste(player, player.getEyePosition().add(3, 0, 0));
            Entity failed = attempted.get();
            check(failed != null, "Paste failure callback was not reached");
            check(world.getEntity(failed.getUUID()) == null, "Failed attempted portal remains registered");
            check(clipboard.get(player) == saved, "Failed paste consumed the clipboard");
            confirmPaste(player, player.getEyePosition().add(3, 0, 0));
            check(!clipboard.containsKey(player), "Successful retry did not consume the clipboard");
            List<Portal> pasted = new ArrayList<>();
            for (Entity entity : world.getAllEntities()) {
                if (entity instanceof Portal candidate && !candidate.isRemoved() && tag.equals(candidate.portalTag)) {
                    pasted.add(candidate);
                }
            }
            check(pasted.size() == 1, "Retry duplicated a portal left by the failed callback");
            pasted.forEach(Entity::discard);
        }
        finally {
            failOnce.set(false);
        }
    }

    private static void cutClusterKeepsDestinationWorld(ServerPlayer player, ServerLevel destination) {
        ServerLevel source = (ServerLevel) player.level();
        Portal original = portal(source, player.getEyePosition().add(2, 0, 0), destination, new Vec3(20, 100, 20));
        original.portalTag = "regression-cluster-" + UUID.randomUUID();
        original.setThickness(3);
        original.setScaling(2);
        original.setPortalShape(BoxPortalShape.FACING_OUTWARDS);
        Portal flipped = PortalManipulation.createFlippedPortal(original, Portal.ENTITY_TYPE);
        Portal reverse = PortalManipulation.createReversePortal(original, Portal.ENTITY_TYPE);
        Portal parallel = PortalManipulation.createFlippedPortal(reverse, Portal.ENTITY_TYPE);
        PortalExtension extension = PortalExtension.get(original);
        extension.flippedPortal = flipped;
        extension.reversePortal = reverse;
        extension.parallelPortal = parallel;
        List<Portal> originals = List.of(original, flipped, reverse, parallel);
        for (Portal fixture : originals) {
            check(fixture.level().addFreshEntity(fixture), "Cannot spawn cluster fixture");
        }
        PortalWandInteraction.handleCopyCutPortal(player, original.getUUID(), true);
        confirmPaste(player, player.getEyePosition().add(3, 0, 0));
        for (Portal fixture : originals) check(fixture.isRemoved(), "Cut left a cluster member alive");
        for (ServerLevel world : List.of(source, destination)) {
            List<Portal> pastedPortals = new ArrayList<>();
            for (Entity entity : world.getAllEntities()) {
                if (entity instanceof Portal pasted && !pasted.isRemoved() && original.portalTag.equals(pasted.portalTag)) {
                    check(pasted.level() == world, "Pasted cluster member registered in the wrong world");
                    check(pasted.getThickness() == (world == source ? 3 : 6), "Cluster paste lost volume");
                    pastedPortals.add(pasted);
                }
            }
            check(pastedPortals.size() == 2, "Cluster paste did not preserve two portals in each dimension");
            pastedPortals.forEach(Entity::discard);
        }
    }

    private static void transformedExitOffset(ServerLevel world) {
        Portal portal = portal(world, new Vec3(0, 100, 0), world, new Vec3(30, 110, 30));
        Entity entity = EntityTypes.ARMOR_STAND.create(world, EntitySpawnReason.COMMAND);
        check(entity != null, "Cannot create exit-offset fixture");
        McHelper.setEyePos(entity, portal.getOriginPos().add(0, 0, -0.2), portal.getOriginPos().add(0, 0, 0.2));
        Method getExit = method(ServerTeleportationManager.class, "getRegularEntityTeleportedEyePos", Entity.class, Portal.class);
        for (double degrees : new double[]{90, 180}) {
            for (double scale : new double[]{0.5, 2}) {
                portal.setRotation(DQuaternion.rotationByDegrees(new Vec3(0, 1, 0), degrees));
                portal.setScaling(scale);
                Vec3 actual = (Vec3) invoke(getExit, null, entity, portal);
                Vec3 expected = portal.getDestPos().add(portal.transformLocalVecNonScale(new Vec3(0, 0, -0.05)));
                check(actual.distanceToSqr(expected) < 1.0e-12, "Exit offset is not in destination coordinates");
            }
        }
    }

    private static void privatePortalTracking(ServerPlayer player, ServerLevel remoteWorld) {
        // Inspect the applied mixin, not the untransformed mixin class. This is
        // the same predicate used for both adding and removing actual pairings.
        Method predicate = Arrays.stream(ChunkMap.TrackedEntity.class.getDeclaredMethods())
            .filter(candidate -> candidate.getName().contains("recWatches"))
            .filter(candidate -> Arrays.equals(candidate.getParameterTypes(), new Class<?>[]{
                Entity.class, int.class, ImmPtlChunkTracking.PlayerWatchRecord.class, ServerPlayer.class
            })).findFirst().orElseThrow(() -> new AssertionError("Tracking mixin predicate is missing"));
        predicate.setAccessible(true);
        for (Level viewedWorld : List.of(player.level(), remoteWorld)) {
            Portal portal = portal((ServerLevel) viewedWorld, player.getEyePosition(), remoteWorld, new Vec3(0, 100, 0));
            var record = new ImmPtlChunkTracking.PlayerWatchRecord(player, viewedWorld.dimension(), 0, 0, 0, true, false);
            portal.specificPlayerId = UUID.randomUUID();
            check(!(boolean) invoke(predicate, null, portal, 128, record, player), "Private portal exposed to another player");
            portal.specificPlayerId = player.getUUID();
            check((boolean) invoke(predicate, null, portal, 128, record, player), "Owner cannot track private portal");
            portal.specificPlayerId = null;
            check((boolean) invoke(predicate, null, portal, 128, record, player), "Public portal cannot be tracked");
        }
    }

    private static void passengerTreeTransfer(
        ServerTeleportationManager manager, ServerLevel source, ServerLevel destination, boolean recreate
    ) {
        List<Entity> originals = List.of(spawnRider(source), spawnRider(source), spawnRider(source), spawnRider(source));
        Entity root = originals.get(0);
        check(originals.get(1).startRiding(root, true, true), "Cannot mount child fixture");
        check(originals.get(2).startRiding(originals.get(1), true, true), "Cannot mount grandchild fixture");
        check(originals.get(3).startRiding(root, true, true), "Cannot mount sibling fixture");
        List<UUID> ids = originals.stream().map(Entity::getUUID).toList();

        // Register the duplicate UUID before the transfer; failure must leave
        // the entire original tree mounted and present in its source world.
        Entity duplicate = EntityTypes.ARMOR_STAND.create(destination, EntitySpawnReason.COMMAND);
        check(duplicate != null, "Cannot create duplicate fixture");
        duplicate.setUUID(ids.get(2));
        duplicate.setPos(0, 100, 0);
        check(destination.addFreshEntity(duplicate), "Cannot register duplicate UUID fixture");
        check(manager.changeEntityDimension(root, destination.dimension(), new Vec3(0, 100, 0), recreate) == root,
            "Failed preparation changed root identity");
        check(originals.get(2).getVehicle() == originals.get(1) && originals.get(1).getVehicle() == root
            && originals.get(3).getVehicle() == root, "Failed transfer damaged passenger links");
        for (Entity original : originals) {
            check(!original.isRemoved() && original.level() == source, "Failed transfer stranded a tree member");
        }
        duplicate.discard();

        Entity transferred = manager.changeEntityDimension(root, destination.dimension(), new Vec3(0, 100, 0), recreate);
        check(transferred.level() == destination, "Root did not change dimension");
        check(transferred.getSelfAndPassengers().count() == 4, "Passenger subtree was lost or duplicated");
        List<Entity> targets = ids.stream().map(destination::getEntity).toList();
        for (int i = 0; i < originals.size(); i++) {
            Entity target = targets.get(i);
            check(target != null && !target.isRemoved(), "Destination is missing a tree member");
            check(target.getId() == originals.get(i).getId(), "Transfer changed numeric entity ID");
            check((target != originals.get(i)) == recreate, "Recreation mode was not respected");
            check(!recreate || originals.get(i).isRemoved(), "Recreated original was left alive");
            check(liveCopies(source, ids.get(i)) + liveCopies(destination, ids.get(i)) == 1,
                "Transfer left multiple live copies of one UUID");
        }
        check(targets.get(1).getVehicle() == targets.get(0) && targets.get(2).getVehicle() == targets.get(1)
            && targets.get(3).getVehicle() == targets.get(0), "Transfer flattened or reordered passenger hierarchy");
        check(sameIdentityOrder(targets.get(0).getPassengers(), List.of(targets.get(1), targets.get(3))),
            "Transfer changed passenger seat order");
        if (recreate) {
            manager.changeEntityDimension(root, destination.dimension(), new Vec3(0, 100, 0), true);
            check(transferred.getSelfAndPassengers().count() == 4, "Stale-root retry duplicated passengers");
        }
        targets.forEach(Entity::discard);
    }

    private static long liveCopies(ServerLevel world, UUID id) {
        long count = 0;
        for (Entity entity : world.getAllEntities()) {
            if (!entity.isRemoved() && entity.getUUID().equals(id)) count++;
        }
        return count;
    }

    private record SourceFixture(
        Entity entity, Entity vehicle, List<Entity> passengers, Vec3 position, Vec3 velocity,
        Vec3 lastTickPosition, float yaw, float pitch, float headYaw, float bodyYaw, Pose pose, int boardingCooldown,
        Object playerSection, Object chunkTrackingView, ServerPlayer globalOwner, int globalCount
    ) {}

    private static List<Entity> nestedTree(ServerLevel source, String scope) {
        List<Entity> tree = List.of(spawnRider(source), spawnRider(source), spawnRider(source), spawnRider(source));
        check(tree.get(1).startRiding(tree.get(0), true, true), "Cannot mount failure-test child");
        check(tree.get(2).startRiding(tree.get(1), true, true), "Cannot mount failure-test grandchild");
        check(tree.get(3).startRiding(tree.get(0), true, true), "Cannot mount failure-test sibling");
        for (int i = 0; i < tree.size(); i++) {
            Entity entity = tree.get(i);
            entity.addTag(scope);
            McHelper.setPosAndLastTickPos(entity, new Vec3(i, 100 + i, 0), new Vec3(i - 0.2, 100 + i, 0));
            entity.setDeltaMovement(new Vec3(0.1 * (i + 1), 0.03, -0.1));
            entity.setYRot(15 * i);
            entity.setXRot(5 * i);
        }
        return tree;
    }

    private static List<SourceFixture> captureFixture(List<Entity> tree) {
        List<SourceFixture> states = new ArrayList<>();
        for (Entity entity : tree) {
            states.add(new SourceFixture(entity, entity.getVehicle(), List.copyOf(entity.getPassengers()), entity.position(),
                entity.getDeltaMovement(), McHelper.lastTickPosOf(entity), entity.getYRot(), entity.getXRot(),
                entity.getYHeadRot(), entity instanceof LivingEntity living ? living.yBodyRot : 0, entity.getPose(),
                field(entity, "boardingCooldown"),
                entity instanceof ServerPlayer player ? player.getLastSectionPos() : null,
                entity instanceof ServerPlayer player ? player.getChunkTrackingView() : null,
                entity instanceof ServerPlayer player ? player.level().getServer().getPlayerList().getPlayer(player.getUUID()) : null,
                entity instanceof ServerPlayer player ? countGlobalPlayer(player.level().getServer(), player) : 0));
        }
        return states;
    }

    private static void assertSourceFixture(ServerLevel source, List<SourceFixture> states) {
        for (SourceFixture state : states) {
            Entity entity = state.entity();
            check(!entity.isRemoved() && entity.level() == source
                && EntityRegistration.inspect(source, entity) == EntityRegistration.RegistrationState.REGISTERED,
                "Rollback did not restore the exact original registration: " + entity.getUUID());
            check(entity.getVehicle() == state.vehicle() && sameIdentityOrder(entity.getPassengers(), state.passengers()),
                "Rollback changed hierarchy or seat identity: " + entity.getUUID());
            check(entity.position().equals(state.position()) && entity.getDeltaMovement().equals(state.velocity())
                && McHelper.lastTickPosOf(entity).equals(state.lastTickPosition()), "Rollback changed motion state");
            check(entity.getYRot() == state.yaw() && entity.getXRot() == state.pitch() && entity.getPose() == state.pose(),
                "Rollback changed rotation or pose");
            check(entity.getYHeadRot() == state.headYaw()
                && (!(entity instanceof LivingEntity living) || living.yBodyRot == state.bodyYaw()),
                "Rollback/correction changed independent head or body rotation");
            check((int) field(entity, "boardingCooldown") == state.boardingCooldown(), "Rollback changed boarding cooldown");
            if (entity instanceof ServerPlayer player) {
                check(Objects.equals(player.getLastSectionPos(), state.playerSection())
                    && Objects.equals(player.getChunkTrackingView(), state.chunkTrackingView()), "Rollback changed player tracking context");
                check(field(player.gameMode, "level") == source
                    && source.getServer().getPlayerList().getPlayer(player.getUUID()) == state.globalOwner()
                    && countGlobalPlayer(source.getServer(), player) == state.globalCount(),
                    "Rollback changed player world context or global identity");
            }
        }
    }

    private static boolean sameIdentityOrder(List<Entity> actual, List<Entity> expected) {
        if (actual.size() != expected.size()) return false;
        for (int i = 0; i < actual.size(); i++) if (actual.get(i) != expected.get(i)) return false;
        return true;
    }

    private static void refusedAdmissionRestoresTree(
        ServerTeleportationManager manager, ServerLevel source, ServerLevel destination,
        boolean recreate, int failingMember, boolean callbackFailure
    ) {
        String scope = "regression-transfer-" + UUID.randomUUID();
        List<Entity> tree = nestedTree(source, scope);
        List<SourceFixture> states = captureFixture(tree);
        List<UUID> ids = tree.stream().map(Entity::getUUID).toList();
        UUID rejectedId = ids.get(failingMember);
        AtomicBoolean active = new AtomicBoolean(true);
        AtomicBoolean refuseDestination = new AtomicBoolean(true);
        AtomicBoolean throwOnLoad = new AtomicBoolean(callbackFailure);
        AtomicBoolean observedFailure = new AtomicBoolean(false);
        ServerEntityEvents.ALLOW_LOAD.register((entity, world, reason, deserializing) -> {
            if (!active.get() || !entity.entityTags().contains(scope)) return true;
            // Restoration must work while normal source admission remains
            // vetoed, without granting that privilege to a new entity.
            if (world == source) return false;
            if (!callbackFailure && refuseDestination.get() && world == destination && entity.getUUID().equals(rejectedId)) {
                observedFailure.set(true);
                return false;
            }
            return true;
        });
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (active.get() && world == destination && entity.entityTags().contains(scope)
                && entity.getUUID().equals(rejectedId) && throwOnLoad.getAndSet(false)) {
                observedFailure.set(true);
                throw new IllegalStateException("Injected post-registration tree callback failure");
            }
        });
        try {
            Map<?, ?> cooldowns = field(manager, "lastTeleportGameTime");
            List<Object> oldCooldowns = new ArrayList<>();
            for (Entity member : tree) oldCooldowns.add(cooldowns.get(member));
            check(manager.changeEntityDimension(tree.getFirst(), destination.dimension(), new Vec3(0, 120, 0), recreate) == tree.getFirst(),
                "Refused transaction returned a replacement as success");
            check(observedFailure.get(), "Admission/callback failure was not reached");
            check("REFUSED".equals(field(manager, "lastTransferStatus").toString()),
                "A recoverable failure was not reported as refusal: " + field(manager, "lastTransferDiagnostics"));
            assertSourceFixture(source, states);
            for (int i = 0; i < tree.size(); i++) {
                check(cooldowns.get(tree.get(i)) == oldCooldowns.get(i), "Failed transaction changed cooldown");
                check(liveCopies(destination, ids.get(i)) == 0, "Failed transaction left a destination copy");
            }
            Entity probe = EntityTypes.ARMOR_STAND.create(source, EntitySpawnReason.COMMAND);
            check(probe != null, "Cannot create source-admission probe");
            probe.addTag(scope);
            probe.setPos(0, 100, 0);
            check(!source.addFreshEntity(probe), "Recovery privilege leaked to a new source admission");
            check(EntityRegistration.inspect(source, probe) == EntityRegistration.RegistrationState.ABSENT,
                "Rejected probe is nevertheless registered");

            EntityRegistration.Snapshot oldCapture = EntityRegistration.capture(tree.getFirst());
            check(EntityRegistration.restore(oldCapture).complete(), "A current capture could not be restored");
            refuseDestination.set(false);
            Entity moved = manager.changeEntityDimension(tree.getFirst(), destination.dimension(), new Vec3(0, 120, 0), recreate);
            check("COMMITTED".equals(field(manager, "lastTransferStatus").toString()), "Corrected retry was not committed");
            check(moved.level() == destination && moved.getSelfAndPassengers().count() == tree.size(), "Retry lost tree members");
            List<Entity> targets = new ArrayList<>();
            for (int i = 0; i < ids.size(); i++) {
                Entity target = destination.getEntity(ids.get(i));
                check(target != null && (target != tree.get(i)) == recreate, "Retry did not honor recreation mode");
                check(EntityRegistration.inspect(destination, target) == EntityRegistration.RegistrationState.REGISTERED,
                    "Retry target is not exactly registered");
                targets.add(target);
            }
            try {
                EntityRegistration.restore(oldCapture);
            }
            catch (IllegalStateException expected) {
                // A consumed capture may be rejected rather than returned as
                // incomplete, but may never rewind a later committed move.
            }
            check(moved.level() == destination && sameIdentityOrder(moved.getPassengers(), List.of(targets.get(1), targets.get(3))),
                "Replayed capture rewound or damaged the later move");
            for (Entity target : targets) {
                check(EntityRegistration.inspect(destination, target) == EntityRegistration.RegistrationState.REGISTERED,
                    "Replayed capture removed a later destination registration");
            }
            targets.forEach(Entity::discard);
        }
        finally {
            active.set(false);
            for (Entity entity : tree) if (!entity.isRemoved()) entity.discard();
        }
    }

    private static void hiddenIdConflictPreservesForeignEntity(
        ServerTeleportationManager manager, ServerLevel source, ServerLevel destination, boolean recreate
    ) {
        List<Entity> tree = nestedTree(source, "regression-id-conflict-" + UUID.randomUUID());
        List<SourceFixture> before = captureFixture(tree);
        Entity foreign = EntityTypes.ARMOR_STAND.create(destination, EntitySpawnReason.COMMAND);
        check(foreign != null, "Cannot create hidden foreign entity");
        foreign.setId(tree.get(2).getId());
        foreign.setPos(16000, 100, 16000); // Outside the test's forced/visible chunks.
        UUID foreignId = foreign.getUUID();
        check(destination.addFreshEntity(foreign), "Cannot register hidden foreign fixture");
        try {
            check(EntityRegistration.inspect(destination, foreign) == EntityRegistration.RegistrationState.REGISTERED,
                "Hidden foreign registration is not inspectable");
            manager.changeEntityDimension(tree.getFirst(), destination.dimension(), new Vec3(0, 120, 0), recreate);
            check("REFUSED".equals(field(manager, "lastTransferStatus").toString()), "Hidden numeric ID conflict was accepted");
            assertSourceFixture(source, before);
            check(!foreign.isRemoved() && foreign.level() == destination && foreign.getUUID().equals(foreignId)
                && foreign.getId() == tree.get(2).getId()
                && EntityRegistration.inspect(destination, foreign) == EntityRegistration.RegistrationState.REGISTERED,
                "Conflict handling removed or replaced the foreign object");
        }
        finally {
            foreign.discard();
            tree.forEach(Entity::discard);
        }
    }

    private static void multiplePlayerTreeIsOneTransaction(
        ServerTeleportationManager manager, ServerPlayer primary, ServerLevel destination, int scenario
    ) {
        ServerLevel source = (ServerLevel) primary.level();
        Vec3 primaryPosition = primary.position();
        ServerPlayer carried = addConcretePlayerFixture(source.getServer(), source);
        ServerPlayer bystander = addConcretePlayerFixture(source.getServer(), source);
        List<SourceFixture> bystanderState = captureFixture(List.of(bystander));
        String scope = "regression-multiplayer-tree-" + UUID.randomUUID();
        List<Entity> nonPlayers = List.of(spawnRider(source), spawnRider(source), spawnRider(source));
        Entity root = nonPlayers.getFirst();
        check(nonPlayers.get(1).startRiding(root, true, true) && nonPlayers.get(2).startRiding(root, true, true),
            "Cannot mount multiplayer fixture vehicles");
        ((IEServerPlayerEntity) primary).ip_startRidingWithoutTeleportRequest(nonPlayers.get(1));
        ((IEServerPlayerEntity) carried).ip_startRidingWithoutTeleportRequest(root);
        List<Entity> tree = root.getSelfAndPassengers().toList();
        for (Entity member : tree) member.addTag(scope);
        List<SourceFixture> before = captureFixture(tree);
        List<ServerPlayer> globalBefore = List.copyOf(source.getServer().getPlayerList().getPlayers());
        Map<?, ?> cooldowns = field(manager, "lastTeleportGameTime");
        Object primaryCooldown = cooldowns.get(primary);
        Object carriedCooldown = cooldowns.get(carried);
        Object primaryHistory = manager.lastPosition.get(primary);
        Object carriedHistory = manager.lastPosition.get(carried);
        Portal portal = portal(source, primary.getEyePosition(), destination, new Vec3(0, 120, 0));
        check(source.addFreshEntity(portal), "Cannot spawn multiplayer fixture portal");
        AtomicBoolean active = new AtomicBoolean(true);
        AtomicBoolean denyCarried = new AtomicBoolean(scenario == 0);
        AtomicBoolean failPrimaryLoad = new AtomicBoolean(scenario == 1);
        AtomicBoolean observedCarriedRefusal = new AtomicBoolean(false);
        AtomicBoolean observedPrimaryCallback = new AtomicBoolean(false);
        ServerEntityEvents.ALLOW_LOAD.register((entity, world, reason, deserializing) -> {
            if (!active.get() || !entity.entityTags().contains(scope)) return true;
            if (world == source) return false;
            if (world == destination && entity == carried && denyCarried.get()) {
                observedCarriedRefusal.set(true);
                return false;
            }
            return true;
        });
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (active.get() && world == destination && entity == primary && failPrimaryLoad.getAndSet(false)) {
                observedPrimaryCallback.set(true);
                throw new IllegalStateException("Injected second-player-tree post-registration failure");
            }
        });
        try {
            // This goes through the authoritative packet path, not a separate
            // vehicle transfer followed by a later initiating-player transfer.
            manager.onPlayerTeleportedInClient(primary, source.dimension(), primary.getEyePosition(), portal.getUUID());
            if (scenario < 2) {
                check(scenario == 0 ? observedCarriedRefusal.get() : observedPrimaryCallback.get(),
                    "Multiplayer refusal/callback fixture was not reached");
                assertMultiplayerRollback(manager, source, before, globalBefore, primary, carried,
                    primaryCooldown, carriedCooldown, primaryHistory, carriedHistory);
                assertUnaffectedPlayer(source, bystanderState, bystander);
                return;
            }
            check("COMMITTED".equals(field(manager, "lastTransferStatus").toString()), "Allowed multiplayer transfer was not committed");
            check(primary.level() == destination && carried.level() == destination, "Multiplayer retry split players across worlds");
            assertUnaffectedPlayer(source, bystanderState, bystander);
            Entity movedRoot = primary.getRootVehicle();
            check(movedRoot.getUUID().equals(root.getUUID()) && movedRoot != root, "Multiplayer retry lost/reused the wrong root");
            check(carried.getRootVehicle() == movedRoot && movedRoot.getSelfAndPassengers().count() == tree.size(),
                "Multiplayer retry lost a sibling or nested player");
            check(samePlayerOrder(source.getServer().getPlayerList().getPlayers(), globalBefore)
                && source.getServer().getPlayerList().getPlayer(primary.getUUID()) == primary
                && source.getServer().getPlayerList().getPlayer(carried.getUUID()) == carried,
                "Transfer changed the global player list or its UUID mapping");
            check(((IEServerPlayNetworkHandler) carried.connection).ip_hasAwaitingTeleport(),
                "Carried player did not receive a destination correction after commit");
            check(destination.dimension() == field(carried.connection, "ip_dimOfAwaitingPosition"),
                "Carried-player correction contains the wrong destination");
            active.set(false);
            // This server-authoritative cleanup emits a real correction to
            // Player0. The outer test stage yields and waits for its actual ACK.
            manager.forceTeleportPlayer(primary, source.dimension(), primaryPosition);
        }
        finally {
            active.set(false);
            portal.discard();
            ((IEServerPlayerEntity) primary).ip_stopRidingWithoutTeleportRequest();
            ((IEServerPlayerEntity) carried).ip_stopRidingWithoutTeleportRequest();
            for (UUID id : nonPlayers.stream().map(Entity::getUUID).toList()) {
                for (ServerLevel world : List.of(source, destination)) {
                    Entity member = world.getEntity(id);
                    if (member != null) member.discard();
                }
            }
            removeConcretePlayerFixture(source.getServer(), carried);
            removeConcretePlayerFixture(source.getServer(), bystander);
        }
    }

    private static void nonglobalPlayerMembershipIsPreserved(
        ServerTeleportationManager manager, ServerLevel source, ServerLevel destination
    ) {
        ServerPlayer fake = addConcretePlayerFixture(source.getServer(), source, false);
        Entity root = spawnRider(source);
        ((IEServerPlayerEntity) fake).ip_startRidingWithoutTeleportRequest(root);
        List<Entity> tree = root.getSelfAndPassengers().toList();
        List<SourceFixture> before = captureFixture(tree);
        List<ServerPlayer> globalBefore = List.copyOf(source.getServer().getPlayerList().getPlayers());
        AtomicBoolean refuse = new AtomicBoolean(true);
        ServerEntityEvents.ALLOW_LOAD.register((entity, world, reason, deserializing) ->
            !(refuse.get() && world == destination && entity == fake));
        try {
            manager.changeEntityDimension(root, destination.dimension(), new Vec3(0, 120, 0), true);
            check("REFUSED".equals(field(manager, "lastTransferStatus").toString()), "Nonglobal fixture refusal did not recover");
            assertSourceFixture(source, before);
            check(countGlobalPlayer(source.getServer(), fake) == 0 && source.getServer().getPlayerList().getPlayer(fake.getUUID()) == null,
                "Rollback logged a nonglobal player into the global list");
            refuse.set(false);
            Entity moved = manager.changeEntityDimension(root, destination.dimension(), new Vec3(0, 120, 0), true);
            check("COMMITTED".equals(field(manager, "lastTransferStatus").toString()) && fake.level() == destination,
                "World-registered nonglobal player was incorrectly required to log in");
            check(fake.getRootVehicle() == moved && countGlobalPlayer(source.getServer(), fake) == 0
                && source.getServer().getPlayerList().getPlayer(fake.getUUID()) == null
                && samePlayerOrder(source.getServer().getPlayerList().getPlayers(), globalBefore),
                "Committed fake-player transfer changed global player membership");
        }
        finally {
            refuse.set(false);
            ((IEServerPlayerEntity) fake).ip_stopRidingWithoutTeleportRequest();
            removeConcretePlayerFixture(source.getServer(), fake);
            for (ServerLevel world : List.of(source, destination)) {
                Entity existing = world.getEntity(root.getUUID());
                if (existing != null) existing.discard();
            }
        }
    }

    private static void sameWorldMoveAfterRestoreKeepsNativeCallbacks(ServerTeleportationManager manager, ServerLevel world) {
        String scope = "regression-in-place-" + UUID.randomUUID();
        List<Entity> tree = nestedTree(world, scope);
        List<SourceFixture> before = captureFixture(tree);
        AtomicBoolean active = new AtomicBoolean(true);
        AtomicBoolean admissionCalled = new AtomicBoolean(false);
        ServerEntityEvents.ALLOW_LOAD.register((entity, level, reason, deserializing) -> {
            if (active.get() && entity.entityTags().contains(scope)) {
                admissionCalled.set(true);
                return false;
            }
            return true;
        });
        try {
            EntityRegistration.Snapshot capture = EntityRegistration.capture(tree.get(2));
            check(EntityRegistration.restore(capture).complete(), "Could not restore same-world capture");
            Vec3 eyeBefore = McHelper.getEyePos(tree.getFirst());
            check(manager.changeEntityDimension(tree.getFirst(), world.dimension(), eyeBefore.add(20, 0, 0), true) == tree.getFirst(),
                "Same-world move unnecessarily recreated its root");
            check("COMMITTED".equals(field(manager, "lastTransferStatus").toString()), "Same-world move was disabled/refused");
            for (int i = 0; i < tree.size(); i++) {
                check(tree.get(i).position().distanceToSqr(before.get(i).position().add(20, 0, 0)) < 1.0e-12,
                    "Same-world move did not move the entire tree");
                check(EntityRegistration.inspect(world, tree.get(i)) == EntityRegistration.RegistrationState.REGISTERED,
                    "Restored native callback lost section/lookup ownership on movement");
                check(tree.get(i).getVehicle() == before.get(i).vehicle()
                    && sameIdentityOrder(tree.get(i).getPassengers(), before.get(i).passengers()),
                    "Same-world move changed existing riding relationships");
            }
            check(!admissionCalled.get(), "Same-world move incorrectly ran a new admission gate");
            manager.changeEntityDimension(tree.getFirst(), world.dimension(), eyeBefore, false);
            check("COMMITTED".equals(field(manager, "lastTransferStatus").toString()),
                "Moved/restored entity could not be captured for a second move");
            for (Entity entity : tree) {
                check(EntityRegistration.inspect(world, entity) == EntityRegistration.RegistrationState.REGISTERED,
                    "Second movement left a stale native section callback");
            }
        }
        finally {
            active.set(false);
            tree.forEach(Entity::discard);
        }
    }

    private static void sameWorldTravelPermissionChecksEveryMember(ServerTeleportationManager manager, ServerLevel world) {
        List<Entity> tree = nestedTree(world, "regression-same-world-policy-" + UUID.randomUUID());
        List<SourceFixture> before = captureFixture(tree);
        Method transfer = method(ServerTeleportationManager.class, "transferEntityTree", Entity.class,
            ResourceKey.class, Vec3.class, boolean.class, Portal.class, BiPredicate.class);
        Map<Entity, Integer> counts = new IdentityHashMap<>();
        AtomicBoolean rejectLast = new AtomicBoolean(true);
        BiPredicate<Entity, ServerLevel> policy = (original, destination) -> {
            check(original.level() == world && destination == world && !original.isRemoved(),
                "Travel permission saw a mutated source context");
            counts.merge(original, 1, Integer::sum);
            return !rejectLast.get() || original != tree.getLast();
        };
        try {
            // Fabric's production hook is unconditional. This private adapter
            // exercises controller enumeration/refusal; Neo event publication
            // itself still requires the native runtime fixture.
            invoke(transfer, manager, tree.getFirst(), world.dimension(), McHelper.getEyePos(tree.getFirst()).add(20, 0, 0),
                false, null, policy);
            check("REFUSED".equals(field(manager, "lastTransferStatus").toString()), "Same-world cancellation was skipped");
            assertSourceFixture(world, before);
            for (Entity member : tree) check(counts.getOrDefault(member, 0) == 1,
                "Same-world preflight did not check each original exactly once");
            counts.clear();
            rejectLast.set(false);
            invoke(transfer, manager, tree.getFirst(), world.dimension(), McHelper.getEyePos(tree.getFirst()).add(20, 0, 0),
                false, null, policy);
            check("COMMITTED".equals(field(manager, "lastTransferStatus").toString()), "Allowed same-world move was refused");
            for (Entity member : tree) check(counts.getOrDefault(member, 0) == 1,
                "Allowed same-world preflight repeated or skipped a member");
        }
        finally {
            tree.forEach(Entity::discard);
        }
    }

    private static void cancelledVillagerKeepsMemories(
        ServerTeleportationManager manager, ServerLevel source, ServerLevel destination, boolean recreate
    ) {
        Villager original = EntityTypes.VILLAGER.create(source, EntitySpawnReason.COMMAND);
        check(original != null, "Cannot create controller villager fixture");
        original.setPos(8, 100, 8);
        check(source.addFreshEntity(original), "Cannot register controller villager fixture");
        GlobalPos home = GlobalPos.of(source.dimension(), new BlockPos(8, 64, 8));
        GlobalPos job = GlobalPos.of(source.dimension(), new BlockPos(9, 64, 8));
        original.getBrain().setMemory(MemoryModuleType.HOME, home);
        original.getBrain().setMemory(MemoryModuleType.JOB_SITE, job);
        AtomicBoolean refuse = new AtomicBoolean(true);
        AtomicBoolean reached = new AtomicBoolean(false);
        ServerEntityEvents.ALLOW_LOAD.register((entity, world, reason, deserializing) -> {
            if (refuse.get() && world == destination && entity.getUUID().equals(original.getUUID())) {
                reached.set(true);
                return false;
            }
            return true;
        });
        try {
            manager.changeEntityDimension(original, destination.dimension(), new Vec3(8, 120, 8), recreate);
            check(reached.get() && "REFUSED".equals(field(manager, "lastTransferStatus").toString()),
                "Villager cancellation was not reached/recovered");
            check(EntityRegistration.inspect(source, original) == EntityRegistration.RegistrationState.REGISTERED,
                "Canceled villager did not return to its exact source registration");
            check(home.equals(original.getBrain().getMemory(MemoryModuleType.HOME).orElse(null))
                && job.equals(original.getBrain().getMemory(MemoryModuleType.JOB_SITE).orElse(null)),
                "Controller detachment/compensation erased the original villager's brain");
            refuse.set(false);
            Entity result = manager.changeEntityDimension(original, destination.dimension(), new Vec3(8, 120, 8), recreate);
            check(result instanceof Villager && result.level() == destination
                && "COMMITTED".equals(field(manager, "lastTransferStatus").toString()), "Corrected villager retry did not commit");
            Villager moved = (Villager) result;
            check(home.equals(moved.getBrain().getMemory(MemoryModuleType.HOME).orElse(null))
                && job.equals(moved.getBrain().getMemory(MemoryModuleType.JOB_SITE).orElse(null)),
                "Villager retry lost saved HOME/JOB_SITE memories");
        }
        finally {
            refuse.set(false);
            for (ServerLevel world : List.of(source, destination)) {
                Entity live = world.getEntity(original.getUUID());
                if (live != null) live.discard();
            }
        }
    }

    private record SentPacket(ServerPlayer recipient, Packet<?> packet, long order) {}
    private record PacketView(ServerPlayer recipient, Packet<?> packet, long order, Integer dimension) {}
    private record PacketPeer(ServerPlayer player, EmbeddedChannel channel) {}

    private static void cancelledRootResendsSourcePassengerPackets(
        ServerTeleportationManager manager, ServerLevel source, ServerLevel destination
    ) {
        Queue<SentPacket> sent = new ConcurrentLinkedQueue<>();
        AtomicLong sequence = new AtomicLong();
        PacketPeer rider = packetPeer(source, sent, sequence);
        PacketPeer secondRider = packetPeer(source, sent, sequence);
        PacketPeer watcher = packetPeer(source, sent, sequence);
        Entity root = spawnRider(source);
        Entity vehicle = spawnRider(source);
        check(vehicle.startRiding(root, true, true), "Cannot mount packet-fixture vehicle");
        ((IEServerPlayerEntity) rider.player()).ip_startRidingWithoutTeleportRequest(vehicle);
        ((IEServerPlayerEntity) secondRider.player()).ip_startRidingWithoutTeleportRequest(root);
        List<Entity> tree = root.getSelfAndPassengers().toList();
        Portal portal = portal(source, rider.player().getEyePosition(), destination, new Vec3(0, 120, 0));
        check(source.addFreshEntity(portal), "Cannot register packet-fixture portal");
        AtomicBoolean cancel = new AtomicBoolean(true);
        AtomicBoolean reached = new AtomicBoolean(false);
        ServerEntityEvents.ALLOW_LOAD.register((entity, world, reason, deserializing) -> {
            if (cancel.get() && world == destination && entity.getUUID().equals(root.getUUID())) {
                reached.set(true);
                return false;
            }
            return true;
        });
        try {
            // Model a client which has actually received this source chunk,
            // then use the real tracking methods to establish pairings/cache.
            ImmPtlChunkTracking.updateForPlayer(watcher.player());
            var watchRecords = ImmPtlChunkTracking.getWatchRecordForChunk(source.dimension(), 0, 0);
            check(watchRecords != null && watchRecords.get(watcher.player()) != null, "Watcher fixture has no source chunk record");
            watchRecords.get(watcher.player()).isLoadedToPlayer = true;
            IEChunkMap chunkMap = (IEChunkMap) source.getChunkSource().chunkMap;
            ChunkMap.TrackedEntity originalVehicleTracker = chunkMap.ip_getEntityTrackerMap().get(vehicle.getId());
            PacketRedirection.withForceRedirect(source, () -> {
                for (Entity node : tree) {
                    ChunkMap.TrackedEntity tracker = chunkMap.ip_getEntityTrackerMap().get(node.getId());
                    check(tracker != null && ((IETrackedEntity) tracker).ip_getEntity() == node, "Missing exact fixture tracker");
                    ((IETrackedEntity) tracker).ip_updateEntityTrackingStatus();
                    ((IETrackedEntity) tracker).ip_sendChanges();
                }
            });
            Object serverEntity = field(originalVehicleTracker, "serverEntity");
            check(sameIdentityOrder(field(serverEntity, "lastPassengers"), List.of(rider.player())),
                "Fixture did not establish the unchanged lastPassengers cache");
            List<SourceFixture> before = captureFixture(tree);
            List<SourceFixture> watcherBefore = captureFixture(List.of(watcher.player()));
            rider.channel().runPendingTasks();
            secondRider.channel().runPendingTasks();
            watcher.channel().runPendingTasks();
            sent.clear();
            sequence.set(0);

            manager.onPlayerTeleportedInClient(rider.player(), source.dimension(), rider.player().getEyePosition(), portal.getUUID());
            rider.channel().runPendingTasks();
            secondRider.channel().runPendingTasks();
            watcher.channel().runPendingTasks();
            check(reached.get() && "REFUSED".equals(field(manager, "lastTransferStatus").toString()),
                "Root admission was not canceled/recovered");
            assertSourceFixture(source, before);
            assertUnaffectedPlayer(source, watcherBefore, watcher.player());
            check(chunkMap.ip_getEntityTrackerMap().get(vehicle.getId()) == originalVehicleTracker,
                "Fixture did not preserve the immediate vehicle's original tracker");
            check(sameIdentityOrder(field(serverEntity, "lastPassengers"), List.of(rider.player())),
                "Fixture cache changed, hiding the missing-remount-packet case");

            List<PacketView> packets = new ArrayList<>();
            for (SentPacket packet : sent) collectPacketViews(packet, packet.packet(), null, packets);
            long lastCorrection = -1;
            for (ServerPlayer affected : List.of(rider.player(), secondRider.player())) {
                long correction = -1;
                for (PacketView packet : packets) {
                    if (packet.recipient() == affected && packet.packet() instanceof ClientboundPlayerPositionPacket) {
                        correction = Math.max(correction, packet.order());
                    }
                }
                check(correction >= 0, "Affected client did not receive a real position correction");
                lastCorrection = Math.max(lastCorrection, correction);
            }
            boolean observedInitialDismount = false;
            for (PacketView packet : packets) {
                if (packet.recipient() == rider.player() && packet.order() < lastCorrection
                    && packet.packet() instanceof ClientboundSetPassengersPacket passengers
                    && passengers.getVehicle() == vehicle.getId() && passengers.getPassengers().length == 0) {
                    observedInitialDismount = true;
                }
                check(packet.recipient() != watcher.player() || !(packet.packet() instanceof ClientboundPlayerPositionPacket),
                    "Unrelated watcher received a position correction");
            }
            check(observedInitialDismount, "Fixture did not emit the real empty-passenger dismount packet");
            int sourceDimension = PortalAPI.serverDimKeyToInt(source.getServer(), source.dimension());
            for (ServerPlayer recipient : List.of(rider.player(), secondRider.player(), watcher.player())) {
                for (Entity node : tree) {
                    // A watcher only requires packets for the two tracked
                    // vehicles; affected clients also need explicit empty seats.
                    if (recipient == watcher.player() && node != root && node != vehicle) continue;
                    int[] expected = node.getPassengers().stream().mapToInt(Entity::getId).toArray();
                    boolean found = false;
                    for (PacketView packet : packets) {
                        if (packet.recipient() == recipient && packet.order() > lastCorrection
                            && packet.packet() instanceof ClientboundSetPassengersPacket passengers
                            && passengers.getVehicle() == node.getId() && Arrays.equals(passengers.getPassengers(), expected)) {
                            check(packet.dimension() != null && packet.dimension() == sourceDimension,
                                "Restored graph was sent outside the source redirection context");
                            found = true;
                        }
                    }
                    check(found, "No ordered source graph packet after all corrections for " + recipient.getUUID() + "/" + node.getId());
                }
            }
        }
        finally {
            cancel.set(false);
            portal.discard();
            ((IEServerPlayerEntity) rider.player()).ip_stopRidingWithoutTeleportRequest();
            ((IEServerPlayerEntity) secondRider.player()).ip_stopRidingWithoutTeleportRequest();
            removeConcretePlayerFixture(source.getServer(), rider.player());
            removeConcretePlayerFixture(source.getServer(), secondRider.player());
            removeConcretePlayerFixture(source.getServer(), watcher.player());
            for (Entity node : List.of(root, vehicle)) {
                if (!node.isRemoved()) node.discard();
            }
            rider.channel().finishAndReleaseAll();
            secondRider.channel().finishAndReleaseAll();
            watcher.channel().finishAndReleaseAll();
        }
    }

    private static PacketPeer packetPeer(ServerLevel world, Queue<SentPacket> sent, AtomicLong sequence) {
        AtomicReference<ServerPlayer> recipient = new AtomicReference<>();
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
                if (message instanceof Packet<?> packet) sent.add(new SentPacket(recipient.get(), packet, sequence.incrementAndGet()));
                context.write(message, promise);
            }
        }, connection);
        ServerPlayer player = addConcretePlayerFixture(world.getServer(), world, true, connection);
        recipient.set(player);
        return new PacketPeer(player, channel);
    }

    private static void collectPacketViews(SentPacket sent, Packet<?> packet, Integer dimension, List<PacketView> output) {
        collectPacketViews(sent, packet, dimension, output, new int[]{0});
    }

    private static void collectPacketViews(
        SentPacket sent, Packet<?> packet, Integer dimension, List<PacketView> output, int[] childOrder
    ) {
        if (packet instanceof ClientboundBundlePacket bundle) {
            for (Packet<?> child : bundle.subPackets()) collectPacketViews(sent, child, dimension, output, childOrder);
        }
        else if (packet instanceof ClientboundCustomPayloadPacket custom && custom.payload() instanceof PacketRedirection.Payload redirected) {
            collectPacketViews(sent, redirected.packet(), redirected.dimensionIntId(), output, childOrder);
        }
        else {
            output.add(new PacketView(sent.recipient(), packet, (sent.order() << 32) + childOrder[0]++, dimension));
        }
    }

    private static void preflightRefusalReleasesCapture(
        ServerTeleportationManager manager, ServerLevel source, ServerLevel destination
    ) {
        List<Entity> tree = nestedTree(source, "regression-preflight-" + UUID.randomUUID());
        List<SourceFixture> before = captureFixture(tree);
        ResourceKey<Level> missing = ResourceKey.create(Registries.DIMENSION, Identifier.parse("imm_ptl:regression_absent"));
        try {
            manager.changeEntityDimension(tree.getFirst(), missing, new Vec3(0, 120, 0), true);
            check("REFUSED".equals(field(manager, "lastTransferStatus").toString()), "Missing destination was accepted");
            assertSourceFixture(source, before);
            EntityRegistration.Snapshot afterMissing = EntityRegistration.capture(tree.getFirst());
            check(EntityRegistration.restore(afterMissing).complete(), "Missing-destination refusal leaked its capture lease");

            manager.changeEntityDimension(tree.getFirst(), destination.dimension(), new Vec3(Double.NaN, 120, 0), true);
            check("REFUSED".equals(field(manager, "lastTransferStatus").toString()), "Invalid target was accepted");
            assertSourceFixture(source, before);
            EntityRegistration.Snapshot afterInvalid = EntityRegistration.capture(tree.get(2));
            check(EntityRegistration.restore(afterInvalid).complete(), "Invalid-target refusal leaked its whole-root capture lease");
        }
        finally {
            tree.forEach(Entity::discard);
        }
    }

    private static void cleanupCallbackFailureIsReported(
        ServerTeleportationManager manager, ServerLevel source, ServerLevel destination, boolean recreate
    ) {
        String scope = "regression-recovery-failure-" + UUID.randomUUID();
        List<Entity> tree = nestedTree(source, scope);
        List<SourceFixture> before = captureFixture(tree);
        AtomicBoolean active = new AtomicBoolean(true);
        AtomicBoolean failLoad = new AtomicBoolean(true);
        AtomicBoolean failCleanup = new AtomicBoolean(true);
        AtomicBoolean cleanupReached = new AtomicBoolean(false);
        ServerEntityEvents.ALLOW_LOAD.register((entity, world, reason, deserializing) ->
            !active.get() || world != source || !entity.entityTags().contains(scope));
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (active.get() && world == destination && entity.entityTags().contains(scope)
                && entity.getUUID().equals(tree.get(2).getUUID()) && failLoad.getAndSet(false)) {
                throw new IllegalStateException("Injected admission failure before recovery failure");
            }
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
            if (active.get() && world == destination && entity.entityTags().contains(scope)
                && entity.getUUID().equals(tree.get(1).getUUID()) && failCleanup.getAndSet(false)) {
                cleanupReached.set(true);
                throw new IllegalStateException("Injected callback failure during attempted-entity cleanup");
            }
        });
        try {
            int retainedBefore = manager.getRecoveryFailures().size();
            manager.changeEntityDimension(tree.getFirst(), destination.dimension(), new Vec3(0, 120, 0), recreate);
            check(cleanupReached.get(), "Recovery callback failure fixture did not run");
            check("RECOVERY_FAILED".equals(field(manager, "lastTransferStatus").toString()),
                "Incomplete cleanup was presented as ordinary refusal/success");
            check(manager.getRecoveryFailures().size() > retainedBefore, "Recovery failure diagnostic was not retained");
            check(manager.getRecoveryFailures().stream().anyMatch(message -> message.contains("REMOVE_ATTEMPT")
                || message.contains("RESTORE_SOURCE")), "Recovery diagnostic does not name its failed invariant");
            // One foreign cleanup callback failed once, not forever. Cleanup
            // must continue to the other attempts and source repair regardless.
            assertSourceFixture(source, before);
            for (Entity original : tree) {
                check(liveCopies(destination, original.getUUID()) == 0, "A later cleanup slot was skipped after one callback failed");
            }
        }
        finally {
            active.set(false);
            for (Entity entity : tree) if (!entity.isRemoved()) entity.discard();
        }
    }

    private static void assertMultiplayerRollback(
        ServerTeleportationManager manager, ServerLevel source, List<SourceFixture> before,
        List<ServerPlayer> globalBefore, ServerPlayer primary, ServerPlayer carried,
        Object primaryCooldown, Object carriedCooldown, Object primaryHistory, Object carriedHistory
    ) {
        check("REFUSED".equals(field(manager, "lastTransferStatus").toString()),
            "Multiplayer failure did not finish recovery: " + field(manager, "lastTransferDiagnostics"));
        assertSourceFixture(source, before);
        check(samePlayerOrder(source.getServer().getPlayerList().getPlayers(), globalBefore), "Rollback changed global player order/identity");
        check(source.getServer().getPlayerList().getPlayer(primary.getUUID()) == primary
            && source.getServer().getPlayerList().getPlayer(carried.getUUID()) == carried, "Rollback changed global player UUID mappings");
        Map<?, ?> cooldowns = field(manager, "lastTeleportGameTime");
        check(cooldowns.get(primary) == primaryCooldown && cooldowns.get(carried) == carriedCooldown,
            "Failed multiplayer transaction changed cooldowns");
        check(manager.lastPosition.get(primary) == primaryHistory && manager.lastPosition.get(carried) == carriedHistory,
            "Failed multiplayer transaction changed history");
        for (ServerPlayer player : List.of(primary, carried)) {
            check(player.level() == source && field(player.gameMode, "level") == source, "Rollback did not restore full player world context");
            check(((IEServerPlayNetworkHandler) player.connection).ip_hasAwaitingTeleport(), "Rollback did not correct every affected player");
            check(source.dimension() == field(player.connection, "ip_dimOfAwaitingPosition"), "Rollback correction has the wrong dimension");
        }
    }

    private static boolean samePlayerOrder(List<ServerPlayer> actual, List<ServerPlayer> expected) {
        if (actual.size() != expected.size()) return false;
        for (int i = 0; i < actual.size(); i++) if (actual.get(i) != expected.get(i)) return false;
        return true;
    }

    private static void assertUnaffectedPlayer(ServerLevel source, List<SourceFixture> state, ServerPlayer player) {
        assertSourceFixture(source, state);
        check(!((IEServerPlayNetworkHandler) player.connection).ip_hasAwaitingTeleport(),
            "An unrelated player received a transfer correction");
    }

    private static ServerPlayer addConcretePlayerFixture(MinecraftServer server, ServerLevel world) {
        return addConcretePlayerFixture(server, world, true);
    }

    private static ServerPlayer addConcretePlayerFixture(MinecraftServer server, ServerLevel world, boolean global) {
        return addConcretePlayerFixture(server, world, global, new Connection(PacketFlow.SERVERBOUND));
    }

    private static ServerPlayer addConcretePlayerFixture(
        MinecraftServer server, ServerLevel world, boolean global, Connection connection
    ) {
        UUID id = UUID.randomUUID();
        GameProfile profile = new GameProfile(id, "PortalR" + id.toString().substring(0, 6));
        ServerPlayer player = new ServerPlayer(server, world, profile, ClientInformation.createDefault());
        // Concrete vanilla connection/listener objects keep correction and
        // player-world code real; this fixture does not need another renderer
        // or a second logged-in desktop client.
        new ServerGamePacketListenerImpl(server, connection, player,
            CommonListenerCookie.createInitial(profile, false));
        McHelper.setPosAndLastTickPos(player, new Vec3(0, 100, 0), new Vec3(-0.1, 100, 0));
        player.setDeltaMovement(new Vec3(0.2, 0.01, -0.1));
        player.setYHeadRot(41);
        if (global) {
            List<ServerPlayer> players = field(server.getPlayerList(), "players");
            players.add(player);
            server.getPlayerList().getPlayersByUUID().put(player.getUUID(), player);
        }
        check(world.addFreshEntity(player), "Cannot register concrete player fixture");
        return player;
    }

    private static int countGlobalPlayer(MinecraftServer server, ServerPlayer expected) {
        int count = 0;
        for (ServerPlayer actual : server.getPlayerList().getPlayers()) if (actual == expected) count++;
        return count;
    }

    private static void removeConcretePlayerFixture(MinecraftServer server, ServerPlayer player) {
        ImmPtlChunkTracking.removePlayerFromChunkTrackersAndEntityTrackers(player);
        player.discard();
        List<ServerPlayer> players = field(server.getPlayerList(), "players");
        players.removeIf(candidate -> candidate == player);
        if (server.getPlayerList().getPlayersByUUID().get(player.getUUID()) == player) {
            server.getPlayerList().getPlayersByUUID().remove(player.getUUID());
        }
        player.getTextFilter().leave();
    }

    private record MountedFixture(
        ServerLevel source, ServerLevel destination, Entity root, Entity vehicle, Entity sibling,
        AtomicReference<Portal> portal
    ) {}

    private static void mountedPlayerValidationAndCrossing(
        ClientGameTestContext context, TestServerContext serverContext, UUID playerId
    ) {
        ResourceKey<Level> sourceDimension = serverContext.computeOnServer(server -> realPlayer(server, playerId).level().dimension());
        awaitRealPlayerSettled(context, serverContext, playerId, sourceDimension, "before mounted setup");
        MountedFixture fixture = serverContext.computeOnServer(server -> createMountedFixture(realPlayer(server, playerId), server.getLevel(Level.NETHER)));
        try {
            // Mount spawn/tracking packets must reach the real client before
            // the packet-validation and rollback stages use that riding graph.
            awaitRealPlayerSettled(context, serverContext, playerId, sourceDimension, "after mounted setup");
            serverContext.runOnServer(server -> mountedValidationAndRefusal(
                ServerTeleportationManager.of(server), realPlayer(server, playerId), fixture));
            awaitRealPlayerSettled(context, serverContext, playerId, sourceDimension, "after mounted refusal");
            serverContext.runOnServer(server -> completeMountedCrossing(
                ServerTeleportationManager.of(server), realPlayer(server, playerId), fixture));
            awaitRealPlayerSettled(context, serverContext, playerId, fixture.destination().dimension(), "after mounted destination correction");
        }
        finally {
            serverContext.runOnServer(server -> {
                ServerPlayer player = realPlayer(server, playerId);
                ((IEServerPlayerEntity) player).ip_stopRidingWithoutTeleportRequest();
                Portal portal = fixture.portal().get();
                if (portal != null && !portal.isRemoved()) portal.discard();
                for (Entity original : List.of(fixture.root(), fixture.vehicle(), fixture.sibling())) {
                    for (ServerLevel world : List.of(fixture.source(), fixture.destination())) {
                        Entity live = world.getEntity(original.getUUID());
                        if (live != null) live.discard();
                    }
                }
            });
        }
        ResourceKey<Level> finalDimension = serverContext.computeOnServer(server -> realPlayer(server, playerId).level().dimension());
        awaitRealPlayerSettled(context, serverContext, playerId, finalDimension, "after mounted cleanup");
    }

    private static MountedFixture createMountedFixture(ServerPlayer player, ServerLevel destination) {
        ServerLevel source = (ServerLevel) player.level();
        check(!((IEServerPlayNetworkHandler) player.connection).ip_hasAwaitingTeleport(),
            "Mounted setup started before the real teleport acknowledgement");
        Entity root = spawnRider(source);
        Entity vehicle = spawnRider(source);
        Entity sibling = spawnRider(source);
        for (Entity node : List.of(root, vehicle, sibling)) node.setNoGravity(true);
        check(vehicle.startRiding(root, true, true) && sibling.startRiding(root, true, true), "Cannot mount vehicle tree");
        ((IEServerPlayerEntity) player).ip_startRidingWithoutTeleportRequest(vehicle);
        check(player.getVehicle() == vehicle, "Cannot mount test player");
        return new MountedFixture(source, destination, root, vehicle, sibling, new AtomicReference<>());
    }

    private static void mountedValidationAndRefusal(
        ServerTeleportationManager manager, ServerPlayer player, MountedFixture fixture
    ) {
        ServerLevel source = fixture.source();
        ServerLevel destination = fixture.destination();
        check(!((IEServerPlayNetworkHandler) player.connection).ip_hasAwaitingTeleport(), "Real mounted setup acknowledgement did not settle");
        // Keep the controlled portal behind the stationary rider while the
        // scheduler advances; an incidental real crossing is not this fixture.
        Portal portal = portal(source, player.getEyePosition().add(0, 0, -2), destination, new Vec3(0, 120, 0));
        fixture.portal().set(portal);
        check(source.addFreshEntity(portal), "Cannot spawn player crossing portal");
        Method validate = method(ServerTeleportationManager.class, "validatePlayerTeleportationAndGetReason",
            ServerPlayer.class, net.minecraft.resources.ResourceKey.class, Vec3.class, Portal.class);
        check(invoke(validate, manager, player, source.dimension(), player.position(), portal) == null,
            "Legitimate mounted crossing was rejected");
        portal.specificPlayerId = UUID.randomUUID();
        check(invoke(validate, manager, player, source.dimension(), player.position(), portal) != null,
            "Mounted player bypassed private portal permission");
        portal.specificPlayerId = null;
        portal.setTeleportable(false);
        check(invoke(validate, manager, player, source.dimension(), player.position(), portal) != null,
            "Mounted player bypassed disabled portal permission");
        portal.setTeleportable(true);
        check(invoke(validate, manager, player, destination.dimension(), player.position(), portal) != null,
            "Mounted player bypassed source dimension validation");
        check(invoke(validate, manager, player, source.dimension(), player.position().add(17, 0, 0), portal) != null,
            "Mounted player bypassed packet distance validation");
        portal.setOriginPos(player.getEyePosition().add(100, 0, 0));
        check(invoke(validate, manager, player, source.dimension(), player.position(), portal) != null,
            "Mounted player bypassed portal distance validation");
        portal.setOriginPos(player.getEyePosition().add(0, 0, -2));
        nonFinitePacketsHaveNoSideEffects(manager, player);
        mountedRefusedTransferHasNoStateChanges(manager, player, portal, fixture.root(), fixture.vehicle(), fixture.sibling(), destination);
    }

    private static void completeMountedCrossing(
        ServerTeleportationManager manager, ServerPlayer player, MountedFixture fixture
    ) {
        ServerLevel source = fixture.source();
        ServerLevel destination = fixture.destination();
        Portal portal = fixture.portal().get();
        check(player.level() == source && !((IEServerPlayNetworkHandler) player.connection).ip_hasAwaitingTeleport(),
            "Mounted retry started before the real refusal correction was acknowledged");
        portal.setOriginPos(player.getEyePosition().add(0, 0, -2));
        manager.onPlayerTeleportedInClient(player, source.dimension(), player.getEyePosition(), portal.getUUID());
        check(player.level() == destination, "Valid mounted crossing did not move player");
        check(player.getVehicle() != null && player.getVehicle().getUUID().equals(fixture.vehicle().getUUID()),
            "Crossing lost player's immediate vehicle");
        check(player.getRootVehicle().getUUID().equals(fixture.root().getUUID()), "Crossing lost ancestor vehicle");
        check(destination.getEntity(fixture.sibling().getUUID()).getVehicle() == player.getRootVehicle(), "Crossing lost sibling rider");
        for (Entity original : List.of(fixture.root(), fixture.vehicle(), fixture.sibling())) {
            check(original.isRemoved() && liveCopies(source, original.getUUID()) == 0,
                "Mounted crossing left original vehicle alive");
        }
        // The server-side packet fixture did not predict the move on the real
        // client. Send a genuine authoritative correction, then let that client
        // switch worlds and acknowledge it outside this runnable.
        ((IEServerPlayNetworkHandler) player.connection).ip_sendPositionCorrection();
        player.connection.resetPosition();
        Method validate = method(ServerTeleportationManager.class, "validatePlayerTeleportationAndGetReason",
            ServerPlayer.class, ResourceKey.class, Vec3.class, Portal.class);
        check("has awaiting teleport".equals(invoke(validate, manager, player, destination.dimension(), player.position(), portal)),
            "Mounted player bypassed an awaiting teleport acknowledgement");
    }

    private static void mountedRefusedTransferHasNoStateChanges(
        ServerTeleportationManager manager, ServerPlayer player, Portal portal, Entity root, Entity vehicle,
        Entity sibling, ServerLevel destination
    ) {
        ServerLevel source = (ServerLevel) player.level();
        Vec3 before = player.position();
        Map<?, ?> cooldowns = field(manager, "lastTeleportGameTime");
        Object oldCooldown = cooldowns.get(player);
        Object oldLastPosition = manager.lastPosition.get(player);
        List<Entity> seats = root.getPassengers();
        Entity conflict = EntityTypes.ARMOR_STAND.create(destination, EntitySpawnReason.COMMAND);
        check(conflict != null, "Cannot create mounted refusal fixture");
        conflict.setUUID(sibling.getUUID());
        conflict.setPos(0, 100, 0);
        check(destination.addFreshEntity(conflict), "Cannot register mounted refusal fixture");
        try {
            manager.onPlayerTeleportedInClient(player, source.dimension(), player.getEyePosition(), portal.getUUID());
            check(player.level() == source && player.position().equals(before), "Refused crossing moved the player");
            check(player.getVehicle() == vehicle && player.getRootVehicle() == root, "Refused crossing changed the player's mount");
            check(sameIdentityOrder(root.getPassengers(), seats), "Refused crossing changed the vehicle tree or seat order");
            check(cooldowns.get(player) == oldCooldown, "Refused crossing changed player cooldown");
            check(manager.lastPosition.get(player) == oldLastPosition, "Refused crossing changed last position");
            check(((IEServerPlayNetworkHandler) player.connection).ip_hasAwaitingTeleport(),
                "Refused predicted crossing did not send a correction");
            check(before.equals(field(player.connection, "awaitingPositionFromClient")), "Correction contains the wrong position");
            check(source.dimension() == field(player.connection, "ip_dimOfAwaitingPosition"), "Correction contains the wrong dimension");
            for (Entity member : List.of(root, vehicle, sibling)) {
                check(!member.isRemoved() && source.getEntity(member.getUUID()) == member,
                    "Refused mounted crossing lost an original tree member");
            }
        }
        finally {
            conflict.discard();
        }
    }

    private static Entity spawnRider(ServerLevel world) {
        Entity entity = EntityTypes.ARMOR_STAND.create(world, EntitySpawnReason.COMMAND);
        check(entity != null, "Cannot create passenger fixture");
        entity.setPos(0, 100, 0);
        check(world.addFreshEntity(entity), "Cannot spawn passenger fixture");
        return entity;
    }

    private static Portal portal(ServerLevel source, Vec3 origin, ServerLevel destination, Vec3 target) {
        Portal portal = Portal.ENTITY_TYPE.create(source, EntitySpawnReason.COMMAND);
        check(portal != null, "Cannot create portal fixture");
        portal.setOriginPos(origin);
        portal.setDestDim(destination.dimension());
        portal.setDestination(target);
        portal.setOrientationRotation(DQuaternion.identity);
        portal.setWidth(4);
        portal.setHeight(6);
        return portal;
    }

    private static Method method(Class<?> owner, String name, Class<?>... parameters) {
        try {
            Method method = owner.getDeclaredMethod(name, parameters);
            method.setAccessible(true);
            return method;
        }
        catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static Object invoke(Method method, Object owner, Object... arguments) {
        try {
            return method.invoke(owner, arguments);
        }
        catch (InvocationTargetException exception) {
            throw new AssertionError("Regression operation failed", exception.getCause());
        }
        catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object owner, String name) {
        return (T) readField(owner.getClass(), owner, name);
    }

    @SuppressWarnings("unchecked")
    private static <T> T staticField(Class<?> owner, String name) {
        return (T) readField(owner, null, name);
    }

    private static Object readField(Class<?> type, Object owner, String name) {
        try {
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                try {
                    Field field = current.getDeclaredField(name);
                    field.setAccessible(true);
                    return field.get(owner);
                }
                catch (NoSuchFieldException absent) {
                    // Vanilla player-list and entity fields may be inherited.
                }
            }
            throw new NoSuchFieldException(name);
        }
        catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
