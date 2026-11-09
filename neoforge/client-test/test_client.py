#!/usr/bin/env python3
"""Explicit native packaged-jar real-client smoke test; never invoked by a production build."""
from __future__ import annotations

import argparse
from contextlib import contextmanager
import importlib.util
import json
import os
from pathlib import Path
import queue
import re
import secrets
import shutil
import signal
import stat
import struct
import subprocess
import sys
import tempfile
import threading
import time
import zipfile

MODULE = Path(__file__).resolve().parent
ROOT = MODULE.parent.parent
SCRATCH = Path('/tmp/opencode')
MARKER = '.ip-native-client-owned'
IMAGES = {'portal-view.png', 'after-crossing.png', 'after-return.png'}
REALM_IMAGES = {'realm-portal-view.png', 'realm-terrain.png', 'realm-sky.png', 'realm-return.png', 'realm-terrain-replay.png'}
MIXIN_AUDIT_FAILURE = re.compile(r'Could not force-load|ClassAlreadyLoadedException|Uninitialized mixin')
NATIVE_LOADING_FAILURE = re.compile(r'Failed to start FML|Missing or unsupported mandatory dependencies')
PROFILES = {
    'vanilla': set(),
    'performance': {'sodium', 'lithium', 'ferritecore', 'entityculling', 'immediatelyfast'},
    'performance-iris': {'sodium', 'lithium', 'ferritecore', 'entityculling', 'immediatelyfast', 'iris'},
}


def load_module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


artifact_checks = load_module('ip_native_artifact_checks', MODULE.parent / 'recovery-test/test_server.py')
smoke = load_module('ip_existing_smoke_helpers', ROOT / '.github/scripts/smoke_test.py')


def display_module(path):
    if path is None or not path.is_file():
        raise ValueError('Supply the maintained wrapper with --display-wrapper or IP_NATIVE_DISPLAY_WRAPPER')
    module = load_module('ip_native_private_display', path.resolve())
    if not callable(getattr(module, 'verify_owned_display', None)):
        raise ValueError('Display wrapper must implement verify_owned_display()')
    return module


def verify_dispatch(wrapper, directory, nonce):
    wrapper.verify_owned_display()
    directory = directory.resolve(strict=True)
    if (directory.parent != SCRATCH.resolve() or not directory.name.startswith('ip-native-client-world-')
            or directory.stat().st_uid != os.getuid() or stat.S_IMODE(directory.stat().st_mode) != 0o700
            or len(nonce) < 32 or (directory / MARKER).read_text().strip() != nonce
            or (directory / 'eula.txt').read_text().strip() != 'eula=true'):
        raise ValueError('Not a fresh approved owned native-client directory')
    if os.environ.get('ALSOFT_DRIVERS') != 'null' or os.environ.get('SDL_VIDEODRIVER') != 'x11':
        raise ValueError('Native client must use isolated X11 and null audio')


def child_environment(directory):
    environment = os.environ.copy()
    for key in ['WAYLAND_DISPLAY', 'WAYLAND_SOCKET', 'XDG_SESSION_TYPE', 'PIPEWIRE_REMOTE']:
        environment.pop(key, None)
    for name in ['home', 'config', 'cache', 'data', 'tmp']:
        (directory / name).mkdir(mode=0o700)
    environment.update({
        'HOME': str(directory / 'home'), 'XDG_CONFIG_HOME': str(directory / 'config'),
        'XDG_CACHE_HOME': str(directory / 'cache'), 'XDG_DATA_HOME': str(directory / 'data'),
        'TMPDIR': str(directory / 'tmp'), 'SDL_VIDEODRIVER': 'x11', 'SDL_AUDIODRIVER': 'dummy',
        'ALSOFT_DRIVERS': 'null', 'ALSOFT_CONF': str(directory / 'alsoft.conf'),
        'PULSE_SERVER': f'unix:{directory}/no-audio', 'PIPEWIRE_REMOTE': 'ip-native-client-no-audio',
        'JAVA_TOOL_OPTIONS': '-XX:ActiveProcessorCount=2 -Djava.awt.headless=true',
    })
    (directory / 'alsoft.conf').write_text('[general]\ndrivers = null\n')
    # Do not redirect XDG_RUNTIME_DIR: it belongs to the maintained private display.
    environment['JAVA_HOME'] = os.environ.get('JAVA_HOME', str(Path.home() / '.cache/immersive-portals/toolchains/jdk-25'))
    environment['GRADLE_USER_HOME'] = os.environ.get('GRADLE_USER_HOME', str(Path.home() / '.cache/immersive-portals/gradle'))
    return environment


def gradle_command(args, directory, report, manifest, nonce, java_home=None):
    java_home = java_home or Path(os.environ.get('JAVA_HOME', str(Path.home() / '.cache/immersive-portals/toolchains/jdk-25')))
    command = [str(ROOT / 'gradlew'), '-p', str(MODULE), '--no-daemon', '--max-workers=2', '--console=plain',
               '--project-cache-dir', str(directory / 'gradle-project-cache'),
               '-Porg.gradle.java.installations.auto-download=false', f'-Dorg.gradle.java.home={java_home}', 'runSmokeClient',
               f'-PclientRunDir={directory}', f'-PclientNonce={nonce}', f'-PclientReportDir={report}',
               f'-PdisplayWrapper={args.display_wrapper.resolve()}', f'-PparentArtifact={manifest["path"]}',
               f'-PparentArtifactSha256={manifest["sha256"]}', f'-PdimlibSha256={manifest["bundles"]["dimlib"]}',
               f'-PclothSha256={manifest["bundles"]["cloth"]}', f'-PclientProfile={args.profile}',
               f'-PawayTicks={args.away_ticks}', f'-PshaderPackExpected={str(args.shader_pack is not None).lower()}']
    if args.offline:
        command.insert(3, '--offline')
    if getattr(args, 'realm', False):
        command.append('-Prealm=true')
    return command


