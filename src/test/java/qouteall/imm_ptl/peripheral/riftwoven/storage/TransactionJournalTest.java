package qouteall.imm_ptl.peripheral.riftwoven.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** Registry-free crash boundary tests: the fake player disk is distinct from volatile inventory. */
class TransactionJournalTest {
    @TempDir Path directory;

    private static byte[] bytes(String string) { return string.getBytes(StandardCharsets.UTF_8); }
    private static byte[] plan(int before, int after) { return ByteBuffer.allocate(8).putInt(before).putInt(after).array(); }

    private static final class Disk {
        int count = 10;
        String receipt = "";
        String dimension = "riftwoven:realm_test";
        int saves;
    }

    private static final class Player implements TransactionJournal.Participant {
        final Disk disk;
        int count;
        String receipt;
        String dimension;
        int applications;
        boolean failBeforeSave;
        boolean failAfterSave;
        boolean swallowSave;

        Player(Disk disk) { this.disk = disk; count = disk.count; receipt = disk.receipt; dimension = disk.dimension; }
        @Override public String receipt() { return receipt; }
        @Override public void receipt(String receipt) { this.receipt = receipt; }
        @Override public void apply(byte[] plan) throws IOException {
            ByteBuffer buffer = ByteBuffer.wrap(plan);
            int before = buffer.getInt();
            int after = buffer.getInt();
            if (count != before) throw new IOException("Inventory preimage mismatch");
            count = after;
            applications++;
        }
        @Override public void saveAndForce(String expectedReceipt) throws IOException {
            disk.saves++;
            if (failBeforeSave) throw new IOException("Player save unavailable");
            if (!swallowSave) {
                disk.count = count;
                disk.receipt = receipt;
                disk.dimension = dimension;
            }
            if (failAfterSave) throw new IOException("Failure after durable save");
            if (!disk.receipt.equals(expectedReceipt)) throw new IOException("Receipt verification failed");
        }
    }

    private TransactionJournal journal() throws IOException { return new TransactionJournal(new LedgerStore(directory)); }

    private static void initial(TransactionJournal journal, UUID realm) throws IOException {
        var draft = journal.prepare(realm, null, bytes("draft"), new byte[0]);
        journal.complete(draft, null);
    }

    @Test void eachForcedFileAndRenameBoundaryPreservesPaymentAndFrozenIdentity() throws Exception {
        for (int failAt = 1; failAt <= 6; failAt++) {
            Path root = directory.resolve("case-" + failAt);
            AtomicInteger boundary = new AtomicInteger();
            AtomicInteger failure = new AtomicInteger(Integer.MAX_VALUE);
            LedgerStore store = new LedgerStore(root, phase -> {
                if (boundary.incrementAndGet() == failure.get()) throw new IOException("Injected " + phase);
            });
            TransactionJournal journal = new TransactionJournal(store);
            UUID realm = UUID.randomUUID();
            UUID actor = UUID.randomUUID();
            initial(journal, realm);
            boundary.set(0);
            failure.set(failAt);
            Disk disk = new Disk();
            Player player = new Player(disk);
            assertThrows(IOException.class, () -> {
                var prepared = journal.prepare(realm, actor, bytes("immutable-seed=37;profile=v1"), plan(10, 6));
                journal.complete(prepared, player);
            });
            // Reopen after simulated process loss: volatile mutation and receipt are discarded.
            TransactionJournal restored = new TransactionJournal(new LedgerStore(root));
            Player restoredPlayer = new Player(disk);
            var pending = restored.pendingFor(actor);
            if (failAt == 1) {
                // The initial prepare never reached rename, so neither payment nor identity was published.
                assertNull(pending);
                assertEquals(10, disk.count);
                assertArrayEquals(bytes("draft"), restored.head(realm).state());
            } else {
                if (pending != null) {
                    assertArrayEquals(bytes("immutable-seed=37;profile=v1"), pending.state());
                    restored.complete(pending, restoredPlayer);
                }
                assertEquals(6, disk.count, "Exactly four diamonds must remain consumed across recovery");
                assertArrayEquals(bytes("immutable-seed=37;profile=v1"), restored.head(realm).state());
                assertNull(restored.pendingFor(actor));
                assertTrue(player.applications + restoredPlayer.applications <= 1,
                    "An inventory carrying a durable receipt must not be charged again");
            }
        }
    }

