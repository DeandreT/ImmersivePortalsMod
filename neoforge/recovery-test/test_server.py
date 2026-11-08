#!/usr/bin/env python3
"""Run only the native recovery harness on a new owned loopback world, retaining results."""

import argparse
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import secrets
import select
import shutil
import signal
import socket
import subprocess
import tempfile
import time
import tomllib
import zipfile


MODULE = Path(__file__).resolve().parent
ROOT = MODULE.parent.parent
SCRATCH = Path('/tmp/opencode')
PROC = Path('/proc')
MARKER = '.ip-native-recovery-owned'
LEASE = '.ip-native-recovery-gradle.json'


def expected_case_names():
    names = {'packaged_mod_dedicated_boot', 'forced_mount_event', 'concrete_two_player_packets',
             'preexisting_candidate_false', 'preexisting_candidate_true'}
    for recreate in ['false', 'true']:
        for index in [0, 2, 3]:
            names.add(f'join_cancel_{recreate}_{index}')
            names.add(f'after_added_throw_{recreate}_{index}')
        for mode in ['dismount_cancel', 'mount_cancel']:
            names.add(f'{mode}_{recreate}')
        for same_world in ['false', 'true']:
            names.add(f'travel_cancel_retry_{recreate}_same_{same_world}')
            names.add(f'hidden_foreign_{recreate}_uuid_{same_world}')
        names.add(f'villager_brain_{recreate}')
    return names


def validate_cold_client_initializers(log):
    cold = {
        'qouteall/imm_ptl/neoforge/client/ImmersivePortalsNeoForgeClient',
        'qouteall/imm_ptl/core/platform_specific/IPModEntryClient',
        'qouteall/imm_ptl/core/IPCGlobal',
        'qouteall/imm_ptl/core/ClientWorldLoader',
        'qouteall/imm_ptl/core/portal/PortalRenderInfo',
        'qouteall/imm_ptl/core/teleportation/ClientTeleportationManager',
        'qouteall/imm_ptl/peripheral/wand/PortalWandItemClient',
        'qouteall/imm_ptl/core/platform_specific/ClientPlatformOperations',
        'qouteall/imm_ptl/core/network/ImmPtlNetworkingClient',
        'qouteall/imm_ptl/core/portal/global_portals/GlobalPortalStorageClient',
        'qouteall/imm_ptl/core/collision/CollisionHelperClient',
        'qouteall/imm_ptl/core/ScaleUtilsClient',
    }
    initialized = {line.split("Initializing '", 1)[1].split("'", 1)[0].replace('.', '/')
                   for line in log.splitlines() if "Initializing '" in line}
    if not initialized:
        raise AssertionError('JVM class-initialization evidence is missing; annotation metadata is not side-safety proof')
    hot = cold & initialized
    if hot:
        raise AssertionError(f'Dedicated server initialized client entrypoints/core statics: {sorted(hot)}')
    return sorted(cold)


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def validate_artifact(artifact):
    """Require a packaged native mod and its own complete, current native bundles."""
    if not artifact.is_file():
        raise ValueError('Build the parent native artifact before running this harness')
    sources = [ROOT / 'src/main', ROOT / 'neoforge/src/main', ROOT / 'dimlib/src/main',
               ROOT / 'dimlib/neoforge/src/main']
    inputs = [ROOT / 'gradle.properties', ROOT / 'neoforge/build.gradle']
    for source in sources:
        inputs.extend(p for p in source.rglob('*') if p.is_file())
    if any(p.stat().st_mtime_ns > artifact.stat().st_mtime_ns for p in inputs):
        raise ValueError('Parent jar is older than production inputs; refresh buildAll first in the assigned build slot')
    with zipfile.ZipFile(artifact) as jar:
        metadata = tomllib.loads(jar.read('META-INF/neoforge.mods.toml').decode())
        mods = {mod['modId']: str(mod['version']) for mod in metadata['mods']}
        if mods.get('immersive_portals') != '7.0.1-dev' or 'fabric.mod.json' in jar.namelist():
            raise ValueError('Expected the native packaged parent version 7.0.1-dev, without Fabric metadata')
        if any('recovery_test/' in name for name in jar.namelist()):
            raise ValueError('The development harness must not be packaged in the release mod')
        bundled = json.loads(jar.read('META-INF/jarjar/metadata.json'))['jars']
        hashes = {}
        for name, group, artifact_name in [
            ('dimlib', 'qouteall.dimlib', 'dimlib-neoforge'),
            ('cloth', 'me.shedaniel.cloth', 'cloth-config-neoforge'),
        ]:
            matches = [item['path'] for item in bundled if item['identifier'] == {
                'group': group, 'artifact': artifact_name}]
            if len(matches) != 1:
                raise ValueError(f'Missing or ambiguous native bundle: {artifact_name}')
            hashes[name] = sha256(jar.read(matches[0]))
        dimlib = ROOT / 'dimlib/neoforge/build/libs/dimlib-neoforge-1.1.0+mc26.3.jar'
        if not dimlib.is_file() or hashes['dimlib'] != sha256(dimlib.read_bytes()):
            raise ValueError('The parent jar does not bundle the current native DimLib artifact')
    return {'path': str(artifact), 'sha256': sha256(artifact.read_bytes()), 'bundles': hashes}


