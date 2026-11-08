package qouteall.imm_ptl.recovery_test;

import com.google.gson.GsonBuilder;
import com.mojang.authlib.GameProfile;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityMountEvent;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.api.PortalAPI;
import qouteall.imm_ptl.core.ducks.IEPlayerPositionLookS2CPacket;
import qouteall.imm_ptl.core.mixin.common.entity_sync.registration.RegistrationEntityAccessor;
import qouteall.imm_ptl.core.network.PacketRedirection;
import qouteall.imm_ptl.core.platform_shared.EntityRegistration;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.custom_portal_gen.CustomPortalGeneration;
import qouteall.imm_ptl.core.teleportation.ServerTeleportationManager;
import qouteall.imm_ptl.peripheral.wand.PortalWandItem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/** An opt-in dedicated-server mod. It is never a source set or dependency of the release mod. */
@Mod(NativeRecoveryMod.ID)
public final class NativeRecoveryMod {
    static final String ID = "ip_native_recovery_test";
    private static final Logger LOGGER = LoggerFactory.getLogger(ID);
    private static final DeferredRegister.Entities TYPES = DeferredRegister.createEntities(ID);
    private static final DeferredHolder<EntityType<?>, EntityType<ProbeEntity>> PROBE = TYPES.registerEntityType(
        "recovery_probe", ProbeEntity::new, MobCategory.MISC,
        builder -> builder.sized(0.5F, 1.975F).clientTrackingRange(5));
    private static NativeRecoveryMod instance;
    private MinecraftServer server;
    private ServerLevel source;
    private ServerLevel destination;
    private State active;
    private final List<Case> cases = new ArrayList<>();
    private final List<Map<String, Object>> results = new ArrayList<>();
    private final Map<String, Object> report = new LinkedHashMap<>();
    private final Set<Entity> owned = Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<Peer> peers = new ArrayList<>();
    private final ConcurrentLinkedQueue<Sent> sent = new ConcurrentLinkedQueue<>();
    private final AtomicLong sequence = new AtomicLong();
    private int ticks;
    private int warmupTicks;
    private int nextCase;
    private int assertions;
    private boolean finished;

    public NativeRecoveryMod(IEventBus modBus) {
        validateLease();
        instance = this;
        TYPES.register(modBus);
        modBus.addListener((EntityAttributeCreationEvent event) -> event.put(PROBE.get(), ArmorStand.createAttributes().build()));
        NeoForge.EVENT_BUS.addListener(this::started);
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(this::join);
        NeoForge.EVENT_BUS.addListener(this::mount);
        NeoForge.EVENT_BUS.addListener(this::travel);
    }

    private static void validateLease() {
        try {
            String nonce = System.getProperty("ip.recovery.recoveryNonce", "");
            Path approved = Path.of("/tmp/opencode").toRealPath();
            Path world = Path.of("").toRealPath();
            Path report = Path.of(System.getProperty("ip.recovery.recoveryReport", "")).toAbsolutePath().normalize();
            if (nonce.length() < 32 || !world.getParent().equals(approved)
                || !world.getFileName().toString().startsWith("ip-native-recovery-world-")
                || !Files.readString(world.resolve(".ip-native-recovery-owned")).trim().equals(nonce)
                || report.getParent() == null || !report.getParent().toRealPath().getParent().equals(approved)
                || !report.getParent().getFileName().toString().startsWith("ip-native-recovery-report-")) {
                throw new IllegalStateException("This development mod requires a fresh owned recovery-test world and report directory");
            }
        }
        catch (IOException error) { throw new IllegalStateException("Native recovery ownership verification failed", error); }
    }

    /** The failure is after the real manager's registration and real NeoForge added lifecycle. */
    public static final class ProbeEntity extends ArmorStand {
        public ProbeEntity(EntityType<? extends ArmorStand> type, Level world) { super(type, world); }

        @Override
        public void onAddedToLevel() {
            super.onAddedToLevel();
            State state = instance.active;
            if (state != null && state.includes(this) && level() == state.destination
                && state.mode == Mode.ADDED_THROW && state.reject && getUUID().equals(state.rejected)) {
                instance.check(EntityRegistration.inspect(state.destination, this) == EntityRegistration.RegistrationState.REGISTERED,
                    "onAddedToLevel exception must follow full normal registration");
                state.hit = state.everHit = true;
                throw new IllegalStateException("IP_NATIVE_RECOVERY_INJECTED_AFTER_ADDED " + getUUID());
            }
        }
    }

