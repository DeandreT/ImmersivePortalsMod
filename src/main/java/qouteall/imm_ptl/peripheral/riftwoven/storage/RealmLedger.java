package qouteall.imm_ptl.peripheral.riftwoven.storage;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import qouteall.imm_ptl.peripheral.mixin.common.riftwoven.MixinPlayerList_RealmSave;
import qouteall.imm_ptl.peripheral.riftwoven.profile.RealmProfile;
import qouteall.imm_ptl.peripheral.riftwoven.profile.RealmRecipe;

/**
 * Server-thread-only durable escrow, paid immutable identities, permissions and return records.
 * A prepared identity reserves its capacity and seed even if activation or saving fails.
 * Every public mutation either returns a committed result or throws; exceptions are not success.
 */
public final class RealmLedger {
    public static final int DEFAULT_REALM_CAP = 32;
    public static final int MAX_CONTRIBUTIONS = 1024;
    public static final int MAX_MEMBERS = 128;
    public static final int MAX_GROWING_DRAFTS_PER_OWNER = 16;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<MinecraftServer, RealmLedger> INSTANCES = new WeakHashMap<>();
    private final MinecraftServer server;
    private final TransactionJournal journal;
    private final TransactionJournal visits;
    private final int capacity;
    private final Map<UUID, String> invalid = new HashMap<>();
    private final Map<UUID, String> invalidVisits = new HashMap<>();

    public enum Permission {
        ENTER(1), BUILD(2), VIEW(4);
        private final int bit;
        Permission(int bit) { this.bit = bit; }
    }

    public enum WithdrawalStatus { COMMITTED, EMPTY, INVENTORY_FULL }
    public record Withdrawal(WithdrawalStatus status, Contribution contribution) { }
    public enum Recovery { NOTHING_PENDING, COMMITTED }
    private record State(Draft draft, RealmRecord realm, Map<UUID, Integer> members) { }
    private record Slot(int slot, String beforeItem, int beforeCount, String afterItem, int afterCount) { }

    public static synchronized RealmLedger open(MinecraftServer server) throws IOException {
        return open(server, DEFAULT_REALM_CAP);
    }

    public static synchronized RealmLedger open(MinecraftServer server, int capacity) throws IOException {
        RealmLedger existing = INSTANCES.get(server);
        if (existing != null) return existing;
        if (capacity < 1 || capacity > 128) throw new IllegalArgumentException("Realm capacity must be 1..128");
        RealmLedger ledger = new RealmLedger(server, capacity);
        INSTANCES.put(server, ledger);
        return ledger;
    }

    /** Call on server shutdown to release the per-server instance. */
    public static synchronized void close(MinecraftServer server) { INSTANCES.remove(server); }

    private RealmLedger(MinecraftServer server, int capacity) throws IOException {
        this.server = server;
        this.capacity = capacity;
        Path root = server.getWorldPath(LevelResource.ROOT).resolve("data/riftwoven");
        journal = new TransactionJournal(new LedgerStore(root.resolve("realms")));
        visits = new TransactionJournal(new LedgerStore(root.resolve("visits")));
        // Validate payloads before completing metadata-only intents left by interrupted writes.
        for (TransactionJournal.Event event : journal.heads()) validate(event);
        for (TransactionJournal.Event event : journal.pending()) {
            if (validate(event) && event.actor() == null) journal.complete(event, null);
        }
        for (TransactionJournal.Event event : visits.heads()) validateVisit(event);
        for (TransactionJournal.Event event : visits.pending()) {
            if (validateVisit(event)) visits.complete(event, null);
        }
    }

    private boolean validateVisit(TransactionJournal.Event event) {
        try {
            decodeVisit(event.state());
            if (event.actor() != null) throw new IOException("Visit journal unexpectedly requires player payment");
            return true;
        } catch (IOException | RuntimeException exception) {
            invalidVisits.put(event.realm(), exception.toString());
            return false;
        }
    }

    private boolean validate(TransactionJournal.Event event) {
        try {
            State state = decodeState(event.state());
            if (!state.draft.id().equals(event.realm())) throw new IOException("Realm payload identity mismatch");
            decodePlan(event.plan());
            if (event.actor() != null && !event.actor().equals(state.draft.owner())) throw new IOException("Payment actor is not draft owner");
            return true;
        } catch (IOException | RuntimeException exception) {
            invalid.put(event.realm(), exception.toString());
            return false;
        }
    }