def free_loopback_port():
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0))
        return listener.getsockname()[1]


def process_start(process):
    return (process / 'stat').read_text().rsplit(')', 1)[1].split()[19]


def gradle_lease(directory):
    try:
        lease = json.loads((directory / LEASE).read_text())
        nonce = (directory / MARKER).read_text().strip()
        if len(nonce) >= 32 and lease['nonce'] == nonce:
            return int(lease['pid']), lease['start']
    except (OSError, ValueError, KeyError, TypeError):
        pass
    return None


def has_owned_environment(pid, directory):
    try:
        nonce = (directory / MARKER).read_text().strip()
        wanted = f'IP_NATIVE_RECOVERY_NONCE={nonce}'.encode()
        return len(nonce) >= 32 and wanted in (PROC / str(pid) / 'environ').read_bytes().split(b'\0')
    except OSError:
        return False


def owned_java_identity(pid, directory, java_home, expected=None):
    """Read ownership between matching start identities; callers must also pin the PID."""
    try:
        process = PROC / str(pid)
        start = process_start(process)
        if expected is not None and start != expected:
            return None
        if (process.stat().st_uid != os.getuid()
                or (process / 'exe').resolve() != (Path(java_home) / 'bin/java').resolve()):
            return None
        owned = ((process / 'cwd').resolve() == directory
                 or gradle_lease(directory) == (pid, start)
                 or has_owned_environment(pid, directory))
        if owned and process_start(process) == start:
            return start
    except (OSError, ValueError, IndexError, RuntimeError):
        pass
    return None


def pidfd_alive(descriptor):
    poller = select.poll()
    poller.register(descriptor, select.POLLIN)
    return not poller.poll(0)


def require_pidfds():
    """Probe the actual kernel/permission support before any child can start."""
    if not callable(getattr(os, 'pidfd_open', None)) or not callable(getattr(signal, 'pidfd_send_signal', None)):
        raise RuntimeError('PID-handle process ownership is required for safe native recovery cleanup')
    try:
        descriptor = os.pidfd_open(os.getpid())
        try:
            signal.pidfd_send_signal(descriptor, 0)
        finally:
            os.close(descriptor)
    except OSError as error:
        raise RuntimeError('PID-handle process ownership is unavailable; refusing to launch') from error


def owned_java_processes(directory, java_home):
    """Identify only this disposable world's JVM, even if Gradle detached its daemon."""
    if directory is None:
        return {}
    found = {}
    candidates = {int(entry.name) for entry in PROC.iterdir() if entry.name.isdecimal()}
    lease = gradle_lease(directory)
    if lease is not None:
        candidates.add(lease[0])
    for pid in candidates:
        try:
            descriptor = os.pidfd_open(pid)
        except ProcessLookupError:
            continue
        try:
            identity = owned_java_identity(pid, directory, java_home)
            # A dead handle also rejects PID reuse within one kernel clock tick,
            # where two different processes could have the same /proc start text.
            if identity is not None and pidfd_alive(descriptor):
                found[pid] = identity
        finally:
            os.close(descriptor)
    return found


def signal_owned_java(pid, identity, directory, java_home, sig):
    try:
        descriptor = os.pidfd_open(pid)
    except ProcessLookupError:
        return
    try:
        # Revalidate exact Java/uid/start and cwd, saved Gradle lease or inherited
        # nonce while the handle is pinned. Never infer a detached process's group.
        if (owned_java_identity(pid, directory, java_home, identity) == identity
                and pidfd_alive(descriptor)):
            try:
                signal.pidfd_send_signal(descriptor, sig)
            except ProcessLookupError:
                pass
    finally:
        os.close(descriptor)


