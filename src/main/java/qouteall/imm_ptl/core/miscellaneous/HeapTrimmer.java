package qouteall.imm_ptl.core.miscellaneous;

import com.mojang.logging.LogUtils;
import com.sun.management.HotSpotDiagnosticMXBean;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.IPGlobal;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import javax.management.MBeanServer;
import javax.management.ObjectName;

/**
 * Loading the game allocates far more than it keeps (mixin transformation, resource loading, world loading), and
 * by default G1 holds on to free heap of up to 70% of the capacity. So a game with about 300 MB of live data can
 * occupy 800 MB. This tells the running JVM to give free memory back, and collects once while loading anyway hides
 * the pause. The heap still grows when the game needs it. Only for G1, and a fixed -Xms keeps its minimum.
 * <p>
 * Native memory has the same problem: glibc keeps what was freed for later, about 200 MB after loading a world.
 * Returning it needs a call that the JVM provides on Linux. It runs on its own thread, so the game keeps rendering.
 */
public final class HeapTrimmer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String MIN_FREE_PERCENT = "15";
    private static final String MAX_FREE_PERCENT = "35";

    private static final long NATIVE_TRIM_INTERVAL_MILLIS = 60_000;

    private static boolean ratiosApplied = false;
    private static boolean nativeTrimStarted = false;

    private HeapTrimmer() {}

    /**
     * Call at a moment when a short pause doesn't matter.
     */
    public static void trimNow() {
        if (!IPGlobal.trimJavaHeap) {
            return;
        }
        if (applyRatios()) {
            long before = Runtime.getRuntime().totalMemory();
            System.gc();
            LOGGER.info(
                "Trimmed the Java heap from {} MiB to {} MiB",
                before / (1024 * 1024), Runtime.getRuntime().totalMemory() / (1024 * 1024)
            );
        }
        startNativeTrimming();
    }

    private static void startNativeTrimming() {
        if (nativeTrimStarted || !System.getProperty("os.name").startsWith("Linux")) {
            return;
        }
        nativeTrimStarted = true;
        Thread.ofPlatform().daemon().name("ImmPtl Memory Trimmer").start(() -> {
            try {
                ObjectName diagnosticCommand = new ObjectName("com.sun.management:type=DiagnosticCommand");
                MBeanServer server = ManagementFactory.getPlatformMBeanServer();
                while (true) {
                    Object result = server.invoke(
                        diagnosticCommand, "systemTrimNativeHeap",
                        new Object[]{null}, new String[]{String[].class.getName()}
                    );
                    LOGGER.debug("{}", result);
                    Thread.sleep(NATIVE_TRIM_INTERVAL_MILLIS);
                }
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            catch (Exception e) {
                // not available on this C library or JVM
                LOGGER.info("Cannot trim native memory: {}", e.toString());
            }
        });
    }

    private static boolean applyRatios() {
        if (ratiosApplied) {
            return true;
        }
        boolean isG1 = ManagementFactory.getGarbageCollectorMXBeans().stream()
            .map(GarbageCollectorMXBean::getName)
            .anyMatch(name -> name.startsWith("G1"));
        if (!isG1) {
            return false;
        }
        try {
            HotSpotDiagnosticMXBean bean = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
            // the minimum first: each has to stay valid against the other's current value
            bean.setVMOption("MinHeapFreeRatio", MIN_FREE_PERCENT);
            bean.setVMOption("MaxHeapFreeRatio", MAX_FREE_PERCENT);
            ratiosApplied = true;
            return true;
        }
        catch (RuntimeException e) {
            LOGGER.info("Cannot change the JVM's heap sizing, leaving it as it is", e);
            return false;
        }
    }
}
