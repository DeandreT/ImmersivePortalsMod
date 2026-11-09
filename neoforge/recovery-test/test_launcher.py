"""Launcher acceptance checks only. These never start Gradle, Minecraft or a network client."""

import copy
import errno
import io
import json
import os
from pathlib import Path
import signal
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, call, patch
import warnings
import zipfile

import test_server as launcher


class ResultValidationTest(unittest.TestCase):
    def setUp(self):
        self.manifest = {'sha256': 'parent', 'bundles': {
            'dimlib': 'dimlib', 'cloth': 'cloth', 'iris_bootstrap': 'bootstrap'}}
        self.result = {
            'cases': [{'name': name, 'status': 'PASS', 'assertions': 1}
                      for name in sorted(launcher.expected_case_names())],
            'expected_cases': len(launcher.expected_case_names()),
            'status': 'PASS_WITH_LIMITATIONS', 'failed_cases': 0, 'skipped_required_cases': 0,
            'limitations': ['Server fixtures are not actual clients'],
            'loaded_parent_sha256': 'parent', 'loaded_dimlib_sha256': 'dimlib',
            'loaded_cloth_config_sha256': 'cloth',
            'native_player_partial_admission': {
                'first_player_registered_before_second_refusal': True,
                'destination_join_events_at_refusal': 4,
                'destination_join_events_after_retry': 8,
            },
        }
        player_case = next(case for case in self.result['cases'] if case['name'] == 'concrete_two_player_packets')
        player_case['native_event_evidence'] = {'destination_join_events': 8}
        self.log = 'IP_NATIVE_RECOVERY_DONE status=PASS_WITH_LIMITATIONS'

    def test_complete_server_fixture_coverage_keeps_limitations(self):
        self.assertEqual(launcher.validate_runtime_result(self.result, self.manifest, self.log), 31)

    def test_missing_duplicate_failed_skipped_or_zero_assertion_cases_fail(self):
        for mutation in ['missing', 'duplicate', 'failed', 'skipped', 'zero', 'no_limitations']:
            with self.subTest(mutation=mutation):
                result = copy.deepcopy(self.result)
                if mutation == 'missing':
                    result['cases'].pop()
                elif mutation == 'duplicate':
                    result['cases'][1] = result['cases'][0]
                elif mutation == 'failed':
                    result['cases'][0]['status'] = 'FAIL'
                elif mutation == 'skipped':
                    result['skipped_required_cases'] = 1
                elif mutation == 'zero':
                    result['cases'][0]['assertions'] = 0
                else:
                    result['limitations'] = []
                with self.assertRaises(AssertionError):
                    launcher.validate_runtime_result(result, self.manifest, self.log)

    def test_wrong_loaded_artifact_bundle_or_failure_marker_fails(self):
        for key in ['loaded_parent_sha256', 'loaded_dimlib_sha256', 'loaded_cloth_config_sha256']:
            result = copy.deepcopy(self.result)
            result[key] = 'other'
            with self.assertRaises(AssertionError):
                launcher.validate_runtime_result(result, self.manifest, self.log)
        with self.assertRaises(AssertionError):
            launcher.validate_runtime_result(self.result, self.manifest,
                                             self.log + '\nIP_NATIVE_RECOVERY_CASE_FAIL fixture')

    def test_cold_client_initializers_require_actual_log_records_and_reject_hot_client(self):
        self.assertEqual(len(launcher.validate_cold_client_initializers("[info][class,init] Initializing 'java/lang/Object'")), 12)
        for log in ['', "[info][class,init] Initializing 'qouteall/imm_ptl/core/IPCGlobal'"]:
            with self.assertRaises(AssertionError):
                launcher.validate_cold_client_initializers(log)

    def test_incomplete_second_player_partial_admission_evidence_fails(self):
        for mutation in ['missing', 'not_registered', 'early_refusal', 'missing_retry', 'wrong_event_total']:
            with self.subTest(mutation=mutation):
                result = copy.deepcopy(self.result)
                if mutation == 'missing':
                    result.pop('native_player_partial_admission')
                elif mutation == 'not_registered':
                    result['native_player_partial_admission']['first_player_registered_before_second_refusal'] = False
                elif mutation == 'early_refusal':
                    result['native_player_partial_admission']['destination_join_events_at_refusal'] = 2
                elif mutation == 'missing_retry':
                    result['native_player_partial_admission']['destination_join_events_after_retry'] = 4
                else:
                    player_case = next(case for case in result['cases'] if case['name'] == 'concrete_two_player_packets')
                    player_case['native_event_evidence']['destination_join_events'] = 6
                with self.assertRaisesRegex(AssertionError, 'partial first-player admission'):
                    launcher.validate_runtime_result(result, self.manifest, self.log)


