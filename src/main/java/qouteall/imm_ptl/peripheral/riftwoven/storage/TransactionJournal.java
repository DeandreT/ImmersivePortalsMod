package qouteall.imm_ptl.peripheral.riftwoven.storage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Immutable PREPARED events and hash-bound COMMIT markers; only one pending event per player. */
public final class TransactionJournal {
    public static final int MAX_REALMS = 4096;
    public static final int MAX_TOTAL_EVENTS = 32_768;
    private static final int MAGIC = 0x52574631;
    private final LedgerStore store;
    private final Map<UUID, Event> heads = new HashMap<>();
    private final Map<UUID, Event> pending = new HashMap<>();
    private final Map<UUID, String> failures = new HashMap<>();
    private final Set<UUID> directories = new HashSet<>();
    private long totalEvents;

    public record Event(UUID realm, int revision, UUID actor, UUID transaction, byte[] state, byte[] plan) {
        public Event { state = state.clone(); plan = plan.clone(); }
        @Override public byte[] state() { return state.clone(); }
        @Override public byte[] plan() { return plan.clone(); }
    }

    public interface Participant {
        String receipt() throws IOException;
        /** Validate the entire plan before making any inventory change. Must not partially apply. */
        void apply(byte[] plan) throws IOException;
        void receipt(String transaction);
        /** Must verify the receipt actually reached stable storage, not merely invoke a best-effort save. */
        void saveAndForce(String expectedReceipt) throws IOException;
    }

    @FunctionalInterface
    public interface IdentityReservation {
        boolean isPaid(Event event) throws IOException;
    }

    public TransactionJournal(LedgerStore store) throws IOException {
        this.store = store;
        try (var directories = Files.list(store.root())) {
            List<Path> paths = directories.limit(MAX_REALMS + 1L).toList();
            if (paths.size() > MAX_REALMS) throw new IOException("Journal realm capacity exceeded");
            for (Path path : paths) {
                UUID realm;
                try {
                    realm = UUID.fromString(path.getFileName().toString());
                    if (!realm.toString().equals(path.getFileName().toString())) throw new IllegalArgumentException("Noncanonical realm directory");
                }
                catch (IllegalArgumentException exception) { throw new IOException("Unknown journal entry: " + path, exception); }
                this.directories.add(realm);
                try { loadRealm(realm, path); }
                catch (IOException | RuntimeException exception) {
                    failures.put(realm, exception.toString());
                    heads.remove(realm);
                    pending.remove(realm);
                }
            }
        }
        Map<UUID, UUID> actors = new HashMap<>();
        for (Event event : pending.values()) {
            if (event.actor() == null) continue;
            UUID existing = actors.putIfAbsent(event.actor(), event.realm());
            if (existing != null) {
                failures.put(existing, "Multiple pending transactions for one player");
                failures.put(event.realm(), "Multiple pending transactions for one player");
            }
        }
    }