def process_identity(pid, directory, java_home, start=None, require_cwd=True):
    try:
        process = Path('/proc') / str(pid)
        current = (process / 'stat').read_text().rsplit(')', 1)[1].split()[19]
        valid = (process.stat().st_uid == os.getuid()
                 and (process / 'exe').resolve() == (java_home / 'bin/java').resolve()
                 and (start is None or current == start)
                 and (not require_cwd or (process / 'cwd').resolve() == directory))
        return current if valid else None
    except (OSError, ValueError, IndexError):
        return None


def has_owned_environment(pid, directory):
    try:
        nonce = (directory / MARKER).read_text().strip()
        wanted = f'IP_NATIVE_CLIENT_NONCE={nonce}'.encode()
        return len(nonce) >= 32 and wanted in (Path('/proc') / str(pid) / 'environ').read_bytes().split(b'\0')
    except OSError:
        return False


def owned_java(directory, java_home):
    found = {}
    for entry in Path('/proc').iterdir():
        if entry.name.isdecimal():
            pid = int(entry.name)
            # Capture identity before reading cwd/nonce, then require that same
            # process afterwards. Ownership from a dead JVM cannot authorize a
            # replacement which reused its PID during the environment read.
            identity = process_identity(pid, directory, java_home, require_cwd=False)
            if identity is not None and (
                process_identity(pid, directory, java_home, identity) == identity
                or has_owned_environment(pid, directory)
            ) and process_identity(pid, directory, java_home, identity, require_cwd=False) == identity:
                found[pid] = identity
    try:
        lease = json.loads((directory / '.ip-native-client-gradle.json').read_text())
        if lease['nonce'] == (directory / MARKER).read_text().strip():
            pid = int(lease['pid'])
            identity = process_identity(pid, directory, java_home, lease['start'], require_cwd=False)
            if identity is not None:
                found[pid] = identity
    except (OSError, ValueError, KeyError):
        pass
    return found


@contextmanager
def fresh_world(java_home):
    directory = Path(tempfile.mkdtemp(prefix='ip-native-client-world-', dir=SCRATCH)).resolve()
    directory.chmod(0o700)
    try:
        yield directory
    finally:
        # Preserve the directory/lease if safe termination ever fails, rather than
        # deleting the ownership evidence out from under a live detached daemon.
        if owned_java(directory, java_home):
            raise RuntimeError(f'Owned JVM is still live; preserving its world and ownership marker: {directory}')
        shutil.rmtree(directory)


def terminate(process, directory, java_home):
    if process is not None and process.poll() is None:
        try:
            os.killpg(process.pid, signal.SIGTERM)
            process.wait(timeout=15)
        except ProcessLookupError:
            pass
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait(timeout=10)
    if directory is None:
        return
    for sig in [signal.SIGTERM, signal.SIGKILL]:
        for pid, identity in owned_java(directory, java_home).items():
            signal_owned_java(pid, identity, directory, java_home, sig)
        deadline = time.monotonic() + 5
        while owned_java(directory, java_home) and time.monotonic() < deadline:
            time.sleep(0.05)
        if not owned_java(directory, java_home):
            return
    raise RuntimeError('Cannot safely stop this owned native-client JVM')


def signal_owned_java(pid, identity, directory, java_home, sig):
    # Pin the process before rechecking ownership: a recycled numeric PID must
    # never redirect cleanup to a different process after the identity check.
    try:
        descriptor = os.pidfd_open(pid)
    except ProcessLookupError:
        return
    try:
        if process_identity(pid, directory, java_home, identity, require_cwd=False) == identity:
            try:
                signal.pidfd_send_signal(descriptor, sig)
            except ProcessLookupError:
                pass
    finally:
        os.close(descriptor)


def validate_java(java_home):
    try:
        version = re.search(r'^JAVA_VERSION="(\d+)(?:[.+-][^"]*)?"$',
                            (java_home / 'release').read_text(), re.MULTILINE)
        if (java_home / 'bin/java').is_file() and version and int(version[1]) == 25:
            return
    except OSError:
        pass
    raise ValueError('JAVA_HOME must be an existing Java 25 installation')


@contextmanager
def cancellation_handlers():
    def interrupted(signum, _frame):
        for sig in [signal.SIGTERM, signal.SIGINT]:
            signal.signal(sig, signal.SIG_IGN)
        raise InterruptedError(f'Native-client supervisor stopped by signal {signum}')

    previous = {sig: signal.signal(sig, interrupted) for sig in [signal.SIGTERM, signal.SIGINT]}
    try:
        yield
    finally:
        for sig, handler in previous.items():
            signal.signal(sig, handler)


@contextmanager
def defer_cancellation():
    pending = []
    def defer(signum, frame):
        pending.append((signum, frame))
    previous = {sig: signal.signal(sig, defer) for sig in [signal.SIGTERM, signal.SIGINT]}
    try:
        yield
    finally:
        for sig, handler in previous.items():
            signal.signal(sig, handler)
        if pending:
            signum, frame = pending[0]
            handler = previous[signum]
            if callable(handler):
                handler(signum, frame)
            elif handler != signal.SIG_IGN:
                raise InterruptedError(f'Native-client launch stopped by signal {signum}')


@contextmanager
def launch_process(command, cleanup, **kwargs):
    # Defer cancellation until both the child reference and its cleanup guard
    # exist. Do not mask signals across exec: the child needs graceful SIGTERM.
    process = None
    try:
        with defer_cancellation():
            process = subprocess.Popen(command, **kwargs)
        yield process
    finally:
        if process is not None:
            # A first signal during timeout/fatal cleanup must not interrupt its
            # waits or SIGKILL escalation. Deliver it after cleanup finishes.
            with defer_cancellation():
                cleanup(process)


