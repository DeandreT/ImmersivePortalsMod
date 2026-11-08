"""Lightweight acceptance tests; only harmless owned Python children, never a game/display."""
import argparse
import copy
import io
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

import test_client as runner


class DispatchTest(unittest.TestCase):
    def test_real_pid_handle_stops_only_the_owned_child(self):
        with tempfile.TemporaryDirectory(dir='/tmp/opencode') as temporary:
            directory = Path(temporary)
            java = directory / 'toolchain'
            (java / 'bin').mkdir(parents=True)
            (java / 'bin/java').symlink_to(sys.executable)
            children = []
            try:
                for _ in range(2):
                    children.append(subprocess.Popen([sys.executable, '-c', 'import signal; signal.pause()'], cwd=directory))
                owned, other = children
                identity = runner.process_identity(owned.pid, directory, java)
                self.assertIsNotNone(identity)
                runner.signal_owned_java(owned.pid, identity, directory, java, signal.SIGTERM)
                self.assertEqual(owned.wait(timeout=5), -signal.SIGTERM)
                self.assertIsNone(other.poll())
            finally:
                for child in children:
                    if child.poll() is None:
                        child.kill()
                    child.wait(timeout=5)

    def test_real_child_is_reaped_when_cancellation_arrives_during_launch(self):
        popen = subprocess.Popen
        children = []
        def launch(*args, **kwargs):
            child = popen(*args, **kwargs)
            children.append(child)
            signal.raise_signal(signal.SIGTERM)
            return child
        def cleanup(child):
            child.terminate()
            child.wait(timeout=5)
        try:
            with patch.object(runner.subprocess, 'Popen', side_effect=launch):
                with self.assertRaises(InterruptedError):
                    with runner.cancellation_handlers():
                        with runner.launch_process([sys.executable, '-c', 'import signal; signal.pause()'], cleanup):
                            self.fail('Cancelled child must not be handed to its caller')
            self.assertEqual(len(children), 1)
            self.assertEqual(children[0].returncode, -signal.SIGTERM)
        finally:
            for child in children:
                if child.poll() is None:
                    child.kill()
                child.wait(timeout=5)

    def test_java_25_ga_updates_and_ea_are_accepted_but_other_majors_are_not(self):
        with tempfile.TemporaryDirectory(dir='/tmp/opencode') as temporary:
            java = Path(temporary)
            (java / 'bin').mkdir()
            (java / 'bin/java').touch()
            for version in ['25', '25.0.1', '25-ea+36']:
                with self.subTest(version=version):
                    (java / 'release').write_text(f'JAVA_VERSION="{version}"\nOS_NAME="Linux"\n')
                    runner.validate_java(java)
            for version in ['21.0.9', '250', '125.0.1', 'invalid']:
                with self.subTest(version=version):
                    (java / 'release').write_text(f'JAVA_VERSION="{version}"\n')
                    with self.assertRaisesRegex(ValueError, 'Java 25'):
                        runner.validate_java(java)

    def test_pid_handle_is_opened_before_ownership_recheck_and_always_closed(self):
        order = []
        with patch.object(runner.os, 'pidfd_open', side_effect=lambda pid: order.append('open') or 42), \
                patch.object(runner, 'process_identity', side_effect=lambda *args, **kw: order.append('check') or 'start'), \
                patch.object(runner.signal, 'pidfd_send_signal', side_effect=lambda fd, sig: order.append(('signal', fd, sig))), \
                patch.object(runner.os, 'close', side_effect=lambda fd: order.append(('close', fd))), \
                patch.object(runner.os, 'kill') as kill:
            runner.signal_owned_java(123, 'start', Path('/owned'), Path('/java'), signal.SIGTERM)
        self.assertEqual(order, ['open', 'check', ('signal', 42, signal.SIGTERM), ('close', 42)])
        kill.assert_not_called()

    def test_recycled_or_exited_pid_cannot_be_signalled(self):
        with patch.object(runner.os, 'pidfd_open', return_value=42), \
                patch.object(runner, 'process_identity', return_value=None), \
                patch.object(runner.signal, 'pidfd_send_signal') as send, patch.object(runner.os, 'close') as close:
            runner.signal_owned_java(123, 'old-start', Path('/owned'), Path('/java'), signal.SIGKILL)
            send.assert_not_called()
            close.assert_called_once_with(42)
        with patch.object(runner.os, 'pidfd_open', side_effect=ProcessLookupError), \
                patch.object(runner.signal, 'pidfd_send_signal') as send:
            runner.signal_owned_java(123, 'start', Path('/owned'), Path('/java'), signal.SIGTERM)
            send.assert_not_called()

    def test_nonce_from_exited_jvm_does_not_authorize_recycled_pid(self):
        for identity_after in [None, 'replacement']:
            with self.subTest(identity_after=identity_after), \
                    patch.object(Path, 'iterdir', return_value=[SimpleNamespace(name='123')]), \
                    patch.object(runner, 'process_identity', side_effect=['original', None, identity_after]) as identity, \
                    patch.object(runner, 'has_owned_environment', return_value=True):
                self.assertEqual(runner.owned_java(Path('/nonexistent-owned-world'), Path('/java')), {})
                self.assertEqual(identity.call_args.args[3], 'original')

    def test_launch_cancellation_is_deferred_until_cleanup_is_armed(self):
        process = Mock(pid=123)
        cleanup = Mock()
        def launch(*_args, **_kwargs):
            signal.raise_signal(signal.SIGTERM)
            return process
        with patch.object(runner.subprocess, 'Popen', side_effect=launch):
            with self.assertRaisesRegex(InterruptedError, 'signal'):
                with runner.cancellation_handlers():
                    with runner.launch_process([], cleanup):
                        self.fail('Cancellation should be delivered before use of the child')
        cleanup.assert_called_once_with(process)

    def test_outer_sigterm_stops_wrapper_and_restores_signal_handlers(self):
        previous = {sig: signal.getsignal(sig) for sig in [signal.SIGTERM, signal.SIGINT]}
        process = Mock(pid=123)
        process.poll.return_value = None
        def interrupted_wait(**_kwargs):
            signal.getsignal(signal.SIGTERM)(signal.SIGTERM, None)
        # The first wait receives SIGTERM; cleanup's wait then returns normally.
        waits = 0
        def wait(**kwargs):
            nonlocal waits
            waits += 1
            if waits == 1:
                interrupted_wait(**kwargs)
            return 0
        process.wait.side_effect = wait
        with patch.dict(os.environ, {}, clear=True), patch.object(runner, 'display_module'), \
                patch.object(runner.subprocess, 'Popen', return_value=process), patch.object(runner.os, 'killpg') as killpg:
            with self.assertRaisesRegex(InterruptedError, 'signal'):
                runner.main(['--accept-eula', '--display-wrapper', '/explicit/wrapper.py'])
            killpg.assert_called_once_with(123, signal.SIGTERM)
            self.assertEqual(waits, 2)
        for sig, handler in previous.items():
            self.assertEqual(signal.getsignal(sig), handler)

    def test_first_cancellation_during_cleanup_does_not_bypass_escalation(self):
        process = Mock(pid=123)
        process.poll.return_value = None
        waits = 0
        def wait(**_kwargs):
            nonlocal waits
            waits += 1
            if waits == 1:
                signal.raise_signal(signal.SIGTERM)
                raise runner.subprocess.TimeoutExpired('owned-wrapper', 25)
            return 0
        process.wait.side_effect = wait
        with patch.object(runner.subprocess, 'Popen', return_value=process), patch.object(runner.os, 'killpg') as killpg:
            with self.assertRaisesRegex(InterruptedError, 'signal'):
                with runner.cancellation_handlers():
                    with runner.launch_process([], runner.stop_wrapper):
                        pass
            self.assertEqual(killpg.call_args_list, [unittest.mock.call(123, signal.SIGTERM),
                                                   unittest.mock.call(123, signal.SIGKILL)])
            self.assertEqual(waits, 2)

    def test_missing_pid_handle_support_refuses_launch(self):
        with patch.object(runner, 'display_module'), patch.object(runner.os, 'pidfd_open', None), \
                patch.object(runner.subprocess, 'Popen') as popen:
            with self.assertRaisesRegex(RuntimeError, 'PID-handle'):
                runner.main(['--accept-eula', '--display-wrapper', '/explicit/wrapper.py'])
            popen.assert_not_called()

    def test_no_eula_cannot_launch_or_load_a_wrapper(self):
        with patch.object(runner, 'display_module') as wrapper, patch.object(runner.subprocess, 'Popen') as popen:
            with self.assertRaises(SystemExit):
                runner.main([])
            wrapper.assert_not_called()
            popen.assert_not_called()

    def test_missing_wrapper_fails_closed(self):
        with patch.dict(os.environ, {}, clear=True), patch.object(runner.subprocess, 'Popen') as popen:
            with self.assertRaisesRegex(ValueError, 'maintained wrapper'):
                runner.main(['--accept-eula'])
            popen.assert_not_called()

    def test_shader_profile_requires_iris(self):
        with patch.object(runner.subprocess, 'Popen') as popen:
            with self.assertRaises(SystemExit):
                runner.main(['--accept-eula', '--shader-pack', 'unused'])
            popen.assert_not_called()

    def test_actual_dispatch_rechecks_display_before_any_process(self):
        wrapper = Mock()
        wrapper.verify_owned_display.side_effect = RuntimeError('stale Xvfb')
        with patch.object(runner.subprocess, 'Popen') as popen:
            with self.assertRaisesRegex(RuntimeError, 'stale Xvfb'):
                runner.supervise([], {}, None, None, wrapper, 'nonce', time.monotonic() + 10)
            popen.assert_not_called()

    def test_expired_deadline_never_dispatches_a_process(self):
        with patch.object(runner.subprocess, 'Popen') as popen:
            with self.assertRaisesRegex(TimeoutError, 'before dispatch'):
                runner.supervise([], {}, None, None, Mock(), 'nonce', time.monotonic() - 1)
            popen.assert_not_called()

    def test_owned_world_nonce_mode_eula_audio_required(self):
        with tempfile.TemporaryDirectory(prefix='ip-native-client-world-', dir='/tmp/opencode') as temporary:
            directory = Path(temporary)
            nonce = 'n' * 48
            (directory / runner.MARKER).write_text(nonce)
            (directory / 'eula.txt').write_text('eula=true')
            wrapper = Mock()
            with patch.dict(os.environ, {'ALSOFT_DRIVERS': 'null', 'SDL_VIDEODRIVER': 'x11'}):
                runner.verify_dispatch(wrapper, directory, nonce)
                wrapper.verify_owned_display.assert_called_once()
                with self.assertRaises(ValueError):
                    runner.verify_dispatch(wrapper, directory, 'other' * 10)
                directory.chmod(0o755)
                with self.assertRaises(ValueError):
                    runner.verify_dispatch(wrapper, directory, nonce)
                directory.chmod(0o700)
                with patch.dict(os.environ, {'ALSOFT_DRIVERS': 'pulse'}):
                    with self.assertRaisesRegex(ValueError, 'null audio'):
                        runner.verify_dispatch(wrapper, directory, nonce)

    def test_private_home_audio_and_display_environment(self):
        with tempfile.TemporaryDirectory(dir='/tmp/opencode') as temporary:
            with patch.dict(os.environ, {'WAYLAND_DISPLAY': 'desktop', 'WAYLAND_SOCKET': 'desktop',
                                         'XDG_SESSION_TYPE': 'wayland', 'XDG_RUNTIME_DIR': '/private/display', 'DISPLAY': ':12345'}):
                environment = runner.child_environment(Path(temporary))
            self.assertEqual(environment['DISPLAY'], ':12345')
            self.assertEqual(environment['XDG_RUNTIME_DIR'], '/private/display')
            self.assertEqual(environment['ALSOFT_DRIVERS'], 'null')
            self.assertEqual(environment['SDL_VIDEODRIVER'], 'x11')
            self.assertTrue(environment['HOME'].startswith(temporary))
            for name in ['WAYLAND_DISPLAY', 'WAYLAND_SOCKET', 'XDG_SESSION_TYPE']:
                self.assertNotIn(name, environment)

    def test_command_loads_only_standalone_client_with_exact_packaged_artifact(self):
        args = argparse.Namespace(display_wrapper=Path('/explicit/wrapper.py'), profile='performance-iris',
                                  away_ticks=2600, shader_pack=Path('/explicit/shader'), offline=True)
        command = runner.gradle_command(args, Path('/owned/world'), Path('/owned/report'),
                                        {'path': '/packaged/parent.jar', 'sha256': 'parent', 'bundles': {'dimlib': 'dimlib', 'cloth': 'cloth'}}, 'n' * 48)
        self.assertIn('runSmokeClient', command)
        self.assertIn('--max-workers=2', command)
        self.assertIn('--offline', command)
        self.assertIn('-PparentArtifact=/packaged/parent.jar', command)
        self.assertIn('-PclientProfile=performance-iris', command)
        self.assertNotIn('recoveryTest', command)
        self.assertNotIn('buildAll', command)

    def test_failed_cleanup_preserves_owned_world_and_lease(self):
        directory = None
        with patch.object(runner, 'owned_java', return_value={123: 'start'}):
            with self.assertRaisesRegex(RuntimeError, 'preserving'):
                with runner.fresh_world(Path('/java')) as directory:
                    (directory / runner.MARKER).write_text('owned')
        self.assertTrue((directory / runner.MARKER).exists())
        # This is the unit test's own directory; no process was started.
        import shutil
        shutil.rmtree(directory)

    def test_fail_fast_output_cannot_be_reported_as_a_pass(self):
        process = Mock()
        process.stdout = io.StringIO('IP_NATIVE_CLIENT_FAIL injected\nCaused by: actual nested mixin cause\n')
        with tempfile.TemporaryDirectory(dir='/tmp/opencode') as temporary:
            with patch.object(runner.subprocess, 'Popen', return_value=process), patch.object(runner, 'terminate') as terminate:
                with self.assertRaisesRegex(RuntimeError, 'Fail-fast'):
                    runner.supervise([], {'JAVA_HOME': '/java'}, Path('/owned'), Path(temporary), Mock(), 'nonce', time.monotonic() + 10)
                terminate.assert_called_once()
                self.assertIn('Caused by: actual nested mixin cause', (Path(temporary) / 'client.log').read_text())

    def test_exit_zero_without_real_framebuffers_cannot_pass(self):
        process = Mock()
        process.stdout = io.StringIO('IP_NATIVE_CLIENT_DONE status=RUNTIME_PASS\n')
        process.wait.return_value = 0
        with tempfile.TemporaryDirectory(dir='/tmp/opencode') as temporary:
            with patch.object(runner.subprocess, 'Popen', return_value=process), patch.object(runner, 'terminate'):
                with self.assertRaisesRegex(AssertionError, 'framebuffer'):
                    runner.supervise([], {'JAVA_HOME': '/java'}, Path('/owned'), Path(temporary), Mock(), 'nonce', time.monotonic() + 10)

    def test_shader_copy_accepts_only_an_explicit_pack_not_a_profile(self):
        with tempfile.TemporaryDirectory(dir='/tmp/opencode') as temporary:
            base = Path(temporary)
            source = base / 'pack'
            source.mkdir()
            game = base / 'game'
            (game / 'config').mkdir(parents=True)
            with self.assertRaisesRegex(ValueError, 'contain shaders'):
                runner.copy_shader_pack(source, game)
            (source / 'shaders').mkdir()
            (source / 'shaders/final.fsh').write_text('unit-only shader fixture')
            runner.copy_shader_pack(source, game)
            self.assertEqual((game / 'shaderpacks/pack/shaders/final.fsh').read_text(), 'unit-only shader fixture')
            with self.assertRaisesRegex(ValueError, 'never a user/repository profile'):
                runner.copy_shader_pack(runner.ROOT, game)