class ArtifactValidationTest(unittest.TestCase):
    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory(prefix='native-recovery-launcher-unit-', dir='/tmp/opencode')
        self.addCleanup(self.scratch.cleanup)
        self.root = Path(self.scratch.name)
        for name in ['gradle.properties', 'settings.gradle', 'neoforge/build.gradle', 'neoforge/settings.gradle',
                     'neoforge/gradle.properties', 'dimlib/neoforge/build.gradle',
                     'dimlib/neoforge/settings.gradle', 'dimlib/neoforge/gradle.properties',
                     'neoforge/iris-bootstrap/build.gradle', 'neoforge/iris-bootstrap/settings.gradle',
                     'neoforge/iris-bootstrap/gradle.properties']:
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('')
        self.dimlib = self.root / 'dimlib/neoforge/build/libs/dimlib-neoforge-1.1.0+mc26.3.jar'
        self.dimlib.parent.mkdir(parents=True)
        self.dimlib.write_bytes(b'unit-validation-archive-not-a-runtime-mod')
        self.bootstrap = self.root / launcher.BOOTSTRAP_ARTIFACT
        self.bootstrap.parent.mkdir(parents=True)
        self.bootstrap.write_bytes(self.library_bytes())
        self.artifact = self.root / 'parent.jar'
        self.bundles = [
            {'identifier': {'group': 'qouteall.dimlib', 'artifact': 'dimlib-neoforge'}, 'path': 'dimlib.jar'},
            {'identifier': {'group': 'me.shedaniel.cloth', 'artifact': 'cloth-config-neoforge'}, 'path': 'cloth.jar'},
            {'identifier': {'group': 'qouteall', 'artifact': 'riftwoven-iris-bootstrap'}, 'path': 'bootstrap.jar'},
        ]
        root_patch = patch.object(launcher, 'ROOT', self.root)
        root_patch.start()
        self.addCleanup(root_patch.stop)
        self.write_parent()

    def library_bytes(self, *, mod_type='LIBRARY', provider=None, extra=None, omit=()):
        entries = {
            'META-INF/MANIFEST.MF': 'Manifest-Version: 1.0\r\n'
                                  f'FMLModType: {mod_type}\r\n'
                                  'Automatic-Module-Name: riftwoven.iris.bootstrap\r\n\r\n',
            launcher.BOOTSTRAP_SERVICE: launcher.BOOTSTRAP_PROVIDER + '\n' if provider is None else provider,
            launcher.BOOTSTRAP_PROVIDER.replace('.', '/') + '.class': b'class-bytes-for-fixture',
        }
        entries.update(extra or {})
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, 'w') as jar:
            for name, contents in entries.items():
                if name not in omit:
                    jar.writestr(name, contents)
        return stream.getvalue()

    def write_parent(self, *, bootstrap=None, bundles=None, extra=(), omit=()):
        with zipfile.ZipFile(self.artifact, 'w') as jar:
            jar.writestr('META-INF/neoforge.mods.toml', '[[mods]]\nmodId="immersive_portals"\nversion="7.1.0-dev"\n')
            jar.writestr('META-INF/jarjar/metadata.json', json.dumps({'jars': self.bundles if bundles is None else bundles}))
            for name, data in [('dimlib.jar', self.dimlib.read_bytes()), ('cloth.jar', b'unit-validation-cloth'),
                               ('bootstrap.jar', self.bootstrap.read_bytes() if bootstrap is None else bootstrap), *extra]:
                if name not in omit:
                    jar.writestr(name, data)

    def test_requires_native_current_bundles_and_fresh_parent(self):
        manifest = launcher.validate_artifact(self.artifact)
        self.assertEqual(manifest['path'], str(self.artifact))
        self.assertEqual(set(manifest['bundles']), {'dimlib', 'cloth', 'iris_bootstrap'})
        self.assertEqual(manifest['bundles']['iris_bootstrap'], launcher.sha256(self.bootstrap.read_bytes()))
        self.dimlib.write_bytes(b'changed-bundle')
        with self.assertRaisesRegex(ValueError, 'current native DimLib'):
            launcher.validate_artifact(self.artifact)
        newer = self.artifact.stat().st_mtime_ns + 1_000_000_000
        os.utime(self.root / 'gradle.properties', ns=(newer, newer))
        with self.assertRaisesRegex(ValueError, 'older than production inputs'):
            launcher.validate_artifact(self.artifact)

    def test_bootstrap_requires_exactly_one_declared_and_physical_bundle(self):
        for mutation in ['missing', 'duplicate', 'wrong_group', 'wrong_artifact', 'missing_entry', 'duplicate_entry']:
            with self.subTest(mutation=mutation):
                bundles = copy.deepcopy(self.bundles)
                if mutation == 'missing':
                    bundles.pop()
                elif mutation == 'duplicate':
                    bundles.append(copy.deepcopy(bundles[-1]))
                elif mutation == 'wrong_group':
                    bundles[-1]['identifier']['group'] = 'other'
                elif mutation == 'wrong_artifact':
                    bundles[-1]['identifier']['artifact'] = 'other'
                extra = [('bootstrap.jar', self.bootstrap.read_bytes())] if mutation == 'duplicate_entry' else []
                with warnings.catch_warnings():
                    warnings.simplefilter('ignore', UserWarning)
                    self.write_parent(bundles=bundles, extra=extra,
                                      omit=['bootstrap.jar'] if mutation == 'missing_entry' else [])
                with self.assertRaisesRegex(ValueError, 'Missing or ambiguous native bundle'):
                    launcher.validate_artifact(self.artifact)

    def test_bootstrap_wrong_or_missing_library_type_and_mod_metadata_fail(self):
        invalid = [self.library_bytes(mod_type='MOD'),
                   self.library_bytes(omit=['META-INF/MANIFEST.MF']),
                   self.library_bytes(extra={'META-INF/MANIFEST.MF': 'Manifest-Version: 1.0\r\n\r\n'}),
                   self.library_bytes(extra={'META-INF/neoforge.mods.toml': '[[mods]]\n'}),
                   self.library_bytes(extra={'fabric.mod.json': '{}'})]
        for data in invalid:
            with self.subTest(data=data):
                self.bootstrap.write_bytes(data)
                self.write_parent()
                with self.assertRaisesRegex(ValueError, 'LIBRARY|library archive'):
                    launcher.validate_artifact(self.artifact)

    def test_bootstrap_wrong_missing_or_multiple_service_providers_fail(self):
        for data in [self.library_bytes(provider='wrong.Provider\n'),
                     self.library_bytes(provider=launcher.BOOTSTRAP_PROVIDER + '\nother.Provider\n'),
                     self.library_bytes(omit=[launcher.BOOTSTRAP_SERVICE])]:
            with self.subTest(data=data):
                self.bootstrap.write_bytes(data)
                self.write_parent()
                with self.assertRaisesRegex(ValueError, 'service'):
                    launcher.validate_artifact(self.artifact)

    def test_bootstrap_module_identity_and_main_manifest_section_are_required(self):
        for manifest in ['Manifest-Version: 1.0\r\nFMLModType: LIBRARY\r\n'
                         'Automatic-Module-Name: wrong.module\r\n\r\n',
                         'Manifest-Version: 1.0\r\n\r\nName: other\r\n'
                         'FMLModType: LIBRARY\r\nAutomatic-Module-Name: riftwoven.iris.bootstrap\r\n\r\n']:
            self.bootstrap.write_bytes(self.library_bytes(extra={'META-INF/MANIFEST.MF': manifest}))
            self.write_parent()
            with self.assertRaisesRegex(ValueError, 'module identity|FMLModType'):
                launcher.validate_artifact(self.artifact)
        self.bootstrap.write_bytes(self.library_bytes(extra={'META-INF/MANIFEST.MF':
            'Manifest-Version: 1.0\r\nFMLModType: LIBRARY\r\nAutomatic-Module-Name: riftwoven.iris.\r\n bootstrap\r\n\r\n'}))
        self.write_parent()
        launcher.validate_artifact(self.artifact)

    def test_duplicate_nested_entries_fail_even_with_matching_standalone(self):
        stream = io.BytesIO(self.bootstrap.read_bytes())
        with warnings.catch_warnings():
            warnings.simplefilter('ignore', UserWarning)
            with zipfile.ZipFile(stream, 'a') as jar:
                jar.writestr(launcher.BOOTSTRAP_SERVICE, launcher.BOOTSTRAP_PROVIDER + '\n')
        self.bootstrap.write_bytes(stream.getvalue())
        self.write_parent()
        with self.assertRaisesRegex(ValueError, 'duplicate entries'):
            launcher.validate_artifact(self.artifact)

    def test_bootstrap_requires_current_standalone_bytes_and_file(self):
        self.bootstrap.write_bytes(self.library_bytes(extra={'extra-resource': 'different current bytes'}))
        with self.assertRaisesRegex(ValueError, 'current native Iris bootstrap'):
            launcher.validate_artifact(self.artifact)
        self.bootstrap.unlink()
        with self.assertRaisesRegex(ValueError, 'current native Iris bootstrap'):
            launcher.validate_artifact(self.artifact)

    def test_nested_crc_and_invalid_zip_fail_even_when_standalone_bytes_match(self):
        data = bytearray(self.bootstrap.read_bytes())
        data[data.index(b'class-bytes-for-fixture')] ^= 1
        for invalid in [bytes(data), b'not-a-library-archive']:
            with self.subTest(invalid=invalid):
                self.bootstrap.write_bytes(invalid)
                self.write_parent()
                with self.assertRaisesRegex(ValueError, 'CRC|archive'):
                    launcher.validate_artifact(self.artifact)

    def test_test_helpers_and_extra_or_missing_bootstrap_classes_fail(self):
        processor = launcher.BOOTSTRAP_PROVIDER.replace('.', '/') + '.class'
        for data in [self.library_bytes(extra={'qouteall/imm_ptl/recovery_test/Fixture.class': b'helper'}),
                     self.library_bytes(extra={'qouteall/imm_ptl/client_test/Fixture.class': b'helper'}),
                     self.library_bytes(extra={'qouteall/imm_ptl/iris_bootstrap/PublishedFixture.class': b'helper'}),
                     self.library_bytes(omit=[processor])]:
            with self.subTest(data=data):
                self.bootstrap.write_bytes(data)
                self.write_parent()
                with self.assertRaisesRegex(ValueError, 'test helpers|production ClassProcessor'):
                    launcher.validate_artifact(self.artifact)
        for helper in ['qouteall/imm_ptl/client_test/Fixture.class', 'qouteall/imm_ptl/recovery_test/Fixture.class']:
            self.write_parent(extra=[(helper, b'helper')])
            with self.assertRaisesRegex(ValueError, 'development harness'):
                launcher.validate_artifact(self.artifact)

    def test_newer_native_sources_and_build_settings_reject_matching_current_bundle(self):
        for name in ['neoforge/iris-bootstrap/src/main/java/Processor.java',
                     'neoforge/iris-bootstrap/src/main/resources/service',
                     'neoforge/iris-bootstrap/build.gradle', 'neoforge/iris-bootstrap/settings.gradle',
                     'settings.gradle', 'neoforge/settings.gradle', 'dimlib/neoforge/build.gradle',
                     'dimlib/neoforge/settings.gradle', 'dimlib/neoforge/gradle.properties']:
            with self.subTest(name=name):
                path = self.root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.touch()
                self.write_parent()
                newer = self.artifact.stat().st_mtime_ns + 1_000_000_000
                os.utime(path, ns=(newer, newer))
                with self.assertRaisesRegex(ValueError, 'older than production inputs'):
                    launcher.validate_artifact(self.artifact)
                os.utime(path, ns=(0, 0))

    def test_bootstrap_build_and_test_churn_do_not_make_package_stale(self):
        newer = self.artifact.stat().st_mtime_ns + 1_000_000_000
        for name in ['neoforge/iris-bootstrap/build/reports/tests/test/index.html',
                     'neoforge/iris-bootstrap/src/test/java/ProcessorTest.java',
                     'neoforge/recovery-test/test_launcher.py', 'neoforge/client-test/test_client.py']:
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('test-only change')
            os.utime(path, ns=(newer, newer))
        launcher.validate_artifact(self.artifact)

    def test_no_eula_never_launches_any_process(self):
        with patch('sys.argv', ['test_server.py']), patch.object(launcher.subprocess, 'Popen') as popen:
            with self.assertRaises(SystemExit):
                launcher.main()
            popen.assert_not_called()