def stop_wrapper(process):
    if process.poll() is None:
        os.killpg(process.pid, signal.SIGTERM)
        try:
            process.wait(timeout=25)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait(timeout=5)


def marker_color(rgb, color):
    r, g, b = rgb
    if max(rgb) < 30:
        return False
    return (r > 1.25 * g and r > 1.25 * b) if color == 'red' else (g > 1.15 * r and g > 1.1 * b)


def check_image(path, deadline):
    data = path.read_bytes()
    if data[:8] != b'\x89PNG\r\n\x1a\n' or len(data) < 24:
        raise AssertionError(f'Missing actual framebuffer PNG: {path.name}')
    width, height = struct.unpack('>II', data[16:24])
    if width < 640 or height < 400:
        raise AssertionError('Framebuffer image is too small')
    crops = [('nether_floor', 'iw*0.1:ih*0.1:iw*0.45:ih*0.45', 'red')] if path.name == 'after-crossing.png' else [
        ('portal_destination', 'iw*0.07:ih*0.06:iw*0.465:ih*0.26', 'red'),
        ('local_terrain', 'iw*0.1:ih*0.1:iw*0.05:ih*0.1', 'green')]
    result = {}
    for name, crop, color in crops:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError('Native-client lifetime exceeded during screenshot validation')
        raw = subprocess.run(['ffmpeg', '-loglevel', 'error', '-i', str(path), '-vf', f'crop={crop},scale=1:1',
                              '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-'], check=True, capture_output=True,
                             timeout=min(15, remaining)).stdout
        if len(raw) != 3 or not marker_color(tuple(raw), color):
            raise AssertionError(f'{path.name}: {name} is not the expected {color} terrain marker: {list(raw)}')
        result[name] = {'rgb': list(raw), 'crop': crop, 'expected': color}
    return result


def check_realm_image(path, deadline):
    """Natural terrain has no fixed marker colors; reject blank captures, retain actual RGB evidence."""
    data = path.read_bytes()
    if data[:8] != b'\x89PNG\r\n\x1a\n' or len(data) < 24:
        raise AssertionError(f'Missing actual realm framebuffer PNG: {path.name}')
    width, height = struct.unpack('>II', data[16:24])
    if width < 640 or height < 400:
        raise AssertionError('Realm framebuffer image is too small')
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        raise TimeoutError('Native-client lifetime exceeded during realm screenshot validation')
    raw = subprocess.run(['ffmpeg', '-loglevel', 'error', '-i', str(path), '-vf', 'scale=64:40',
                          '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-'], check=True, capture_output=True,
                         timeout=min(15, remaining)).stdout
    if len(raw) != 64 * 40 * 3:
        raise AssertionError('Incomplete decoded realm framebuffer')
    colors = {tuple(raw[index:index + 3]) for index in range(0, len(raw), 3)}
    mean = sum(raw) / len(raw)
    if len(colors) < 16 or mean < 8 or mean > 250:
        raise AssertionError(f'Blank/unusable realm framebuffer: {path.name}')
    return {'width': width, 'height': height, 'sampled_rgb_colors': len(colors), 'mean_rgb': mean,
            'validation': 'nonblank actual framebuffer; natural terrain/sky pixels are not a fixed-color oracle'}


def copy_shader_pack(source, directory):
    source = source.resolve(strict=True)
    if source in {Path.home().resolve(), ROOT, *ROOT.parents}:
        raise ValueError('Shader input must be one explicit pack, never a user/repository profile')
    if source.is_dir():
        if not (source / 'shaders').is_dir() or any(path.is_symlink() for path in source.rglob('*')):
            raise ValueError('Shader directory must contain shaders/ and no links outside the private copy')
    else:
        with zipfile.ZipFile(source) as pack:
            if not any(name.startswith('shaders/') for name in pack.namelist()):
                raise ValueError('Shader zip has no shaders/ root')
    packs = directory / 'shaderpacks'
    packs.mkdir()
    if source.is_dir():
        shutil.copytree(source, packs / source.name)
    else:
        shutil.copyfile(source, packs / source.name)
    (directory / 'config/iris.properties').write_text(f'enableShaders=true\nshaderPack={source.name}\n')