def terminate_owned(process, directory, java_home):
    if process is not None and process.poll() is None:
        try:
            descriptor = os.pidfd_open(process.pid)
        except ProcessLookupError:
            descriptor = None
        try:
            for sig, timeout in [(signal.SIGTERM, 20), (signal.SIGKILL, 10)]:
                if descriptor is not None and process.poll() is None:
                    try:
                        signal.pidfd_send_signal(descriptor, sig)
                    except ProcessLookupError:
                        pass
                try:
                    process.wait(timeout=timeout)
                    break
                except subprocess.TimeoutExpired:
                    if sig == signal.SIGKILL:
                        raise RuntimeError('Owned native test launcher could not be stopped safely')
        finally:
            if descriptor is not None:
                os.close(descriptor)
    for sig in [signal.SIGTERM, signal.SIGKILL]:
        for pid, identity in owned_java_processes(directory, java_home).items():
            signal_owned_java(pid, identity, directory, java_home, sig)
        deadline = time.monotonic() + 5
        while owned_java_processes(directory, java_home) and time.monotonic() < deadline:
            time.sleep(0.1)
        if not owned_java_processes(directory, java_home):
            return
    raise RuntimeError('Owned native test JVM could not be stopped safely')


@contextmanager
def cancellation_handlers():
    def interrupted(signum, _frame):
        for sig in [signal.SIGTERM, signal.SIGINT]:
            signal.signal(sig, signal.SIG_IGN)
        raise InterruptedError(f'Native recovery supervisor stopped by signal {signum}')

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
                raise InterruptedError(f'Native recovery launch stopped by signal {signum}')


@contextmanager
def launch_process(command, cleanup, **kwargs):
    # A raising handler inside Popen can lose a started child. Defer cancellation
    # until its cleanup guard exists, without a fork-inherited pthread signal mask.
    process = None
    try:
        with defer_cancellation():
            process = subprocess.Popen(command, **kwargs)
        yield process
    finally:
        # A first signal during timeout cleanup cannot skip its waits/escalation.
        with defer_cancellation():
            cleanup(process)


@contextmanager
def recovery_world(java_home, report_dir):
    directory = Path(tempfile.mkdtemp(prefix='ip-native-recovery-world-', dir=SCRATCH)).resolve()
    cleanup_failed = False

    def cleanup(process):
        nonlocal cleanup_failed
        try:
            terminate_owned(process, directory, java_home)
        except BaseException:
            cleanup_failed = True
            raise

    try:
        yield directory, cleanup
    finally:
        # Never let TemporaryDirectory delete a live daemon's ownership evidence,
        # including when the original launcher (not a Java cwd match) cannot stop.
        if not cleanup_failed:
            with defer_cancellation():
                cleanup(None)
                for folder in ['logs', 'crash-reports']:
                    if (directory / folder).is_dir():
                        shutil.copytree(directory / folder, report_dir / folder, dirs_exist_ok=True)
                shutil.rmtree(directory)


def validate_runtime_result(result, manifest, log):
    cases = result.get('cases', [])
    names = [case['name'] for case in cases]
    if (set(names) != expected_case_names() or len(cases) != result.get('expected_cases') or len(set(names)) != len(names)
            or any(case.get('status') != 'PASS' or case.get('assertions', 0) == 0 for case in cases)
            or result.get('skipped_required_cases') != 0 or result.get('failed_cases') != 0
            or result.get('status') != 'PASS_WITH_LIMITATIONS' or not result.get('limitations')):
        raise AssertionError('Native qualification has failed, missing, duplicate or skipped required cases')
    if ('IP_NATIVE_RECOVERY_DONE status=PASS_WITH_LIMITATIONS' not in log
            or 'IP_NATIVE_RECOVERY_CASE_FAIL' in log or 'IP_NATIVE_RECOVERY_CLEANUP_FAIL' in log
            or 'RECOVERY_FAILED for portal transfer' in log):
        raise AssertionError('Native server logs do not confirm clean required-case completion')
    player_case = next(case for case in cases if case['name'] == 'concrete_two_player_packets')
    partial = result.get('native_player_partial_admission', {})
    if (partial.get('first_player_registered_before_second_refusal') is not True
            or partial.get('destination_join_events_at_refusal') != 4
            or partial.get('destination_join_events_after_retry') != 8
            or player_case.get('native_event_evidence', {}).get('destination_join_events') != 8):
        raise AssertionError('Native player fixture does not prove partial first-player admission before second-player refusal')
    if result.get('loaded_parent_sha256') != manifest['sha256']:
        raise AssertionError('Runtime did not load the exact validated packaged parent artifact')
    for mod, name in [('dimlib', 'dimlib'), ('cloth_config', 'cloth')]:
        if result.get(f'loaded_{mod}_sha256') != manifest['bundles'][name]:
            raise AssertionError(f'Runtime did not load the exact packaged native bundle: {mod}')
    return len(cases)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--accept-eula', action='store_true',
                        help="Accept Minecraft's EULA only for this new disposable test world")
    parser.add_argument('--artifact', type=Path,
                        default=MODULE.parent / 'build/libs/immersive-portals-7.0.1-dev-mc26.3-neoforge.jar')
    parser.add_argument('--timeout', type=int, default=900, help='Whole inner Gradle/server process deadline, seconds')
    args = parser.parse_args()
    if not args.accept_eula:
        parser.error('Explicit --accept-eula is required; see https://aka.ms/MinecraftEULA')
    if not 60 <= args.timeout <= 3600:
        parser.error('--timeout must be between 60 and 3600 seconds')
    require_pidfds()
    with cancellation_handlers():
        run(args)


