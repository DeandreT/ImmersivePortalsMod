"""Non-game regression tests for disposable test-directory safety."""

import importlib.util
import concurrent.futures
import hashlib
import io
import json
from pathlib import Path
import tempfile
import threading
from types import SimpleNamespace
import unittest
from unittest import mock


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("smoke_test", ROOT / ".github/scripts/smoke_test.py")
smoke = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(smoke)


class SmokeDirectoryTest(unittest.TestCase):
    def test_new_directory_is_marked_and_can_be_reused(self):
        with tempfile.TemporaryDirectory() as temporary:
            work = Path(temporary) / "work"
            self.assertEqual(smoke.fresh_dir(work), work)
            (work / "old-log.txt").write_text("disposable")
            smoke.fresh_dir(work)
            self.assertFalse((work / "old-log.txt").exists())
            self.assertEqual((work / smoke.WORK_MARKER).read_text(), smoke.WORK_MARKER_CONTENT)

    def test_unowned_directory_and_its_content_survive(self):
        with tempfile.TemporaryDirectory() as temporary:
            work = Path(temporary) / "save"
            work.mkdir()
            saved = work / "level.dat"
            saved.write_bytes(b"world data")
            with self.assertRaises(smoke.SmokeFailure):
                smoke.fresh_dir(work)
            self.assertEqual(saved.read_bytes(), b"world data")

    def test_incorrect_marker_does_not_authorize_deletion(self):
        with tempfile.TemporaryDirectory() as temporary:
            work = Path(temporary)
            (work / smoke.WORK_MARKER).write_text("unrelated file")
            with self.assertRaises(smoke.SmokeFailure):
                smoke.fresh_dir(work)
            self.assertTrue(work.is_dir())

    def test_symlink_cannot_authorize_deletion(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            target = smoke.fresh_dir(root / "target")
            link = root / "link"
            link.symlink_to(target, target_is_directory=True)
            with self.assertRaises(smoke.SmokeFailure):
                smoke.fresh_dir(link)
            self.assertTrue(target.is_dir())

    def test_symlinked_marker_does_not_authorize_deletion(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            target = root / "marker"
            target.write_text(smoke.WORK_MARKER_CONTENT)
            work = root / "work"
            work.mkdir()
            (work / smoke.WORK_MARKER).symlink_to(target)
            with self.assertRaises(smoke.SmokeFailure):
                smoke.fresh_dir(work)
            self.assertTrue(target.is_file())

    def test_home_repository_and_ancestors_are_never_deleted(self):
        for path in {Path.home(), ROOT, *ROOT.parents}:
            with self.subTest(path=path), mock.patch.object(smoke.shutil, "rmtree") as remove:
                with self.assertRaises(smoke.SmokeFailure):
                    smoke.fresh_dir(path)
                remove.assert_not_called()

    def test_non_directory_is_preserved(self):
        with tempfile.TemporaryDirectory() as temporary:
            work = Path(temporary) / "file"
            work.write_text("keep")
            with self.assertRaises(smoke.SmokeFailure):
                smoke.fresh_dir(work)
            self.assertEqual(work.read_text(), "keep")


class SmokeDownloadTest(unittest.TestCase):
    def test_download_is_verified_before_becoming_visible(self):
        content = b"verified archive"
        with tempfile.TemporaryDirectory() as temporary:
            dest = Path(temporary) / "archive.jar"
            with mock.patch.object(smoke.urllib.request, "urlopen", return_value=io.BytesIO(content)):
                smoke.fetch("https://example.invalid/archive.jar", dest, hashlib.sha1(content).hexdigest())
            self.assertEqual(dest.read_bytes(), content)
            self.assertEqual(list(Path(temporary).glob("*.part")), [])

    def test_bad_download_preserves_previous_cache_entry(self):
        with tempfile.TemporaryDirectory() as temporary:
            dest = Path(temporary) / "archive.jar"
            dest.write_bytes(b"previous")
            with mock.patch.object(smoke.urllib.request, "urlopen", return_value=io.BytesIO(b"corrupt")):
                with self.assertRaises(smoke.SmokeFailure):
                    smoke.fetch("https://example.invalid/archive.jar", dest, hashlib.sha1(b"expected").hexdigest())
            self.assertEqual(dest.read_bytes(), b"previous")
            self.assertEqual(list(Path(temporary).glob("*.part")), [])

    def test_network_failure_leaves_no_partial_cache_entry(self):
        with tempfile.TemporaryDirectory() as temporary:
            dest = Path(temporary) / "archive.jar"
            with mock.patch.object(smoke.urllib.request, "urlopen", side_effect=OSError("network unavailable")):
                with self.assertRaises(OSError):
                    smoke.fetch("https://example.invalid/archive.jar", dest)
            self.assertEqual(list(Path(temporary).iterdir()), [])

    def test_concurrent_downloads_do_not_share_temporary_files(self):
        content = b"shared archive"
        rendezvous = threading.Barrier(2)

        def response(*args, **kwargs):
            rendezvous.wait(timeout=5)
            return io.BytesIO(content)

        with tempfile.TemporaryDirectory() as temporary:
            dest = Path(temporary) / "archive.jar"
            with mock.patch.object(smoke.urllib.request, "urlopen", side_effect=response):
                with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
                    jobs = [pool.submit(smoke.fetch, "https://example.invalid/archive.jar", dest) for _ in range(2)]
                    for job in jobs:
                        self.assertEqual(job.result(timeout=10), dest)
            self.assertEqual(dest.read_bytes(), content)
            self.assertEqual(list(Path(temporary).glob("*.part")), [])


class SmokeTerrainTest(unittest.TestCase):
    def test_initial_and_return_use_the_same_positive_ground_crop(self):
        for stage in ["before crossing", "after returning"]:
            with self.subTest(stage=stage), mock.patch.object(smoke, "mean_color", return_value=(90, 115, 63)) as color:
                self.assertEqual(smoke.check_local_terrain(Path("frame.png"), stage), (90, 115, 63))
                color.assert_called_once_with(Path("frame.png"), "iw*0.1:ih*0.1:iw*0.05:ih*0.1")

    def test_initial_sky_black_and_neutral_crops_fail_instead_of_faking_return_loss(self):
        for rgb in [(165, 194, 233), (0, 0, 0), (0, 1, 0), (80, 80, 80)]:
            with self.subTest(rgb=rgb), mock.patch.object(smoke, "mean_color", return_value=rgb):
                with self.assertRaisesRegex(smoke.SmokeFailure, "before crossing"):
                    smoke.check_local_terrain(Path("frame.png"), "before crossing")


class SmokeServerTest(unittest.TestCase):
    def test_offline_server_is_bound_only_to_localhost(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            launcher = root / "launcher.jar"
            launcher.write_bytes(b"test launcher")
            api = root / "fabric-api.jar"
            api.write_bytes(b"test dependency")
            work = root / "server"
            work.mkdir()
            args = SimpleNamespace(cache=root / "cache", no_mod=True, no_portal=True)
            props = {"minecraft_version": "26.3", "loader_version": "0.20.1"}
            with mock.patch.object(smoke, "fetch_json", return_value=[{"version": "test"}]), \
                    mock.patch.object(smoke, "fetch", return_value=launcher), \
                    mock.patch.object(smoke, "fabric_api_jar", return_value=api):
                smoke.setup_server(args, props, work, port=25599)
            properties = dict(line.split("=", 1) for line in (work / "server.properties").read_text().splitlines())
            self.assertEqual(properties["online-mode"], "false")
            self.assertEqual(properties["server-ip"], "127.0.0.1")
            self.assertEqual(properties["server-port"], "25599")
            settings = json.loads(properties["generator-settings"])
            self.assertEqual(sum(layer["height"] for layer in settings["layers"]), 4)
            self.assertEqual(settings["biome"], "minecraft:plains")

    def test_setup_readiness_is_signalled_after_a_tick_delay(self):
        setup = smoke.DATAPACK["data/smoke/function/setup.mcfunction"]
        self.assertTrue(setup.rstrip().endswith("schedule function smoke:ready 20t replace"))
        self.assertEqual(smoke.DATAPACK["data/smoke/function/ready.mcfunction"], "say SMOKE_SETUP_READY\n")

    def test_mod_free_fixture_keeps_the_same_readiness_signal(self):
        with tempfile.TemporaryDirectory() as temporary:
            world = Path(temporary)
            smoke.write_datapack(world, with_portal=False)
            functions = world / "datapacks/smoke/data/smoke/function"
            self.assertIn("schedule function smoke:ready", (functions / "setup.mcfunction").read_text())
            self.assertIn("SMOKE_SETUP_READY", (functions / "ready.mcfunction").read_text())
            self.assertNotIn("immersive_portals", (functions / "setup.mcfunction").read_text())


if __name__ == "__main__":
    unittest.main()
