package qouteall.imm_ptl.peripheral.riftwoven;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import qouteall.dimlib.api.DimensionAPI;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.api.PortalAPI;
import qouteall.imm_ptl.core.platform_shared.PlatformHooks;
import qouteall.imm_ptl.core.platform_shared.ServerLifecycleEvents;
import qouteall.imm_ptl.core.platform_shared.ServerTickEvents;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.peripheral.riftwoven.profile.RealmRecipe;
import qouteall.imm_ptl.peripheral.riftwoven.storage.Departure;
import qouteall.imm_ptl.peripheral.riftwoven.storage.Draft;
import qouteall.imm_ptl.peripheral.riftwoven.storage.RealmLedger;
import qouteall.imm_ptl.peripheral.riftwoven.storage.RealmRecord;
import qouteall.imm_ptl.peripheral.riftwoven.worldgen.RealmBiomeSource;
import qouteall.imm_ptl.peripheral.riftwoven.worldgen.RealmChunkGenerator;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/** Shared, server-authoritative survival entrypoint. Existing portal registries stay unchanged. */
public final class Riftwoven {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final Identifier SEED_ID = Identifier.parse("riftwoven:realm_seed");
    public static final Identifier PORTAL_ID = Identifier.parse("riftwoven:realm_portal");
    public static final RealmCoreBlock CORE = new RealmCoreBlock(BlockBehaviour.Properties.of()
        .setId(ResourceKey.create(Registries.BLOCK, SEED_ID)).strength(4, 1200)
        .sound(SoundType.AMETHYST).pushReaction(PushReaction.IMMOVEABLE)
        .lightLevel(state -> 3 + state.getValue(RealmCoreBlock.GROWTH) * 5));
    public static final RealmSeedItem SEED = new RealmSeedItem(CORE, new Item.Properties()
        .setId(ResourceKey.create(Registries.ITEM, SEED_ID)).useBlockDescriptionPrefix().stacksTo(1));
    public static final BlockEntityType<RealmCoreBlockEntity> CORE_ENTITY =
        new BlockEntityType<>(RealmCoreBlockEntity::new, Set.of(CORE));
    public static final EntityType<RealmPortal> REALM_PORTAL = Portal.createPortalEntityType(PORTAL_ID, RealmPortal::new);
    private static final Map<MinecraftServer, RuntimeState> STATES = new WeakHashMap<>();

    private static final class RuntimeState {
        final Map<UUID, Long> heldUntil = new HashMap<>();
        final Map<UUID, Channel> channels = new HashMap<>();
        final Map<UUID, Integer> extraction = new HashMap<>();
        final Map<UUID, Integer> recoverySelection = new HashMap<>();
        final Set<UUID> recovered = new HashSet<>();
        final Set<UUID> entered = new HashSet<>();
        final Map<UUID, Integer> recoveryRetry = new HashMap<>();
    }

    private record Channel(ResourceKey<Level> level, BlockPos core, UUID realm, long start, boolean awaken) {}
    @FunctionalInterface private interface Action { void run() throws IOException; }

    private Riftwoven() {}