    @Test void sameOnlinePlayerRetriesFailedSaveWithoutApplyingTwice() throws Exception {
        TransactionJournal journal = journal();
        UUID realm = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        initial(journal, realm);
        Disk disk = new Disk();
        Player player = new Player(disk);
        player.failBeforeSave = true;
        var prepared = journal.prepare(realm, actor, bytes("seed=12"), plan(10, 6));
        assertThrows(IOException.class, () -> journal.complete(prepared, player));
        assertEquals(6, player.count);
        assertEquals(10, disk.count);
        assertArrayEquals(bytes("draft"), journal.head(realm).state());
        player.failBeforeSave = false;
        journal.complete(prepared, player);
        assertEquals(1, player.applications);
        assertEquals(2, disk.saves, "A failed phase must not suppress the retry's forced player save");
        assertEquals(6, disk.count);
    }

    @Test void absentDiskReceiptReplaysOriginalIntentAfterProcessLoss() throws Exception {
        TransactionJournal journal = journal();
        UUID realm = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        initial(journal, realm);
        Disk disk = new Disk();
        Player player = new Player(disk);
        player.failBeforeSave = true;
        var prepared = journal.prepare(realm, actor, bytes("fixed-landscape=998"), plan(10, 6));
        assertThrows(IOException.class, () -> journal.complete(prepared, player));
        TransactionJournal restored = journal();
        Player fromDisk = new Player(disk);
        restored.complete(restored.pendingFor(actor), fromDisk);
        assertEquals(6, disk.count);
        assertArrayEquals(bytes("fixed-landscape=998"), restored.head(realm).state());
    }

    @Test void unsavedInventoryBaselineIsDurableBeforePreparedAndSurvivesCrashBeforePayment() throws Exception {
        TransactionJournal journal = journal();
        UUID realm = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        initial(journal, realm);
        Disk disk = new Disk();
        Player player = new Player(disk);
        player.count = 19; // Newly obtained items are not in the previous vanilla player .dat.
        var event = journal.prepareInventory(realm, actor, bytes("fixed-seed=77"), plan(19, 15), player);
        assertEquals(19, disk.count, "PREPARED must never refer to an unsaved inventory preimage");
        assertFalse(disk.receipt.isEmpty());
        assertNotEquals(event.transaction().toString(), disk.receipt, "Baseline and applied-payment receipts are distinct");
        // Crash here, before payment mutation: login loads the exact forced baseline.
        TransactionJournal restored = journal();
        Player fromDisk = new Player(disk);
        restored.complete(restored.pendingFor(actor), fromDisk);
        assertEquals(15, disk.count);
        assertEquals(1, fromDisk.applications);
        assertArrayEquals(bytes("fixed-seed=77"), restored.head(realm).state());
    }

    @Test void swallowedBaselineSaveCannotPassWithAnOlderExistingReceipt() throws Exception {
        TransactionJournal journal = journal();
        UUID realm = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        initial(journal, realm);
        Disk disk = new Disk();
        disk.receipt = UUID.randomUUID().toString();
        String oldReceipt = disk.receipt;
        Player player = new Player(disk);
        player.count = 19;
        player.swallowSave = true;
        assertThrows(IOException.class, () -> journal.prepareInventory(realm, actor, bytes("not-published"), plan(19, 15), player));
        assertEquals(oldReceipt, disk.receipt);
        assertNotEquals(oldReceipt, player.receipt);
        assertNull(journal.pendingFor(actor));
        assertEquals(10, disk.count);
        assertEquals(19, player.count);
    }

    @Test void freshPlayerStateMarkerPersistsExteriorPositionBeforeVisitTombstone() throws Exception {
        Disk disk = new Disk();
        disk.receipt = UUID.randomUUID().toString();
        String oldReceipt = disk.receipt;
        Player player = new Player(disk);
        player.dimension = "minecraft:overworld";
        TransactionJournal.saveFreshBaseline(player);
        assertEquals("minecraft:overworld", disk.dimension);
        assertNotEquals(oldReceipt, disk.receipt);
        String firstMarker = disk.receipt;
        TransactionJournal.saveFreshBaseline(player);
        assertNotEquals(firstMarker, disk.receipt, "Every exterior save must prove a new snapshot, not an old payment receipt");
        player.swallowSave = true;
        player.dimension = "minecraft:the_nether";
        assertThrows(IOException.class, () -> TransactionJournal.saveFreshBaseline(player));
        assertEquals("minecraft:overworld", disk.dimension, "A failed new save cannot certify the new exterior snapshot");
    }