def run(args):
    artifact = args.artifact.resolve()
    manifest = validate_artifact(artifact)
    environment = os.environ.copy()
    environment.setdefault('JAVA_HOME', str(Path.home() / '.cache/immersive-portals/toolchains/jdk-25'))
    environment.setdefault('GRADLE_USER_HOME', str(Path.home() / '.cache/immersive-portals/gradle'))
    environment['JAVA_TOOL_OPTIONS'] = (environment.get('JAVA_TOOL_OPTIONS', '')
                                       + ' -XX:ActiveProcessorCount=2 -Djava.awt.headless=true').strip()
    if not (Path(environment['JAVA_HOME']) / 'bin/java').is_file():
        raise ValueError('Set JAVA_HOME to an existing Java 25 installation; this harness does not install tools')
    report_dir = Path(tempfile.mkdtemp(prefix='ip-native-recovery-report-', dir=SCRATCH))
    runtime_report = report_dir / 'runtime-results.json'
    summary = {'artifact': manifest, 'status': 'NOT_RUN', 'world_removed': False,
               'runtime_report': str(runtime_report)}
    directory = None
    failure = None
    try:
        with recovery_world(environment['JAVA_HOME'], report_dir) as (directory, cleanup):
            nonce = secrets.token_hex(24)
            (directory / MARKER).write_text(nonce + '\n')
            (directory / 'eula.txt').write_text('eula=true\n')
            (directory / 'server.properties').write_text(
                'server-ip=127.0.0.1\n'
                f'server-port={free_loopback_port()}\n'
                'enable-rcon=false\nenable-query=false\nwhite-list=true\nenforce-whitelist=true\n'
                'online-mode=false\nenforce-secure-profile=false\nlevel-name=recovery-world\n'
                'level-seed=0\ngenerate-structures=false\nview-distance=2\nsimulation-distance=2\n'
                'max-players=1\nmax-tick-time=120000\n')
            command = [str(ROOT / 'gradlew'), '-p', str(MODULE), '--no-daemon', '--max-workers=2',
                       '--console=plain', '--project-cache-dir', str(directory / 'gradle-project-cache'),
                       'runRecoveryServer', f'-PrecoveryRunDir={directory}', f'-PrecoveryNonce={nonce}',
                       f'-PrecoveryReport={runtime_report}', f'-PparentArtifact={artifact}',
                       f'-PparentArtifactSha256={manifest["sha256"]}',
                       f'-PdimlibSha256={manifest["bundles"]["dimlib"]}',
                       f'-PclothSha256={manifest["bundles"]["cloth"]}']
            with (report_dir / 'server.log').open('w') as log:
                # A single-use daemon can detach before runRecoveryServer writes
                # its lease, including during configuration/dependency resolution.
                environment['IP_NATIVE_RECOVERY_NONCE'] = nonce
                with launch_process(command, cleanup, cwd=MODULE, env=environment,
                                    stdout=log, stderr=subprocess.STDOUT, start_new_session=True) as process:
                    process.wait(timeout=args.timeout)
            if process.returncode != 0:
                raise RuntimeError(f'Native test server/build exited with {process.returncode}')
            if not runtime_report.is_file():
                raise AssertionError('Dedicated server did not produce native runtime results')
            result = json.loads(runtime_report.read_text())
            log = (report_dir / 'server.log').read_text(errors='replace')
            passed = validate_runtime_result(result, manifest, log)
            cold = validate_cold_client_initializers((report_dir / 'class-init.log').read_text())
            summary['status'] = 'PASS_WITH_LIMITATIONS'
            summary['required_cases_passed'] = passed
            summary['cold_client_initializers_verified'] = cold
            summary['limitations'] = result['limitations']
        summary['world_removed'] = True
    except BaseException as error:
        failure = error
        summary['status'] = 'FAIL'
        summary['failure'] = str(error)
        summary['world_removed'] = directory is not None and not directory.exists()
    finally:
        summary['world_removed'] = directory is not None and not directory.exists()
        if directory is not None and directory.exists():
            summary['preserved_world'] = str(directory)
        (report_dir / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
        print(f'Native recovery status: {summary["status"]}; retained report: {report_dir}', flush=True)
    if failure is not None:
        raise failure


if __name__ == '__main__':
    main()