def validate_result(result, manifest, profile, log, realm=False):
    if (result.get('status') != 'RUNTIME_PASS' or result.get('physical_side') != 'CLIENT'
            or result.get('profile') != profile or result.get('login_events') != 1
            or result.get('actual_local_connection') is not True or result.get('rendered_frames', 0) < 30
            or result.get('server_published') is not False
            or result.get('normal_terrain_rebuild_requested') is not True
            or result.get('away_ticks_completed', 0) < 200):
        raise AssertionError('Incomplete actual single-client integrated-server qualification')
    for key in ['config_start', 'config_complete', 'dimension_sync', 'portal_sync']:
        if result.get(key, 0) <= 0:
            raise AssertionError(f'Actual protocol observation missing: {key}')
    if not realm and (result.get('prediction_dimensions') != ['minecraft:the_nether', 'minecraft:overworld']
            or result.get('server_teleport_requests', 0) < 2):
        raise AssertionError('Both dimension transitions must be authentic predicted portal crossings')
    artifacts = result.get('artifacts', {})
    for key, wanted in [('parent_path', manifest['path']), ('parent_sha256', manifest['sha256']),
                        ('dimlib_sha256', manifest['bundles']['dimlib']), ('cloth_sha256', manifest['bundles']['cloth'])]:
        if artifacts.get(key) != wanted:
            raise AssertionError(f'Actual loaded packaged-artifact evidence differs: {key}')
    mods = artifacts.get('optional_mods', [])
    if {mod['id'] for mod in mods} != PROFILES[profile] or len(mods) != len(PROFILES[profile]):
        raise AssertionError('Actual optional native mod set differs from the requested profile')
    for mod in mods:
        outer = Path(mod['outer_path'])
        if artifact_checks.sha256(outer.read_bytes()) != mod['outer_sha256']:
            raise AssertionError('An original optional runtime outer jar changed')
        if mod.get('loaded_sha256') != mod['outer_sha256']:
            with zipfile.ZipFile(outer) as jar:
                if not any(artifact_checks.sha256(jar.read(name)) == mod.get('loaded_sha256')
                           for name in jar.namelist() if name.startswith('META-INF/jarjar/') and name.endswith('.jar')):
                    raise AssertionError('Loaded optional native mod bytes do not belong to the original outer jar')
    if realm:
        validate_realm_result(result, log)
        return
    landings = result.get('landings', [])
    expected = [('source_ready', 'minecraft:overworld', -20), ('nether_landing', 'minecraft:the_nether', 100),
                ('overworld_return', 'minecraft:overworld', -20)]
    if len(landings) != 3:
        raise AssertionError('Missing source/crossing/return landing snapshots')
    for actual, (stage, dimension, y) in zip(landings, expected):
        if (actual.get('stage') != stage or actual.get('dimension') != dimension or actual.get('client_dimension') != dimension
                or actual.get('awaiting_teleport') is not False or actual.get('registered') is not True
                or abs(actual.get('server_y', 1e9) - y) >= 0.15 or abs(actual.get('client_y', 1e9) - y) >= 0.15
                or abs(actual.get('server_x', 1e9) - 0.5) >= 1 or abs(actual.get('server_z', 1e9) - 0.5) >= 1):
            raise AssertionError(f'Incorrect/unsettled actual client/server landing: {stage}')
    for marker in ['IP_NATIVE_CLIENT_BEGIN', 'IP_NATIVE_CLIENT_CROSSED minecraft:the_nether correct_landing=true',
                   'IP_NATIVE_CLIENT_RETURNED minecraft:overworld correct_landing=true', 'IP_NATIVE_CLIENT_DONE status=RUNTIME_PASS']:
        if marker not in log:
            raise AssertionError(f'Missing positive real-client log marker: {marker}')
    if smoke.FATAL_LOG.search(log) or NATIVE_LOADING_FAILURE.search(log) or 'IP_NATIVE_CLIENT_FAIL' in log or 'IP_NATIVE_CLIENT_REPORT_FAIL' in log:
        raise AssertionError('Native client logged a fatal error')


