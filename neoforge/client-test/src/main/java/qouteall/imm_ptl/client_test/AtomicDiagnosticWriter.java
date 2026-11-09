package qouteall.imm_ptl.client_test;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Publishes a complete diagnostic before a subsequent fail-fast operation can stop the JVM. */
final class AtomicDiagnosticWriter {
    static void write(Path target, String text) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), "." + target.getFileName(), ".partial");
        try {
            Files.writeString(temporary, text);
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            // Fail explicitly if atomic publication is unsupported; never expose a truncated receipt.
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private AtomicDiagnosticWriter() {}
}