    private void started(ServerStartedEvent event) {
        server = event.getServer();
        source = server.overworld();
        destination = server.getLevel(Level.NETHER);
        report.put("loader", "NeoForge 26.3.0.101");
        report.put("minecraft", "26.3");
        report.put("cases", results);
        report.put("limitations", List.of(
            "Concrete native ServerPlayer fixtures use real vanilla listeners and EmbeddedChannels, not logged-in clients.",
            "NeoForge's public configureMockConnection game-test API supplies test-only outbound channel compatibility; no real network negotiation or incoming teleport ACK is simulated.",
            "Global online-player admission, an actual native tracker watcher and client teleport settlement are not qualified by these server-only fixtures."));
        cases.add(new Case("packaged_mod_dedicated_boot", this::packagedBoot));
        for (boolean recreate : new boolean[]{false, true}) {
            for (int index : new int[]{0, 2, 3}) {
                cases.add(new Case("join_cancel_" + recreate + "_" + index,
                    () -> failedTransfer(Mode.JOIN_CANCEL, index, recreate, false, false)));
                cases.add(new Case("after_added_throw_" + recreate + "_" + index,
                    () -> failedTransfer(Mode.ADDED_THROW, index, recreate, false, false)));
            }
            for (Mode mode : new Mode[]{Mode.DISMOUNT_CANCEL, Mode.MOUNT_CANCEL}) {
                cases.add(new Case(mode.name().toLowerCase(java.util.Locale.ROOT) + "_" + recreate,
                    () -> failedTransfer(mode, 3, recreate, false, false)));
            }
            for (boolean sameWorld : new boolean[]{false, true}) {
                cases.add(new Case("travel_cancel_retry_" + recreate + "_same_" + sameWorld,
                    () -> failedTransfer(Mode.TRAVEL_CANCEL, 3, recreate, sameWorld, false)));
            }
            cases.add(new Case("villager_brain_" + recreate,
                () -> failedTransfer(Mode.JOIN_CANCEL, 3, recreate, false, true)));
            for (boolean uuidConflict : new boolean[]{false, true}) {
                cases.add(new Case("hidden_foreign_" + recreate + "_uuid_" + uuidConflict,
                    () -> hiddenForeign(recreate, uuidConflict)));
            }
        }
        for (boolean hidden : new boolean[]{false, true}) {
            cases.add(new Case("preexisting_candidate_" + hidden, () -> preexistingCandidate(hidden)));
        }
        cases.add(new Case("forced_mount_event", this::forcedMount));
        cases.add(new Case("concrete_two_player_packets", this::playerPackets));
        report.put("expected_cases", cases.size());
        LOGGER.info("IP_NATIVE_RECOVERY_BEGIN cases={}", cases.size());
    }

    private void tick(ServerTickEvent.Post event) {
        if (server == null || event.getServer() != server || finished) { return; }
        ticks++;
        if (ticks < 60) { return; }
        if (warmupTicks > 0) { warmupTicks--; return; }
        if (nextCase == cases.size()) { finish(); return; }
        Case current = cases.get(nextCase++);
        assertions = 0;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", current.name);
        try {
            current.test.run();
            result.put("status", "PASS");
            LOGGER.info("IP_NATIVE_RECOVERY_CASE_PASS {} assertions={}", current.name, assertions);
        }
        catch (Throwable failure) {
            result.put("status", "FAIL");
            result.put("failure", failure.toString());
            LOGGER.error("IP_NATIVE_RECOVERY_CASE_FAIL {}", current.name, failure);
        }
        finally {
            if (active != null) { result.put("native_event_evidence", active.evidence()); }
            active = null;
            try { cleanup(); }
            catch (Throwable failure) {
                result.put("status", "FAIL");
                result.put("cleanup_failure", failure.toString());
                LOGGER.error("IP_NATIVE_RECOVERY_CLEANUP_FAIL {}", current.name, failure);
            }
            result.put("assertions", assertions);
            results.add(result);
            writeReport();
        }
    }

