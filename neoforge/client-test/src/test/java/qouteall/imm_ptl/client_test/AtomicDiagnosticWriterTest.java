package qouteall.imm_ptl.client_test;

import java.nio.file.Files;
import java.nio.file.Path;

/** Tests real atomic publication using a caller-owned scratch directory, without a game. */
public final class AtomicDiagnosticWriterTest {
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        Path receipt = directory.resolve("receipt.json");
        AtomicDiagnosticWriter.write(receipt, "{\"status\":\"PARTIAL_DIAGNOSTIC\",\"unicode\":\"é\"}");
        require(Files.readString(receipt).contains("é"), "Receipt must retain complete UTF-8 content");
        AtomicDiagnosticWriter.write(receipt, "replacement");
        require(Files.readString(receipt).equals("replacement"), "Atomic replacement must publish the complete new receipt");
        Path blocked = directory.resolve("blocked");
        Files.createDirectory(blocked);
        try {
            AtomicDiagnosticWriter.write(blocked, "must not replace a directory");
            throw new AssertionError("An invalid target must fail explicitly");
        } catch (java.io.IOException expected) {
            require(Files.isDirectory(blocked), "Failed publication must preserve the existing target");
        }
        try (var files = Files.list(directory)) {
            require(files.noneMatch(path -> path.getFileName().toString().endsWith(".partial")),
                "Success and failure must both clean only their own temporary receipt");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) { throw new AssertionError(message); }
    }
}