    private void loadRealm(UUID realm, Path directory) throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid realm directory");
        List<Path> files;
        try (var stream = Files.list(directory)) {
            files = stream.limit(LedgerStore.MAX_EVENTS * 3L + 1).toList();
        }
        if (files.size() > LedgerStore.MAX_EVENTS * 3) throw new IOException("Journal directory too large");
        int entries = 0;
        for (Path file : files) {
            String name = file.getFileName().toString();
            if (name.matches("[0-9]{5}\\.entry")) entries++;
            else if (!name.matches("[0-9]{5}\\.commit") && !name.matches("\\.writing-.*\\.tmp")) {
                throw new IOException("Unknown realm journal file: " + name);
            }
        }
        if (entries == 0) {
            // No PREPARED publication means the protocol never mutated a player. Preserve staging
            // evidence, but do not invent a corrupt paid identity for an empty/unpublished draft.
            for (Path file : files) {
                if (file.getFileName().toString().endsWith(".commit")) throw new IOException("Orphan commit marker");
            }
            return;
        }
        totalEvents += entries;
        for (int revision = 0; revision < entries; revision++) {
            Path path = store.event(realm, revision);
            byte[] bytes = store.read(path);
            Event event = decode(bytes);
            if (!event.realm().equals(realm) || event.revision() != revision) throw new IOException("Event identity mismatch");
            Path marker = marker(path);
            if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
                if (!MessageDigest.isEqual(store.read(marker), digest(bytes))) throw new IOException("Commit digest mismatch");
                heads.put(realm, event);
            } else {
                if (revision != entries - 1) throw new IOException("Uncommitted event precedes later event");
                pending.put(realm, event);
            }
        }
        for (Path file : files) {
            if (file.getFileName().toString().endsWith(".commit")) {
                Path entry = file.resolveSibling(file.getFileName().toString().replace(".commit", ".entry"));
                if (!Files.exists(entry, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Orphan commit marker");
            }
        }
    }

    public Map<UUID, String> failures() { return Map.copyOf(failures); }
    public List<Event> heads() { return List.copyOf(heads.values()); }
    public List<Event> pending() { return List.copyOf(pending.values()); }
    public Event head(UUID realm) throws IOException { check(realm); return heads.get(realm); }
    public Event pending(UUID realm) throws IOException { check(realm); return pending.get(realm); }
    public long remainingGlobalEvents() { return Math.max(0, MAX_TOTAL_EVENTS - totalEvents); }
    public int remainingRealmEvents(UUID realm) throws IOException {
        check(realm);
        Event latest = pending.getOrDefault(realm, heads.get(realm));
        return latest == null ? LedgerStore.MAX_EVENTS : LedgerStore.MAX_EVENTS - latest.revision() - 1;
    }

    /** Quarantined identities reserve one slot each; one corrupt realm is not a global admission gate. */
    public Set<UUID> reservedIdentities(Set<UUID> additionalQuarantined, IdentityReservation reservation) throws IOException {
        Set<UUID> result = new HashSet<>(failures.keySet());
        result.addAll(additionalQuarantined);
        for (Event event : heads.values()) {
            if (!result.contains(event.realm()) && reservation.isPaid(event)) result.add(event.realm());
        }
        for (Event event : pending.values()) {
            if (!result.contains(event.realm()) && reservation.isPaid(event)) result.add(event.realm());
        }
        return Set.copyOf(result);
    }

    public Event pendingFor(UUID actor) throws IOException {
        for (Event event : pending.values()) {
            if (actor.equals(event.actor())) { check(event.realm()); return event; }
        }
        return null;
    }

    /** Raw journal publication. Inventory adapters must use prepareInventory to force their preimage first. */
    public Event prepare(UUID realm, UUID actor, byte[] state, byte[] plan) throws IOException {
        check(realm);
        if (pending.containsKey(realm)) throw new IOException("Realm has an unresolved transaction");
        if (actor != null && pendingFor(actor) != null) throw new IOException("Player has an unresolved transaction");
        if (totalEvents >= MAX_TOTAL_EVENTS) throw new IOException("Journal event capacity reached; existing worlds and returns remain retained");
        Event previous = heads.get(realm);
        if (!directories.contains(realm) && directories.size() >= MAX_REALMS) throw new IOException("Journal directory capacity reached");
        Event event = new Event(realm, previous == null ? 0 : previous.revision() + 1, actor, UUID.randomUUID(), state, plan);
        byte[] encoded = encode(event);
        if (encoded.length > LedgerStore.MAX_FILE_BYTES) throw new IOException("Event too large");
        Path path = store.event(realm, event.revision());
        directories.add(realm);
        // Retain the reservation even when publication throws after rename. A retry uses these exact bytes.
        pending.put(realm, event);
        totalEvents++;
        store.publish(path, encoded);
        return event;
    }

    /**
     * Establishes a fresh, verified durable preimage before publishing an inventory transaction.
     * Callers must recover any outstanding transaction first; a baseline never erases its receipt.
     */
    public Event prepareInventory(UUID realm, UUID actor, byte[] state, byte[] plan, Participant participant) throws IOException {
        if (actor == null || participant == null) throw new IllegalArgumentException("Inventory transaction requires a player");
        check(realm);
        if (pending.containsKey(realm) || pendingFor(actor) != null) throw new IOException("Recover the pending transaction before saving a baseline");
        saveFreshBaseline(participant);
        return prepare(realm, actor, state, plan);
    }

    /** A new nonce prevents a swallowed save from passing verification against an old receipt. */
    public static void saveFreshBaseline(Participant participant) throws IOException {
        String previous = participant.receipt();
        String nonce;
        do { nonce = UUID.randomUUID().toString(); } while (nonce.equals(previous));
        participant.receipt(nonce);
        participant.saveAndForce(nonce);
    }

    public void complete(Event event, Participant participant) throws IOException {
        check(event.realm());
        Event current = pending.get(event.realm());
        if (current == null || !current.transaction().equals(event.transaction())) throw new IOException("Not the pending transaction");
        byte[] encoded = encode(event);
        Path path = store.event(event.realm(), event.revision());
        store.publish(path, encoded);
        if (event.actor() != null) {
            if (participant == null) throw new IOException("Player must be online to reconcile payment");
            String receipt = event.transaction().toString();
            if (!receipt.equals(participant.receipt())) {
                participant.apply(event.plan());
                participant.receipt(receipt);
            }
            // Even if a previous attempt applied the plan but failed later, force the normal save again.
            participant.saveAndForce(receipt);
        }
        store.publish(marker(path), digest(encoded));
        heads.put(event.realm(), event);
        pending.remove(event.realm());
    }

    private void check(UUID realm) throws IOException {
        if (failures.containsKey(realm)) throw new IOException("Realm journal is corrupt; no changes made: " + failures.get(realm));
    }

    private static Path marker(Path event) {
        return event.resolveSibling(event.getFileName().toString().replace(".entry", ".commit"));
    }

    private static byte[] digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).getBytes(StandardCharsets.US_ASCII); }
        catch (NoSuchAlgorithmException exception) { throw new AssertionError(exception); }
    }

    private static byte[] encode(Event event) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeInt(1);
            output.writeUTF(event.realm().toString());
            output.writeInt(event.revision());
            output.writeUTF(event.actor() == null ? "" : event.actor().toString());
            output.writeUTF(event.transaction().toString());
            output.writeInt(event.state.length);
            output.write(event.state);
            output.writeInt(event.plan.length);
            output.write(event.plan);
        }
        byte[] payload = bytes.toByteArray();
        bytes.writeBytes(digest(payload));
        return bytes.toByteArray();
    }

    private static Event decode(byte[] bytes) throws IOException {
        if (bytes.length < 64) throw new IOException("Truncated journal checksum");
        byte[] payload = Arrays.copyOf(bytes, bytes.length - 64);
        if (!MessageDigest.isEqual(Arrays.copyOfRange(bytes, bytes.length - 64, bytes.length), digest(payload))) {
            throw new IOException("Prepared event checksum mismatch");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (input.readInt() != MAGIC || input.readInt() != 1) throw new IOException("Unsupported journal schema");
            UUID realm = UUID.fromString(input.readUTF());
            int revision = input.readInt();
            if (revision < 0 || revision >= LedgerStore.MAX_EVENTS) throw new IOException("Invalid revision");
            String actor = input.readUTF();
            UUID transaction = UUID.fromString(input.readUTF());
            byte[] state = boundedBytes(input);
            byte[] plan = boundedBytes(input);
            if (input.available() != 0) throw new IOException("Trailing event data");
            return new Event(realm, revision, actor.isEmpty() ? null : UUID.fromString(actor), transaction, state, plan);
        } catch (IllegalArgumentException exception) { throw new IOException("Invalid event identifiers", exception); }
    }

    private static byte[] boundedBytes(DataInputStream input) throws IOException {
        int size = input.readInt();
        if (size < 0 || size > LedgerStore.MAX_FILE_BYTES || size > input.available()) throw new IOException("Invalid field size");
        return input.readNBytes(size);
    }
}
