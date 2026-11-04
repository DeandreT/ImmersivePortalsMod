package qouteall.imm_ptl.core.miscellaneous;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.Arrays;

/**
 * Logs frame times while the system property imm_ptl.logFrameStats is true, for comparing builds and settings.
 * Each line covers about 5 seconds: average FPS, the slowest 1% of frames, and the slowest frame.
 */
public final class FrameStats {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean ENABLED = Boolean.getBoolean("imm_ptl.logFrameStats");
    private static final long REPORT_NANOS = 5_000_000_000L;

    private static long[] frameNanos = new long[4096];
    private static int frameCount = 0;
    private static long lastFrameTime = 0;
    private static long windowStart = 0;

    private FrameStats() {}

    public static void onEndFrame() {
        if (!ENABLED) {
            return;
        }
        long now = System.nanoTime();
        if (lastFrameTime != 0) {
            if (frameCount == frameNanos.length) {
                frameNanos = Arrays.copyOf(frameNanos, frameNanos.length * 2);
            }
            frameNanos[frameCount++] = now - lastFrameTime;
        }
        else {
            windowStart = now;
        }
        lastFrameTime = now;

        if (now - windowStart >= REPORT_NANOS && frameCount > 0) {
            Arrays.sort(frameNanos, 0, frameCount);
            LOGGER.info(
                "ImmPtl frame stats: fps={} p99={}ms max={}ms",
                Math.round(frameCount * 1e9 / (now - windowStart)),
                Math.round(frameNanos[Math.min(frameCount - 1, frameCount * 99 / 100)] / 1e6),
                Math.round(frameNanos[frameCount - 1] / 1e6)
            );
            frameCount = 0;
            windowStart = now;
        }
    }
}
