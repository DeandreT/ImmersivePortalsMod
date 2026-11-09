"""Lightweight acceptance tests; only harmless owned test children, never a game/display."""
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
        self.assertNotIn('-Prealm=true', command)

    def test_realm_is_explicit_property_forwarding_not_a_new_build_or_runtime(self):
        args = argparse.Namespace(display_wrapper=Path('/explicit/wrapper.py'), profile='vanilla',
                                  away_ticks=200, shader_pack=None, offline=True, realm=True)
        command = runner.gradle_command(args, Path('/owned/world'), Path('/owned/report'),
                                        {'path': '/packaged/riftwoven.jar', 'sha256': 'parent',
                                         'bundles': {'dimlib': 'dimlib', 'cloth': 'cloth'}}, 'n' * 48)
        self.assertIn('-Prealm=true', command)
        self.assertIn('-PparentArtifact=/packaged/riftwoven.jar', command)
        self.assertEqual(command.count('runSmokeClient'), 1)
        self.assertIn("'shaderPackExpected', 'realm'", (runner.MODULE / 'build.gradle').read_text())

    def test_realm_selection_keeps_existing_iris_profile_gate(self):
        with patch.object(runner.subprocess, 'Popen') as popen:
            with self.assertRaises(SystemExit):
                runner.main(['--accept-eula', '--realm', '--shader-pack', 'unused', '--profile', 'performance'])
            popen.assert_not_called()

    def test_realm_and_baseline_framebuffer_sets_cannot_be_substituted(self):
        for realm, name in [(True, 'portal-view.png'), (False, 'realm-portal-view.png')]:
            with self.subTest(realm=realm), tempfile.TemporaryDirectory(dir='/tmp/opencode') as temporary:
                process = Mock()
                process.stdout = io.StringIO(f'IP_NATIVE_CLIENT_SCREENSHOT {name}\n')
                with self.assertRaisesRegex(AssertionError, 'Unexpected'):
                    runner.observe_client(process, Path(temporary), Mock(), time.monotonic() + 10, realm=realm)

    def test_realm_requires_original_four_and_supplemental_actual_framebuffers(self):
        process = Mock()
        process.stdout = io.StringIO(''.join(f'IP_NATIVE_CLIENT_SCREENSHOT {name}\n' for name in sorted(runner.REALM_IMAGES)))
        process.wait.return_value = 0
        with tempfile.TemporaryDirectory(dir='/tmp/opencode') as temporary, \
                patch.object(runner, 'check_realm_image', return_value={'nonblank': True}) as check, \
                patch.object(runner, 'check_image') as baseline:
            images = runner.observe_client(process, Path(temporary), Mock(), time.monotonic() + 10, realm=True)
        self.assertEqual(set(images), runner.REALM_IMAGES)
        self.assertEqual(check.call_count, 5)
        baseline.assert_not_called()

    def test_no_original_or_replay_framebuffer_can_be_omitted(self):
        for omitted in runner.REALM_IMAGES:
            process = Mock()
            process.stdout = io.StringIO(''.join(f'IP_NATIVE_CLIENT_SCREENSHOT {name}\n'
                                               for name in sorted(runner.REALM_IMAGES - {omitted})))
            process.wait.return_value = 0
            with self.subTest(omitted=omitted), tempfile.TemporaryDirectory(dir='/tmp/opencode') as temporary, \
                    patch.object(runner, 'check_realm_image', return_value={'nonblank': True}), \
                    self.assertRaisesRegex(AssertionError, 'Not all actual framebuffer'):
                runner.observe_client(process, Path(temporary), Mock(), time.monotonic() + 10, realm=True)

    def test_realm_mixin_audit_failure_is_latched(self):
        process = Mock()
        process.stdout = io.StringIO('Could not force-load unhandled mixin target\nIP_NATIVE_CLIENT_DONE status=RUNTIME_PASS\n')
        process.wait.return_value = 0
        with tempfile.TemporaryDirectory(dir='/tmp/opencode') as temporary:
            with self.assertRaisesRegex(RuntimeError, 'Fail-fast'):
                runner.observe_client(process, Path(temporary), Mock(), time.monotonic() + 10, realm=True)

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
        for log in ['', self.log + '\nMixinApplyError injected', self.log + '\nIP_NATIVE_CLIENT_FAIL injected',
                    self.log + '\nFailed to start FML: loading errors encountered',
                    self.log + '\nMissing or unsupported mandatory dependencies']:
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