def validate_realm_result(result, log):
    if result.get('scenario') != 'riftwoven_realm':
        raise AssertionError('Requested realm scenario did not run')
    evidence = result.get('realm', {})
    dimension = evidence.get('dimension', '')
    if not re.fullmatch(r'riftwoven:realm_[0-9a-f]{32}', dimension):
        raise AssertionError('Missing actual ledger realm dimension')
    for key in ['formation_valid', 'survival_player', 'offering_refund_reoffering', 'saved_idempotent_retry', 'world_saved_before_retry']:
        if evidence.get(key) is not True:
            raise AssertionError(f'Missing actual realm ledger/core evidence: {key}')
    if (evidence.get('unpaid_worlds') != 3 or evidence.get('realm_count') != 1
            or evidence.get('diamonds_before') != 8 or evidence.get('diamonds_after') != 4
            or evidence.get('actual_level_seed') != evidence.get('realm_seed')
            or not isinstance(evidence.get('realm_seed'), int) or evidence['realm_seed'] == evidence.get('overworld_seed')
            or not 128 <= evidence.get('radius', 0) <= 1024
            or evidence.get('border_size') != evidence['radius'] * 2
            or evidence.get('suns') != 2 or evidence.get('moons') != 1
            or not evidence.get('terrain_sample', {}).get('state')):
        raise AssertionError('Actual realm payment, independent seed, finite border or recipe differs')
    for key in ['realm_private_portal_received', 'realm_generated_chunk_received', 'realm_environment_before_entry',
                'realm_entry_predicted', 'realm_entry_ack_settled', 'realm_camera_same_dimension_reposition',
                'realm_clock_advanced', 'realm_photo_weather_clear', 'realm_return_ack_settled', 'realm_departure_retained_until_ack',
                'realm_departure_cleared_after_ack', 'realm_overworld_environment_restored', 'realm_mixin_audit_invoked']:
        if result.get(key) is not True:
            raise AssertionError(f'Missing actual realm-client evidence: {key}')
    if (result.get('realm_original_prediction_dimensions') != [dimension]
            or result.get('prediction_dimensions') != [dimension, 'ip_native_client_test:terrain_replay']
            or result.get('server_teleport_requests', 0) < 2
            or result.get('realm_remote_sky_extractions', 0) < 1 or result.get('realm_sky_extractions', 0) < 1
            or result.get('realm_two_sun_draws', 0) < 1):
        raise AssertionError('Realm requires its own genuine predicted entry and actual remote/inside sky draws')
    weather = result.get('realm_photo_weather', {})
    if (any(weather.get(key) != 0 for key in ['server_rain', 'server_thunder', 'client_rain', 'client_thunder'])
            or weather.get('clear_ticks', 0) < 60):
        raise AssertionError('Realm sky photograph was not taken after actual clear-weather fade and fresh draws')
    for key in ['realm_preview_clock', 'realm_inside_clock', 'realm_final_clock']:
        clock = result.get(key, {})
        if (clock.get('suns') != 2 or clock.get('moons') != 1
                or not all(isinstance(clock.get(field), (int, float)) for field in [
                    'server_clock_ticks', 'client_clock_ticks', 'server_rate', 'client_rate', 'server_physical_ticks', 'overworld_clock_ticks'])
                or abs(clock['server_clock_ticks'] - clock['client_clock_ticks']) > 80
                or abs(clock['server_rate'] - clock['client_rate']) > 0.0001
                or abs(clock['server_clock_ticks'] - clock['overworld_clock_ticks']) < 100):
            raise AssertionError(f'Missing/wrong actual dimension-scoped realm clock: {key}')
    if (result['realm_final_clock']['server_physical_ticks'] <= result['realm_preview_clock']['server_physical_ticks']
            or result['realm_final_clock']['client_clock_ticks'] <= result['realm_preview_clock']['client_clock_ticks']):
        raise AssertionError('Actual realm clocks did not advance')
    expected = [('realm_source_ready', 'minecraft:overworld'), ('realm_landing', dimension),
                ('realm_terrain_camera', dimension),
                ('realm_overworld_return', 'minecraft:overworld')]
    landings = result.get('landings', [])
    if len(landings) != len(expected):
        raise AssertionError('Missing actual realm source/entry/terrain-camera/return landing snapshots')
    for actual, (stage, dim) in zip(landings, expected):
        if (actual.get('stage') != stage or actual.get('dimension') != dim or actual.get('client_dimension') != dim
                or actual.get('awaiting_teleport') is not False or actual.get('registered') is not True
                or not all(isinstance(actual.get(f'{side}_{axis}'), (int, float)) for side in ['server', 'client'] for axis in 'xyz')
                or sum((actual[f'server_{axis}'] - actual[f'client_{axis}']) ** 2 for axis in 'xyz') >= 0.25):
            raise AssertionError(f'Incorrect/unsettled actual realm client/server landing: {stage}')
        if dim == 'minecraft:overworld' and sum((actual[f'server_{axis}'] - value) ** 2
                                               for axis, value in zip('xyz', [0.5, 101, 3.5])) >= 0.25:
            raise AssertionError(f'Wrong recorded safe realm departure/return position: {stage}')
        if stage == 'realm_terrain_camera':
            camera = result.get('realm_selected_camera', {})
            if (not all(isinstance(camera.get(axis), (int, float)) for axis in 'xyz')
                    or sum((actual[f'server_{axis}'] - camera[axis]) ** 2 for axis in 'xyz') >= 0.25):
                raise AssertionError('Actual realm terrain-camera landing differs from selected natural terrain site')
    validate_photo_readiness(result.get('realm_terrain_main_draw', {}), dimension,
                             result.get('realm_selected_camera', {}), result.get('realm_terrain_readiness', {}),
                             result.get('realm_terrain_ready_consecutive_frames', 0))
    server_sample, client_sample = result.get('realm_terrain_server', {}), result.get('realm_terrain_client', {})
    for sample in [server_sample, client_sample]:
        if (sample.get('dimension') != dimension or sample.get('position') != result.get('realm_selected_camera')
                or sample.get('floor_sturdy') is not True or sample.get('feet') != 'Block{minecraft:air}'
                or sample.get('head') != 'Block{minecraft:air}'):
            raise AssertionError('Actual main terrain camera/ground sample is not settled/supported')
    if server_sample.get('floor') != client_sample.get('floor'):
        raise AssertionError('Actual main terrain client/server ground differs')
    for marker in ['IP_NATIVE_CLIENT_BEGIN', f'IP_NATIVE_REALM_CROSSED {dimension} awaiting_teleport=false',
                   'IP_NATIVE_REALM_RETURNED minecraft:overworld awaiting_teleport=false', 'IP_NATIVE_CLIENT_DONE status=RUNTIME_PASS']:
        if marker not in log:
            raise AssertionError(f'Missing positive realm-client log marker: {marker}')
    if (smoke.FATAL_LOG.search(log) or NATIVE_LOADING_FAILURE.search(log) or MIXIN_AUDIT_FAILURE.search(log)
            or 'IP_NATIVE_CLIENT_FAIL' in log or 'IP_NATIVE_CLIENT_REPORT_FAIL' in log):
        raise AssertionError('Native realm client logged a fatal/mixin audit error')
    validate_terrain_replay(result.get('terrain_replay', {}))