class ProcessOwnershipTest(unittest.TestCase):
    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory(prefix='native-recovery-ownership-unit-', dir='/tmp/opencode')
        self.addCleanup(self.scratch.cleanup)
        self.root = Path(self.scratch.name)
        self.world = self.root / 'world'
        self.world.mkdir()
        (self.world / launcher.MARKER).write_text('n' * 48 + '\n')
        self.java_home = self.root / 'jdk'
        (self.java_home / 'bin').mkdir(parents=True)
        (self.java_home / 'bin/java').touch()
        self.proc = self.root / 'proc'
        self.pid = 123456
        self.process = self.proc / str(self.pid)
        self.process.mkdir(parents=True)
        (self.process / 'exe').symlink_to(self.java_home / 'bin/java')
        (self.process / 'cwd').symlink_to(self.world)
        (self.process / 'stat').write_text('123456 (java (test)) ' + ' '.join(['S'] + ['0'] * 18 + ['100']))
        self.patches = [patch.object(launcher, 'PROC', self.proc),
                        patch.object(launcher.os, 'pidfd_open', return_value=77),
                        patch.object(launcher.os, 'close'),
                        patch.object(launcher, 'pidfd_alive', return_value=True),
                        patch.object(launcher.signal, 'pidfd_send_signal')]
        self.proc_patch, self.open_fd, self.close_fd, self.alive, self.send = [p.start() for p in self.patches]
        for p in self.patches:
            self.addCleanup(p.stop)

    def lease(self, **changes):
        lease = {'pid': self.pid, 'start': '100', 'nonce': 'n' * 48}
        lease.update(changes)
        (self.world / launcher.LEASE).write_text(json.dumps(lease))

    def detach(self):
        (self.process / 'cwd').unlink()
        (self.process / 'cwd').symlink_to(self.root)

    def signal(self):
        launcher.signal_owned_java(self.pid, '100', self.world, self.java_home, signal.SIGTERM)

    def test_exact_java_uid_cwd_and_start_are_required(self):
        self.assertEqual(launcher.owned_java_processes(self.world, self.java_home), {self.pid: '100'})
        with patch.object(launcher.os, 'getuid', return_value=os.getuid() + 1):
            self.assertEqual(launcher.owned_java_processes(self.world, self.java_home), {})
        (self.process / 'exe').unlink()
        (self.process / 'exe').symlink_to(self.root / 'other-java')
        self.assertEqual(launcher.owned_java_processes(self.world, self.java_home), {})
        self.signal()
        self.send.assert_not_called()

    def test_detached_gradle_requires_exact_saved_lease(self):
        self.detach()
        self.assertEqual(launcher.owned_java_processes(self.world, self.java_home), {})
        for mutation in [{'nonce': 'other'}, {'pid': self.pid + 1}, {'start': '200'}]:
            with self.subTest(mutation=mutation):
                self.lease(**mutation)
                self.assertEqual(launcher.owned_java_processes(self.world, self.java_home), {})
        self.lease()
        self.assertEqual(launcher.owned_java_processes(self.world, self.java_home), {self.pid: '100'})
        self.signal()
        self.send.assert_called_once_with(77, signal.SIGTERM)

    def test_detached_gradle_before_lease_accepts_exact_inherited_nonce(self):
        self.detach()
        (self.process / 'environ').write_bytes(b'OTHER=value\0IP_NATIVE_RECOVERY_NONCE=' + b'n' * 48 + b'\0')
        self.assertFalse((self.world / launcher.LEASE).exists())
        self.assertEqual(launcher.owned_java_processes(self.world, self.java_home), {self.pid: '100'})
        self.signal()
        self.send.assert_called_once_with(77, signal.SIGTERM)

    def test_detached_gradle_rejects_absent_wrong_or_partial_nonce(self):
        self.detach()
        self.assertEqual(launcher.owned_java_processes(self.world, self.java_home), {})
        for environment in [b'', b'OTHER=value\0', b'IP_NATIVE_RECOVERY_NONCE=' + b'x' * 48 + b'\0',
                            b'OTHER_IP_NATIVE_RECOVERY_NONCE=' + b'n' * 48 + b'\0',
                            b'IP_NATIVE_RECOVERY_NONCE=' + b'n' * 48 + b'-suffix\0']:
            with self.subTest(environment=environment):
                (self.process / 'environ').write_bytes(environment)
                self.assertEqual(launcher.owned_java_processes(self.world, self.java_home), {})
                self.signal()
        self.send.assert_not_called()

    def test_detached_gradle_requires_sufficiently_long_existing_marker(self):
        self.detach()
        (self.world / launcher.MARKER).write_text('short')
        (self.process / 'environ').write_bytes(b'IP_NATIVE_RECOVERY_NONCE=short\0')
        self.assertEqual(launcher.owned_java_processes(self.world, self.java_home), {})
        self.signal()
        (self.world / launcher.MARKER).unlink()
        self.assertEqual(launcher.owned_java_processes(self.world, self.java_home), {})
        self.signal()
        self.send.assert_not_called()

    def test_pid_reuse_during_nonce_read_rejects_discovery_and_signaling(self):
        self.detach()
        (self.process / 'environ').write_bytes(b'IP_NATIVE_RECOVERY_NONCE=' + b'n' * 48 + b'\0')
        original = launcher.has_owned_environment

        def reused_after_read(pid, directory):
            owned = original(pid, directory)
            (self.process / 'stat').write_text('123456 (replacement) ' + ' '.join(['S'] + ['0'] * 18 + ['200']))
            return owned

        with patch.object(launcher, 'has_owned_environment', side_effect=reused_after_read):
            self.assertEqual(launcher.owned_java_processes(self.world, self.java_home), {})
            (self.process / 'stat').write_text('123456 (java) ' + ' '.join(['S'] + ['0'] * 18 + ['100']))
            self.signal()
        self.send.assert_not_called()

    def test_inherited_nonce_does_not_bypass_java_uid_or_live_pidfd(self):
        self.detach()
        (self.process / 'environ').write_bytes(b'IP_NATIVE_RECOVERY_NONCE=' + b'n' * 48 + b'\0')
        with patch.object(launcher.os, 'getuid', return_value=os.getuid() + 1):
            self.assertEqual(launcher.owned_java_processes(self.world, self.java_home), {})
            self.signal()
        self.alive.return_value = False
        self.assertEqual(launcher.owned_java_processes(self.world, self.java_home), {})
        self.signal()
        self.alive.return_value = True
        (self.process / 'exe').unlink()
        (self.process / 'exe').symlink_to(self.root / 'other-java')
        self.assertEqual(launcher.owned_java_processes(self.world, self.java_home), {})
        self.signal()
        self.send.assert_not_called()

    def test_identity_change_during_cwd_or_lease_read_is_rejected(self):
        for detached in [False, True]:
            with self.subTest(detached=detached):
                if detached:
                    self.detach()
                    self.lease()
                with patch.object(launcher, 'process_start', side_effect=['100', '200']):
                    self.assertEqual(launcher.owned_java_processes(self.world, self.java_home), {})

    def test_handle_is_pinned_before_ownership_read(self):
        events = []
        self.open_fd.side_effect = lambda pid: events.append('open') or 77
        self.alive.side_effect = lambda fd: events.append('alive') or True
        self.close_fd.side_effect = lambda fd: events.append('close')
        with patch.object(launcher, 'owned_java_identity', side_effect=lambda *a: events.append('identity') or '100'):
            self.assertEqual(launcher.owned_java_processes(self.world, self.java_home), {self.pid: '100'})
        self.assertEqual(events, ['open', 'identity', 'alive', 'close'])

    def test_exit_during_ownership_read_rejects_even_same_tick_pid_reuse(self):
        self.alive.return_value = False
        self.assertEqual(launcher.owned_java_processes(self.world, self.java_home), {})
        self.signal()
        self.send.assert_not_called()
        self.assertEqual(self.close_fd.call_count, 2)

    def test_reused_pid_or_lost_cwd_between_discovery_and_signal_is_rejected(self):
        self.assertEqual(launcher.owned_java_processes(self.world, self.java_home), {self.pid: '100'})
        with patch.object(launcher, 'process_start', return_value='200'):
            self.signal()
        self.detach()
        self.signal()
        self.send.assert_not_called()

    def test_detached_lease_is_rechecked_before_signaling(self):
        self.detach()
        self.lease()
        self.assertEqual(launcher.owned_java_processes(self.world, self.java_home), {self.pid: '100'})
        self.lease(nonce='another run')
        self.signal()
        self.send.assert_not_called()

    def test_signal_only_uses_pinned_handle_not_numeric_pid_or_group(self):
        with patch.object(launcher.os, 'kill') as kill, patch.object(launcher.os, 'killpg') as killpg:
            self.signal()
            kill.assert_not_called()
            killpg.assert_not_called()
        self.send.assert_called_once_with(77, signal.SIGTERM)
        self.close_fd.assert_called_once_with(77)

    def test_exit_after_last_check_never_falls_back_to_pid_signal(self):
        self.send.side_effect = ProcessLookupError
        with patch.object(launcher.os, 'kill') as kill, patch.object(launcher.os, 'killpg') as killpg:
            self.signal()
            kill.assert_not_called()
            killpg.assert_not_called()
        self.send.assert_called_once_with(77, signal.SIGTERM)
        self.close_fd.assert_called_once_with(77)

    def test_opening_handle_for_exited_process_is_harmless(self):
        self.open_fd.side_effect = ProcessLookupError
        self.assertEqual(launcher.owned_java_processes(self.world, self.java_home), {})
        self.signal()
        self.send.assert_not_called()
        self.close_fd.assert_not_called()