    @Test void durableReceiptFinalizesRatherThanRechargingAfterSaveFailure() throws Exception {
        TransactionJournal journal = journal();
        UUID realm = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        initial(journal, realm);
        Disk disk = new Disk();
        Player player = new Player(disk);
        player.failAfterSave = true;
        var prepared = journal.prepare(realm, actor, bytes("fixed-seed"), plan(10, 6));
        assertThrows(IOException.class, () -> journal.complete(prepared, player));
        TransactionJournal restored = journal();
        Player fromDisk = new Player(disk);
        restored.complete(restored.pendingFor(actor), fromDisk);
        assertEquals(0, fromDisk.applications);
        assertEquals(6, disk.count);
    }

    @Test void withdrawalRecoveryDoesNotDuplicateRefund() throws Exception {
        TransactionJournal journal = journal();
        UUID realm = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        initial(journal, realm);
        Disk disk = new Disk();
        Player player = new Player(disk);
        player.failAfterSave = true;
        var prepared = journal.prepare(realm, actor, bytes("withdrawn-latest"), plan(10, 26));
        assertThrows(IOException.class, () -> journal.complete(prepared, player));
        TransactionJournal restored = journal();
        Player fromDisk = new Player(disk);
        restored.complete(restored.pendingFor(actor), fromDisk);
        assertEquals(26, disk.count);
        assertEquals(0, fromDisk.applications);
    }

    @Test void mismatchedInventoryFailsClosedButUnrelatedPlayerStillCommits() throws Exception {
        TransactionJournal journal = journal();
        UUID failedRealm = UUID.randomUUID();
        UUID validRealm = UUID.randomUUID();
        UUID failedActor = UUID.randomUUID();
        UUID validActor = UUID.randomUUID();
        initial(journal, failedRealm);
        initial(journal, validRealm);
        var failed = journal.prepare(failedRealm, failedActor, bytes("pending-seed"), plan(10, 6));
        Disk disk = new Disk();
        disk.count = 9;
        assertThrows(IOException.class, () -> journal.complete(failed, new Player(disk)));
        assertNotNull(journal.pendingFor(failedActor));
        assertThrows(IOException.class, () -> journal.prepare(validRealm, failedActor, bytes("another"), plan(9, 5)));
        Disk goodDisk = new Disk();
        var good = journal.prepare(validRealm, validActor, bytes("committed-seed"), plan(10, 6));
        journal.complete(good, new Player(goodDisk));
        assertEquals(6, goodDisk.count);
        assertEquals(9, disk.count);
    }

    @Test void malformedJournalIsPreservedAndOnlyItsRealmFails() throws Exception {
        TransactionJournal journal = journal();
        UUID brokenRealm = UUID.randomUUID();
        UUID healthyRealm = UUID.randomUUID();
        initial(journal, brokenRealm);
        initial(journal, healthyRealm);
        Path broken = new LedgerStore(directory).event(brokenRealm, 0);
        byte[] corruption = bytes("malformed evidence");
        Files.write(broken, corruption);
        TransactionJournal restored = journal();
        assertTrue(restored.failures().containsKey(brokenRealm));
        assertThrows(IOException.class, () -> restored.head(brokenRealm));
        assertArrayEquals(corruption, Files.readAllBytes(broken));
        var healthy = restored.prepare(healthyRealm, UUID.randomUUID(), bytes("valid"), plan(10, 6));
        restored.complete(healthy, new Player(new Disk()));
        assertArrayEquals(bytes("valid"), restored.head(healthyRealm).state());
    }

    @Test void failuresAtStartMiddleAndEndDoNotBlockValidPlayersOrDuplicateCorrectedRetries() throws Exception {
        for (int failedIndex : new int[] { 0, 1, 3 }) {
            TransactionJournal journal = new TransactionJournal(new LedgerStore(directory.resolve("batch-" + failedIndex)));
            UUID[] realms = new UUID[4];
            UUID[] actors = new UUID[4];
            Disk[] disks = new Disk[4];
            for (int index = 0; index < 4; index++) {
                realms[index] = UUID.randomUUID();
                actors[index] = UUID.randomUUID();
                disks[index] = new Disk();
                initial(journal, realms[index]);
            }
            for (int index = 0; index < 4; index++) {
                var event = journal.prepare(realms[index], actors[index], bytes("seed=" + index), plan(10, 6));
                if (index == failedIndex) {
                    disks[index].count = 9;
                    Player invalidPlayer = new Player(disks[index]);
                    assertThrows(IOException.class, () -> journal.complete(event, invalidPlayer));
                } else journal.complete(event, new Player(disks[index]));
            }
            disks[failedIndex].count = 10;
            journal.complete(journal.pendingFor(actors[failedIndex]), new Player(disks[failedIndex]));
            for (int index = 0; index < 4; index++) {
                assertEquals(6, disks[index].count);
                assertEquals(1, disks[index].saves, "Retry only the failed player's transaction");
                assertNull(journal.pendingFor(actors[index]));
                assertArrayEquals(bytes("seed=" + index), journal.head(realms[index]).state());
            }
        }
    }