def validate_terrain_replay(replay):
    dimension = 'ip_native_client_test:terrain_replay'
    camera = {'x': 24.5, 'y': 68, 'z': 24.5}
    if (replay.get('status') != 'DIAGNOSTIC_CAPTURED' or replay.get('dimension') != dimension
            or replay.get('seed') != -8484714376220950612 or replay.get('actual_level_seed') != replay.get('seed')
            or replay.get('camera') != camera or replay.get('yaw') != 135 or replay.get('pitch') != 12
            or not replay.get('coverage')):
        raise AssertionError('Missing/wrong supplemental original-seed terrain replay')
    for key in ['metadata_sent_before_chunk_requests', 'client_metadata_before_predicted_entry', 'ordinary_entry_predicted',
                'arrival_geometry_copied_without_identity', 'natural_floor_matches', 'safe_return_ack_settled']:
        if replay.get(key) is not True:
            raise AssertionError(f'Missing supplemental terrain replay evidence: {key}')
    if replay.get('entry_server_requests', 0) < 1:
        raise AssertionError('Supplemental ordinary portal replay has no actual server entry request')
    if replay.get('clear_ticks', 0) < 60 or not replay.get('natural_floor', '').startswith('Block{minecraft:grass_block}'):
        raise AssertionError('Replay did not preserve the original natural grass site/normal clear-weather fade')
    for side in ['server', 'client']:
        sample = replay.get(side, {})
        if (sample.get('dimension') != dimension or sample.get('position') != camera
                or sample.get('feet') != 'Block{minecraft:air}' or sample.get('head') != 'Block{minecraft:air}'
                or not sample.get('floor', '').startswith('Block{minecraft:grass_block}') or sample.get('floor_sturdy') is not True):
            raise AssertionError(f'Wrong replay actual natural camera/ground: {side}')
    if replay['server'].get('rain') != 0 or replay['server'].get('thunder') != 0:
        raise AssertionError('Replay weather did not clear normally')
    readiness = replay.get('render_readiness', {})
    if any(readiness.get(key) is not True for key in ['camera_matches', 'orientation_matches', 'unclipped_main_view', 'ground_mesh_ready', 'ready']):
        raise AssertionError('Replay main-view camera/clipping/ground mesh is not ready')
    draw = replay.get('main_draw', {})
    if (draw.get('portal_layer') != 0 or draw.get('clip_plane') != [0, 0, 0, 0]
            or not isinstance(draw.get('view_determinant'), (int, float)) or not 0.99 < draw['view_determinant'] < 1.01):
        raise AssertionError('Replay main view has wrong clip or reflected camera state')
    validate_photo_readiness(draw, dimension, camera, readiness, replay.get('ready_consecutive_frames', 0))
    landings = replay.get('landings', [])
    expected = [('diagnostic_portal_arrival', dimension, None), ('diagnostic_arrival', dimension, camera),
                ('diagnostic_safe_return', 'minecraft:overworld', {'x': 0.5, 'y': 101, 'z': 3.5})]
    if len(landings) != 3:
        raise AssertionError('Missing separate replay arrival/safe-return snapshots')
    for actual, (stage, dim, position) in zip(landings, expected):
        if (actual.get('stage') != stage or actual.get('server_dimension') != dim or actual.get('client_dimension') != dim
                or actual.get('awaiting_teleport') is not False or actual.get('registered') is not True
                or any(not all(isinstance(actual.get(f'{side}_position', {}).get(axis), (int, float)) for axis in 'xyz')
                       or (position is not None and sum((actual[f'{side}_position'][axis] - position[axis]) ** 2 for axis in 'xyz') >= 0.25)
                       for side in ['server', 'client'])):
            raise AssertionError(f'Incorrect/unsettled separate replay landing: {stage}')
        if sum((actual['server_position'][axis] - actual['client_position'][axis]) ** 2 for axis in 'xyz') >= 0.25:
            raise AssertionError(f'Mismatched separate replay landing: {stage}')


