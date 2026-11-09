"""Helper-only contracts; run with Python unittest and the repository's JDK on JAVA_HOME.

The coordinate test compiles the actual two pure Java planning methods, not a Python
reimplementation. It neither starts Minecraft nor packages any mod artifacts.
"""

import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "src/gametest/java/qouteall/imm_ptl/gametest/RealmSeedRegressionTest.java"


def method(source, name):
    start = re.search(r"    private static [^\n]+\b" + name + r"\(", source).start()
    opening = source.index("{", start)
    depth = 1
    end = opening + 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end]


class RealmSeedStagingTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = SOURCE.read_text()

    def test_every_full_chunk_request_yields_a_normal_tick(self):
        warm = method(self.source, "warmChunks")
        self.assertRegex(warm, re.compile(
            r"for \(ChunkPos chunk : chunks\) \{\s*"
            r"server.runOnServer\(s -> s.getLevel\(dimension\(record\)\)"
            r".getChunk\(chunk.x\(\), chunk.z\(\)\)\);\s*"
            r"context.waitTick\(\);\s*\}"))
        self.assertEqual(warm.count(".getChunk("), 1)
        self.assertIn("check(chunks.size() <= 293,", warm)
        self.assertNotIn("300-tick", self.source)
        self.assertNotIn("normal ticket lifetime", self.source)
        self.assertIn("server.waitFor(s -> chunks.stream().allMatch", warm)
        self.assertIn(".getChunkSource().getChunkNow(chunk.x(), chunk.z()) != null), 600)", warm)

    def test_paid_commit_and_verification_cannot_regenerate_chunk_loops(self):
        commit = method(self.source, "commitPaidFixtures")
        for operation in ("getChunk", "ensureWorld", "verifyWorld", "geography"):
            self.assertNotIn(operation, commit)
        for name in ("verifyWorld", "probeFeatureSeedAndTerrace", "geography"):
            self.assertNotIn(".getChunk(", method(self.source, name))
        loaded = method(self.source, "loadedChunk")
        self.assertIn(".getChunkNow(x, z)", loaded)
        self.assertIn("check(chunk != null,", loaded)
        self.assertNotIn(".getChunk(", loaded)

    def test_world_is_inspected_before_feature_neighborhood_warming(self):
        staged = method(self.source, "verifyWorldCooperatively")
        operations = (
            'warmChunks(context, server, record, "inspection", plan.inspection())',
            "requireLoadedChunks(world, plan.inspection())",
            "verifyWorld(world, record)",
            "return geography(world)",
            'warmChunks(context, server, record, "features", plan.features())',
            "requireLoadedChunks(world, plan.features())",
            "probeFeatureSeedAndTerrace(world,",
        )
        positions = [staged.index(operation) for operation in operations]
        self.assertEqual(positions, sorted(positions))
        self.assertEqual(staged.count("context.waitTick();"), 3)

    def test_each_phase_releases_its_exact_loader_objects_even_on_failure(self):
        staged = method(self.source, "verifyWorldCooperatively")
        for phase, variable in (("inspection", "inspectionHolds"), ("features", "featureHolds")):
            with self.subTest(phase=phase):
                self.assertRegex(staged, re.compile(
                    rf"List<ChunkLoader> {variable} = probeChunkLoaders\(record, plan.{phase}\(\)\);"
                    rf".*?try \{{.*?for \(ChunkLoader loader : {variable}\) "
                    rf"PortalAPI.addGlobalChunkLoader\(s, loader\);.*?"
                    rf'warmChunks\(context, server, record, "{phase}", plan.{phase}\(\)\);'
                    rf".*?requireLoadedChunks\(world, plan.{phase}\(\)\);.*?"
                    rf"\}} finally \{{\s*server.runOnServer\(s -> \{{\s*"
                    rf"for \(ChunkLoader loader : {variable}\) PortalAPI.removeGlobalChunkLoader\(s, loader\);",
                    re.DOTALL))
        loaders = method(self.source, "probeChunkLoaders")
        self.assertLess(loaders.index("check(chunks.size() <= 293,"), loaders.index("new ChunkLoader("))
        self.assertIn("new ChunkLoader(dimension(record), chunk.x(), chunk.z(), 0)", loaders)
        self.assertIn(".toList()", loaders)
        for forbidden in ("addChunkLoaderForPlayer", "setChunkForced", "updateChunkForced", "TicketType.",
                          "WeakReference", "addTicket", "removeTicket", "additionalChunkLoaders.clear"):
            self.assertNotIn(forbidden, self.source)

    def test_actual_java_coordinate_plans_cover_all_probes_and_remain_bounded(self):
        home = os.environ.get("JAVA_HOME")
        javac = str(Path(home) / "bin/javac") if home else shutil.which("javac")
        java = str(Path(home) / "bin/java") if home else shutil.which("java")
        if not javac or not java:
            self.skipTest("Existing JDK required to execute the actual Java planning methods")
        harness = r"""
import java.util.*;
public class RealmProbePlanContract {
    private record ChunkPos(int x, int z) { }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        List<ChunkPos> terrace = List.of(new ChunkPos(-1, -1), new ChunkPos(-1, 0),
            new ChunkPos(0, -1), new ChunkPos(0, 0));
        for (int radius : new int[]{128, 129, 255, 256, 1024}) {
            List<ChunkPos> actual = inspectionChunks(radius);
            Set<ChunkPos> expected = new HashSet<>(terrace);
            expected.add(new ChunkPos((radius + 31) >> 4, 0));
            expected.add(new ChunkPos((radius + 20) >> 4, 0));
            for (int x : new int[]{-48, -32, 32, 48}) for (int z : new int[]{-32, 32}) {
                expected.add(new ChunkPos(x >> 4, z >> 4));
            }
            require(new HashSet<>(actual).equals(expected), "Border/terrace/geography coverage");
            require(actual.size() == expected.size() && actual.size() <= 14, "Inspection bound/deduplication");
        }
        for (int cx : new int[]{-64, -1, 0, 2, 64}) {
            for (int cz : new int[]{-64, -1, 0, 2, 64}) {
                for (int radius : new int[]{0, 1, 8}) {
                    List<ChunkPos> actual = featureChunks(cx, cz, radius);
                    Set<ChunkPos> expected = new HashSet<>(terrace);
                    int coverage = Math.max(1, radius);
                    for (int x = cx - coverage; x <= cx + coverage; x++) {
                        for (int z = cz - coverage; z <= cz + coverage; z++) expected.add(new ChunkPos(x, z));
                    }
                    require(new HashSet<>(actual).equals(expected), "Unchanged FEATURES square/outcrop coverage");
                    require(actual.size() == expected.size() && actual.size() <= 293, "Feature bound/deduplication");
                    require(actual.subList(actual.size() - 4, actual.size()).equals(terrace), "Include shelter last");
                    for (int localX : new int[]{0, 8, 15}) for (int localZ : new int[]{0, 8, 15}) {
                        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
                            require(actual.contains(new ChunkPos((cx * 16 + localX + dx) >> 4,
                                (cz * 16 + localZ + dz) >> 4)), "Outcrop scan crossing chunk edges");
                        }
                    }
                    try {
                        actual.add(new ChunkPos(999, 999));
                        throw new AssertionError("Plan must be immutable");
                    } catch (UnsupportedOperationException expectedFailure) { }
                }
            }
        }
    }
METHODS
}
""".replace("METHODS", method(self.source, "inspectionChunks") + "\n"
            + method(self.source, "featureChunks"))
        with tempfile.TemporaryDirectory(prefix="realm-probe-plan-", dir="/tmp/opencode") as temporary:
            folder = Path(temporary)
            source = folder / "RealmProbePlanContract.java"
            source.write_text(harness)
            subprocess.run([javac, "-d", str(folder), str(source)], check=True, capture_output=True, timeout=60)
            subprocess.run([java, "-cp", str(folder), "RealmProbePlanContract"], check=True,
                           capture_output=True, timeout=60)


if __name__ == "__main__":
    unittest.main()