class RealmResultTest(unittest.TestCase):
    """Schema/gate fixtures only; these tests never claim real Minecraft runtime coverage."""
    @staticmethod
    def photo_fixture(dimension, camera):
        readiness = {key: True for key in ['camera_matches', 'orientation_matches', 'unclipped_main_view',
            'ground_mesh_ready', 'dimension_matches', 'neighbors_loaded_and_lit', 'ground_supported',
            'foreground_meshes_ready', 'compile_queue_empty', 'ready']}
        draw = {'dimension': dimension, 'draw_sequence': 20, 'ready_consecutive_frames': 5,
                'render_frame_identity': 30, 'capture_frame_identity': 30,
                'camera': {**camera, 'y': camera['y'] + 1.62}, 'yaw': 135, 'pitch': 12,
                'portal_layer': 0, 'clip_plane': [0, 0, 0, 0], 'view_determinant': 1,
                'sodium_geometry': -1, 'compile_queue_empty': True,
                'visible_sections': [{'x': int(camera['x']) // 16, 'y': (int(camera['y']) - 1) // 16,
                                      'z': int(camera['z']) // 16, 'uncompiled': False, 'solid_indices': 60, 'empty_air': False}]}
        return draw, readiness

    @staticmethod
    def add_sodium_fixture(draw):
        floor = tuple(draw['visible_sections'][0][axis] for axis in 'xyz')
        coordinates = [floor] + [(floor[0] + dx, floor[1] + dy, floor[2] + dz)
            for dx in [-1, 0, 1] for dy in [-1, 0, 1] for dz in [-1, 0, 1] if (dx, dy, dz) != (0, 0, 0)]
        sections = [{'x': x, 'y': y, 'z': z, 'built': True, 'drawn_geometry': (x, y, z) == floor,
                     'solid_allocation_length': 100 if (x, y, z) == floor else 0,
                     'box_visible': True, 'tree_present': True, 'in_frustum': True, 'empty_air': False}
                    for x, y, z in coordinates]
        draw.update(sodium_geometry=100, sodium_pending=0, sodium_section_evidence={'verified': True, 'sections': sections})
        return sections

    def setUp(self):
        self.manifest = {'path': '/parent.jar', 'sha256': 'parent', 'bundles': {'dimlib': 'dimlib', 'cloth': 'cloth'}}
        self.dimension = 'riftwoven:realm_' + '1' * 32
        self.result = {
            'status': 'RUNTIME_PASS', 'scenario': 'riftwoven_realm', 'physical_side': 'CLIENT', 'profile': 'vanilla',
            'login_events': 1, 'actual_local_connection': True, 'server_published': False, 'rendered_frames': 40,
            'normal_terrain_rebuild_requested': True, 'away_ticks_completed': 200,
            'config_start': 1, 'config_complete': 1, 'dimension_sync': 2, 'portal_sync': 1,
            'prediction_dimensions': [self.dimension, 'ip_native_client_test:terrain_replay'], 'server_teleport_requests': 2,
            'realm_original_prediction_dimensions': [self.dimension],
            'artifacts': {'parent_path': '/parent.jar', 'parent_sha256': 'parent', 'dimlib_sha256': 'dimlib',
                          'cloth_sha256': 'cloth', 'optional_mods': []},
            'realm': {'dimension': self.dimension, 'formation_valid': True, 'survival_player': True,
                      'offering_refund_reoffering': True, 'saved_idempotent_retry': True, 'world_saved_before_retry': True,
                      'unpaid_worlds': 3, 'realm_count': 1, 'diamonds_before': 8, 'diamonds_after': 4,
                      'realm_seed': 42, 'actual_level_seed': 42, 'overworld_seed': 0, 'radius': 128,
                      'border_size': 256.0, 'suns': 2, 'moons': 1, 'terrain_sample': {'state': 'Block{minecraft:grass_block}'}},
            'realm_remote_sky_extractions': 1, 'realm_sky_extractions': 2, 'realm_two_sun_draws': 2,
            'realm_photo_weather': {'server_rain': 0, 'server_thunder': 0, 'client_rain': 0, 'client_thunder': 0, 'clear_ticks': 60},
            'realm_selected_camera': {'x': 24.5, 'y': 68, 'z': 24.5},
            'landings': [{'stage': stage, 'dimension': dim, 'client_dimension': dim, 'server_x': x, 'server_y': y,
                          'server_z': z, 'client_x': x, 'client_y': y, 'client_z': z,
                          'awaiting_teleport': False, 'registered': True}
                         for stage, dim, x, y, z in [('realm_source_ready', 'minecraft:overworld', 0.5, 101, 3.5),
                                                    ('realm_landing', self.dimension, 0.5, 80, -0.5),
                                                    ('realm_terrain_camera', self.dimension, 24.5, 68, 24.5),
                                                   ('realm_overworld_return', 'minecraft:overworld', 0.5, 101, 3.5)]]}
        for key in ['realm_private_portal_received', 'realm_generated_chunk_received', 'realm_environment_before_entry',
                    'realm_entry_predicted', 'realm_entry_ack_settled', 'realm_camera_same_dimension_reposition',
                    'realm_clock_advanced', 'realm_photo_weather_clear', 'realm_return_ack_settled', 'realm_departure_retained_until_ack',
                    'realm_departure_cleared_after_ack', 'realm_overworld_environment_restored', 'realm_mixin_audit_invoked']:
            self.result[key] = True
        for key, ticks in [('realm_preview_clock', 100), ('realm_inside_clock', 200), ('realm_final_clock', 400)]:
            self.result[key] = {'suns': 2, 'moons': 1, 'server_clock_ticks': ticks, 'client_clock_ticks': ticks + 1,
                                'server_physical_ticks': ticks, 'server_rate': 1.0, 'client_rate': 1.0,
                                 'overworld_clock_ticks': 6000 + ticks}
        main_draw, main_readiness = self.photo_fixture(self.dimension, self.result['realm_selected_camera'])
        self.result.update(realm_terrain_main_draw=main_draw, realm_terrain_readiness=main_readiness,
                           realm_terrain_ready_consecutive_frames=5)
        for side in ['server', 'client']:
            self.result['realm_terrain_' + side] = {'dimension': self.dimension,
                'position': copy.deepcopy(self.result['realm_selected_camera']), 'floor_sturdy': True,
                'floor': 'Block{minecraft:grass_block}[snowy=false]', 'feet': 'Block{minecraft:air}', 'head': 'Block{minecraft:air}'}
        replay_dimension = 'ip_native_client_test:terrain_replay'
        camera = {'x': 24.5, 'y': 68, 'z': 24.5}
        sample = {'dimension': replay_dimension, 'position': camera, 'feet': 'Block{minecraft:air}',
                  'head': 'Block{minecraft:air}', 'floor': 'Block{minecraft:grass_block}[snowy=false]', 'floor_sturdy': True}
        self.result['terrain_replay'] = {
            'status': 'DIAGNOSTIC_CAPTURED', 'dimension': replay_dimension, 'seed': -8484714376220950612,
            'actual_level_seed': -8484714376220950612, 'camera': camera, 'yaw': 135, 'pitch': 12,
            'coverage': 'supplemental terrain-only admin dimension, not paid/private doorway qualification',
            'metadata_sent_before_chunk_requests': True, 'client_metadata_before_predicted_entry': True,
            'ordinary_entry_predicted': True, 'entry_server_requests': 1,
            'arrival_geometry_copied_without_identity': True, 'natural_floor_matches': True,
            'safe_return_ack_settled': True, 'clear_ticks': 60, 'natural_floor': sample['floor'],
            'server': {**copy.deepcopy(sample), 'rain': 0, 'thunder': 0}, 'client': copy.deepcopy(sample),
            'render_readiness': {key: True for key in ['camera_matches', 'orientation_matches', 'unclipped_main_view', 'ground_mesh_ready', 'ready']},
            'main_draw': {'portal_layer': 0, 'clip_plane': [0, 0, 0, 0], 'view_determinant': 1},
            'landings': [{'stage': stage, 'server_dimension': dim, 'client_dimension': dim,
                          'server_position': position, 'client_position': position, 'awaiting_teleport': False, 'registered': True}
                         for stage, dim, position in [('diagnostic_portal_arrival', replay_dimension, {'x': 0.5, 'y': 80, 'z': -0.2}),
                                                     ('diagnostic_arrival', replay_dimension, camera),
                                                     ('diagnostic_safe_return', 'minecraft:overworld', {'x': 0.5, 'y': 101, 'z': 3.5})]]}
        replay_draw, replay_readiness = self.photo_fixture(replay_dimension, camera)
        self.result['terrain_replay'].update(main_draw=replay_draw, render_readiness=replay_readiness, ready_consecutive_frames=5)
        self.log = '\n'.join(['IP_NATIVE_CLIENT_BEGIN', f'IP_NATIVE_REALM_CROSSED {self.dimension} awaiting_teleport=false',
                              'IP_NATIVE_REALM_RETURNED minecraft:overworld awaiting_teleport=false',
                              'IP_NATIVE_CLIENT_DONE status=RUNTIME_PASS'])

    def validate(self, result=None):
        runner.validate_result(self.result if result is None else result, self.manifest, 'vanilla', self.log, realm=True)

    def test_separate_realm_evidence_accepts_only_opt_in_without_spoofed_nether_predictions(self):
        self.validate()
        with self.assertRaisesRegex(AssertionError, 'predicted portal'):
            runner.validate_result(self.result, self.manifest, 'vanilla', self.log)

    def test_baseline_prediction_list_cannot_stand_in_for_actual_realm_entry(self):
        self.result['prediction_dimensions'] = ['minecraft:the_nether', 'minecraft:overworld']
        with self.assertRaisesRegex(AssertionError, 'genuine predicted entry'):
            self.validate()

    def test_every_positive_realm_feature_gate_is_required(self):
        for key, value in self.result.items():
            if key.startswith('realm_') and value is True:
                with self.subTest(key=key):
                    result = copy.deepcopy(self.result)
                    result[key] = False
                    with self.assertRaisesRegex(AssertionError, 'realm-client evidence'):
                        self.validate(result)

    def test_ledger_payment_seed_border_and_saved_retry_cannot_be_faked_by_baseline_result(self):
        for key, value in [('diamonds_after', 8), ('actual_level_seed', 0), ('realm_seed', 0),
                           ('border_size', 60000000), ('saved_idempotent_retry', False), ('realm_count', 2)]:
            with self.subTest(key=key):
                result = copy.deepcopy(self.result)
                result['realm'][key] = value
                with self.assertRaises(AssertionError):
                    self.validate(result)

    def test_clock_and_remote_sky_evidence_must_be_actual_and_dimension_scoped(self):
        for key in ['realm_remote_sky_extractions', 'realm_sky_extractions', 'realm_two_sun_draws']:
            result = copy.deepcopy(self.result)
            result[key] = 0
            with self.subTest(key=key), self.assertRaises(AssertionError):
                self.validate(result)
        for field, value in [('client_clock_ticks', 6000), ('moons', 2), ('client_rate', 0), ('overworld_clock_ticks', 100)]:
            result = copy.deepcopy(self.result)
            result['realm_preview_clock'][field] = value
            with self.subTest(field=field), self.assertRaisesRegex(AssertionError, 'dimension-scoped'):
                self.validate(result)
        for field, value in [('server_rain', 0.1), ('client_rain', 0.1), ('server_thunder', 0.1),
                             ('client_thunder', 0.1), ('clear_ticks', 59)]:
            result = copy.deepcopy(self.result)
            result['realm_photo_weather'][field] = value
            with self.subTest(field=field), self.assertRaisesRegex(AssertionError, 'clear-weather'):
                self.validate(result)

    def test_unsettled_or_mismatched_realm_return_is_not_success(self):
        for key, value in [('awaiting_teleport', True), ('registered', False), ('client_z', 90), ('server_y', -20)]:
            result = copy.deepcopy(self.result)
            result['landings'][-1][key] = value
            with self.subTest(key=key), self.assertRaisesRegex(AssertionError, 'landing|return position'):
                self.validate(result)

    def test_all_original_landings_and_intentional_terrain_snapshot_are_required_in_order(self):
        self.validate()
        for index in range(4):
            result = copy.deepcopy(self.result)
            del result['landings'][index]
            with self.subTest(missing=index), self.assertRaisesRegex(AssertionError, 'landing snapshots'):
                self.validate(result)
        result = copy.deepcopy(self.result)
        result['landings'][2] = copy.deepcopy(result['landings'][1])
        with self.assertRaisesRegex(AssertionError, 'landing'):
            self.validate(result)
        result = copy.deepcopy(self.result)
        result['landings'][1:3] = reversed(result['landings'][1:3])
        with self.assertRaisesRegex(AssertionError, 'landing'):
            self.validate(result)
        result = copy.deepcopy(self.result)
        result['landings'].append(copy.deepcopy(result['landings'][2]))
        with self.assertRaisesRegex(AssertionError, 'landing snapshots'):
            self.validate(result)

    def test_terrain_diagnostic_snapshot_must_be_settled_in_realm_at_selected_site(self):
        for key, value in [('awaiting_teleport', True), ('registered', False),
                           ('dimension', 'minecraft:overworld'), ('client_dimension', 'minecraft:overworld'),
                           ('client_y', 10), ('server_x', 100)]:
            result = copy.deepcopy(self.result)
            result['landings'][2][key] = value
            with self.subTest(key=key), self.assertRaisesRegex(AssertionError, 'landing'):
                self.validate(result)
        result = copy.deepcopy(self.result)
        result['realm_selected_camera']['y'] = 10
        with self.assertRaisesRegex(AssertionError, 'selected natural terrain site'):
            self.validate(result)
        result = copy.deepcopy(self.result)
        del result['realm_selected_camera']
        with self.assertRaisesRegex(AssertionError, 'selected natural terrain site'):
            self.validate(result)

    def test_supplemental_replay_cannot_replace_paid_checks_or_change_frozen_reproduction(self):
        self.validate()
        for key, value in [('seed', 42), ('actual_level_seed', 42), ('camera', {'x': 0, 'y': 80, 'z': 0}),
                           ('yaw', 180), ('pitch', 0), ('dimension', self.dimension), ('status', 'RUNTIME_PASS'),
                           ('natural_floor_matches', False), ('safe_return_ack_settled', False),
                           ('client_metadata_before_predicted_entry', False), ('ordinary_entry_predicted', False),
                           ('entry_server_requests', 0), ('clear_ticks', 59)]:
            result = copy.deepcopy(self.result)
            result['terrain_replay'][key] = value
            with self.subTest(key=key), self.assertRaisesRegex(AssertionError, 'replay|Replay'):
                self.validate(result)
        result = copy.deepcopy(self.result)
        del result['terrain_replay']
        with self.assertRaisesRegex(AssertionError, 'supplemental'):
            self.validate(result)
        for key, value in [('realm_original_prediction_dimensions', ['ip_native_client_test:terrain_replay']),
                           ('prediction_dimensions', [self.dimension])]:
            result = copy.deepcopy(self.result)
            result[key] = value
            with self.subTest(predictions=key), self.assertRaisesRegex(AssertionError, 'genuine predicted entry'):
                self.validate(result)

    def test_replay_requires_real_natural_site_unclipped_mesh_and_separate_settled_return(self):
        for side in ['server', 'client']:
            for key, value in [('floor', 'Block{minecraft:air}'), ('floor_sturdy', False), ('head', 'Block{minecraft:stone}')]:
                result = copy.deepcopy(self.result)
                result['terrain_replay'][side][key] = value
                with self.subTest(side=side, key=key), self.assertRaisesRegex(AssertionError, 'natural camera/ground'):
                    self.validate(result)
        for key in self.result['terrain_replay']['render_readiness']:
            result = copy.deepcopy(self.result)
            result['terrain_replay']['render_readiness'][key] = False
            with self.subTest(readiness=key), self.assertRaisesRegex(AssertionError, 'not ready|readiness'):
                self.validate(result)
        result = copy.deepcopy(self.result)
        result['terrain_replay']['main_draw']['clip_plane'] = [0, 1, 0, 0]
        with self.assertRaisesRegex(AssertionError, 'wrong clip'):
            self.validate(result)
        result = copy.deepcopy(self.result)
        result['terrain_replay']['landings'][-1]['awaiting_teleport'] = True
        with self.assertRaisesRegex(AssertionError, 'replay landing'):
            self.validate(result)

    def test_both_photos_reject_missing_ground_foreground_and_pending_work_despite_claimed_ready(self):
        for replay in [False, True]:
            for change in ['uncompiled_floor', 'missing_floor', 'zero_solid', 'pending_queue', 'uncompiled_foreground', 'stale_dimension', 'stale_frames', 'stale_capture_frame']:
                result = copy.deepcopy(self.result)
                draw = result['terrain_replay']['main_draw'] if replay else result['realm_terrain_main_draw']
                if change == 'uncompiled_floor': draw['visible_sections'][0]['uncompiled'] = True
                elif change == 'missing_floor': draw['visible_sections'] = []
                elif change == 'zero_solid': draw['visible_sections'][0]['solid_indices'] = 0
                elif change == 'pending_queue': draw['compile_queue_empty'] = False
                elif change == 'uncompiled_foreground':
                    draw['visible_sections'].append({**draw['visible_sections'][0], 'x': 0, 'uncompiled': True})
                elif change == 'stale_dimension': draw['dimension'] = 'minecraft:overworld'
                elif change == 'stale_capture_frame': draw['capture_frame_identity'] = 31
                else: draw['ready_consecutive_frames'] = 1
                with self.subTest(replay=replay, change=change), self.assertRaisesRegex(AssertionError, 'Terrain photo'):
                    self.validate(result)

    def test_empty_air_and_compiled_occluded_foreground_need_no_positive_mesh(self):
        draw = self.result['realm_terrain_main_draw']
        draw['visible_sections'].extend([
            {**draw['visible_sections'][0], 'x': 0, 'empty_air': True, 'uncompiled': True, 'solid_indices': 0},
            {**draw['visible_sections'][0], 'z': 0, 'uncompiled': False, 'solid_indices': 0}])
        self.validate()

    def test_sodium_any_geometry_is_not_coordinate_ground_evidence(self):
        draw = self.result['realm_terrain_main_draw']
        draw.update(sodium_geometry=100, sodium_pending=0)
        with self.assertRaisesRegex(AssertionError, 'Sodium camera-floor'):
            self.validate()
        self.add_sodium_fixture(draw)
        self.validate()
        for key, value in [('built', False), ('drawn_geometry', False), ('solid_allocation_length', 0), ('x', 0)]:
            result = copy.deepcopy(self.result)
            result['realm_terrain_main_draw']['sodium_section_evidence']['sections'][0][key] = value
            with self.subTest(key=key), self.assertRaisesRegex(AssertionError, 'Sodium camera-floor'):
                self.validate(result)
        draw['sodium_pending'] = 1
        with self.assertRaisesRegex(AssertionError, 'Sodium camera-floor'):
            self.validate()

    def test_sodium_expanded_neighbor_visibility_is_not_exact_foreground_requirement(self):
        draw = self.result['realm_terrain_main_draw']
        sections = self.add_sodium_fixture(draw)
        section = next(value for value in sections if tuple(value[axis] for axis in 'xyz') == (2, 3, 2))
        section.update(built=False, drawn_geometry=False, solid_allocation_length=0, box_visible=True,
                       tree_present=False, in_frustum=False)
        self.validate()  # Broad query accepts a neighbor, but this exact behind/occluded cell is not required.
        section['tree_present'] = True
        self.validate()  # Outside the actual current camera frustum still is not foreground.
        section.update(tree_present=False, in_frustum=True)
        self.validate()  # A neighboring tree entry must not stand in for this exact absent cell.
        section['tree_present'] = True
        with self.assertRaisesRegex(AssertionError, 'Sodium camera-floor'):
            self.validate()  # Exact, in-frustum, nonair and unbuilt must still block the photograph.
        section['built'] = True
        self.validate()  # Compiled foreground may legitimately have no geometry of its own.
        del section['tree_present']
        with self.assertRaisesRegex(AssertionError, 'Sodium camera-floor'):
            self.validate()  # Unknown visibility evidence fails closed.

    def test_main_and_replay_wait_for_actual_consecutive_frames_without_rebuild(self):
        sources = runner.MODULE / 'src/main/java/qouteall/imm_ptl/client_test'
        main = (sources / 'NativeRealmSmoke.java').read_text()
        replay = (sources / 'TerrainReplaySmoke.java').read_text()
        host = (sources / 'NativeClientSmoke.java').read_text()
        self.assertIn('|| ticks < 100', main)
        self.assertIn('if (!RealmTerrainDiagnostics.photoReady()) return false;', main)
        self.assertIn('if (!RealmTerrainDiagnostics.photoReady()) return false;', replay)
        self.assertIn('realm_terrain_initial_main_draw', main)
        self.assertIn('TimeUnit.SECONDS.toNanos(limit)', main)
        self.assertIn('TimeUnit.SECONDS.toNanos(180)', replay)
        self.assertIn('captureName.equals("realm-terrain-replay.png")', host)
        self.assertIn('RealmTerrainDiagnostics.freezePhoto()', host)
        self.assertIn('RenderFrameEvent.Pre', host)
        self.assertIn('RealmTerrainDiagnostics.photoReadyThisFrame()', host)
        self.assertIn('report.put("latest_main_draw", draw)', replay)

    def test_ready_then_post_without_a_new_main_draw_rejects_actual_frame_guard(self):
        java_home = Path(os.environ.get('JAVA_HOME', Path.home() / '.cache/immersive-portals/toolchains/jdk-25'))
        self.assertTrue((java_home / 'bin/javac').is_file(), 'The existing Java 25 toolchain is required; nothing is installed')
        sources = runner.MODULE / 'src'
        relative = Path('qouteall/imm_ptl/client_test')
        with tempfile.TemporaryDirectory(dir='/tmp/opencode') as temporary:
            subprocess.run([str(java_home / 'bin/javac'), '-d', temporary,
                            str(sources / 'main/java' / relative / 'PhotoFrameGuard.java'),
                            str(sources / 'test/java' / relative / 'PhotoFrameGuardTest.java')], check=True, capture_output=True)
            subprocess.run([str(java_home / 'bin/java'), '-cp', temporary,
                            'qouteall.imm_ptl.client_test.PhotoFrameGuardTest'], check=True, capture_output=True)


class BaselineDiagnosticsTest(unittest.TestCase):
    def test_baseline_photo_timing_oracles_and_realm_guards_are_unchanged(self):
        sources = runner.MODULE / 'src/main/java/qouteall/imm_ptl/client_test'
        host = (sources / 'NativeClientSmoke.java').read_text()
        self.assertIn('settled(client, Level.OVERWORLD, -20) && stageTicks > 200 && frames > 30', host)
        self.assertIn('if (!RealmTerrainDiagnostics.photoReadyThisFrame()) { return; }', host)
        self.assertIn('RealmTerrainDiagnostics.freezePhoto();', host)
        self.assertLess(host.index('BaselineMarkerDiagnostics.freezeAndWrite();'),
                        host.index('Screenshot.takeScreenshot('))
        diagnostic = (sources / 'BaselineMarkerDiagnostics.java').read_text()
        self.assertIn('ChunkSectionLayer.SOLID', diagnostic)
        self.assertIn('ChunkSectionLayer.TRANSLUCENT', diagnostic)
        self.assertIn('section.getSectionNode() == SectionPos.asLong(pos)', diagnostic)
        self.assertIn('FRAME.matches(draw.longValue())', diagnostic)
        self.assertNotIn('allChanged(', diagnostic)
        self.assertNotIn('compileSync(', diagnostic)
        self.assertNotIn('compileAsync(', diagnostic)
        self.assertNotIn('.options.', diagnostic)

    def test_actual_command_outcome_rejects_absent_and_failed_callbacks_but_accepts_zero(self):
        java_home = Path(os.environ.get('JAVA_HOME', Path.home() / '.cache/immersive-portals/toolchains/jdk-25'))
        sources = runner.MODULE / 'src'
        relative = Path('qouteall/imm_ptl/client_test')
        with tempfile.TemporaryDirectory(dir='/tmp/opencode') as temporary:
            subprocess.run([str(java_home / 'bin/javac'), '-d', temporary,
                            str(sources / 'main/java' / relative / 'BaselineCommandOutcome.java'),
                            str(sources / 'test/java' / relative / 'BaselineCommandOutcomeTest.java')],
                           check=True, capture_output=True)
            subprocess.run([str(java_home / 'bin/java'), '-cp', temporary,
                            'qouteall.imm_ptl.client_test.BaselineCommandOutcomeTest'],
                           check=True, capture_output=True)


class RealmPreviewProbeTest(unittest.TestCase):
    def test_dimension_sky_probes_preserve_first_snapshot_and_never_increment_qualification(self):
        java_home = Path(os.environ.get('JAVA_HOME', Path.home() / '.cache/immersive-portals/toolchains/jdk-25'))
        sources = runner.MODULE / 'src'
        relative = Path('qouteall/imm_ptl/client_test')
        with tempfile.TemporaryDirectory(dir='/tmp/opencode') as temporary:
            subprocess.run([str(java_home / 'bin/javac'), '-d', temporary,
                            str(sources / 'main/java' / relative / 'Evidence.java'),
                            str(sources / 'main/java' / relative / 'SkyExtractionEvidence.java'),
                            str(sources / 'test/java' / relative / 'SkyExtractionEvidenceTest.java')], check=True, capture_output=True)
            subprocess.run([str(java_home / 'bin/java'), '-cp', temporary,
                            'qouteall.imm_ptl.client_test.SkyExtractionEvidenceTest'], check=True, capture_output=True)

    def test_preview_retains_failed_predicates_without_changing_original_gates(self):
        sources = runner.MODULE / 'src/main/java/qouteall/imm_ptl/client_test'
        main = (sources / 'NativeRealmSmoke.java').read_text()
        observer = (sources / 'mixin/RealmSkyObserver.java').read_text()
        for key in ['realm_preview_first', 'realm_preview_latest', 'realm_preview_failure', 'false_conditions',
                    'current_remote_terrain_state', 'expected_terrain_state', 'latest_sampled_server',
                    'received_private_portals', 'actual_remote_biome_seed', 'cached_biome_seed',
                    'clock_time_difference', 'sampled_server_environment', 'sky_observer_actual_realm', 'shader_pack_active']:
            self.assertIn('"' + key + '"', main)
        self.assertIn('if (!privateViewReady(client) || !environmentReady(client, false)) return false;', main)
        self.assertIn('remote.getBlockState(fixture.terrainSample()).toString().equals(fixture.terrainState())', main)
        self.assertIn('if (layout.suns() == 2 && layout.moons() == 1) Evidence.REALM_SKY_EXTRACTIONS.incrementAndGet();', observer)
        self.assertIn('SkyExtractionEvidence.head', observer)
        self.assertIn('SkyExtractionEvidence.returned', observer)


class PreAuditDiagnosticsTest(unittest.TestCase):
    def test_checkpoint_precedes_existing_realm_audit_and_does_not_add_a_baseline_audit(self):
        sources = runner.MODULE / 'src/main/java/qouteall/imm_ptl/client_test'
        realm = (sources / 'NativeRealmSmoke.java').read_text()
        host = (sources / 'NativeClientSmoke.java').read_text()
        self.assertIn('host.checkpointBeforeMixinAudit();\n                MixinEnvironment.getCurrentEnvironment().audit();', realm)
        self.assertEqual(realm.count('MixinEnvironment.getCurrentEnvironment().audit();'), 1)
        self.assertNotIn('.audit();', host)
        self.assertIn('checkpointBeforeMixinAudit();\n                    finish(client, null);', host)
        for key in ['PARTIAL_DIAGNOSTIC', 'BEFORE_MIXIN_AUDIT', 'realm-pre-audit-diagnostics.json',
                    'baseline-pre-audit-diagnostics.json', 'landings', 'prediction_dimensions',
                    'server_teleport_requests', 'mixin_audit_evidence']:
            self.assertIn('"' + key + '"', host)
        self.assertIn('new LinkedHashMap<String, Object>(report)', host)
        evidence = (sources / 'MixinAuditEvidence.java').read_text()
        self.assertIn('BlockGetter.class.getDeclaredMethods()', evidence)
        self.assertIn('method.getAnnotation(MixinMerged.class)', evidence)
        self.assertIn('descriptorString()', evidence)
        self.assertIn('Mixins.getMixinsForClass(TARGET)', evidence)
        self.assertIn('getDeclaredMethod("getUnhandledTargets")', evidence)
        self.assertNotIn('getDeclaredField(', evidence)

    def test_trace_is_optional_client_only_and_retained_with_bounded_rotation(self):
        gradle = (runner.MODULE / 'build.gradle').read_text()
        self.assertIn("environmentVariable('IP_NATIVE_MIXIN_CLASS_TRACE').getOrElse('false') == 'true'", gradle)
        self.assertIn('jvmArgument("-Xlog:class+load=info,class+init=info:', gradle)
        self.assertIn('${file(report)}/class-load-init.log:', gradle)
        self.assertIn('filecount=4,filesize=8M', gradle)
        self.assertNotIn('IP_NATIVE_MIXIN_CLASS_TRACE', (runner.MODULE / 'test_client.py').read_text())

    def test_atomic_writer_publishes_complete_replacements_and_cleans_failed_attempts(self):
        java_home = Path(os.environ.get('JAVA_HOME', Path.home() / '.cache/immersive-portals/toolchains/jdk-25'))
        sources = runner.MODULE / 'src'
        relative = Path('qouteall/imm_ptl/client_test')
        with tempfile.TemporaryDirectory(dir='/tmp/opencode') as temporary:
            subprocess.run([str(java_home / 'bin/javac'), '-d', temporary,
                            str(sources / 'main/java' / relative / 'AtomicDiagnosticWriter.java'),
                            str(sources / 'test/java' / relative / 'AtomicDiagnosticWriterTest.java')],
                           check=True, capture_output=True)
            receipt = Path(temporary) / 'receipts'
            receipt.mkdir()
            subprocess.run([str(java_home / 'bin/java'), '-cp', temporary,
                            'qouteall.imm_ptl.client_test.AtomicDiagnosticWriterTest', str(receipt)],
                           check=True, capture_output=True)


class StableTerrainSamplingTest(unittest.TestCase):
    def test_scheduled_leaf_state_changes_cannot_invalidate_natural_geological_sample(self):
        java_home = Path(os.environ.get('JAVA_HOME', Path.home() / '.cache/immersive-portals/toolchains/jdk-25'))
        sources = runner.MODULE / 'src'
        relative = Path('qouteall/imm_ptl/client_test')
        with tempfile.TemporaryDirectory(dir='/tmp/opencode') as temporary:
            subprocess.run([str(java_home / 'bin/javac'), '-d', temporary,
                            str(sources / 'main/java' / relative / 'StableTerrainSamplePolicy.java'),
                            str(sources / 'test/java' / relative / 'StableTerrainSamplePolicyTest.java')], check=True, capture_output=True)
            subprocess.run([str(java_home / 'bin/java'), '-cp', temporary,
                            'qouteall.imm_ptl.client_test.StableTerrainSamplePolicyTest'], check=True, capture_output=True)

    def test_selection_is_read_only_bounded_same_column_and_full_state_equality_remains(self):
        sources = runner.MODULE / 'src/main/java/qouteall/imm_ptl/client_test'
        scene = (sources / 'RealmScene.java').read_text()
        selection = (sources / 'StableTerrainSample.java').read_text()
        main = (sources / 'NativeRealmSmoke.java').read_text()
        self.assertIn('StableTerrainSample.choose(realm, 24, 24)', scene)
        for requirement in ['level.getChunk(x >> 4, z >> 4)', 'StableTerrainSamplePolicy.MAX_SCAN_DEPTH',
                            'state.isRandomlyTicking()', 'state.isCollisionShapeFullBlock(level, position)',
                            'state.isSolidRender()', 'state.hasBlockEntity()', '!state.getFluidState().isEmpty()']:
            self.assertIn(requirement, selection)
        self.assertNotIn('setBlock', selection)
        self.assertIn('remote.getBlockState(fixture.terrainSample()).toString().equals(fixture.terrainState())', main)
        self.assertIn('client.level.getBlockState(fixture.terrainSample()).toString().equals(fixture.terrainState())', main)


if __name__ == '__main__':
    unittest.main()