    public static void init() {
        PlatformHooks.register(BuiltInRegistries.BLOCK, SEED_ID, CORE);
        PlatformHooks.register(BuiltInRegistries.ITEM, SEED_ID, SEED);
        PlatformHooks.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, SEED_ID, CORE_ENTITY);
        PlatformHooks.register(BuiltInRegistries.ENTITY_TYPE, PORTAL_ID, REALM_PORTAL);
        PlatformHooks.register(BuiltInRegistries.CHUNK_GENERATOR, "riftwoven:realm", RealmChunkGenerator.MAP_CODEC);
        PlatformHooks.register(BuiltInRegistries.BIOME_SOURCE, "riftwoven:realm", RealmBiomeSource.MAP_CODEC);
        DimensionAPI.suppressExperimentalWarningForNamespace("riftwoven");
        RealmInput.init();
        qouteall.imm_ptl.peripheral.riftwoven.environment.RealmEnvironment.init();
        ServerTickEvents.END_SERVER_TICK.register(Riftwoven::tick);
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try {
                for (RealmRecord record : RealmLedger.open(server).realms()) {
                    try { ensureWorld(server, record); }
                    catch (RuntimeException | IOException exception) {
                        LOGGER.error("Cannot activate realm {}; its paid identity is retained", record.id(), exception);
                    }
                }
            } catch (IOException exception) { LOGGER.error("Cannot open realm ledger", exception); }
        });
        IPGlobal.SERVER_CLEANUP_EVENT.register(server -> {
            STATES.remove(server);
            RealmLedger.close(server);
        });
    }

    private static RuntimeState state(MinecraftServer server) {
        return STATES.computeIfAbsent(server, ignored -> new RuntimeState());
    }

    private static RealmLedger ledger(ServerPlayer player) throws IOException {
        return RealmLedger.open(player.level().getServer());
    }

    private static void guarded(ServerPlayer player, Action action) {
        try { action.run(); }
        catch (IOException | IllegalArgumentException | IllegalStateException exception) {
            player.sendSystemMessage(Component.literal("Riftwoven: " + exception.getMessage()));
            LOGGER.warn("Realm action refused for {}: {}", player.getUUID(), exception.toString());
        }
    }

    public static boolean canPlaceReference(ServerPlayer player, ItemStack stack) {
        UUID id = RealmSeedItem.reference(stack);
        if (id == null) return true;
        try {
            Draft draft = ledger(player).draft(id);
            return draft != null && (draft.owner().equals(player.getUUID()) || ledger(player).canView(id, player.getUUID()));
        } catch (IOException exception) { return false; }
    }

    public static void placed(ServerPlayer player, BlockPos pos, ItemStack stack) {
        placed(player, (ServerLevel) player.level(), pos, stack);
    }

    public static void placed(ServerPlayer player, ServerLevel target, BlockPos pos, ItemStack stack) {
        guarded(player, () -> {
            if (!(target.getBlockEntity(pos) instanceof RealmCoreBlockEntity core)) return;
            UUID reference = RealmSeedItem.reference(stack);
            if (reference == null) reference = ledger(player).createDraft(player);
            core.bind(reference);
            updateGrowth(target, core, ledger(player));
            player.sendSystemMessage(Component.literal("Realm Seed planted. Use materials to offer up to "
                + RealmRecipe.transferLimit() + "; empty-hand use inspects; sneak-use empty-handed withdraws."
                + " Sneak and hold use with four diamonds to awaken. No world is sampled before payment."));
        });
    }

    public static void useCore(ServerPlayer player, BlockPos pos, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND || player.isSpectator() || !player.isAlive()) return;
        guarded(player, () -> {
            if (!(player.level().getBlockEntity(pos) instanceof RealmCoreBlockEntity core)) return;
            RealmLedger ledger = ledger(player);
            if (isRealm(player.level()) && pos.equals(new BlockPos(0, 80, 4))) {
                returnPlayer(player);
                return;
            }
            ledger.recover(player);
            if (core.realmId() == null) core.bind(ledger.createDraft(player));
            UUID id = core.realmId();
            Draft draft = ledger.draft(id);
            if (draft == null) throw new IOException("Unknown realm reference; no replacement world was generated.");
            RealmRecord realm = ledger.realm(id);
            if (realm != null) {
                if (!ledger.canView(id, player.getUUID())) {
                    throw new IOException("The owner must grant viewing permission before opening a revealing rift.");
                }
                if (!player.getMainHandItem().isEmpty()) {
                    player.sendSystemMessage(Component.literal(RealmRecipe.summary(realm.profile())));
                    return;
                }
                begin(player, core, false);
                return;
            }
            if (!draft.owner().equals(player.getUUID())) throw new IOException("Only the seed's owner may manage its offerings.");
            if (player.getMainHandItem().isEmpty()) {
                if (player.isShiftKeyDown()) {
                    var refund = ledger.withdraw(player, id);
                    player.sendSystemMessage(Component.literal("Withdrawal: " + refund.status()
                        + (refund.contribution() == null ? "" : " — " + refund.contribution())));
                }
                player.sendSystemMessage(Component.literal(RealmRecipe.summary(RealmRecipe.resolve(ledger.draft(id).recipe()))));
            } else if (player.isShiftKeyDown() && player.getMainHandItem().is(Items.DIAMOND)) {
                begin(player, core, true);
            } else {
                String problem = RealmFormation.problem(player.level(), pos, core.getBlockState().getValue(RealmCoreBlock.FACING));
                if (problem != null) throw new IOException(problem);
                int count = Math.min(RealmRecipe.transferLimit(), player.getMainHandItem().getCount());
                var offered = ledger.offer(player, id, hand, count);
                player.sendOverlayMessage(Component.literal("Offered " + offered + ". Empty-hand use to inspect."));
            }
            updateGrowth((ServerLevel) player.level(), core, ledger);
        });
    }

    private static void begin(ServerPlayer player, RealmCoreBlockEntity core, boolean awaken) throws IOException {
        String problem = RealmFormation.problem(player.level(), core.getBlockPos(), core.getBlockState().getValue(RealmCoreBlock.FACING));
        if (problem != null) throw new IOException(problem);
        RuntimeState state = state(player.level().getServer());
        Channel channel = new Channel(player.level().dimension(), core.getBlockPos(), core.realmId(),
            player.level().getGameTime(), awaken);
        if (state.channels.putIfAbsent(player.getUUID(), channel) == null) {
            var ledger = ledger(player);
            var profile = awaken ? RealmRecipe.resolve(ledger.draft(core.realmId()).recipe()) : ledger.realm(core.realmId()).profile();
            player.sendSystemMessage(Component.literal(RealmRecipe.summary(profile)
                + " Shader packs can replace the sky; extra visible bodies require the pack's normal sun/moon rendering."
                + (awaken ? " Hold sneak + use for three seconds. Awakening costs four diamonds and is irreversible."
                : " Hold use for three seconds to accept these dangers and open your private rift."
                + " Normal death rules apply; the arrival core and thirty-second emergency ritual provide return access.")));
        }
    }

    public static void input(ServerPlayer player, boolean held) {
        RuntimeState state = state(player.level().getServer());
        if (held) state.heldUntil.put(player.getUUID(), player.level().getGameTime() + 8);
        else {
            state.heldUntil.remove(player.getUUID());
            state.channels.remove(player.getUUID());
            state.extraction.remove(player.getUUID());
        }
    }

    private static void tick(MinecraftServer server) {
        RuntimeState state = state(server);
        Set<UUID> online = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID playerId = player.getUUID();
            online.add(playerId);
            if (!state.recovered.contains(playerId)
                && state.recoveryRetry.getOrDefault(playerId, 0) <= server.getTickCount()) {
                try { ledger(player).recover(player); state.recovered.add(playerId); }
                catch (IOException exception) {
                    // Payment recovery and visit recovery are independent. A blocked offering
                    // must never disable the arrival core or the emergency extraction ritual.
                    state.recoveryRetry.put(playerId, server.getTickCount() + 100);
                }
            }
            long now = player.level().getGameTime();
            if (isRealm(player.level())) state.entered.add(playerId);
            else if (state.entered.contains(playerId)
                && state.recoveryRetry.getOrDefault(playerId, 0) <= server.getTickCount()
                && !((qouteall.imm_ptl.core.ducks.IEServerPlayNetworkHandler) player.connection).ip_hasAwaitingTeleport()) {
                state.recoveryRetry.put(playerId, server.getTickCount() + 100);
                guarded(player, () -> {
                    Departure departure = ledger(player).departure(playerId);
                    if (departure != null) {
                        ledger(player).savePlayerState(player);
                        ledger(player).confirmDepartureLeft(playerId, departure.realm());
                    }
                    state.entered.remove(playerId);
                });
            }
            boolean held = state.heldUntil.getOrDefault(playerId, Long.MIN_VALUE) >= now;
            Channel channel = state.channels.get(playerId);
            if (channel != null) {
                HitResult hit = player.pick(player.blockInteractionRange(), 0, false);
                if (!held || !player.isAlive() || player.isSpectator() || !player.level().dimension().equals(channel.level())
                    || !(hit instanceof BlockHitResult blockHit) || !blockHit.getBlockPos().equals(channel.core())
                    || channel.awaken() && (!player.isShiftKeyDown() || !player.getMainHandItem().is(Items.DIAMOND))) {
                    state.channels.remove(playerId);
                    player.sendOverlayMessage(Component.literal("Ritual cancelled; no new payment taken."));
                } else if (now - channel.start() >= 60) {
                    state.channels.remove(playerId);
                    guarded(player, () -> completeChannel(player, channel));
                } else if (now % 10 == 0) {
                    player.sendOverlayMessage(Component.literal("Awakening thread: " + (now - channel.start()) * 100 / 60 + "%"));
                }
            }
            if (held && isRealm(player.level()) && player.isShiftKeyDown() && player.getXRot() < -65
                && player.getMainHandItem().isEmpty() && player.getOffhandItem().isEmpty() && player.isAlive()) {
                int progress = state.extraction.merge(playerId, 1, Integer::sum);
                if (progress % 20 == 0) player.sendOverlayMessage(Component.literal("Following your return thread: " + progress / 20 + "/30 seconds"));
                if (progress >= 600) { state.extraction.remove(playerId); returnPlayer(player); }
            } else state.extraction.remove(playerId);
        }
        state.recovered.retainAll(online);
        state.heldUntil.keySet().retainAll(online);
        state.channels.keySet().retainAll(online);
        state.extraction.keySet().retainAll(online);
        state.recoverySelection.keySet().retainAll(online);
        state.entered.retainAll(online);
        state.recoveryRetry.keySet().retainAll(online);
    }

    private static void completeChannel(ServerPlayer player, Channel channel) throws IOException {
        if (!(player.level().getBlockEntity(channel.core()) instanceof RealmCoreBlockEntity core)
            || !channel.realm().equals(core.realmId())) throw new IOException("The core changed; start again.");
        String problem = RealmFormation.problem(player.level(), channel.core(), core.getBlockState().getValue(RealmCoreBlock.FACING));
        if (problem != null) throw new IOException(problem);
        RealmLedger ledger = ledger(player);
        RealmRecord record = channel.awaken() ? ledger.awaken(player, channel.realm()) : ledger.realm(channel.realm());
        if (record == null) throw new IOException("Realm is not awakened.");
        ServerLevel destination = ensureWorld(player.level().getServer(), record);
        prepareArrival(destination, record);
        updateGrowth((ServerLevel) player.level(), core, ledger);
        if (channel.awaken()) award(player, "awaken");
        openRift(player, core, record, destination);
    }

    public static ServerLevel ensureWorld(MinecraftServer server, RealmRecord record) throws IOException {
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, record.dimensionId());
        ServerLevel world = server.getLevel(key);
        if (world == null) {
            var type = server.registryAccess().lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(BuiltinDimensionTypes.OVERWORLD);
            DimensionAPI.addDimensionDynamically(server, record.dimensionId(),
                new LevelStem(type, RealmChunkGenerator.create(server.registryAccess(), record.profile(), record.seed())));
            world = server.getLevel(key);
        }
        if (world == null || !(world.getChunkSource().getGenerator() instanceof RealmChunkGenerator generator)
            || generator.realmSeed() != record.seed() || !generator.profile().equals(record.profile())) {
            throw new IOException("Saved dimension does not match its immutable realm record; refusing to regenerate.");
        }
        world.getWorldBorder().setCenter(0, 0);
        world.getWorldBorder().setSize(record.profile().radius() * 2.0);
        world.getWorldBorder().setWarningBlocks(8);
        world.getWorldBorder().setDamagePerBlock(0);
        return world;
    }

    public static void prepareArrival(ServerLevel world, RealmRecord record) {
        // A reserved generation envelope, never a player's building site. Every activation validates it.
        RealmProtection.withArrivalEdits(() -> {
            for (int x = -8; x <= 8; x++) for (int z = -8; z <= 8; z++) {
                for (int y = 79; y <= 85; y++) {
                    boolean boundary = y == 79 || y == 85 || Math.abs(x) == 8 || Math.abs(z) == 8;
                    boolean door = z == -8 && Math.abs(x) <= 1 && y >= 80 && y <= 82;
                    var block = !boundary || door ? Blocks.AIR.defaultBlockState()
                        : y == 79 || y == 85 ? Blocks.BEDROCK.defaultBlockState() : Blocks.GLASS.defaultBlockState();
                    if (!world.getBlockState(new BlockPos(x, y, z)).equals(block)) {
                        world.setBlock(new BlockPos(x, y, z), block, 3);
                    }
                }
            }
            BlockPos returnPos = new BlockPos(0, 80, 4);
            world.setBlock(returnPos, CORE.defaultBlockState().setValue(RealmCoreBlock.GROWTH, 2), 3);
            if (world.getBlockEntity(returnPos) instanceof RealmCoreBlockEntity core) core.bind(record.id());
            world.setBlock(new BlockPos(-3, 84, 0), Blocks.SEA_LANTERN.defaultBlockState(), 3);
            world.setBlock(new BlockPos(3, 84, 0), Blocks.SEA_LANTERN.defaultBlockState(), 3);
        });
    }

    private static void openRift(ServerPlayer player, RealmCoreBlockEntity core, RealmRecord record,
        ServerLevel destination) throws IOException {
        RealmLedger ledger = ledger(player);
        if (!ledger.canView(record.id(), player.getUUID())) {
            throw new IOException("Viewing permission was revoked.");
        }
        if (isRealm(player.level())) throw new IOException("Open realm entrances from an ordinary dimension; nested visits are not supported.");
        Vec3 departure = safePosition((ServerLevel) player.level(), player.position());
        if (departure == null) throw new IOException("Stand on safe ground before opening the realm.");
        Departure previous = ledger.departure(player.getUUID());
        if (previous != null) {
            // Outside and explicitly selecting a different destination: invalidate old doorways
            // before replacing the abandoned visit intent. Occupants never take this path.
            for (ServerLevel level : player.level().getServer().getAllLevels()) {
                for (var entity : level.getAllEntities()) {
                    if (entity instanceof RealmPortal old && player.getUUID().equals(old.specificPlayerId)) old.discard();
                }
            }
            ledger.savePlayerState(player);
            ledger.confirmDepartureLeft(player.getUUID(), previous.realm());
        }
        if (ledger.canEnter(record.id(), player.getUUID())) {
            ledger.recordDeparture(player.getUUID(), new Departure(record.id(),
                player.level().dimension().identifier().toString(), departure.x, departure.y, departure.z,
                player.getYRot(), player.getXRot()));
        } else {
            player.sendSystemMessage(Component.literal("View-only access: this rift cannot admit you."));
        }
        // One private portal per player; other users explicitly consent at the same core separately.
        ServerLevel source = (ServerLevel) player.level();
        Direction facing = core.getBlockState().getValue(RealmCoreBlock.FACING);
        Vec3 center = Vec3.atBottomCenterOf(core.getBlockPos()).add(0, 2.5, 0);
        RealmPortal portal = REALM_PORTAL.create(source, EntitySpawnReason.COMMAND);
        if (portal == null) throw new IOException("Cannot construct rift entity.");
        portal.configure(record.id(), player.getUUID(), false);
        portal.setTeleportable(ledger.canEnter(record.id(), player.getUUID()));
        portal.setOriginPos(center);
        Vec3 width = Vec3.atLowerCornerOf(facing.getClockWise().getUnitVec3i());
        portal.setOrientationAndSize(width, new Vec3(0, 1, 0), 3, 3);
        portal.setDestinationDimension(destination.dimension());
        portal.setDestination(new Vec3(0.5, 81.5, 0.5));
        qouteall.imm_ptl.peripheral.riftwoven.environment.RealmEnvironment.send(player, destination);
        if (!source.addFreshEntity(portal)) throw new IOException("Rift admission was refused; the paid realm is retained.");
        award(player, "return");
        player.sendSystemMessage(Component.literal("Rift opened. Return core: center terrace. Emergency return: empty both hands, look up, sneak + hold use for thirty seconds."));
    }

    public static boolean portalAdmission(ServerPlayer player, UUID realm, boolean returning) {
        if (realm == null) return false;
        try {
            RealmLedger ledger = ledger(player);
            return returning ? ledger.canReturn(player.getUUID(), realm)
                : ledger.canEnter(realm, player.getUUID()) && ledger.canView(realm, player.getUUID())
                    && ledger.canReturn(player.getUUID(), realm);
        } catch (IOException exception) { return false; }
    }

    public static boolean portalViewAllowed(ServerPlayer player, UUID realm) {
        if (realm == null) return false;
        try { return ledger(player).canView(realm, player.getUUID()); }
        catch (IOException exception) { return false; }
    }

    public static boolean isRealm(Level level) {
        return level instanceof ServerLevel serverLevel && serverLevel.getChunkSource().getGenerator() instanceof RealmChunkGenerator;
    }

    public static boolean canBuild(ServerPlayer player) {
        return canBuild(player, (ServerLevel) player.level());
    }

    public static boolean canBuild(ServerPlayer player, ServerLevel target) {
        if (!isRealm(target)) return true;
        try {
            Identifier dimension = target.dimension().identifier();
            for (RealmRecord record : ledger(player).realms()) {
                if (record.dimensionId().equals(dimension)) return ledger(player).canBuild(record.id(), player.getUUID());
            }
        } catch (IOException exception) { return false; }
        return false;
    }

    public static void returnPlayer(ServerPlayer player) {
        guarded(player, () -> {
            if (!isRealm(player.level())) throw new IOException("You are not visiting a realm.");
            if (player.isPassenger() || player.isVehicle()) {
                throw new IOException("Dismount and let passengers dismount before returning; this route carries only players and their inventories.");
            }
            Departure departure;
            try {
                departure = ledger(player).departure(player.getUUID());
            } catch (IOException exception) {
                // The approved spawn-area fallback does not depend on the payment/visit store.
                // Retain unreadable evidence, but do not trap an occupant behind a broken ledger.
                LOGGER.warn("Cannot read return record for {}; trying safe spawn fallback", player.getUUID(), exception);
                player.sendSystemMessage(Component.literal("Your exact return record is unavailable. Trying the safe Overworld fallback; no realm data will be removed."));
                departure = null;
            }
            ServerLevel target = departure == null ? null : player.level().getServer().getLevel(
                ResourceKey.create(Registries.DIMENSION, Identifier.parse(departure.dimension())));
            Vec3 position = target == null ? null : safePosition(target, new Vec3(departure.x(), departure.y(), departure.z()));
            if (position == null) {
                target = player.level().getServer().overworld();
                BlockPos spawn = player.level().getServer().getRespawnData().globalPos().pos();
                position = findFallback(target, spawn);
            }
            if (position == null) throw new IOException("No safe return location is currently available; no terrain was overwritten.");
            // Use the maintained admission/rollback path; never force-add or bypass cancellation.
            var result = PortalAPI.teleportEntity(player, target, position);
            if (result != player || player.level() != target) throw new IOException("Return transfer was refused; your return record is retained.");
            // Record remains until a later acknowledged exterior tick, not the moment a packet is sent.
        });
    }

    private static Vec3 findFallback(ServerLevel level, BlockPos spawn) {
        for (int radius = 0; radius <= 8; radius++) {
            for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
                if (Math.abs(dx) != radius && Math.abs(dz) != radius) continue;
                for (int dy = 0; dy <= 8; dy++) {
                    Vec3 candidate = Vec3.atBottomCenterOf(spawn.offset(dx, dy, dz));
                    Vec3 safe = safePosition(level, candidate);
                    if (safe != null) return safe;
                }
            }
        }
        return null;
    }

    private static Vec3 safePosition(ServerLevel level, Vec3 position) {
        BlockPos feet = BlockPos.containing(position);
        if (!level.getWorldBorder().isWithinBounds(feet) || !level.getBlockState(feet).isAir()
            || !level.getBlockState(feet.above()).isAir()
            || !level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), Direction.UP)
            || level.getBlockState(feet.below()).is(Blocks.MAGMA_BLOCK)
            || level.getBlockState(feet.below()).is(Blocks.CAMPFIRE)
            || level.getBlockState(feet.below()).is(Blocks.SOUL_CAMPFIRE)) return null;
        return Vec3.atBottomCenterOf(feet);
    }

    public static void recoverReference(ServerPlayer player, InteractionHand hand) {
        guarded(player, () -> {
            RealmLedger ledger = ledger(player);
            List<UUID> references = new ArrayList<>();
            for (Draft draft : ledger.ownedDrafts(player.getUUID())) references.add(draft.id());
            for (RealmRecord record : ledger.accessibleRealms(player.getUUID())) if (!references.contains(record.id())) references.add(record.id());
            references.sort(UUID::compareTo);
            if (references.isEmpty()) throw new IOException("No owned drafts or shared realms to recover.");
            RuntimeState state = state(player.level().getServer());
            int selected = state.recoverySelection.getOrDefault(player.getUUID(), 0) % references.size();
            UUID id = references.get(selected);
            RealmSeedItem.bind(player.getItemInHand(hand), id);
            state.recoverySelection.put(player.getUUID(), selected + 1);
            player.sendSystemMessage(Component.literal("Reference " + (selected + 1) + "/" + references.size() + ": " + id
                + ". Sneak-use in air cycles your references; place to reconnect. No terrain or offerings were reset."));
        });
    }

    private static void updateGrowth(ServerLevel level, RealmCoreBlockEntity core, RealmLedger ledger) throws IOException {
        int growth = ledger.realm(core.realmId()) != null ? 2 : ledger.draft(core.realmId()).contributions().isEmpty() ? 0 : 1;
        level.setBlock(core.getBlockPos(), core.getBlockState().setValue(RealmCoreBlock.GROWTH, growth), 3);
    }

    private static void award(ServerPlayer player, String path) {
        var advancement = player.level().getServer().getAdvancements().get(Identifier.parse("riftwoven:" + path));
        if (advancement != null) player.getAdvancements().award(advancement, path);
    }

    public static void grant(ServerPlayer owner, UUID realm, UUID member, boolean build) throws IOException {
        ledger(owner).grant(owner, realm, member, build
            ? Set.of(RealmLedger.Permission.ENTER, RealmLedger.Permission.VIEW, RealmLedger.Permission.BUILD)
            : Set.of(RealmLedger.Permission.ENTER, RealmLedger.Permission.VIEW));
    }

    public static void grantView(ServerPlayer owner, UUID realm, UUID member) throws IOException {
        ledger(owner).grant(owner, realm, member, Set.of(RealmLedger.Permission.VIEW));
    }

    public static void revoke(ServerPlayer owner, UUID realm, UUID member) throws IOException {
        ledger(owner).revoke(owner, realm, member);
        for (ServerLevel level : owner.level().getServer().getAllLevels()) {
            for (var entity : level.getAllEntities()) {
                if (entity instanceof RealmPortal portal && member.equals(portal.specificPlayerId)
                    && realm.equals(portal.realmId())) portal.discard();
            }
        }
    }

    public static void listRealms(ServerPlayer player) {
        guarded(player, () -> {
            for (Draft draft : ledger(player).ownedDrafts(player.getUUID())) {
                RealmRecord record = ledger(player).realm(draft.id());
                player.sendSystemMessage(Component.literal(draft.id() + (record == null ? " — growing seed" : " — awakened realm")));
            }
        });
    }
}