    private void thread() {
        if (!server.isSameThread()) throw new IllegalStateException("Realm ledger must run on the server thread");
    }

    public Map<UUID, String> failures() {
        Map<UUID, String> result = new HashMap<>(journal.failures());
        result.putAll(invalid);
        return Map.copyOf(result);
    }

    public UUID createDraft(ServerPlayer player) throws IOException {
        thread();
        recover(player);
        int growing = 0;
        for (Draft draft : ownedDrafts(player.getUUID())) {
            if (realm(draft.id()) == null) growing++;
        }
        if (growing >= MAX_GROWING_DRAFTS_PER_OWNER) throw new IOException("Growing draft capacity reached; recover an existing core");
        UUID id = UUID.randomUUID();
        State state = new State(new Draft(id, player.getUUID(), List.of()), null, Map.of());
        commitMetadata(id, state);
        return id;
    }

    /** Null means no such draft; corruption is an exception, never a freshly generated identity. */
    public Draft draft(UUID id) throws IOException {
        thread();
        State state = state(id);
        return state == null ? null : state.draft;
    }

    public RealmRecord realm(UUID id) throws IOException {
        thread();
        State state = state(id);
        return state == null ? null : state.realm;
    }

    public List<RealmRecord> realms() throws IOException {
        thread();
        List<RealmRecord> result = new ArrayList<>();
        for (TransactionJournal.Event event : journal.heads()) {
            if (invalid.containsKey(event.realm()) || journal.failures().containsKey(event.realm())) continue;
            State state = state(event.realm());
            if (state.realm != null) result.add(state.realm);
        }
        return List.copyOf(result);
    }

    /** Owned and explicitly shared realms, suitable for replacement/copy references without payment. */
    public List<RealmRecord> accessibleRealms(UUID player) throws IOException {
        List<RealmRecord> result = new ArrayList<>();
        for (RealmRecord realm : realms()) if (canEnter(realm.id(), player) || canView(realm.id(), player)) result.add(realm);
        return List.copyOf(result);
    }

    /** Includes growing drafts so lost cores can be rebound and their escrow withdrawn. */
    public List<Draft> ownedDrafts(UUID player) throws IOException {
        thread();
        Set<UUID> ids = new HashSet<>();
        for (TransactionJournal.Event event : journal.heads()) ids.add(event.realm());
        for (TransactionJournal.Event event : journal.pending()) ids.add(event.realm());
        List<Draft> result = new ArrayList<>();
        for (UUID id : ids) {
            if (invalid.containsKey(id) || journal.failures().containsKey(id)) continue;
            State state = state(id);
            if (state == null) {
                TransactionJournal.Event pending = journal.pending(id);
                if (pending != null) state = decodeState(pending.state());
            }
            if (state != null && state.draft.owner().equals(player)) result.add(state.draft);
        }
        return List.copyOf(result);
    }

    public Contribution offer(ServerPlayer player, UUID id, InteractionHand hand, int count) throws IOException {
        thread();
        TransactionJournal.Event previous = journal.pendingFor(player.getUUID());
        recover(player);
        if (previous != null) {
            State recovered = decodeState(previous.state());
            if (previous.realm().equals(id) && recovered.realm == null && delta(previous.plan()) < 0) {
                return recovered.draft.contributions().getLast();
            }
            throw new IOException("Previous transaction reconciled; repeat the new interaction deliberately");
        }
        State state = ownedDraft(player, id);
        if (count < 1 || count > RealmRecipe.transferLimit()) throw new IOException("Offering count exceeds the deliberate transfer limit");
        ItemStack held = player.getItemInHand(hand);
        String item = itemId(held);
        if (!RealmRecipe.accepts(item) || !plain(held) || held.getCount() < count ||
            held.getCount() > Math.min(64, held.getMaxStackSize())) {
            throw new IOException("Offer a supported unmodified vanilla ingredient stack");
        }
        if (state.draft.contributions().size() >= MAX_CONTRIBUTIONS) throw new IOException("Offering receipt capacity reached; withdraw first");
        Contribution contribution = new Contribution(item, count);
        List<Contribution> contributions = new ArrayList<>(state.draft.contributions());
        contributions.add(contribution);
        Draft draft = new Draft(id, state.draft.owner(), contributions);
        // Preview validation happens before PREPARED and before taking any item.
        RealmRecipe.resolve(draft.recipe());
        int slot = hand == InteractionHand.MAIN_HAND ? player.getInventory().getSelectedSlot() : Inventory.SLOT_OFFHAND;
        List<Slot> plan = List.of(change(slot, held, item, held.getCount() - count));
        transact(player, id, new State(draft, null, state.members), plan);
        return contribution;
    }