    private void packagedBoot() throws Exception {
        check(FMLEnvironment.getDist() == Dist.DEDICATED_SERVER && server.isDedicatedServer(), "Wrong physical/logical server side");
        check(destination != null, "The real dedicated server must have a Nether");
        Path expected = Path.of(System.getProperty("ip.recovery.parentArtifact")).toRealPath();
        Path actual = ModList.get().getModFileById("immersive_portals").getFile().getFilePath().toRealPath();
        check(Files.isRegularFile(actual) && actual.equals(expected), "Parent mod is not the exact packaged jar: " + actual);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(actual)));
        check(sha.equals(System.getProperty("ip.recovery.parentArtifactSha256")), "Loaded parent artifact changed after launch validation");
        check(ModList.get().isLoaded("dimlib") && ModList.get().isLoaded("cloth_config"), "Native bundled dependencies did not load");
        check(ModList.get().getModContainerById("neoforge").orElseThrow().getModInfo().getVersion().toString().equals("26.3.0.101"),
            "Wrong actual NeoForge runtime version");
        verifyBundle("dimlib", "dimlibSha256");
        verifyBundle("cloth_config", "clothSha256");
        check(!ModList.get().isLoaded("fabricloader") && !ModList.get().isLoaded("connector"), "This is not a native-only classloader");
        check(ServerTeleportationManager.of(server) != null, "Main mod did not initialize server state");
        check(server.registryAccess().lookup(CustomPortalGeneration.REGISTRY_KEY).isPresent()
            && server.registryAccess().lookup(CustomPortalGeneration.LEGACY_REGISTRY_KEY).isPresent(),
            "Both actual portal-generation datapack registries must survive native early declaration");
        check(BuiltInRegistries.ITEM.getValue(PortalWandItem.ID) == PortalWandItem.instance,
            "Native bootstrap missed the common wand item registration");
        check(BuiltInRegistries.ENTITY_TYPE.entrySet().stream().anyMatch(entry -> entry.getValue() == Portal.ENTITY_TYPE),
            "Native bootstrap missed the primary portal EntityType registration");
        report.put("loaded_parent_jar", actual.toString());
        report.put("loaded_parent_sha256", sha);
        report.put("physical_side", FMLEnvironment.getDist().name());
        source.setChunkForced(0, 0, true);
        destination.setChunkForced(0, 0, true);
        source.getChunk(0, 0);
        destination.getChunk(0, 0);
        // Let the real chunk ticket/visibility pipeline settle before visible fixtures.
        warmupTicks = 40;
    }

    private void verifyBundle(String mod, String property) throws Exception {
        Path path = ModList.get().getModFileById(mod).getFile().getFilePath();
        check(Files.isRegularFile(path), "Bundled dependency was substituted with dev sources: " + mod);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        check(sha.equals(System.getProperty("ip.recovery." + property)), "Loaded dependency differs from the parent's bundle: " + mod);
        report.put("loaded_" + mod + "_sha256", sha);
    }

    private void failedTransfer(Mode mode, int rejectedIndex, boolean recreate, boolean sameWorld, boolean brain) {
        List<Entity> tree = tree(brain);
        ServerLevel target = sameWorld ? source : destination;
        State state = new State(mode, source, target, tree, tree.get(rejectedIndex).getUUID());
        active = state;
        List<Saved> saved = save(tree);
        Entity result = manager().changeEntityDimension(tree.getFirst(), target.dimension(), new Vec3(12, 120, 12), recreate);
        check(result == tree.getFirst(), "Refused move returned a replacement");
        check(state.hit, "The actual native failure gate was not reached: " + mode);
        if (mode == Mode.JOIN_CANCEL || mode == Mode.ADDED_THROW) {
            check(state.destinationJoins == rejectedIndex + 1, "First/middle/last failure did not occur at its expected admission slot");
        }
        assertSource(saved);
        assertTravel(state, tree);
        for (Entity entity : tree) {
            if (!sameWorld) { check(EntityRegistration.inspect(destination, entity) == EntityRegistration.RegistrationState.ABSENT,
                "Original identity survived at attempted destination"); }
        }
        for (Entity candidate : state.candidates) {
            check(EntityRegistration.inspect(target, candidate) == (sameWorld
                ? EntityRegistration.RegistrationState.REGISTERED : EntityRegistration.RegistrationState.ABSENT),
                "Tentative candidate survived rollback");
        }
        // The source EJL gate remains closed: only captured originals may use private repair.
        ProbeEntity probe = probe(source);
        probe.addTag(state.tag);
        check(!source.addFreshEntity(probe), "Captured source-repair privilege leaked to a new EJL admission");
        check(state.sourceJoins == 1, "Source restoration called the ordinary cancelled EJL admission");
        check(EntityRegistration.inspect(source, probe) == EntityRegistration.RegistrationState.ABSENT,
            "A refused unrelated source probe registered anyway");
        EntityRegistration.Snapshot fresh = EntityRegistration.capture(tree.getLast());
        check(EntityRegistration.restore(fresh).complete(), "Refusal leaked its exclusive snapshot lease");
        state.reject = false;
        state.priorTravel.add(Map.copyOf(state.travelCounts));
        state.travelCounts.clear();
        state.hit = false;
        int joinsBefore = state.destinationJoins;
        Entity moved = manager().changeEntityDimension(tree.getFirst(), target.dimension(), new Vec3(12, 120, 12), recreate);
        check(moved.level() == target && !moved.isRemoved(), "Corrected native retry did not commit");
        assertTravel(state, tree);
        List<Entity> targets = moved.getSelfAndPassengers().toList();
        check(targets.size() == tree.size(), "Corrected retry lost part of the tree");
        for (int i = 0; i < tree.size(); i++) {
            Entity entity = targets.get(i);
            check(entity.getUUID().equals(tree.get(i).getUUID()) && entity.getId() == tree.get(i).getId(), "Retry changed an entity identity");
            check(EntityRegistration.inspect(target, entity) == EntityRegistration.RegistrationState.REGISTERED,
                "Corrected retry is not fully registered");
            check((entity != tree.get(i)) == (!sameWorld && recreate), "Recreate/reuse mode changed on retry");
            if (brain && entity instanceof Villager villager) { assertBrain(villager); }
        }
        check(state.destinationJoins - joinsBefore == (sameWorld ? 0 : tree.size()), "Retry bypassed or repeated native EJL gates");
    }

    private void join(EntityJoinLevelEvent event) {
        State state = active;
        if (state == null || !state.includes(event.getEntity())) { return; }
        owned.add(event.getEntity());
        if (event.getLevel() == state.source) {
            state.sourceJoins++;
            event.setCanceled(true);
        }
        else if (event.getLevel() == state.destination) {
            state.destinationJoins++;
            state.candidates.add(event.getEntity());
            if (state.mode == Mode.JOIN_CANCEL && state.reject && event.getEntity().getUUID().equals(state.rejected)) {
                if (state.requiredPriorAdmission != null) {
                    check(state.requiredPriorAdmission.level() == state.destination
                        && EntityRegistration.inspect(state.destination, state.requiredPriorAdmission)
                            == EntityRegistration.RegistrationState.REGISTERED,
                        "The first concrete player must already be fully registered before refusing the second player");
                }
                state.hit = state.everHit = true;
                event.setCanceled(true);
            }
        }
    }

    private void mount(EntityMountEvent event) {
        State state = active;
        if (state == null || !state.includes(event.getEntityMounting()) || !state.reject) { return; }
        if (state.mode == Mode.DISMOUNT_CANCEL && event.isDismounting() && event.getLevel() == state.source
            || state.mode == Mode.MOUNT_CANCEL && event.isMounting() && event.getLevel() == state.destination) {
            state.hit = state.everHit = true;
            event.setCanceled(true);
        }
    }

    private void travel(EntityTravelToDimensionEvent event) {
        State state = active;
        if (state == null || !state.includes(event.getEntity())) { return; }
        check(state.originals.stream().anyMatch(entity -> entity == event.getEntity()), "Travel event was not fired for the original object");
        check(event.getDimension() == state.destination.dimension(), "Travel event has the wrong target dimension");
        for (Saved before : state.before) {
            Entity original = before.entity;
            check(original.level() == state.source && !original.isRemoved()
                && EntityRegistration.inspect(state.source, original) == EntityRegistration.RegistrationState.REGISTERED,
                "Dimension travel event ran after source mutation");
            check(original.position().equals(before.position) && original.getDeltaMovement().equals(before.velocity)
                && original.getVehicle() == before.vehicle && sameIdentity(original.getPassengers(), before.passengers),
                "Dimension travel event ran after source movement/dismount");
        }
        int count = state.travelCounts.merge(event.getEntity().getUUID(), 1, Integer::sum);
        check(count == 1, "Dimension travel event fired more than once for an original");
        if (state.mode == Mode.TRAVEL_CANCEL && state.reject && event.getEntity().getUUID().equals(state.rejected)) {
            state.hit = state.everHit = true;
            event.setCanceled(true);
        }
    }

    private void assertTravel(State state, List<Entity> originals) {
        check(state.travelCounts.size() == originals.size(), "A native travel permission event was omitted");
        for (Entity original : originals) { check(state.travelCounts.getOrDefault(original.getUUID(), 0) == 1,
            "Every original must receive exactly one travel event, including same-world movement"); }
    }

    private List<Entity> tree(boolean brain) {
        List<Entity> tree = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Entity entity = brain && i == 2 ? EntityTypes.VILLAGER.create(source, EntitySpawnReason.COMMAND) : probe(source);
            check(entity != null, "Cannot create native fixture entity");
            owned.add(entity);
            entity.setPos(8 + i * 0.1, 100, 8);
            entity.setNoGravity(true);
            check(source.addFreshEntity(entity), "Cannot normally register native source fixture");
            if (entity instanceof Villager villager) {
                villager.getBrain().setMemory(MemoryModuleType.HOME, home());
                villager.getBrain().setMemory(MemoryModuleType.JOB_SITE, job());
            }
            tree.add(entity);
        }
        check(tree.get(1).startRiding(tree.get(0), true, true), "Cannot create source edge 1");
        check(tree.get(2).startRiding(tree.get(1), true, true), "Cannot create source edge 2");
        check(tree.get(3).startRiding(tree.get(0), true, true), "Cannot create source edge 3");
        return tree;
    }

    private ProbeEntity probe(ServerLevel world) {
        ProbeEntity result = PROBE.get().create(world, EntitySpawnReason.COMMAND);
        check(result != null, "Cannot create the registered native test EntityType");
        result.setPos(8, 100, 8);
        result.setNoGravity(true);
        owned.add(result);
        return result;
    }

    private List<Saved> save(List<Entity> tree) {
        return tree.stream().map(entity -> new Saved(entity, entity.position(), entity.getDeltaMovement(),
            entity.getVehicle(), List.copyOf(entity.getPassengers()), entity.isAddedToLevel())).toList();
    }

    private void assertSource(List<Saved> saved) {
        for (Saved state : saved) {
            Entity entity = state.entity;
            check(!entity.isRemoved() && entity.level() == source
                && EntityRegistration.inspect(source, entity) == EntityRegistration.RegistrationState.REGISTERED,
                "Captured original source registration did not recover");
            check(entity.position().equals(state.position) && entity.getDeltaMovement().equals(state.velocity), "Rollback changed source physics");
            check(entity.getVehicle() == state.vehicle && sameIdentity(entity.getPassengers(), state.passengers), "Rollback changed ordered source edges");
            check(entity.isAddedToLevel() == state.added, "Rollback changed native isAddedToLevel metadata");
            if (entity instanceof Villager villager) { assertBrain(villager); }
        }
    }

    private GlobalPos home() { return GlobalPos.of(source.dimension(), new BlockPos(8, 64, 8)); }
    private GlobalPos job() { return GlobalPos.of(source.dimension(), new BlockPos(9, 64, 8)); }
    private void assertBrain(Villager villager) {
        check(villager.getBrain().getMemory(MemoryModuleType.HOME).orElseThrow().equals(home()), "Villager HOME memory changed");
        check(villager.getBrain().getMemory(MemoryModuleType.JOB_SITE).orElseThrow().equals(job()), "Villager JOB_SITE memory changed");
    }

    private void hiddenForeign(boolean recreate, boolean uuidConflict) {
        List<Entity> tree = tree(false);
        ProbeEntity foreign = probe(destination);
        foreign.setPos(24000, 100, 24000);
        if (uuidConflict) { foreign.setUUID(tree.get(2).getUUID()); }
        else { foreign.setId(tree.get(2).getId()); }
        check(destination.addFreshEntity(foreign), "Cannot add hidden foreign fixture");
        check(destination.getEntity(foreign.getUUID()) == null, "Foreign fixture must be in an actual hidden section");
        Object callback = ((RegistrationEntityAccessor) (Object) foreign).ip_registrationCallback();
        boolean flag = foreign.isAddedToLevel();
        List<Saved> saved = save(tree);
        manager().changeEntityDimension(tree.getFirst(), destination.dimension(), new Vec3(8, 120, 8), recreate);
        assertSource(saved);
        check(EntityRegistration.inspect(destination, foreign) == EntityRegistration.RegistrationState.REGISTERED,
            "Hidden ID/UUID conflict evicted a foreign object");
        check(((RegistrationEntityAccessor) (Object) foreign).ip_registrationCallback() == callback
            && foreign.isAddedToLevel() == flag, "Conflict changed a foreign callback or native metadata");
    }

    private void preexistingCandidate(boolean hidden) {
        ProbeEntity original = probe(source);
        ProbeEntity foreign = probe(destination);
        foreign.setId(original.getId()); foreign.setUUID(original.getUUID());
        if (hidden) { foreign.setPos(24100, 100, 24100); }
        check(source.addFreshEntity(original) && destination.addFreshEntity(foreign), "Cannot add direct facade fixtures");
        check((destination.getEntity(foreign.getUUID()) == foreign) == !hidden, "Preexisting-candidate visibility baseline is wrong");
        Object callback = ((RegistrationEntityAccessor) (Object) foreign).ip_registrationCallback();
        EntityRegistration.Snapshot snapshot = EntityRegistration.capture(original);
        try {
            boolean refused = false;
            try { EntityRegistration.beginAttempt(snapshot, original, foreign, destination); }
            catch (IllegalArgumentException expected) { refused = true; }
            check(refused, "Facade borrowed a preexisting native candidate");
        }
        finally { check(EntityRegistration.restore(snapshot).complete(), "Untouched source snapshot could not seal"); }
        check(((RegistrationEntityAccessor) (Object) foreign).ip_registrationCallback() == callback
            && EntityRegistration.inspect(destination, foreign) == EntityRegistration.RegistrationState.REGISTERED,
            "Facade rejection removed the native preexisting candidate");
        check(EntityRegistration.restore(EntityRegistration.capture(foreign)).complete(), "Rejected candidate retained an ownership lease");
    }

    private void forcedMount() {
        ProbeEntity vehicle = probe(destination);
        ProbeEntity rider = probe(destination);
        check(destination.addFreshEntity(vehicle) && destination.addFreshEntity(rider), "Cannot create force-mount fixtures");
        State state = new State(Mode.MOUNT_CANCEL, source, destination, List.of(vehicle, rider), rider.getUUID());
        active = state;
        check(!rider.startRiding(vehicle, true, true), "force=true bypassed the actual native EntityMountEvent");
        check(state.hit && rider.getVehicle() == null && vehicle.getPassengers().isEmpty(), "Native mount cancellation did not preserve both edges");
        state.reject = false;
        check(rider.startRiding(vehicle, true, true), "Corrected forced mount retry failed");
    }

    private void playerPackets() {
        Peer primary = peer();
        Peer carried = peer();
        Peer bystander = peer();
        ProbeEntity root = probe(source);
        ProbeEntity middle = probe(source);
        check(source.addFreshEntity(root) && source.addFreshEntity(middle), "Cannot register multiplayer root fixtures");
        check(middle.startRiding(root, true, true) && primary.player.startRiding(middle, true, true)
            && carried.player.startRiding(middle, true, true), "Cannot mount concrete native player fixtures");
        List<Entity> tree = List.of(root, middle, primary.player, carried.player);
        check(sameIdentity(root.getSelfAndPassengers().toList(), tree),
            "Concrete player admission order must be root, middle, first player, second player");
        State state = new State(Mode.JOIN_CANCEL, source, destination, tree, carried.player.getUUID());
        state.requiredPriorAdmission = primary.player;
        List<Saved> saved = save(tree);
        List<ServerPlayer> global = List.copyOf(server.getPlayerList().getPlayers());
        List<Saved> unaffected = save(List.of(bystander.player));
        active = state;
        sent.clear();
        manager().changeEntityDimension(root, destination.dimension(), new Vec3(8, 120, 8), false);
        for (Peer peer : peers) { peer.channel.runPendingTasks(); peer.channel.flushOutbound(); }
        check(state.hit, "The second carried player's real native EJL cancellation was not reached");
        check(state.destinationJoins == 4, "The second player must be refused only after all four destination EJL gates");
        int joinsAtRefusal = state.destinationJoins;
        assertTravel(state, tree);
        assertSource(saved);
        assertSource(unaffected);
        check(sameIdentity(server.getPlayerList().getPlayers(), global), "Native recovery changed global player-list membership");
        for (Peer peer : peers) {
            check(server.getPlayerList().getPlayer(peer.player.getUUID()) == null, "Nonglobal concrete fixture was auto-logged-in");
            check(source.players().stream().filter(player -> player == peer.player).count() == 1, "World player-list identity was not restored exactly once");
        }
        List<View> views = new ArrayList<>();
        for (Sent packet : sent) { unwrap(packet.recipient, packet.packet, packet.order << 32, null, views); }
        long lastCorrection = -1;
        for (Peer peer : List.of(primary, carried)) {
            long correction = views.stream().filter(view -> view.recipient == peer.player
                && view.packet instanceof ClientboundPlayerPositionPacket).mapToLong(View::order).max().orElse(-1);
            check(correction >= 0, "An affected native connection did not receive a real outgoing position correction");
            lastCorrection = Math.max(lastCorrection, correction);
            for (View view : views) {
                if (view.recipient == peer.player && view.packet instanceof ClientboundPlayerPositionPacket position) {
                    check(((IEPlayerPositionLookS2CPacket) (Object) position).ip_getPlayerDimension() == source.dimension(),
                        "Native correction has the wrong dimension metadata");
                }
            }
        }
        int sourceId = PortalAPI.serverDimKeyToInt(server, source.dimension());
        for (Entity vehicle : tree) {
            int[] expected = vehicle.getPassengers().stream().mapToInt(Entity::getId).toArray();
            for (Peer peer : List.of(primary, carried)) {
                final long afterBoth = lastCorrection;
                check(views.stream().anyMatch(view -> view.recipient == peer.player && view.order > afterBoth
                    && view.dimension != null && view.dimension == sourceId
                    && view.packet instanceof ClientboundSetPassengersPacket graph
                    && graph.getVehicle() == vehicle.getId() && Arrays.equals(graph.getPassengers(), expected)),
                    "Restored native passenger graph did not follow BOTH outgoing corrections, including empty seats");
            }
        }
        check(views.stream().noneMatch(view -> view.recipient == bystander.player
            && view.packet instanceof ClientboundPlayerPositionPacket), "An unrelated native connection received a transfer correction");
        state.reject = false;
        state.priorTravel.add(Map.copyOf(state.travelCounts));
        state.travelCounts.clear();
        Entity moved = manager().changeEntityDimension(root, destination.dimension(), new Vec3(8, 120, 8), false);
        check(moved == root && root.level() == destination, "Corrected concrete-player retry did not commit");
        check(state.destinationJoins == 8, "Corrected concrete-player retry must pass all four destination EJL gates again");
        assertTravel(state, tree);
        for (Entity entity : tree) {
            check(EntityRegistration.inspect(destination, entity) == EntityRegistration.RegistrationState.REGISTERED
                && EntityRegistration.inspect(source, entity) == EntityRegistration.RegistrationState.ABSENT,
                "Corrected concrete-player retry did not move exact registrations");
        }
        check(destination.players().stream().filter(player -> player == primary.player).count() == 1
            && destination.players().stream().filter(player -> player == carried.player).count() == 1,
            "Corrected native retry lost a concrete world player-list member");
        check(sameIdentity(server.getPlayerList().getPlayers(), global), "Corrected native retry changed global membership");
        assertSource(unaffected);
        report.put("native_player_partial_admission", Map.of(
            "first_player_registered_before_second_refusal", true,
            "destination_join_events_at_refusal", joinsAtRefusal,
            "destination_join_events_after_retry", state.destinationJoins));
        report.put("native_player_coverage", "Two nonglobal concrete ServerPlayers plus an unaffected third; actual outgoing EmbeddedChannel packets with NeoForge's official mock-channel-compatibility setup, no incoming ACK or client settlement simulated.");
    }

    private Peer peer() {
        GameProfile profile = new GameProfile(UUID.randomUUID(), "NativeR" + peers.size());
        ServerPlayer player = new ServerPlayer(server, source, profile, ClientInformation.createDefault());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
                if (message instanceof Packet<?> packet) { sent.add(new Sent(player, packet, sequence.incrementAndGet())); }
                context.write(message, promise);
            }
        }, connection);
        // Use NeoForge's maintained game-test channel setup, not a custom send
        // override or a fabricated incoming handshake/teleport acknowledgement.
        NetworkRegistry.configureMockConnection(connection);
        new ServerGamePacketListenerImpl(server, connection, player, CommonListenerCookie.createInitial(profile, false));
        player.setPos(8, 100, 8);
        owned.add(player);
        Peer peer = new Peer(player, channel);
        peers.add(peer);
        check(source.addFreshEntity(player), "Cannot normally register a native concrete ServerPlayer fixture");
        return peer;
    }

    private static void unwrap(ServerPlayer recipient, Packet<?> packet, long order, Integer dimension, List<View> output) {
        if (packet instanceof ClientboundBundlePacket bundle) {
            int child = 0;
            for (Packet<?> item : bundle.subPackets()) { unwrap(recipient, item, order + child++, dimension, output); }
        }
        else if (packet instanceof ClientboundCustomPayloadPacket custom && custom.payload() instanceof PacketRedirection.Payload redirect) {
            unwrap(recipient, redirect.packet(), order, redirect.dimensionIntId(), output);
        }
        else { output.add(new View(recipient, packet, order, dimension)); }
    }

    private void cleanup() {
        for (Entity entity : owned) { if (!entity.isRemoved()) { entity.discard(); } }
        owned.clear();
        for (Peer peer : peers) { peer.channel.finishAndReleaseAll(); peer.player.getTextFilter().leave(); }
        peers.clear();
    }

    private ServerTeleportationManager manager() { return ServerTeleportationManager.of(server); }
    private void check(boolean condition, String message) {
        assertions++;
        if (!condition) { throw new AssertionError(message); }
    }
    private static boolean sameIdentity(List<?> actual, List<?> expected) {
        if (actual.size() != expected.size()) { return false; }
        for (int i = 0; i < actual.size(); i++) { if (actual.get(i) != expected.get(i)) { return false; } }
        return true;
    }

    private void finish() {
        finished = true;
        long failures = results.stream().filter(result -> !result.get("status").equals("PASS")).count();
        report.put("failed_cases", failures);
        report.put("skipped_required_cases", 0);
        report.put("status", failures == 0 ? "PASS_WITH_LIMITATIONS" : "FAIL");
        writeReport();
        LOGGER.info("IP_NATIVE_RECOVERY_DONE status={} cases={} failures={}", report.get("status"), results.size(), failures);
        server.halt(false);
    }

    private void writeReport() {
        try {
            Path output = Path.of(System.getProperty("ip.recovery.recoveryReport"));
            Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(report));
        }
        catch (IOException error) { throw new IllegalStateException("Cannot retain native recovery results", error); }
    }

    private enum Mode { JOIN_CANCEL, ADDED_THROW, DISMOUNT_CANCEL, MOUNT_CANCEL, TRAVEL_CANCEL }
    private static final class State {
        final Mode mode;
        final ServerLevel source, destination;
        final List<Entity> originals;
        final List<Saved> before;
        final UUID rejected;
        final String tag = "ip-native-recovery-" + UUID.randomUUID();
        final Map<UUID, Integer> travelCounts = new LinkedHashMap<>();
        final List<Map<UUID, Integer>> priorTravel = new ArrayList<>();
        final Set<Entity> candidates = Collections.newSetFromMap(new IdentityHashMap<>());
        Entity requiredPriorAdmission;
        boolean reject = true;
        boolean hit, everHit;
        int sourceJoins, destinationJoins;
        State(Mode mode, ServerLevel source, ServerLevel destination, List<Entity> originals, UUID rejected) {
            this.mode = mode; this.source = source; this.destination = destination;
            this.originals = List.copyOf(originals); this.rejected = rejected;
            this.before = originals.stream().map(entity -> new Saved(entity, entity.position(), entity.getDeltaMovement(),
                entity.getVehicle(), List.copyOf(entity.getPassengers()), entity.isAddedToLevel())).toList();
            originals.forEach(entity -> entity.addTag(tag));
        }
        boolean includes(Entity entity) {
            return entity.entityTags().contains(tag)
                || originals.stream().anyMatch(original -> original.getUUID().equals(entity.getUUID()));
        }
        Map<String, Object> evidence() {
            List<Map<UUID, Integer>> permissionAttempts = new ArrayList<>(priorTravel);
            permissionAttempts.add(Map.copyOf(travelCounts));
            return Map.of("mode", mode.name(), "failure_gate_observed", everHit,
                "source_join_events", sourceJoins, "destination_join_events", destinationJoins,
                "permission_attempt_counts", permissionAttempts);
        }
    }
    private record Case(String name, CheckedRunnable test) {}
    @FunctionalInterface private interface CheckedRunnable { void run() throws Exception; }
    private record Saved(Entity entity, Vec3 position, Vec3 velocity, Entity vehicle, List<Entity> passengers, boolean added) {}
    private record Peer(ServerPlayer player, EmbeddedChannel channel) {}
    private record Sent(ServerPlayer recipient, Packet<?> packet, long order) {}
    private record View(ServerPlayer recipient, Packet<?> packet, long order, Integer dimension) {}
}