class CancellationAndCleanupTest(unittest.TestCase):
    def test_actual_kernel_pidfd_probe_and_live_handle_without_launch(self):
        launcher.require_pidfds()
        descriptor = os.pidfd_open(os.getpid())
        try:
            self.assertTrue(launcher.pidfd_alive(descriptor))
        finally:
            os.close(descriptor)

    def test_unsupported_pidfds_refuse_before_artifact_validation_or_launch(self):
        for failure in [None, OSError(errno.ENOSYS, 'unsupported')]:
            with self.subTest(failure=failure):
                replacement = None if failure is None else Mock(side_effect=failure)
                with (patch('sys.argv', ['test_server.py', '--accept-eula']),
                      patch.object(launcher.os, 'pidfd_open', replacement),
                      patch.object(launcher, 'validate_artifact') as artifact,
                      patch.object(launcher.subprocess, 'Popen') as popen):
                    with self.assertRaisesRegex(RuntimeError, 'PID-handle'):
                        launcher.main()
                    artifact.assert_not_called()
                    popen.assert_not_called()

    def test_pidfd_probe_checks_signal_permission_and_closes_handle(self):
        with (patch.object(launcher.os, 'pidfd_open', return_value=77),
              patch.object(launcher.signal, 'pidfd_send_signal', side_effect=PermissionError) as send,
              patch.object(launcher.os, 'close') as close):
            with self.assertRaisesRegex(RuntimeError, 'refusing to launch'):
                launcher.require_pidfds()
            send.assert_called_once_with(77, 0)
            close.assert_called_once_with(77)

    def test_sigterm_during_acquisition_cleans_acquired_child_before_propagating(self):
        process = Mock()
        cleanup = Mock()
        previous = signal.getsignal(signal.SIGTERM)

        def acquire(*args, **kwargs):
            signal.raise_signal(signal.SIGTERM)
            return process

        with (patch.object(launcher.subprocess, 'Popen', side_effect=acquire),
              patch.object(signal, 'pthread_sigmask', side_effect=AssertionError('No inherited signal mask'))):
            with self.assertRaises(InterruptedError), launcher.cancellation_handlers():
                with launcher.launch_process(['never-run'], cleanup):
                    self.fail('Pending launch cancellation must be delivered before yielding')
        cleanup.assert_called_once_with(process)
        self.assertEqual(signal.getsignal(signal.SIGTERM), previous)

    def test_sigterm_during_wait_runs_cleanup(self):
        process = Mock()
        process.wait.side_effect = lambda **kwargs: signal.raise_signal(signal.SIGTERM)
        cleanup = Mock()
        with patch.object(launcher.subprocess, 'Popen', return_value=process):
            with self.assertRaises(InterruptedError), launcher.cancellation_handlers():
                with launcher.launch_process(['never-run'], cleanup) as child:
                    child.wait(timeout=1)
        cleanup.assert_called_once_with(process)

    def test_first_sigterm_during_cleanup_wait_cannot_skip_kill_escalation(self):
        process = Mock(pid=123456)
        process.poll.return_value = None
        waits = []

        def wait(timeout):
            waits.append(timeout)
            if len(waits) == 1:
                signal.raise_signal(signal.SIGTERM)
                raise subprocess.TimeoutExpired('never-run', timeout)
            process.poll.return_value = 0
            return 0

        process.wait.side_effect = wait
        with (patch.object(launcher.subprocess, 'Popen', return_value=process),
              patch.object(launcher.os, 'pidfd_open', return_value=77),
              patch.object(launcher.signal, 'pidfd_send_signal') as send,
              patch.object(launcher.os, 'close') as close,
              patch.object(launcher, 'owned_java_processes', return_value={}),
              patch.object(launcher.os, 'kill') as kill,
              patch.object(launcher.os, 'killpg') as killpg):
            with self.assertRaises(InterruptedError), launcher.cancellation_handlers():
                with launcher.launch_process(['never-run'],
                                             lambda child: launcher.terminate_owned(child, None, Path('/unused'))):
                    pass
            self.assertEqual(waits, [20, 10])
            self.assertEqual(send.call_args_list, [call(77, signal.SIGTERM), call(77, signal.SIGKILL)])
            close.assert_called_once_with(77)
            kill.assert_not_called()
            killpg.assert_not_called()

    def test_failed_child_acquisition_still_runs_cleanup(self):
        cleanup = Mock()
        with patch.object(launcher.subprocess, 'Popen', side_effect=OSError('cannot start')):
            with self.assertRaises(OSError):
                with launcher.launch_process(['never-run'], cleanup):
                    self.fail('Acquisition failed')
        cleanup.assert_called_once_with(None)

    def test_live_owned_jvm_preserves_world_and_lease(self):
        with tempfile.TemporaryDirectory(prefix='native-recovery-cleanup-unit-', dir='/tmp/opencode') as scratch:
            root = Path(scratch)
            with (patch.object(launcher, 'SCRATCH', root),
                  patch.object(launcher, 'owned_java_processes', return_value={123456: '100'}),
                  patch.object(launcher, 'signal_owned_java') as send,
                  patch.object(launcher.time, 'monotonic', side_effect=range(0, 100, 10))):
                with self.assertRaisesRegex(RuntimeError, 'could not be stopped safely'):
                    with launcher.recovery_world(Path('/unused'), root) as (directory, _cleanup):
                        (directory / launcher.MARKER).write_text('n' * 48)
                        (directory / launcher.LEASE).write_text('saved lease')
                self.assertEqual([args.args[-1] for args in send.call_args_list], [signal.SIGTERM, signal.SIGKILL])
                self.assertTrue((directory / launcher.MARKER).is_file())
                self.assertTrue((directory / launcher.LEASE).is_file())

    def test_failed_launcher_cleanup_preserves_world_even_if_no_java_matches(self):
        with tempfile.TemporaryDirectory(prefix='native-recovery-cleanup-unit-', dir='/tmp/opencode') as scratch:
            root = Path(scratch)
            with (patch.object(launcher, 'SCRATCH', root),
                  patch.object(launcher, 'terminate_owned', side_effect=RuntimeError('launcher remains')) as terminate):
                with self.assertRaisesRegex(RuntimeError, 'launcher remains'):
                    with launcher.recovery_world(Path('/unused'), root) as (directory, cleanup):
                        (directory / launcher.MARKER).write_text('owned')
                        cleanup(Mock())
                terminate.assert_called_once()
                self.assertTrue((directory / launcher.MARKER).is_file())

    def test_successful_cleanup_copies_logs_and_removes_world(self):
        with tempfile.TemporaryDirectory(prefix='native-recovery-cleanup-unit-', dir='/tmp/opencode') as scratch:
            root = Path(scratch)
            report = root / 'report'
            report.mkdir()
            with patch.object(launcher, 'SCRATCH', root), patch.object(launcher, 'terminate_owned'):
                with launcher.recovery_world(Path('/unused'), report) as (directory, _cleanup):
                    (directory / 'logs').mkdir()
                    (directory / 'logs/latest.log').write_text('retained')
                self.assertFalse(directory.exists())
                self.assertEqual((report / 'logs/latest.log').read_text(), 'retained')

    def test_sigterm_main_wait_retains_failure_summary_after_cleanup(self):
        self.check_main_failure(cleanup_fails=False)

    def test_failed_main_cleanup_reports_preserved_ownership_directory(self):
        self.check_main_failure(cleanup_fails=True)

    def check_main_failure(self, cleanup_fails):
        with tempfile.TemporaryDirectory(prefix='native-recovery-main-unit-', dir='/tmp/opencode') as scratch:
            root = Path(scratch)
            java_home = root / 'jdk'
            (java_home / 'bin').mkdir(parents=True)
            (java_home / 'bin/java').touch()
            process = Mock()
            if not cleanup_fails:
                process.wait.side_effect = lambda **kwargs: signal.raise_signal(signal.SIGTERM)
            manifest = {'sha256': 'parent', 'bundles': {'dimlib': 'dimlib', 'cloth': 'cloth'}}

            def acquire(command, **kwargs):
                world = next(root.glob('ip-native-recovery-world-*'))
                nonce = (world / launcher.MARKER).read_text().strip()
                self.assertGreaterEqual(len(nonce), 32)
                self.assertEqual(kwargs['env']['IP_NATIVE_RECOVERY_NONCE'], nonce)
                self.assertEqual(os.environ['IP_NATIVE_RECOVERY_NONCE'], 'inherited-unrelated-nonce')
                return process

            with (patch('sys.argv', ['test_server.py', '--accept-eula']),
                  patch.dict(os.environ, {'JAVA_HOME': str(java_home),
                                         'IP_NATIVE_RECOVERY_NONCE': 'inherited-unrelated-nonce'}),
                  patch.object(launcher, 'SCRATCH', root),
                  patch.object(launcher, 'require_pidfds'),
                  patch.object(launcher, 'validate_artifact', return_value=manifest),
                  patch.object(launcher, 'free_loopback_port', return_value=12345),
                  patch.object(launcher.subprocess, 'Popen', side_effect=acquire),
                  patch.object(launcher, 'terminate_owned',
                               side_effect=RuntimeError('owned launcher remains') if cleanup_fails else None)):
                with self.assertRaises(RuntimeError if cleanup_fails else InterruptedError):
                    launcher.main()
            reports = list(root.glob('ip-native-recovery-report-*'))
            self.assertEqual(len(reports), 1)
            summary = json.loads((reports[0] / 'summary.json').read_text())
            self.assertEqual(summary['status'], 'FAIL')
            self.assertEqual(summary['world_removed'], not cleanup_fails)
            if cleanup_fails:
                self.assertTrue((Path(summary['preserved_world']) / launcher.MARKER).is_file())
            else:
                self.assertFalse(list(root.glob('ip-native-recovery-world-*')))


if __name__ == '__main__':
    unittest.main()
