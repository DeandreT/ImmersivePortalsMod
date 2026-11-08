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


def validate_result(result, manifest, profile, log):
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
    if (result.get('prediction_dimensions') != ['minecraft:the_nether', 'minecraft:overworld']
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
    if smoke.FATAL_LOG.search(log) or 'IP_NATIVE_CLIENT_FAIL' in log or 'IP_NATIVE_CLIENT_REPORT_FAIL' in log:
        raise AssertionError('Native client logged a fatal error')


def supervise(command, environment, directory, report, wrapper, nonce, deadline):
    if time.monotonic() >= deadline:
        raise TimeoutError('Native-client lifetime expired before dispatch')
    # Mandatory at the actual dispatch too; never trust only an environment marker.
    wrapper.verify_owned_display()
    with launch_process(command, lambda process: terminate(process, directory, Path(environment['JAVA_HOME'])),
                        cwd=MODULE, env=environment, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                        text=True, bufsize=1, start_new_session=True) as process:
        return observe_client(process, report, wrapper, deadline)


def observe_client(process, report, wrapper, deadline):
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
            if smoke.FATAL_LOG.search(line) or 'IP_NATIVE_CLIENT_FAIL' in line or 'IP_NATIVE_CLIENT_REPORT_FAIL' in line:
                if fatal_line is None:
                    fatal_line = line.strip()[:300]
                    # Java loggers flush stack/cause lines after the headline.
                    # Latch failure permanently, but keep a short drain inside
                    # the original lifetime before terminating owned processes.
                    fatal_deadline = min(deadline, time.monotonic() + 3)
            if fatal_line is None and 'IP_NATIVE_CLIENT_SCREENSHOT ' in line:
                name = line.split('IP_NATIVE_CLIENT_SCREENSHOT ', 1)[1].strip()
                if name not in IMAGES or name in images:
                    raise AssertionError('Unexpected/duplicate framebuffer capture marker')
                images[name] = check_image(report / name, deadline)
    if fatal_line is not None:
        raise RuntimeError(f'Fail-fast native client output (cause retained): {fatal_line}')
    code = process.wait(timeout=max(0.1, deadline - time.monotonic()))
    if code != 0:
        raise RuntimeError(f'Native client/build exited with {code}')
    if set(images) != IMAGES:
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
                                   directory, report, wrapper, nonce, deadline)
                result = json.loads((report / 'runtime-results.json').read_text())
                log = (report / 'client.log').read_text(errors='replace')
                validate_result(result, manifest, args.profile, log)
                if result['away_ticks_completed'] < args.away_ticks or (args.shader_pack is not None and result.get('shader_pack_active') is not True):
                    raise AssertionError('Requested idle/shader conditions were not exercised')
                if any((directory / 'crash-reports').glob('*')):
                    raise AssertionError('Native client wrote a crash report')
                if artifact_checks.sha256(args.artifact.read_bytes()) != manifest['sha256']:
                    raise AssertionError('Parent jar changed while qualifying')
                summary.update(status='PASS_SINGLE_CLIENT_LOCAL', screenshots=images, runtime_report=str(report / 'runtime-results.json'))
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
    parser.add_argument('--artifact', type=Path, default=MODULE.parent / 'build/libs/immersive-portals-7.0.1-dev-mc26.3-neoforge.jar')
    parser.add_argument('--profile', choices=PROFILES, default='vanilla')
    parser.add_argument('--shader-pack', type=Path)
    parser.add_argument('--away-ticks', type=int, default=2600, help='Stay in Nether before returning (200..4000; default 130 seconds)')
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
