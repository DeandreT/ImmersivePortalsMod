package qouteall.imm_ptl.iris_bootstrap;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class VerifierTest {
    @ParameterizedTest
    @ValueSource(strings = {"original", "patched"})
    void actualJava25VerifierSeparatesEarlyResolutionFromNormalLaterInvocation(String mode) throws Exception {
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xverify:all", "-XX:ActiveProcessorCount=2", "-cp", System.getProperty("bootstrap.testClasspath"),
            VerifierProbe.class.getName(), mode, System.getProperty("bootstrap.publishedIris"))
            .redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Owned verifier child timed out");
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("VERIFIED mode=" + mode), output);
            assertTrue(output.contains("later_call_preserved=true"), output);
            System.out.print(output);
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); process.waitFor(); }
        }
    }
}