    @Test void immutableFilesRejectConflictingRetryAndUnsafePaths() throws Exception {
        LedgerStore store = new LedgerStore(directory);
        UUID realm = UUID.randomUUID();
        Path event = store.event(realm, 0);
        store.publish(event, bytes("first"));
        store.publish(event, bytes("first"));
        assertThrows(IOException.class, () -> store.publish(event, bytes("second")));
        assertArrayEquals(bytes("first"), store.read(event));
        assertThrows(IOException.class, () -> store.publish(directory.resolve("../outside"), bytes("no")));
        assertThrows(IOException.class, () -> store.publish(event.resolveSibling("too-large"), new byte[LedgerStore.MAX_FILE_BYTES + 1]));
    }

    @Test void corruptPreparedIdentityCannotBeReinterpretedOrReplaced() throws Exception {
        TransactionJournal journal = journal();
        UUID realm = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        initial(journal, realm);
        var pending = journal.prepare(realm, actor, bytes("seed=12345"), plan(10, 6));
        Path path = new LedgerStore(directory).event(realm, pending.revision());
        byte[] corrupted = Files.readAllBytes(path);
        corrupted[corrupted.length - 65] ^= 1;
        Files.write(path, corrupted);
        TransactionJournal restored = journal();
        assertTrue(restored.failures().containsKey(realm));
        assertThrows(IOException.class, () -> restored.prepare(realm, actor, bytes("new-seed"), plan(10, 6)));
        assertArrayEquals(corrupted, Files.readAllBytes(path));
    }

    @Test void quarantinedIdentityReservesOneSlotWhileHealthyAwakeningsRemainIsolated() throws Exception {
        TransactionJournal journal = journal();
        UUID broken = UUID.randomUUID();
        UUID healthyPaid = UUID.randomUUID();
        UUID growing = UUID.randomUUID();
        initial(journal, broken);
        initial(journal, growing);
        var paid = journal.prepare(healthyPaid, null, bytes("paid"), new byte[0]);
        journal.complete(paid, null);
        Files.write(new LedgerStore(directory).event(broken, 0), bytes("corrupt evidence"));
        UUID unpublished = UUID.randomUUID();
        Path empty = new LedgerStore(directory).realmDirectory(unpublished);
        Files.write(empty.resolve(".writing-unpublished.tmp"), bytes("not published; no inventory could have been mutated"));
        TransactionJournal restored = journal();
        var reservations = restored.reservedIdentities(Set.of(), event -> new String(event.state(), StandardCharsets.UTF_8).equals("paid"));
        assertEquals(Set.of(broken, healthyPaid), reservations);
        assertFalse(restored.failures().containsKey(unpublished), "Unpublished initial writes do not imply a corrupt paid realm");
        assertThrows(IOException.class, () -> restored.prepare(broken, null, bytes("replacement identity"), new byte[0]));
        assertTrue(reservations.size() < 3, "One quarantine must not reject a healthy awakening with an available slot");
        var healthy = restored.prepare(growing, UUID.randomUUID(), bytes("paid"), plan(10, 6));
        restored.complete(healthy, new Player(new Disk()));
        assertEquals(3, restored.reservedIdentities(Set.of(), event -> new String(event.state(), StandardCharsets.UTF_8).equals("paid")).size());
        assertTrue(Files.exists(empty.resolve(".writing-unpublished.tmp")), "Unpublished evidence is preserved, not deleted");
    }

    @Test void journalDoesNotFollowSymlinkedRealmDirectories(@TempDir Path outside) throws Exception {
        UUID realm = UUID.randomUUID();
        Files.createSymbolicLink(directory.resolve(realm.toString()), outside);
        TransactionJournal journal = journal();
        assertTrue(journal.failures().containsKey(realm));
        assertThrows(IOException.class, () -> journal.prepare(realm, null, bytes("no"), new byte[0]));
        try (var children = Files.list(outside)) { assertEquals(0, children.count()); }
    }

    @Test void callerCannotMutatePreparedIdentityViaByteArrayAliasing() throws Exception {
        TransactionJournal journal = journal();
        UUID realm = UUID.randomUUID();
        byte[] seed = bytes("seed=1");
        var event = journal.prepare(realm, null, seed, new byte[0]);
        seed[0] = '!';
        event.state()[0] = '?';
        journal.complete(event, null);
        assertArrayEquals(bytes("seed=1"), journal().head(realm).state());
    }
}