class ResultTest(unittest.TestCase):
    def setUp(self):
        self.manifest = {'path': '/parent.jar', 'sha256': 'parent', 'bundles': {'dimlib': 'dimlib', 'cloth': 'cloth'}}
        self.result = {
            'status': 'RUNTIME_PASS', 'physical_side': 'CLIENT', 'profile': 'vanilla', 'login_events': 1,
            'actual_local_connection': True, 'rendered_frames': 40, 'normal_terrain_rebuild_requested': True,
            'server_published': False,
            'away_ticks_completed': 2600, 'config_start': 1, 'config_complete': 1, 'dimension_sync': 1, 'portal_sync': 2,
            'prediction_dimensions': ['minecraft:the_nether', 'minecraft:overworld'], 'server_teleport_requests': 2,
            'artifacts': {'parent_path': '/parent.jar', 'parent_sha256': 'parent', 'dimlib_sha256': 'dimlib', 'cloth_sha256': 'cloth', 'optional_mods': []},
            'landings': [{'stage': stage, 'dimension': dim, 'client_dimension': dim, 'server_x': 0.5, 'server_z': 0.5,
                          'server_y': y, 'client_y': y, 'awaiting_teleport': False, 'registered': True}
                         for stage, dim, y in [('source_ready', 'minecraft:overworld', -20),
                                               ('nether_landing', 'minecraft:the_nether', 100),
                                               ('overworld_return', 'minecraft:overworld', -20)]]}
        self.log = '\n'.join(['IP_NATIVE_CLIENT_BEGIN', 'IP_NATIVE_CLIENT_CROSSED minecraft:the_nether correct_landing=true',
                              'IP_NATIVE_CLIENT_RETURNED minecraft:overworld correct_landing=true', 'IP_NATIVE_CLIENT_DONE status=RUNTIME_PASS'])

    def test_complete_actual_local_client_evidence(self):
        runner.validate_result(self.result, self.manifest, 'vanilla', self.log)

    def test_missing_real_protocol_observation_fails(self):
        for key in ['config_start', 'config_complete', 'dimension_sync', 'portal_sync']:
            with self.subTest(key=key):
                result = copy.deepcopy(self.result)
                result[key] = 0
                with self.assertRaisesRegex(AssertionError, 'protocol observation'):
                    runner.validate_result(result, self.manifest, 'vanilla', self.log)

    def test_server_command_dimension_changes_are_not_prediction_proof(self):
        result = copy.deepcopy(self.result)
        result['prediction_dimensions'] = []
        with self.assertRaisesRegex(AssertionError, 'predicted portal'):
            runner.validate_result(result, self.manifest, 'vanilla', self.log)

    def test_wrong_or_unsettled_landing_fails(self):
        for key, value in [('awaiting_teleport', True), ('registered', False), ('server_y', 102),
                           ('client_dimension', 'minecraft:overworld')]:
            with self.subTest(key=key):
                result = copy.deepcopy(self.result)
                result['landings'][1][key] = value
                with self.assertRaisesRegex(AssertionError, 'landing'):
                    runner.validate_result(result, self.manifest, 'vanilla', self.log)

    def test_wrong_loaded_parent_or_profile_fails(self):
        result = copy.deepcopy(self.result)
        result['artifacts']['parent_sha256'] = 'dev-substitution'
        with self.assertRaisesRegex(AssertionError, 'packaged-artifact'):
            runner.validate_result(result, self.manifest, 'vanilla', self.log)
        result = copy.deepcopy(self.result)
        result['artifacts']['optional_mods'] = [{'id': 'sodium'}]
        with self.assertRaisesRegex(AssertionError, 'optional native mod'):
            runner.validate_result(result, self.manifest, 'vanilla', self.log)

    def test_positive_markers_and_no_fatal_output_required(self):
        for log in ['', self.log + '\nMixinApplyError injected', self.log + '\nIP_NATIVE_CLIENT_FAIL injected']:
            with self.assertRaises(AssertionError):
                runner.validate_result(self.result, self.manifest, 'vanilla', log)

    def test_black_or_neutral_crops_do_not_pass(self):
        for color in ['red', 'green']:
            self.assertFalse(runner.marker_color((0, 0, 0), color))
            self.assertFalse(runner.marker_color((80, 80, 80), color))
        self.assertTrue(runner.marker_color((140, 35, 30), 'red'))
        self.assertTrue(runner.marker_color((40, 140, 35), 'green'))

    def test_observers_never_replace_protocol_or_controller_behavior(self):
        source = runner.MODULE / 'src/main/java/qouteall/imm_ptl/client_test'
        for path in source.rglob('*.java'):
            text = path.read_text()
            for forbidden in ['configureMockConnection', 'EmbeddedChannel', 'ServerboundAcceptTeleportationPacket',
                              'CallbackInfoReturnable', '@Overwrite', 'cancellable = true']:
                self.assertNotIn(forbidden, text, str(path))
        metadata = json.loads((runner.MODULE / 'src/main/resources/ip_native_client_test.mixins.json').read_text())
        self.assertIn('PredictionObserver', metadata['client'])
        self.assertIn('DimensionInfoObserver', metadata['client'])
        self.assertNotIn('client-test', (runner.ROOT / 'settings.gradle').read_text())


if __name__ == '__main__':
    unittest.main()