    /** Refunds the entire latest deliberate contribution, never drops items into the world. */
    public Withdrawal withdraw(ServerPlayer player, UUID id) throws IOException {
        thread();
        TransactionJournal.Event previous = journal.pendingFor(player.getUUID());
        Contribution recoveredContribution = null;
        if (previous != null && previous.realm().equals(id) && delta(previous.plan()) > 0) {
            State before = requireState(id);
            if (!before.draft.contributions().isEmpty()) recoveredContribution = before.draft.contributions().getLast();
        }
        recover(player);
        if (previous != null) {
            if (recoveredContribution != null) return new Withdrawal(WithdrawalStatus.COMMITTED, recoveredContribution);
            throw new IOException("Previous transaction reconciled; repeat the new interaction deliberately");
        }
        State state = ownedDraft(player, id);
        List<Contribution> contributions = new ArrayList<>(state.draft.contributions());
        if (contributions.isEmpty()) return new Withdrawal(WithdrawalStatus.EMPTY, null);
        Contribution contribution = contributions.getLast();
        List<Slot> plan = refundPlan(player.getInventory(), contribution);
        if (plan == null) return new Withdrawal(WithdrawalStatus.INVENTORY_FULL, contribution);
        contributions.removeLast();
        transact(player, id, new State(new Draft(id, state.draft.owner(), contributions), null, state.members), plan);
        return new Withdrawal(WithdrawalStatus.COMMITTED, contribution);
    }

    /** Charges four plain diamonds once; retries return the same paid identity and frozen profile. */
    public RealmRecord awaken(ServerPlayer player, UUID id) throws IOException {
        thread();
        recover(player);
        State state = requireState(id);
        owner(player.getUUID(), state);
        if (state.realm != null) return state.realm;
        // A quarantined identity consumes one slot without blocking unrelated healthy owners.
        // Its UUID can never be reused or regenerated, even if its payload cannot be decoded.
        Set<UUID> reserved = journal.reservedIdentities(invalid.keySet(), event -> decodeState(event.state()).realm != null);
        if (reserved.size() >= capacity) throw new IOException("Realm registry capacity reached; no payment taken");
        RealmProfile profile = RealmRecipe.resolve(state.draft.recipe());
        List<Slot> payment = paymentPlan(player.getInventory());
        RealmRecord record = new RealmRecord(id, player.getUUID(), RANDOM.nextLong(), profile, state.draft.contributions());
        transact(player, id, new State(state.draft, record, state.members), payment);
        return record;
    }

    /** Run on player join before interactions, and retry after a reported IO failure. */
    public Recovery recover(ServerPlayer player) throws IOException {
        thread();
        TransactionJournal.Event event = journal.pendingFor(player.getUUID());
        if (event == null) return Recovery.NOTHING_PENDING;
        if (!validate(event)) throw new IOException("Prepared transaction is corrupt: " + invalid.get(event.realm()));
        journal.complete(event, participant(player));
        return Recovery.COMMITTED;
    }

    /**
     * Save the current inventory AND position durably with a fresh verified nonce. After a safe
     * exterior arrival, call this before confirmDepartureLeft so a crash cannot restore an
     * interior player position after their independent return record has been tombstoned.
     */
    public void savePlayerState(ServerPlayer player) throws IOException {
        thread();
        recover(player);
        TransactionJournal.saveFreshBaseline(participant(player));
    }

