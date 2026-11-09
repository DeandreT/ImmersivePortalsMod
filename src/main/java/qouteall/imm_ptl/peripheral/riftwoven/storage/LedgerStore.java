package qouteall.imm_ptl.peripheral.riftwoven.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.UUID;

/** Same-filesystem, forced immutable journal files. No atomic-move fallback is permitted. */
public final class LedgerStore {
    public static final int MAX_FILE_BYTES = 262_144;
    public static final int MAX_EVENTS = 16_384;
    private final Path root;
    private final FailurePoint failure;

    @FunctionalInterface
    public interface FailurePoint {
        void reached(String phase) throws IOException;
    }

    public LedgerStore(Path root) throws IOException {
        this(root, phase -> {});
    }

    public LedgerStore(Path root, FailurePoint failure) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        this.failure = failure;
        createDirectory(this.root);
    }

    private static void createDirectory(Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            Path parent = directory.getParent();
            if (parent != null) createDirectory(parent);
            Files.createDirectory(directory);
            if (parent != null) forceDirectory(parent);
        }
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(directory)) {
            throw new IOException("Journal directory is not a real directory: " + directory);
        }
    }

    public Path root() { return root; }

    public Path realmDirectory(UUID realm) throws IOException {
        Path directory = root.resolve(realm.toString());
        createDirectory(directory);
        return directory;
    }

    public Path event(UUID realm, int revision) throws IOException {
        if (revision < 0 || revision >= MAX_EVENTS) throw new IOException("Realm journal capacity reached");
        return realmDirectory(realm).resolve(String.format(java.util.Locale.ROOT, "%05d.entry", revision));
    }

    public byte[] read(Path path) throws IOException {
        checked(path);
        long size = Files.size(path);
        if (size < 1 || size > MAX_FILE_BYTES) throw new IOException("Invalid journal file size: " + path);
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MAX_FILE_BYTES + 1);
            if (bytes.length != size) throw new IOException("Journal changed while reading: " + path);
            return bytes;
        }
    }

    /** A repeated publication must contain exactly the same bytes. Existing evidence is never replaced. */
    public void publish(Path destination, byte[] bytes) throws IOException {
        checked(destination);
        if (bytes.length < 1 || bytes.length > MAX_FILE_BYTES) throw new IOException("Journal payload too large");
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            if (!Arrays.equals(read(destination), bytes)) throw new IOException("Immutable journal conflict: " + destination);
            forceFile(destination);
            forceDirectory(destination.getParent());
            return;
        }
        try (var children = Files.list(destination.getParent())) {
            if (children.filter(path -> path.getFileName().toString().startsWith(".writing-"))
                .limit(33).count() >= 32) throw new IOException("Too many interrupted writes; preserve and inspect journal staging files");
        }
        Path temporary = Files.createTempFile(destination.getParent(), ".writing-", ".tmp");
        // Failed writes intentionally leave their evidence; only completed staging files are removed by rename.
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        failure.reached("file-forced");
        Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
        failure.reached("renamed");
        forceDirectory(destination.getParent());
        failure.reached("directory-forced");
    }

    public static void forceFile(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Not a regular file: " + file);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            channel.force(true);
        }
    }

    public static void forceDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private void checked(Path path) throws IOException {
        if (!path.isAbsolute() || !path.normalize().equals(path) || !path.startsWith(root) || Files.isSymbolicLink(path)) {
            throw new IOException("Unsafe journal path: " + path);
        }
        Path parent = path.getParent();
        while (parent != null && parent.startsWith(root)) {
            if (Files.isSymbolicLink(parent)) throw new IOException("Symlink in journal path: " + parent);
            parent = parent.getParent();
        }
    }
}