def validate_photo_readiness(draw, dimension, camera, readiness, consecutive):
    required = ['camera_matches', 'orientation_matches', 'unclipped_main_view', 'ground_mesh_ready',
                'dimension_matches', 'neighbors_loaded_and_lit', 'ground_supported', 'foreground_meshes_ready',
                'compile_queue_empty', 'ready']
    if (any(readiness.get(key) is not True for key in required) or consecutive < 5
            or draw.get('ready_consecutive_frames') != consecutive or draw.get('draw_sequence', 0) < 1
            or draw.get('render_frame_identity', 0) < 1
            or draw.get('capture_frame_identity') != draw.get('render_frame_identity')
            or draw.get('dimension') != dimension):
        raise AssertionError('Terrain photo lacks fresh consecutive same-view mesh/light readiness')
    observed = draw.get('camera', {})
    if (not all(isinstance(camera.get(axis), (int, float)) and isinstance(observed.get(axis), (int, float)) for axis in 'xyz')
            or any(abs(observed[axis] - camera[axis] - (1.62 if axis == 'y' else 0)) > 0.01 for axis in 'xyz')
            or draw.get('yaw') != 135 or draw.get('pitch') != 12
            or draw.get('portal_layer') != 0 or draw.get('clip_plane') != [0, 0, 0, 0]):
        raise AssertionError('Terrain photo actual draw has wrong camera/orientation/clip')
    floor = (int(camera['x']) // 16, (int(camera['y']) - 1) // 16, int(camera['z']) // 16)
    if draw.get('sodium_geometry', -1) >= 0:
        evidence = draw.get('sodium_section_evidence', {})
        sections = evidence.get('sections', [])
        ground = next((section for section in sections if tuple(section.get(axis) for axis in 'xyz') == floor), {})
        expected_cells = {(floor[0] + dx, floor[1] + dy, floor[2] + dz)
                          for dx in [-1, 0, 1] for dy in [-1, 0, 1] for dz in [-1, 0, 1]}
        if (evidence.get('verified') is not True or draw.get('sodium_pending') != 0
                or ground.get('built') is not True or ground.get('drawn_geometry') is not True
                or ground.get('solid_allocation_length', 0) <= 0
                or len(sections) != 27 or {tuple(section.get(axis) for axis in 'xyz') for section in sections} != expected_cells
                or any(not all(isinstance(section.get(key), bool) for key in ['empty_air', 'tree_present', 'in_frustum', 'built'])
                       or (not section['empty_air'] and section['tree_present'] and section['in_frustum'] and not section['built'])
                       for section in sections)):
            raise AssertionError('Terrain photo Sodium camera-floor/foreground geometry is not ready')
    else:
        sections = draw.get('visible_sections', [])
        ground = next((section for section in sections if tuple(section.get(axis) for axis in 'xyz') == floor), {})
        nearby = [section for section in sections if all(isinstance(section.get(axis), int)
                  and abs(section[axis] - value) <= 1 for axis, value in zip('xyz', floor))]
        if (draw.get('compile_queue_empty') is not True or ground.get('uncompiled') is not False
                or ground.get('solid_indices', 0) <= 0 or not nearby
                or any(not section.get('empty_air') and section.get('uncompiled') is not False for section in nearby)):
            raise AssertionError('Terrain photo camera-floor/foreground meshes or compile queue are not ready')


def supervise(command, environment, directory, report, wrapper, nonce, deadline, realm=False):
    if time.monotonic() >= deadline:
        raise TimeoutError('Native-client lifetime expired before dispatch')
    # Mandatory at the actual dispatch too; never trust only an environment marker.
    wrapper.verify_owned_display()
    with launch_process(command, lambda process: terminate(process, directory, Path(environment['JAVA_HOME'])),
                        cwd=MODULE, env=environment, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                        text=True, bufsize=1, start_new_session=True) as process:
        return observe_client(process, report, wrapper, deadline, realm=realm)


def observe_client(process, report, wrapper, deadline, realm=False):
    lines = queue.Queue(maxsize=512)
    def read():
        try:
            for line in process.stdout:
                lines.put(line)
        finally:
            lines.put(None)
    threading.Thread(target=read, daemon=True).start()
    images = {}
    fatal_line = None
    fatal_deadline = None
    expected_images = REALM_IMAGES if realm else IMAGES
    with (report / 'client.log').open('w') as output:
        while True:
            remaining = min(deadline, fatal_deadline or deadline) - time.monotonic()
            if remaining <= 0:
                if fatal_line is not None:
                    raise RuntimeError(f'Fail-fast native client output (bounded cause drain retained): {fatal_line}')
                raise TimeoutError('Native-client whole build/game lifetime exceeded 900s')
            try:
                line = lines.get(timeout=min(1, remaining))
            except queue.Empty:
                wrapper.verify_owned_display()
                continue
            if line is None:
                break
            output.write(line)
            output.flush()
            print(line, end='', flush=True)
            if (smoke.FATAL_LOG.search(line) or NATIVE_LOADING_FAILURE.search(line) or 'IP_NATIVE_CLIENT_FAIL' in line or 'IP_NATIVE_CLIENT_REPORT_FAIL' in line
                    or (realm and MIXIN_AUDIT_FAILURE.search(line))):
                if fatal_line is None:
                    fatal_line = line.strip()[:300]
                    # Java loggers flush stack/cause lines after the headline.
                    # Latch failure permanently, but keep a short drain inside
                    # the original lifetime before terminating owned processes.
                    fatal_deadline = min(deadline, time.monotonic() + 3)
            if fatal_line is None and 'IP_NATIVE_CLIENT_SCREENSHOT ' in line:
                name = line.split('IP_NATIVE_CLIENT_SCREENSHOT ', 1)[1].strip()
                if name not in expected_images or name in images:
                    raise AssertionError('Unexpected/duplicate framebuffer capture marker')
                images[name] = (check_realm_image if realm else check_image)(report / name, deadline)
    if fatal_line is not None:
        raise RuntimeError(f'Fail-fast native client output (cause retained): {fatal_line}')
    code = process.wait(timeout=max(0.1, deadline - time.monotonic()))
    if code != 0:
        raise RuntimeError(f'Native client/build exited with {code}')
    if set(images) != expected_images:
        raise AssertionError('Not all actual framebuffer captures passed')
    return images


def run(args, wrapper, deadline):
    manifest = artifact_checks.validate_artifact(args.artifact.resolve())
    with zipfile.ZipFile(manifest['path']) as parent:
        if any('client_test/' in name for name in parent.namelist()):
            raise ValueError('The development real-client harness must not be shipped inside the parent mod')
    # Existing artifact validator also rejects stale production sources/bundles.
    if shutil.which('ffmpeg') is None:
        raise ValueError('Existing ffmpeg is required for strict screenshot crops; nothing is installed automatically')
    report = Path(tempfile.mkdtemp(prefix='ip-native-client-report-', dir=SCRATCH))
    summary = {'status': 'FAIL', 'artifact': manifest, 'profile': args.profile, 'world_removed': False,
               'limitations': ['One real physical client with one integrated-server player, using actual local in-memory transport.',
                               'Not remote/TCP or two-player networking, dedicated-server client admission, multiplayer tracking, or a performance benchmark.',
                                'Return terrain is checked after the normal rebuild API; idle renderer eviction itself is not asserted.']}
    if args.realm:
        summary['scenario'] = 'riftwoven_realm'
        summary['limitations'] = [
            'One genuine survival client and integrated server with actual native local in-memory networking, not TCP/multiplayer.',
            'Formation and inventory are fixtures; public core/ledger APIs perform real offering/refund/payment. The doorway is manually configured with public RealmPortal/departure APIs, not the physical hold ritual.',
            'Only entry is predicted. Return invokes the permanent core public API and maintained travel admission/rollback, with normal client ACK settlement.',
            'Terrain camera uses an explicit same-dimension reposition after genuine entry. Realm screenshots reject blank frames; they do not count visible celestial pixels or replace the baseline fixed-color oracle.',
            'Saved journal reopen/idempotent retry and world save are checked, not process restart/reconnect. Mixin audit diagnoses application, not every gameplay path or absent optional mods.',
            'Normal return terrain rebuild is requested; renderer eviction and performance budgets are not asserted.']
        summary['limitations'].append('Supplemental frozen-seed terrain replay is an admin-created diagnostic dimension with ordinary predicted Portal entry and general PortalAPI safe return, not a paid identity/private RealmPortal admission. It occurs later in the JVM than the original first entry. Its image and mesh readiness still require visual inspection; a nonblank image is not terrain-quality proof.')
    directory = None
    environment = None
    try:
        java_home = Path(os.environ.get('JAVA_HOME', str(Path.home() / '.cache/immersive-portals/toolchains/jdk-25')))
        with fresh_world(java_home) as directory:
            nonce = secrets.token_hex(24)
            (directory / MARKER).write_text(nonce + '\n')
            (report / MARKER).write_text(nonce + '\n')
            (directory / 'eula.txt').write_text('eula=true\n')
            environment = child_environment(directory)
            environment['IP_NATIVE_CLIENT_NONCE'] = nonce
            java = Path(environment['JAVA_HOME'])
            validate_java(java)
            (directory / 'options.txt').write_text(smoke.CLIENT_OPTIONS.format(render_distance=4, max_fps=30))
            config = directory / 'config'
            (config / 'immersive_portals.json').write_text(json.dumps({'initialScreenShown': True, 'initialScreenVersion': 1}))
            if args.shader_pack is not None:
                copy_shader_pack(args.shader_pack, directory)
            try:
                # Verify using the exact child environment, including null audio.
                original = os.environ.copy()
                try:
                    os.environ.update(environment)
                    verify_dispatch(wrapper, directory, nonce)
                finally:
                    os.environ.clear()
                    os.environ.update(original)
                images = supervise(gradle_command(args, directory, report, manifest, nonce, java), environment,
                                   directory, report, wrapper, nonce, deadline, realm=args.realm)
                result = json.loads((report / 'runtime-results.json').read_text())
                log = (report / 'client.log').read_text(errors='replace')
                validate_result(result, manifest, args.profile, log, realm=args.realm)
                if result['away_ticks_completed'] < args.away_ticks or (args.shader_pack is not None and result.get('shader_pack_active') is not True):
                    raise AssertionError('Requested idle/shader conditions were not exercised')
                if any((directory / 'crash-reports').glob('*')):
                    raise AssertionError('Native client wrote a crash report')
                if artifact_checks.sha256(args.artifact.read_bytes()) != manifest['sha256']:
                    raise AssertionError('Parent jar changed while qualifying')
                summary.update(status='PASS_REALM_SINGLE_CLIENT_LOCAL' if args.realm else 'PASS_SINGLE_CLIENT_LOCAL',
                               screenshots=images, runtime_report=str(report / 'runtime-results.json'))
            finally:
                terminate(None, directory, java)
                for name in ['logs', 'crash-reports']:
                    if (directory / name).is_dir():
                        shutil.copytree(directory / name, report / name, dirs_exist_ok=True)
        summary['world_removed'] = True
    except BaseException as failure:
        summary['status'] = 'FAIL'
        summary['failure'] = str(failure)
        raise
    finally:
        if directory is not None:
            summary['world_removed'] = not directory.exists()
        (report / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
        print(f'Native real-client status: {summary["status"]}; retained report: {report}', flush=True)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--accept-eula', action='store_true')
    parser.add_argument('--display-wrapper', type=Path, default=os.environ.get('IP_NATIVE_DISPLAY_WRAPPER'))
    parser.add_argument('--artifact', type=Path, default=MODULE.parent / 'build/libs/riftwoven-7.1.0-dev-mc26.3-neoforge.jar')
    parser.add_argument('--realm', action='store_true', help='Optional genuine survival realm scenario; baseline Nether qualification remains the default')
    parser.add_argument('--profile', choices=PROFILES, default='vanilla')
    parser.add_argument('--shader-pack', type=Path)
    parser.add_argument('--away-ticks', type=int, default=2600, help='Stay in the destination before returning (200..4000; default 130 seconds)')
    parser.add_argument('--offline', action='store_true', help='Require existing Gradle/asset/mod caches')
    parser.add_argument('--verify-dispatch', action='store_true', help=argparse.SUPPRESS)
    parser.add_argument('--run-dir', type=Path, help=argparse.SUPPRESS)
    parser.add_argument('--nonce', default='', help=argparse.SUPPRESS)
    args = parser.parse_args(argv)
    if not args.accept_eula:
        parser.error('Explicit --accept-eula is required for this new local disposable world: https://aka.ms/MinecraftEULA')
    if not 200 <= args.away_ticks <= 4000:
        parser.error('--away-ticks must be 200..4000')
    if args.shader_pack and args.profile != 'performance-iris':
        parser.error('--shader-pack requires --profile performance-iris')
    if args.display_wrapper is not None:
        args.display_wrapper = Path(args.display_wrapper)
    wrapper = display_module(args.display_wrapper)
    if args.verify_dispatch:
        if args.run_dir is None:
            parser.error('--verify-dispatch requires --run-dir')
        verify_dispatch(wrapper, args.run_dir, args.nonce)
        return 0
    deadline = min(float(os.environ.get('IP_NATIVE_CLIENT_DEADLINE', 'inf')), time.monotonic() + 900)
    if time.monotonic() >= deadline:
        raise TimeoutError('Native-client whole-run deadline has expired')
    if not callable(getattr(os, 'pidfd_open', None)) or not callable(getattr(signal, 'pidfd_send_signal', None)):
        raise RuntimeError('PID-handle process ownership is required for safe native-client cleanup')
    # Install cancellation before launching even the outside-display wrapper.
    # Its separate session must still be stopped when this outer launcher exits.
    with cancellation_handlers():
        if not os.environ.get('OPENCHAMBER_PARITY_DISPLAY'):
            environment = os.environ.copy()
            environment['IP_NATIVE_CLIENT_DEADLINE'] = str(deadline)
            command = [sys.executable, str(args.display_wrapper.resolve()), '--width', '1280', '--height', '800', '--',
                       sys.executable, str(Path(__file__).resolve()), *(sys.argv[1:] if argv is None else argv)]
            with launch_process(command, stop_wrapper, env=environment, start_new_session=True) as process:
                return process.wait(timeout=max(0.1, deadline - time.monotonic()))
        wrapper.verify_owned_display()
        run(args, wrapper, deadline)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