    private void transact(ServerPlayer player, UUID id, State state, List<Slot> plan) throws IOException {
        byte[] stateBytes = encodeState(state);
        byte[] planBytes = encodePlan(plan);
        decodeState(stateBytes);
        for (Slot slot : decodePlan(planBytes)) {
            stack(slot.beforeItem, slot.beforeCount);
            stack(slot.afterItem, slot.afterCount);
        }
        TransactionJournal.Participant participant = participant(player);
        TransactionJournal.Event event = journal.prepareInventory(id, player.getUUID(), stateBytes, planBytes, participant);
        journal.complete(event, participant);
        player.getInventory().setChanged();
        player.inventoryMenu.broadcastChanges();
    }

    private void commitMetadata(UUID id, State state) throws IOException {
        TransactionJournal.Event event = journal.prepare(id, null, encodeState(state), encodePlan(List.of()));
        journal.complete(event, null);
    }

    private State state(UUID id) throws IOException {
        if (invalid.containsKey(id)) throw new IOException("Invalid realm state: " + invalid.get(id));
        TransactionJournal.Event pending = journal.pending(id);
        if (pending != null && pending.actor() == null) {
            if (!validate(pending)) throw new IOException("Invalid pending realm metadata");
            journal.complete(pending, null);
        }
        TransactionJournal.Event event = journal.head(id);
        return event == null ? null : decodeState(event.state());
    }

    private State requireState(UUID id) throws IOException {
        State state = state(id);
        if (state == null) throw new IOException("Unknown realm or draft");
        return state;
    }

    private State ownedDraft(ServerPlayer player, UUID id) throws IOException {
        State state = requireState(id);
        owner(player.getUUID(), state);
        if (state.realm != null) throw new IOException("Awakened offerings are irreversible");
        return state;
    }

    private static void owner(UUID actor, State state) throws IOException {
        if (!state.draft.owner().equals(actor)) throw new IOException("Only the owner may manage this realm");
    }

    public void grant(ServerPlayer owner, UUID realm, UUID member, Set<Permission> permissions) throws IOException {
        thread();
        recover(owner);
        State state = requireState(realm);
        owner(owner.getUUID(), state);
        if (state.realm == null) throw new IOException("Realm is not awakened");
        Map<UUID, Integer> members = new HashMap<>(state.members);
        int bits = 0;
        for (Permission permission : permissions) bits |= permission.bit;
        if (bits == 0) members.remove(member); else members.put(member, bits);
        if (members.size() > MAX_MEMBERS) throw new IOException("Realm membership capacity reached");
        commitMetadata(realm, new State(state.draft, state.realm, Map.copyOf(members)));
    }

    public void revoke(ServerPlayer owner, UUID realm, UUID member) throws IOException {
        grant(owner, realm, member, Set.of());
    }

    public boolean canEnter(UUID realm, UUID player) throws IOException { return authorized(realm, player, Permission.ENTER); }
    public boolean canBuild(UUID realm, UUID player) throws IOException { return authorized(realm, player, Permission.BUILD); }
    public boolean canView(UUID realm, UUID player) throws IOException { return authorized(realm, player, Permission.VIEW); }

    private boolean authorized(UUID realm, UUID player, Permission permission) throws IOException {
        thread();
        State state = state(realm);
        return state != null && state.realm != null && (state.draft.owner().equals(player) ||
            (state.members.getOrDefault(player, 0) & permission.bit) != 0);
    }

    /** Persist BEFORE transfer. An existing visit is never silently replaced, including after disconnect. */
    public Departure recordDeparture(UUID player, Departure departure) throws IOException {
        thread();
        if (!canEnter(departure.realm(), player)) throw new IOException("Realm entry is not authorized");
        Departure existing = departure(player);
        if (existing != null) {
            if (!existing.realm().equals(departure.realm())) throw new IOException("Player already has an unresolved realm visit");
            return existing;
        }
        Set<UUID> active = new HashSet<>(invalidVisits.keySet());
        active.addAll(visits.failures().keySet());
        for (TransactionJournal.Event visit : visits.heads()) {
            if (!invalidVisits.containsKey(visit.realm()) && decodeVisit(visit.state()) != null) active.add(visit.realm());
        }
        for (TransactionJournal.Event visit : visits.pending()) {
            if (!invalidVisits.containsKey(visit.realm()) && decodeVisit(visit.state()) != null) active.add(visit.realm());
        }
        // Every accepted entry reserves one future immutable exit event, including disconnected visitors.
        if (visits.remainingGlobalEvents() - active.size() < 2 || visits.remainingRealmEvents(player) < 2) {
            throw new IOException("Visit journal capacity reached; entry refused to preserve existing returns");
        }
        TransactionJournal.Event event = visits.prepare(player, null, encodeVisit(departure), new byte[0]);
        visits.complete(event, null);
        return departure;
    }

