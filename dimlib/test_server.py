#!/usr/bin/env python3
"""Exercise dimension add/remove/save/restart using an isolated loopback server.

Requires Java 25, the repository Gradle wrapper, and explicit --accept-eula.
No existing server directory, world, account or credentials are used.
"""

import argparse
import json
import os
from pathlib import Path
import secrets
import shutil
import signal
import socket
import struct
import subprocess
import tempfile
import time


DIMLIB = Path(__file__).resolve().parent
WRAPPER = DIMLIB.parent / "gradlew"


def free_port():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def read_exact(connection, size):
    result = bytearray()
    while len(result) < size:
        chunk = connection.recv(size - len(result))
        if not chunk:
            raise ConnectionError("RCON connection closed")
        result.extend(chunk)
    return bytes(result)


def rcon(port, password, command):
    def exchange(connection, request_id, packet_type, text):
        payload = struct.pack("<ii", request_id, packet_type) + text.encode() + b"\0\0"
        connection.sendall(struct.pack("<i", len(payload)) + payload)
        length = struct.unpack("<i", read_exact(connection, 4))[0]
        if not 10 <= length <= 4_194_304:
            raise RuntimeError("Invalid RCON response length")
        response = read_exact(connection, length)
        response_id, _ = struct.unpack("<ii", response[:8])
        if response_id != request_id:
            raise RuntimeError("RCON authentication or request ID failed")
        return response[8:-2].decode("utf-8")

    with socket.create_connection(("127.0.0.1", port), timeout=5) as connection:
        connection.settimeout(30)
        exchange(connection, 1, 3, password)
        return exchange(connection, 2, 2, command)


def wait_for(process, action, predicate, timeout=240):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"Server exited before {action}: exit {process.returncode}")
        try:
            if predicate():
                return
        except (OSError, ConnectionError):
            pass
        time.sleep(0.25)
    raise TimeoutError(f"Timed out waiting for {action}")


def launch(module, directory, log, environment):
    return subprocess.Popen(
        [str(WRAPPER), "--no-daemon", "--console=plain", "--max-workers=2", "runServer",
         "-Dorg.gradle.jvmargs=-Xmx1G -XX:ActiveProcessorCount=2",
         f"-PdimlibRunDir={directory}"],
        cwd=module, env=environment, stdout=log, stderr=subprocess.STDOUT,
        start_new_session=True,
    )


def stop(process, command):
    try:
        command("stop")
    except (OSError, ConnectionError):
        pass  # The server can close RCON before sending the stop response.
    process.wait(timeout=90)
    if process.returncode != 0:
        raise RuntimeError(f"Test server failed: exit {process.returncode}")


def terminate_owned_process(process):
    if process is not None and process.poll() is None:
        os.killpg(process.pid, signal.SIGTERM)
        try:
            process.wait(timeout=20)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait(timeout=10)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--loader", choices=("fabric", "neoforge"), default="neoforge")
    parser.add_argument("--accept-eula", action="store_true",
                        help="Accept Minecraft's EULA only for the new disposable test server")
    args = parser.parse_args()
    if not args.accept_eula:
        parser.error("Explicit --accept-eula is required; see https://aka.ms/MinecraftEULA")

    environment = os.environ.copy()
    module = DIMLIB / "neoforge" if args.loader == "neoforge" else DIMLIB
    report = Path(tempfile.mkdtemp(prefix=f"dimlib-{args.loader}-report-", dir="/tmp/opencode"))
    results = []
    failure = None
    with tempfile.TemporaryDirectory(prefix=f"dimlib-{args.loader}-server-", dir="/tmp/opencode") as scratch:
        directory = Path(scratch)
        server_port, rcon_port = free_port(), free_port()
        while rcon_port == server_port:
            rcon_port = free_port()
        password = secrets.token_hex(24)
        (directory / "eula.txt").write_text("eula=true\n")
        (directory / "server.properties").write_text(
            "server-ip=127.0.0.1\n"
            f"server-port={server_port}\n"
            "enable-rcon=true\n"
            f"rcon.port={rcon_port}\n"
            f"rcon.password={password}\n"
            "broadcast-rcon-to-ops=false\n"
            "online-mode=false\n"
            "enforce-secure-profile=false\n"
            "level-name=dimlib-test-world\n"
            "level-seed=0\n"
            "generate-structures=false\n"
            "view-distance=2\n"
            "simulation-distance=2\n"
            "max-players=1\n"
        )
        process = None
        try:
            for stage in range(3):
                with (directory / f"server-{stage}.log").open("w") as log:
                    process = launch(module, directory, log, environment)
                    command = lambda text: rcon(rcon_port, password, text)
                    wait_for(process, "RCON readiness", lambda: "players" in command("list"))
                    dimensions = command("dims list")
                    result = {"stage": stage, "startup_dimensions": dimensions.splitlines()}
                    results.append(result)
                    if "minecraft:overworld" not in dimensions:
                        raise AssertionError("The dims command was not registered")
                    if stage == 0:
                        command('dims add_dimension "dimlib_test:persist" void')
                        command('dims add_dimension "dimlib_test:remove" void')
                        wait_for(process, "dynamic add", lambda:
                            "dimlib_test:persist" in command("dims list") and
                            "dimlib_test:remove" in command("dims list"))
                        config = command("dims view_dim_config dimlib_test:persist")
                        if "minecraft:flat" not in config:
                            raise AssertionError("Dynamic LevelStem could not be encoded")
                        result["dynamic_registry_encoding"] = True
                        command("dims remove_dimension dimlib_test:remove")
                        wait_for(process, "dynamic remove", lambda:
                            "dimlib_test:remove" not in command("dims list"))
                    elif stage == 1:
                        if "dimlib_test:persist" not in dimensions or "dimlib_test:remove" in dimensions:
                            raise AssertionError("Added/removed dimensions did not persist across restart")
                        command("dims remove_dimension dimlib_test:persist")
                        wait_for(process, "persistent dimension removal", lambda:
                            "dimlib_test:persist" not in command("dims list"))
                    elif "dimlib_test:" in dimensions:
                        raise AssertionError("Removed dimension reappeared after restart")
                    if "Saved the game" not in command("save-all flush"):
                        raise AssertionError("Server did not confirm saving the world")
                    result["final_dimensions"] = command("dims list").splitlines()
                    stop(process, command)
                    process = None
                    result["passed"] = True
                    print(f"PASS ({args.loader}) stage {stage}: {result['final_dimensions']}", flush=True)
            print(f"PASS ({args.loader}): commands, dynamic add/remove, LevelStem encoding and two restart persistence checks")
        except Exception as error:
            failure = str(error)
            terminate_owned_process(process)
            # Do not expose server.properties or the generated RCON password.
            for path in sorted(directory.glob("server-*.log")):
                print(f"--- {path.name} (last 100 lines) ---")
                print("\n".join(path.read_text(errors="replace").splitlines()[-100:]))
            raise
        finally:
            terminate_owned_process(process)
            for path in directory.glob("server-*.log"):
                shutil.copy2(path, report / path.name)
            for path in (directory / "crash-reports").glob("*.txt"):
                shutil.copy2(path, report / path.name)
            (report / "results.json").write_text(json.dumps({
                "loader": args.loader, "stages": results, "failure": failure
            }, indent=2))
            print(f"Report: {report}", flush=True)


if __name__ == "__main__":
    main()