    public Departure departure(UUID player) throws IOException {
        thread();
        if (invalidVisits.containsKey(player)) throw new IOException("Invalid visit record: " + invalidVisits.get(player));
        TransactionJournal.Event pending = visits.pending(player);
        if (pending != null) {
            decodeVisit(pending.state());
            visits.complete(pending, null);
        }
        TransactionJournal.Event event = visits.head(player);
        return event == null ? null : decodeVisit(event.state());
    }

    public boolean canReturn(UUID player, UUID realm) throws IOException {
        Departure departure = departure(player);
        return departure != null && departure.realm().equals(realm);
    }

    /** Call only after confirmed safe arrival outside the visited realm, never before transfer. */
    public void confirmDepartureLeft(UUID player, UUID realm) throws IOException {
        thread();
        Departure departure = departure(player);
        if (departure == null) return;
        if (!departure.realm().equals(realm)) throw new IOException("Visit realm does not match confirmed exit");
        TransactionJournal.Event event = visits.prepare(player, null, encodeVisit(null), new byte[0]);
        visits.complete(event, null);
    }

    private TransactionJournal.Participant participant(ServerPlayer player) {
        ReceiptAccess access = (ReceiptAccess) player;
        return new TransactionJournal.Participant() {
            @Override public String receipt() throws IOException {
                String receipt = access.riftwoven$receipt();
                if (!receipt.isEmpty()) {
                    try {
                        if (!UUID.fromString(receipt).toString().equals(receipt)) throw new IllegalArgumentException("Noncanonical receipt");
                    } catch (IllegalArgumentException exception) { throw new IOException("Malformed player payment receipt", exception); }
                }
                return receipt;
            }
            @Override public void receipt(String transaction) { access.riftwoven$receipt(transaction); }
            @Override public void apply(byte[] bytes) throws IOException {
                List<Slot> changes = decodePlan(bytes);
                Inventory inventory = player.getInventory();
                // Validate every preimage before changing any slot. An absent receipt is not proof of payment.
                for (Slot change : changes) {
                    ItemStack current = inventory.getItem(change.slot);
                    if (!plain(current) || !itemId(current).equals(change.beforeItem) || current.getCount() != change.beforeCount) {
                        throw new IOException("Prepared inventory preimage changed; transaction remains pending for reconciliation");
                    }
                }
                List<ItemStack> replacements = new ArrayList<>();
                for (Slot change : changes) replacements.add(stack(change.afterItem, change.afterCount));
                for (int index = 0; index < changes.size(); index++) inventory.setItem(changes.get(index).slot, replacements.get(index));
                inventory.setChanged();
            }
            @Override public void saveAndForce(String expectedReceipt) throws IOException {
                // Vanilla catches PlayerDataStorage IO failures, so invocation alone cannot prove success.
                ((MixinPlayerList_RealmSave) server.getPlayerList()).riftwoven$save(player);
                Path path = server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(player.getUUID() + ".dat");
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 16_777_216) {
                    throw new IOException("Player save missing or oversized; transaction remains pending");
                }
                LedgerStore.forceFile(path);
                LedgerStore.forceDirectory(path.getParent());
                var tag = NbtIo.readCompressed(path, NbtAccounter.create(16_777_216));
                if (!expectedReceipt.equals(tag.getStringOr(ReceiptAccess.RECEIPT_TAG, ""))) {
                    throw new IOException("Normal player save did not persist the transaction receipt");
                }
            }
        };
    }

    private static String itemId(ItemStack stack) {
        return stack.isEmpty() ? "minecraft:air" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static boolean plain(ItemStack stack) { return stack.isEmpty() || stack.getComponentsPatch().isEmpty(); }

    private static Slot change(int slot, ItemStack before, String item, int count) {
        return new Slot(slot, itemId(before), before.getCount(), count == 0 ? "minecraft:air" : item, count);
    }

    private static ItemStack stack(String item, int count) throws IOException {
        if (count == 0) return ItemStack.EMPTY;
        Identifier identifier = Identifier.tryParse(item);
        if (identifier == null || !BuiltInRegistries.ITEM.containsKey(identifier)) throw new IOException("Unknown journal item: " + item);
        ItemStack result = new ItemStack(BuiltInRegistries.ITEM.getValue(identifier), count);
        if (count > result.getMaxStackSize()) throw new IOException("Journal stack exceeds its stack limit");
        return result;
    }

    private static List<Slot> paymentPlan(Inventory inventory) throws IOException {
        List<Slot> plan = new ArrayList<>();
        int remaining = 4;
        for (int index = 0; index <= Inventory.INVENTORY_SIZE; index++) {
            int slot = index == Inventory.INVENTORY_SIZE ? Inventory.SLOT_OFFHAND : index;
            ItemStack stack = inventory.getItem(slot);
            if (plain(stack) && itemId(stack).equals("minecraft:diamond") && stack.getCount() <= Math.min(64, stack.getMaxStackSize())) {
                int taken = Math.min(remaining, stack.getCount());
                if (taken > 0) plan.add(change(slot, stack, "minecraft:diamond", stack.getCount() - taken));
                remaining -= taken;
                if (remaining == 0) return List.copyOf(plan);
            }
        }
        throw new IOException("Awakening requires four unmodified diamonds");
    }

    private static List<Slot> refundPlan(Inventory inventory, Contribution contribution) throws IOException {
        List<Slot> plan = new ArrayList<>();
        int remaining = contribution.count();
        int max = stack(contribution.item(), 1).getMaxStackSize();
        // Merge first, then use empty normal storage slots; do not put refunds into equipment or drop them.
        for (int pass = 0; pass < 2; pass++) {
            for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
                ItemStack current = inventory.getItem(slot);
                if (pass == 0 ? current.isEmpty() : !current.isEmpty()) continue;
                if (!plain(current) || (!current.isEmpty() && !itemId(current).equals(contribution.item()))) continue;
                int added = Math.min(remaining, Math.max(0, max - current.getCount()));
                if (added > 0) plan.add(change(slot, current, contribution.item(), current.getCount() + added));
                remaining -= added;
                if (remaining == 0) return List.copyOf(plan);
            }
        }
        return null;
    }

    private static byte[] encodeState(State state) throws IOException {
        JsonObject object = new JsonObject();
        object.addProperty("schema", 1);
        object.addProperty("id", state.draft.id().toString());
        object.addProperty("owner", state.draft.owner().toString());
        JsonArray contributions = new JsonArray();
        for (Contribution contribution : state.draft.contributions()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("item", contribution.item());
            entry.addProperty("count", contribution.count());
            contributions.add(entry);
        }
        object.add("contributions", contributions);
        if (state.realm == null) object.add("realm", JsonNull.INSTANCE);
        else {
            JsonObject realm = new JsonObject();
            realm.addProperty("seed", state.realm.seed());
            realm.addProperty("generator_version", state.realm.generatorVersion());
            realm.addProperty("recipe_version", state.realm.recipeVersion());
            try { realm.add("profile", RealmProfile.CODEC.encodeStart(JsonOps.INSTANCE, state.realm.profile()).getOrThrow()); }
            catch (RuntimeException exception) { throw new IOException("Cannot encode frozen profile", exception); }
            object.add("realm", realm);
        }
        JsonObject members = new JsonObject();
        state.members.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> members.addProperty(entry.getKey().toString(), entry.getValue()));
        object.add("members", members);
        return jsonBytes(object);
    }

    private static State decodeState(byte[] bytes) throws IOException {
        try {
            JsonObject object = json(bytes);
            fields(object, "schema", "id", "owner", "contributions", "realm", "members");
            if (integer(object, "schema") != 1) throw new IOException("Unsupported realm state schema");
            UUID id = UUID.fromString(string(object, "id"));
            UUID owner = UUID.fromString(string(object, "owner"));
            JsonArray array = object.getAsJsonArray("contributions");
            if (array.size() > MAX_CONTRIBUTIONS) throw new IOException("Too many contribution receipts");
            List<Contribution> contributions = new ArrayList<>();
            int total = 0;
            for (JsonElement element : array) {
                JsonObject entry = element.getAsJsonObject();
                fields(entry, "item", "count");
                Contribution contribution = new Contribution(string(entry, "item"), integer(entry, "count"));
                if (!RealmRecipe.accepts(contribution.item())) throw new IOException("Unsupported escrow ingredient");
                total = Math.addExact(total, contribution.count());
                contributions.add(contribution);
            }
            if (total > RealmRecipe.MAX_OFFERINGS) throw new IOException("Escrow exceeds recipe capacity");
            Draft draft = new Draft(id, owner, contributions);
            RealmRecord record = null;
            if (!object.get("realm").isJsonNull()) {
                JsonObject realm = object.getAsJsonObject("realm");
                fields(realm, "seed", "generator_version", "recipe_version", "profile");
                if (integer(realm, "generator_version") != 1) throw new IOException("Unsupported frozen generator version");
                RealmProfile profile = RealmProfile.CODEC.parse(JsonOps.INSTANCE, realm.get("profile")).getOrThrow();
                if (integer(realm, "recipe_version") != profile.version()) throw new IOException("Profile recipe version mismatch");
                record = new RealmRecord(id, owner, realm.get("seed").getAsBigDecimal().longValueExact(), profile, contributions,
                    integer(realm, "recipe_version"), integer(realm, "generator_version"));
            }
            JsonObject memberObject = object.getAsJsonObject("members");
            if (memberObject.size() > MAX_MEMBERS) throw new IOException("Membership capacity exceeded");
            Map<UUID, Integer> members = new HashMap<>();
            for (String member : memberObject.keySet()) {
                int bits = integer(memberObject, member);
                if (bits < 1 || bits > 7) throw new IOException("Invalid permission bits");
                members.put(UUID.fromString(member), bits);
            }
            return new State(draft, record, Map.copyOf(members));
        } catch (RuntimeException exception) { throw new IOException("Malformed realm state; original journal retained", exception); }
    }

    private static byte[] encodePlan(List<Slot> plan) {
        JsonArray array = new JsonArray();
        for (Slot slot : plan) {
            JsonObject entry = new JsonObject();
            entry.addProperty("slot", slot.slot);
            entry.addProperty("before_item", slot.beforeItem);
            entry.addProperty("before_count", slot.beforeCount);
            entry.addProperty("after_item", slot.afterItem);
            entry.addProperty("after_count", slot.afterCount);
            array.add(entry);
        }
        return array.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static List<Slot> decodePlan(byte[] bytes) throws IOException {
        try {
            JsonArray array = parseJson(bytes).getAsJsonArray();
            if (array.size() > 37) throw new IOException("Inventory plan too large");
            Set<Integer> slots = new HashSet<>();
            List<Slot> result = new ArrayList<>();
            for (JsonElement element : array) {
                JsonObject entry = element.getAsJsonObject();
                fields(entry, "slot", "before_item", "before_count", "after_item", "after_count");
                Slot slot = new Slot(integer(entry, "slot"), string(entry, "before_item"), integer(entry, "before_count"),
                    string(entry, "after_item"), integer(entry, "after_count"));
                if ((slot.slot < 0 || slot.slot >= Inventory.INVENTORY_SIZE) && slot.slot != Inventory.SLOT_OFFHAND) throw new IOException("Invalid inventory slot");
                if (!slots.add(slot.slot)) throw new IOException("Duplicate inventory slot");
                validateItem(slot.beforeItem, slot.beforeCount);
                validateItem(slot.afterItem, slot.afterCount);
                result.add(slot);
            }
            return List.copyOf(result);
        } catch (RuntimeException exception) { throw new IOException("Malformed inventory plan", exception); }
    }

    private static int delta(byte[] plan) throws IOException {
        int result = 0;
        for (Slot slot : decodePlan(plan)) result += slot.afterCount - slot.beforeCount;
        return result;
    }

    private static void validateItem(String item, int count) throws IOException {
        if (count < 0 || count > 64 || (count == 0) != item.equals("minecraft:air") ||
            (count > 0 && !item.equals("minecraft:diamond") && !RealmRecipe.accepts(item))) throw new IOException("Invalid inventory preimage or postimage");
    }

    private static byte[] encodeVisit(Departure departure) {
        JsonObject object = new JsonObject();
        object.addProperty("schema", 1);
        if (departure == null) object.add("departure", JsonNull.INSTANCE);
        else {
            JsonObject entry = new JsonObject();
            entry.addProperty("realm", departure.realm().toString());
            entry.addProperty("dimension", departure.dimension());
            entry.addProperty("x", departure.x()); entry.addProperty("y", departure.y()); entry.addProperty("z", departure.z());
            entry.addProperty("yaw", departure.yaw()); entry.addProperty("pitch", departure.pitch());
            object.add("departure", entry);
        }
        return jsonBytes(object);
    }

    private static Departure decodeVisit(byte[] bytes) throws IOException {
        try {
            JsonObject object = json(bytes);
            fields(object, "schema", "departure");
            if (integer(object, "schema") != 1) throw new IOException("Unsupported visit schema");
            if (object.get("departure").isJsonNull()) return null;
            JsonObject entry = object.getAsJsonObject("departure");
            fields(entry, "realm", "dimension", "x", "y", "z", "yaw", "pitch");
            return new Departure(UUID.fromString(string(entry, "realm")), string(entry, "dimension"),
                entry.get("x").getAsDouble(), entry.get("y").getAsDouble(), entry.get("z").getAsDouble(),
                entry.get("yaw").getAsFloat(), entry.get("pitch").getAsFloat());
        } catch (RuntimeException exception) { throw new IOException("Malformed visit; original journal retained", exception); }
    }

    private static byte[] jsonBytes(JsonObject object) { return object.toString().getBytes(StandardCharsets.UTF_8); }
    private static JsonObject json(byte[] bytes) throws IOException { return parseJson(bytes).getAsJsonObject(); }

    /** Strict, bounded parsing also rejects duplicate fields rather than silently replacing evidence. */
    private static JsonElement parseJson(byte[] bytes) throws IOException {
        if (bytes.length > LedgerStore.MAX_FILE_BYTES) throw new IOException("JSON journal field too large");
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setLenient(false);
            JsonElement result = readJson(reader, 0, new int[] { 16_384 });
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Trailing JSON data");
            return result;
        }
    }

    private static JsonElement readJson(JsonReader reader, int depth, int[] remaining) throws IOException {
        if (depth > 32 || --remaining[0] < 0) throw new IOException("JSON journal complexity exceeded");
        switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                JsonObject object = new JsonObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (name.length() > 256 || object.has(name)) throw new IOException("Duplicate or oversized JSON field");
                    object.add(name, readJson(reader, depth + 1, remaining));
                }
                reader.endObject();
                return object;
            }
            case BEGIN_ARRAY -> {
                JsonArray array = new JsonArray();
                reader.beginArray();
                while (reader.hasNext()) array.add(readJson(reader, depth + 1, remaining));
                reader.endArray();
                return array;
            }
            case STRING -> {
                String string = reader.nextString();
                if (string.length() > 512) throw new IOException("JSON string too large");
                return new JsonPrimitive(string);
            }
            case NUMBER -> {
                String number = reader.nextString();
                if (number.length() > 64) throw new IOException("JSON number too large");
                return new JsonPrimitive(new BigDecimal(number));
            }
            case BOOLEAN -> { return new JsonPrimitive(reader.nextBoolean()); }
            case NULL -> { reader.nextNull(); return JsonNull.INSTANCE; }
            default -> throw new IOException("Unexpected JSON token");
        }
    }
    private static String string(JsonObject object, String field) throws IOException {
        JsonElement element = object.get(field);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) throw new IOException("Invalid string field: " + field);
        String value = element.getAsString();
        if (value.length() > 256) throw new IOException("String field too long: " + field);
        return value;
    }
    private static int integer(JsonObject object, String field) throws IOException {
        JsonElement element = object.get(field);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) throw new IOException("Invalid integer field: " + field);
        try { return element.getAsBigDecimal().intValueExact(); }
        catch (ArithmeticException exception) { throw new IOException("Invalid integer: " + field, exception); }
    }
    private static void fields(JsonObject object, String... expected) throws IOException {
        if (!object.keySet().equals(Set.of(expected))) throw new IOException("Unknown or missing schema fields");
    }
}
