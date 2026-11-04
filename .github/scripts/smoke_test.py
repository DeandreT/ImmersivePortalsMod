#!/usr/bin/env python3
"""End-to-end smoke test for the built mod jar. Used by CI and runnable locally.

    smoke_test.py server --jar JAR --work DIR [--no-mod]
    smoke_test.py client --jar JAR --work DIR [--no-mod] [--with-sodium-iris] [--shader-pack PACK]
    smoke_test.py multiplayer --jar JAR --work DIR

Unlike `gradlew runClient`, this loads the remapped release jar through the
production Fabric launcher, so it exercises exactly what players install.

server: boots a dedicated server, summons a portal, checks it survives, then
        records startup time, peak memory, idle CPU and tick time.
client: opens a world with the player standing over a portal to the Nether,
        screenshots the portal view, drops the player through it and requires a
        seamless (non-loading-screen) dimension change. The client needs a
        display; use `xvfb-run` or `gamescope --backend headless --`.

--no-mod measures the same scene without the mod, as a resource baseline.
Metrics are written to DIR/metrics.json. Exit code is non-zero on any failure.
"""
import argparse
import hashlib
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
USER_AGENT = "immersive-portals-smoke-test"
FABRIC_MAVEN = "https://maven.fabricmc.net"
MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"

# Any of these in a log means the mod (or a mod it depends on) is broken.
FATAL_LOG = re.compile(
    r"MixinApplyError|MixinTransformerError|InvalidInjectionException"
    r"|InvalidMixinException|InjectionError|Mixin apply for mod .* failed"
    r"|NoSuchMethodError|NoSuchFieldError|NoClassDefFoundError"
    r"|OutOfMemoryError|Minecraft Crash Report|Incompatible mods found"
    r"|Failed to create backend OpenGL"
)

SERVER_BOOT_TIMEOUT = 300
IDLE_SETTLE_SECONDS = 10
IDLE_SAMPLE_SECONDS = 30
CLIENT_STAGE_TIMEOUT = 300
MULTIPLAYER_PORT = 25599
# Must stay under the gap between SMOKE_READY and SMOKE_RELEASE in DATAPACK (30 s).
CLIENT_SAMPLE_SECONDS = 20

# Budgets for the mod's cost over a mod-free baseline, enforced by `compare`.
MAX_SERVER_EXTRA_RSS_MB = 100
MAX_SERVER_EXTRA_STARTUP_S = 30
MAX_SERVER_IDLE_TICK_MS = 10
MAX_CLIENT_EXTRA_RSS_MB = 100

DATAPACK = {
    # data_major from version.json in the Minecraft jar.
    "pack.mcmeta": json.dumps({"pack": {"min_format": 121, "max_format": 121, "description": "smoke test"}}),
    "data/minecraft/tags/function/load.json": json.dumps({"values": ["smoke:load"]}),
    "data/minecraft/tags/function/tick.json": json.dumps({"values": ["smoke:tick"]}),
    "data/smoke/function/load.mcfunction": """
gamerule respawn_radius 0
gamerule advance_time false
gamerule spawn_mobs false
gamerule fall_damage false
time set noon
scoreboard objectives add smoke dummy
forceload add -16 -16 15 15
execute in minecraft:the_nether run forceload add -16 -16 15 15
schedule function smoke:setup 20t replace
""",
    "data/smoke/function/setup.mcfunction": """
setblock 0 -21 0 minecraft:glass
execute in minecraft:the_nether run fill -7 98 -7 7 119 7 minecraft:netherrack hollow
execute in minecraft:the_nether run fill -6 99 -6 6 99 6 minecraft:glowstone
execute unless entity @e[type=immersive_portals:portal] run function smoke:portal
""",
    # Horizontal 12x12 portal with its front facing up (axisW x axisH = +Y).
    "data/smoke/function/portal.mcfunction": (
        "summon immersive_portals:portal 0.5 -30 0.5 {width:12d,height:12d,thickness:0d,"
        "axisWX:0d,axisWY:0d,axisWZ:1d,axisHX:1d,axisHY:0d,axisHZ:0d,"
        'dimensionTo:"minecraft:the_nether",'
        "destinationX:0.5d,destinationY:108d,destinationZ:0.5d}\n"
    ),
    "data/smoke/function/tick.mcfunction": """
execute as @a[tag=!smoke_placed] run function smoke:place
execute if entity @a run scoreboard players add #t smoke 1
execute if score #t smoke matches 200 run say SMOKE_READY
execute if score #t smoke matches 800 run say SMOKE_RELEASE
execute if score #t smoke matches 800 run setblock 0 -21 0 minecraft:air
execute if score #t smoke matches 800 run setblock 4 -21 0 minecraft:air
execute as @a[tag=!smoke_crossed] at @s if dimension minecraft:the_nether run function smoke:crossed
execute if score #d smoke matches 1.. run scoreboard players add #d smoke 1
execute if score #d smoke matches 200 run say SMOKE_DONE
execute if score #d smoke matches 2500 run function smoke:return
execute if score #d smoke matches 2800 run say SMOKE_BACK
""",
    "data/smoke/function/place.mcfunction": """
execute unless entity @s[name=SmokeTwo] run tp @s 0.5 -20 0.5 0 90
execute if entity @s[name=SmokeTwo] run setblock 4 -21 0 minecraft:glass
execute if entity @s[name=SmokeTwo] run tp @s 4.5 -20 0.5 0 90
tag @s add smoke_placed
""",
    # Long enough away for the overworld's idle renderer to be released (120 s).
    "data/smoke/function/return.mcfunction": """
setblock 0 -21 0 minecraft:glass
execute as @a in minecraft:overworld run tp @s 0.5 -20 0.5 0 90
say SMOKE_RETURN
""",
    "data/smoke/function/crossed.mcfunction": """
tag @s add smoke_crossed
say SMOKE_CROSSED
scoreboard players set #d smoke 1
""",
}

# What the official launcher passes, so that heap behaviour is what players get.
LAUNCHER_JVM_ARGS = [
    "-Xss1M", "-XX:+UnlockExperimentalVMOptions", "-XX:+UseG1GC", "-XX:G1NewSizePercent=20",
    "-XX:G1ReservePercent=20", "-XX:MaxGCPauseMillis=50", "-XX:G1HeapRegionSize=32M",
]

CLIENT_OPTIONS = """\
pauseOnLostFocus:false
onboardAccessibility:false
skipMultiplayerWarnings:true
enableVsync:false
joinedFirstServer:true
tutorialStep:none
renderDistance:{render_distance}
simulationDistance:{render_distance}
maxFps:{max_fps}
fov:0.5
narrator:0
soundCategory_master:0.0
"""


class SmokeFailure(Exception):
    pass


def log(message):
    print(f"[smoke] {message}", flush=True)


def gradle_properties():
    props = {}
    for line in (ROOT / "gradle.properties").read_text().splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            key, value = line.split("=", 1)
            props[key.strip()] = value.strip()
    return props


def fetch(url, dest, sha1=None):
    dest = Path(dest)
    if dest.exists() and (sha1 is None or hashlib.sha1(dest.read_bytes()).hexdigest() == sha1):
        return dest
    dest.parent.mkdir(parents=True, exist_ok=True)
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    tmp = dest.with_name(dest.name + ".part")
    with urllib.request.urlopen(request, timeout=120) as response, open(tmp, "wb") as out:
        shutil.copyfileobj(response, out)
    if sha1 and hashlib.sha1(tmp.read_bytes()).hexdigest() != sha1:
        tmp.unlink()
        raise SmokeFailure(f"checksum mismatch for {url}")
    tmp.replace(dest)
    return dest


def fetch_json(url, cache):
    name = hashlib.sha1(url.encode()).hexdigest() + ".json"
    return json.loads(fetch(url, Path(cache) / "json" / name).read_text())


def maven_path(coordinate):
    group, artifact, version = coordinate.split(":")[:3]
    return f"{group.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}.jar"


def fabric_api_jar(props, cache):
    version = props["fabric_version"]
    path = f"net/fabricmc/fabric-api/fabric-api/{version}/fabric-api-{version}.jar"
    return fetch(f"{FABRIC_MAVEN}/{path}", Path(cache) / "mods" / f"fabric-api-{version}.jar")


def modrinth_jar(slug, version, cache):
    name = f"{slug}-{version}.jar"
    url = f"https://api.modrinth.com/maven/maven/modrinth/{slug}/{version}/{name}"
    return fetch(url, Path(cache) / "mods" / name)


def fresh_dir(path):
    path = Path(path)
    if path.exists():
        shutil.rmtree(path)
    path.mkdir(parents=True)
    return path


def write_datapack(world, with_portal):
    for rel, content in DATAPACK.items():
        if not with_portal:
            if rel.endswith("portal.mcfunction"):
                continue
            content = "".join(line for line in content.splitlines(True) if "immersive_portals" not in line)
        target = world / "datapacks" / "smoke" / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content.lstrip("\n"))


def proc_status(pid):
    """Returns (peak_rss_mb, cpu_seconds) for a running process."""
    status = Path(f"/proc/{pid}/status").read_text()
    peak_kb = int(re.search(r"VmHWM:\s+(\d+)", status).group(1))
    fields = Path(f"/proc/{pid}/stat").read_text().rsplit(")", 1)[1].split()
    ticks = int(fields[11]) + int(fields[12])
    return peak_kb // 1024, ticks / os.sysconf("SC_CLK_TCK")


def scan_for_fatal(*paths):
    for path in paths:
        if Path(path).exists():
            for line in Path(path).read_text(errors="replace").splitlines():
                if FATAL_LOG.search(line):
                    raise SmokeFailure(f"fatal line in {Path(path).name}: {line[:300]}")


def wait_for(pattern, log_path, proc, timeout, label):
    deadline = time.monotonic() + timeout
    regex = re.compile(pattern)
    while time.monotonic() < deadline:
        scan_for_fatal(log_path)
        if Path(log_path).exists():
            match = regex.search(Path(log_path).read_text(errors="replace"))
            if match:
                return match
        if proc.poll() is not None:
            raise SmokeFailure(f"process exited with {proc.returncode} waiting for {label}")
        time.sleep(1)
    raise SmokeFailure(f"timed out after {timeout}s waiting for {label}")


def stop_process(proc, timeout=60):
    if proc.poll() is None:
        proc.send_signal(signal.SIGTERM)
        try:
            proc.wait(timeout)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait()


def wants_portal(args):
    return not (args.no_mod or args.no_portal)


def jvm_profile(java, pid, work):
    """Live heap and native memory of a JVM started with NativeMemoryTracking. Forces a full GC."""
    jcmd = str(Path(java).parent / "jcmd")

    def run(*command):
        return subprocess.run([jcmd, str(pid), *command], check=True, capture_output=True, text=True, timeout=180).stdout

    histogram = run("GC.class_histogram")
    (Path(work) / "class-histogram.txt").write_text(histogram)
    native = run("VM.native_memory", "summary")
    (Path(work) / "native-memory.txt").write_text(native)

    def committed_mb(section):
        match = re.search(rf"-\s+{section} \(reserved=\d+KB, committed=(\d+)KB", native)
        return int(match.group(1)) // 1024 if match else 0

    rss_kb = int(re.search(r"VmRSS:\s+(\d+)", Path(f"/proc/{pid}/status").read_text()).group(1))
    return {
        "live_heap_mb": int(re.search(r"^Total\s+\d+\s+(\d+)", histogram, re.M).group(1)) // (1024 * 1024),
        "loaded_classes": int(re.search(r"(\d+)\s+\d+\s+java\.lang\.Class\b", histogram).group(1)),
        "committed_heap_mb": committed_mb("Java Heap"),
        "class_metadata_mb": committed_mb("Class"),
        "jit_code_mb": committed_mb("Code"),
        "rss_after_gc_mb": rss_kb // 1024,
    }


def gpu_memory_mb(pid):
    """GPU memory the kernel driver charges to a process (amdgpu, i915, ...); 0 on software rendering."""
    per_client = {}
    for info in Path(f"/proc/{pid}/fdinfo").iterdir():
        try:
            text = info.read_text()
        except OSError:
            continue
        client = re.search(r"drm-client-id:\s*(\d+)", text)
        if client:
            # Drivers can report one region under both names, so don't add them up.
            sizes = re.findall(r"drm-total-(?:vram|gtt|local|system):\s*(\d+) KiB", text) \
                or re.findall(r"drm-memory-(?:vram|gtt):\s*(\d+) KiB", text)
            per_client[client.group(1)] = sum(int(size) for size in sizes)
    if per_client:
        return sum(per_client.values()) // 1024
    # NVIDIA's driver doesn't report through fdinfo.
    try:
        output = subprocess.run(
            ["nvidia-smi", "pmon", "-c", "1", "-s", "m"], capture_output=True, text=True, timeout=30
        ).stdout
    except (OSError, subprocess.SubprocessError):
        return 0
    for line in output.splitlines():
        fields = line.split()
        if len(fields) > 3 and fields[1] == str(pid) and fields[3].isdigit():
            return int(fields[3])
    return 0


def current_rss_mb(pid):
    return int(re.search(r"VmRSS:\s+(\d+)", Path(f"/proc/{pid}/status").read_text()).group(1)) // 1024


def gc_pauses(gc_log, start_offset):
    """(number of pauses, total pause ms) logged after a position in a -Xlog:gc file."""
    with open(gc_log, errors="replace") as file:
        file.seek(start_offset)
        pauses = [float(ms) for ms in re.findall(r"Pause .* ([\d.]+)ms$", file.read(), re.M)]
    return len(pauses), round(sum(pauses))


def report_metrics(work, metrics):
    (Path(work) / "metrics.json").write_text(json.dumps(metrics, indent=2))
    log("metrics: " + json.dumps(metrics))


def setup_server(args, props, work, port=0):
    """Prepares a server directory and returns the launcher jar."""
    mods = work / "mods"
    mods.mkdir()
    installer = fetch_json("https://meta.fabricmc.net/v2/versions/installer", args.cache)[0]["version"]
    launcher = fetch(
        f"https://meta.fabricmc.net/v2/versions/loader/{props['minecraft_version']}"
        f"/{props['loader_version']}/{installer}/server/jar",
        Path(args.cache) / f"fabric-server-{props['minecraft_version']}-{props['loader_version']}.jar",
    )
    shutil.copy(fabric_api_jar(props, args.cache), mods)
    if not args.no_mod:
        shutil.copy(args.jar, mods)

    (work / "eula.txt").write_text("eula=true\n")
    (work / "server.properties").write_text(
        "level-type=minecraft\\:flat\nlevel-seed=1\nonline-mode=false\ndifficulty=peaceful\n"
        "view-distance=6\nsimulation-distance=6\nspawn-monsters=false\n"
        f"spawn-animals=false\nspawn-npcs=false\nwhite-list=false\nserver-port={port}\n"
    )
    world = work / "world"
    world.mkdir()
    write_datapack(world, wants_portal(args))
    (work / "config").mkdir()
    (work / "config" / "immersive_portals.json").write_text(os.environ.get("SMOKE_CONFIG", "{}"))
    return launcher


def run_server(args, props):
    work = fresh_dir(args.work)
    launcher = setup_server(args, props, work)

    log_path = work / "server.log"
    started = time.monotonic()
    with open(log_path, "w") as out:
        proc = subprocess.Popen(
            [args.java, "-Xmx2G", "-XX:NativeMemoryTracking=summary", "-jar", str(launcher), "nogui"],
            cwd=work, stdin=subprocess.PIPE, stdout=out, stderr=subprocess.STDOUT, text=True,
        )

    def send(command):
        proc.stdin.write(command + "\n")
        proc.stdin.flush()

    try:
        wait_for(r"\bDone \(\d", log_path, proc, SERVER_BOOT_TIMEOUT, "server startup")
        startup = time.monotonic() - started
        log(f"server started in {startup:.1f}s")
        time.sleep(IDLE_SETTLE_SECONDS)

        if wants_portal(args):
            # Checked after several seconds of ticking: invalid portals remove themselves.
            send("execute if entity @e[type=immersive_portals:portal]")
            wait_for(r"Test passed[.,] [Cc]ount: 1", log_path, proc, 30, "the portal entity to exist")
            log("portal entity exists and survived ticking")
        if not args.no_mod:
            send("help portal")
            wait_for(r"/portal ", log_path, proc, 30, "the /portal command")

        _, cpu_before = proc_status(proc.pid)
        time.sleep(IDLE_SAMPLE_SECONDS)
        peak_mb, cpu_after = proc_status(proc.pid)
        rss_mb = current_rss_mb(proc.pid)
        profile = jvm_profile(args.java, proc.pid, work)
        send("tick query")
        tick = wait_for(r"Average time per tick: ([\d.]+)ms", log_path, proc, 30, "tick stats")

        send("stop")
        if proc.wait(120) != 0:
            raise SmokeFailure(f"server exited with {proc.returncode} on stop")
    finally:
        stop_process(proc)

    scan_for_fatal(log_path, work / "logs" / "latest.log")
    if not args.no_mod and "Failed to load function" in log_path.read_text(errors="replace"):
        raise SmokeFailure("the test data pack did not load, see server.log")
    if (work / "crash-reports").exists() and list((work / "crash-reports").glob("*")):
        raise SmokeFailure("server wrote a crash report")

    report_metrics(work, {
        "mode": "server", "mod": not args.no_mod,
        "startup_s": round(startup, 1),
        "rss_mb": rss_mb,
        "peak_rss_mb": peak_mb,
        "idle_cpu_cores": round((cpu_after - cpu_before) / IDLE_SAMPLE_SECONDS, 3),
        "idle_tick_ms": float(tick.group(1)),
        **profile,
    })
    return work


def client_classpath(props, cache):
    version = props["minecraft_version"]
    manifest = fetch_json(MANIFEST, cache)
    entry = next(v for v in manifest["versions"] if v["id"] == version)
    meta = json.loads(fetch(entry["url"], Path(cache) / "versions" / f"{version}.json", entry["sha1"]).read_text())
    client = meta["downloads"]["client"]
    client_jar = fetch(client["url"], Path(cache) / "versions" / f"{version}.jar", client["sha1"])

    libraries = {}
    for lib in meta["libraries"]:
        rules = lib.get("rules", [])
        allowed = not rules or any(
            r["action"] == "allow" and r.get("os", {}).get("name", "linux") == "linux" for r in rules
        ) and not any(
            r["action"] == "disallow" and r.get("os", {}).get("name") == "linux" for r in rules
        )
        artifact = lib["downloads"].get("artifact")
        if allowed and artifact:
            key = ":".join(lib["name"].split(":")[:2] + lib["name"].split(":")[3:])
            libraries[key] = fetch(artifact["url"], Path(cache) / "libraries" / artifact["path"], artifact["sha1"])

    # Fabric's libraries win over vanilla's on a clash, as in the real launcher.
    profile = fetch_json(
        f"https://meta.fabricmc.net/v2/versions/loader/{version}/{props['loader_version']}/profile/json", cache
    )
    for lib in profile["libraries"]:
        path = maven_path(lib["name"])
        base = lib.get("url", FABRIC_MAVEN).rstrip("/")
        key = ":".join(lib["name"].split(":")[:2])
        libraries[key] = fetch(f"{base}/{path}", Path(cache) / "libraries" / path, lib.get("sha1"))

    return [str(p) for p in libraries.values()] + [str(client_jar)], profile["mainClass"], meta["assetIndex"]["id"]


def screenshot(game, dest, pid):
    """Takes a screenshot with Minecraft's own key if it can, so it's the real framebuffer."""
    folder = game / "screenshots"
    before = set(folder.glob("*.png")) if folder.exists() else set()
    windows = subprocess.run(
        ["xdotool", "search", "--pid", str(pid)], capture_output=True, text=True, timeout=30,
    ).stdout.split()
    for _ in range(3):
        for window in windows:
            subprocess.run(["xdotool", "key", "--window", window, "F2"], check=False, timeout=30)
            # Xvfb has no window manager to give the window keyboard focus, and SDL only takes focused input.
            subprocess.run(["xdotool", "windowfocus", window], check=False, timeout=30)
            subprocess.run(["xdotool", "key", "F2"], check=False, timeout=30)
        deadline = time.monotonic() + 6
        while time.monotonic() < deadline:
            new = set(folder.glob("*.png")) - before if folder.exists() else set()
            if new:
                time.sleep(1)  # Saved on a background thread; let the write finish.
                shutil.copy(new.pop(), dest)
                return
            time.sleep(0.5)
    # Under Xvfb the key doesn't reach the game, but its framebuffer can be read directly (not under gamescope).
    subprocess.run(
        ["ffmpeg", "-y", "-loglevel", "error", "-f", "x11grab", "-i", os.environ["DISPLAY"], "-frames:v", "1", str(dest)],
        check=True, timeout=60,
    )


def mean_color(path, crop):
    """Average (r, g, b) of the crop, given as an ffmpeg `w:h:x:y` expression."""
    raw = subprocess.run(
        ["ffmpeg", "-loglevel", "error", "-i", str(path), "-vf", f"crop={crop},scale=1:1",
         "-f", "rawvideo", "-pix_fmt", "rgb24", "-"],
        check=True, capture_output=True, timeout=60,
    ).stdout
    return tuple(raw[:3])


def looks_like_nether(args, r, g, b):
    """The sampled band is dark Nether rock. A shader pack changes its colour but it must stay dark, not washed out."""
    if max(r, g, b) < 20:
        return False  # a black screenshot says nothing
    return min(r, g, b) < 170 if args.shader_pack else r >= g * 1.5


def setup_game_dir(args, props, game, with_mod):
    (game / "options.txt").write_text(CLIENT_OPTIONS.format(render_distance=args.render_distance, max_fps=args.max_fps))
    (game / "config").mkdir()
    # SMOKE_CONFIG: config options to compare, as JSON.
    config = {"initialScreenShown": True, "initialScreenVersion": 1, **json.loads(os.environ.get("SMOKE_CONFIG", "{}"))}
    (game / "config" / "immersive_portals.json").write_text(json.dumps(config))
    mods = game / "mods"
    mods.mkdir()
    shutil.copy(fabric_api_jar(props, args.cache), mods)
    if with_mod:
        shutil.copy(args.jar, mods)
    if args.extra_mods:
        for jar in Path(args.extra_mods).glob("*.jar"):
            shutil.copy(jar, mods)
    if args.shader_pack:
        pack = Path(args.shader_pack)
        (game / "shaderpacks").mkdir()
        if pack.is_dir():
            shutil.copytree(pack, game / "shaderpacks" / pack.name)
        else:
            shutil.copy(pack, game / "shaderpacks")
        (game / "config" / "iris.properties").write_text(f"enableShaders=true\nshaderPack={pack.name}\n")
    if args.with_sodium_iris or args.shader_pack:
        shutil.copy(modrinth_jar("sodium", props["sodium_version"], args.cache), mods)
        shutil.copy(modrinth_jar("iris", props["iris_version"], args.cache), mods)


def client_command(args, props, game, gc_log, username, join_args):
    classpath, main_class, asset_index = client_classpath(props, args.cache)
    return [
        args.java, "-Xmx4G", *LAUNCHER_JVM_ARGS, "-XX:NativeMemoryTracking=summary", f"-Xlog:gc:file={gc_log}",
        "-Dimm_ptl.logFrameStats=true",
        *os.environ.get("SMOKE_JVM_ARGS", "").split(), "-cp", os.pathsep.join(classpath), main_class,
        "--username", username, "--version", props["minecraft_version"],
        "--gameDir", str(game), "--assetsDir", str(game / "assets"), "--assetIndex", asset_index,
        "--accessToken", "0", "--userType", "legacy", "--versionType", "release",
        "--width", args.window.split("x")[0], "--height", args.window.split("x")[1], *join_args,
    ]


def start_client(work, game, command):
    # The screenshot key is sent through X11, so don't let the game pick Wayland.
    env = {k: v for k, v in os.environ.items() if k != "WAYLAND_DISPLAY"}
    with open(work / f"{game.name}.out", "w") as out:
        return subprocess.Popen(command, cwd=game, stdout=out, stderr=subprocess.STDOUT, env=env)


def run_client(args, props):
    # The world comes from a server run so the portal and terrain are the same each time.
    server_args = argparse.Namespace(**{**vars(args), "work": str(Path(args.work) / "server")})
    work = Path(args.work)
    if work.exists():
        shutil.rmtree(work)
    server_work = run_server(server_args, props)

    game = fresh_dir(work / "game")
    shutil.copytree(server_work / "world", game / "saves" / "smoke")
    setup_game_dir(args, props, game, with_mod=not args.no_mod)

    log_path = game / "logs" / "latest.log"
    gc_log = work / "gc.log"
    proc = start_client(work, game, client_command(
        args, props, game, gc_log, "SmokeTest", ["--quickPlaySingleplayer", "smoke"]
    ))

    idle_gpu_mb = None
    try:
        wait_for(r"SMOKE_READY", log_path, proc, CLIENT_STAGE_TIMEOUT, "the player to load into the world")
        log("player in world, standing over the portal")
        _, cpu_before = proc_status(proc.pid)
        gc_start = gc_log.stat().st_size
        stats_start = len(log_path.read_text(errors="replace"))
        time.sleep(CLIENT_SAMPLE_SECONDS)
        frame_stats = re.findall(
            r"frame stats: fps=(\d+) p99=(\d+)ms max=(\d+)ms", log_path.read_text(errors="replace")[stats_start:]
        )
        peak_mb, cpu_after = proc_status(proc.pid)
        cpu_cores = (cpu_after - cpu_before) / CLIENT_SAMPLE_SECONDS
        gpu_mb = gpu_memory_mb(proc.pid)
        rss_mb = current_rss_mb(proc.pid)
        pause_count, pause_ms = gc_pauses(gc_log, gc_start)
        process_status = Path(f"/proc/{proc.pid}/status").read_text()
        rss_parts = {
            f"{name.lower()}_mb": int(re.search(rf"{name}:\s+(\d+)", process_status).group(1)) // 1024
            for name in ("RssAnon", "RssFile", "RssShmem")
        }
        profile = jvm_profile(args.java, proc.pid, work)

        # Taken last so chunks have appeared even on a slow software renderer.
        screenshot(game, work / "portal-view.png", proc.pid)
        if wants_portal(args):
            # A band of the Nether wall seen through the portal; without a
            # working portal view this part of the screen is overworld grass.
            r, g, b = mean_color(work / "portal-view.png", "iw*0.07:ih*0.06:iw*0.465:ih*0.26")
            log(f"colour through the portal: rgb({r}, {g}, {b})")
            if not looks_like_nether(args, r, g, b):
                raise SmokeFailure("the Nether is not visible through the portal")

        if wants_portal(args):
            wait_for(r"SMOKE_RELEASE", log_path, proc, CLIENT_STAGE_TIMEOUT, "the release of the player")
            wait_for(r"SMOKE_CROSSED", log_path, proc, 60, "the player to cross the portal")
            wait_for(r"Client Teleported", log_path, proc, 10, "the client-side seamless teleport")
            log("player crossed into the Nether through the portal")
            wait_for(r"SMOKE_DONE", log_path, proc, CLIENT_STAGE_TIMEOUT, "the player to stay in the Nether")
            screenshot(game, work / "after-crossing.png", proc.pid)
            peak_mb, _ = proc_status(proc.pid)
            if not args.skip_return:
                wait_for(r"SMOKE_RETURN", log_path, proc, CLIENT_STAGE_TIMEOUT, "the trip back")
                idle_gpu_mb = gpu_memory_mb(proc.pid)
                wait_for(r"SMOKE_BACK", log_path, proc, CLIENT_STAGE_TIMEOUT, "the overworld to reload")
                screenshot(game, work / "after-return.png", proc.pid)
                # The player is back where it started: overworld terrain and the view through the portal both rebuilt.
                r, g, b = mean_color(work / "after-return.png", "iw*0.07:ih*0.06:iw*0.465:ih*0.26")
                log(f"colour through the portal after returning: rgb({r}, {g}, {b})")
                if not looks_like_nether(args, r, g, b):
                    raise SmokeFailure("the portal view did not come back after returning")
                r, g, b = mean_color(work / "after-return.png", "iw*0.1:ih*0.1:iw*0.05:ih*0.1")
                log(f"colour of the overworld ground after returning: rgb({r}, {g}, {b})")
                if not args.shader_pack and (g <= r or g <= b):
                    raise SmokeFailure("the overworld terrain did not come back after returning")
    finally:
        stop_process(proc)
    # -9: it was still saving when stop_process gave up on it, which only says the machine was busy.
    if proc.returncode not in (0, 143, -signal.SIGTERM, -signal.SIGKILL):
        raise SmokeFailure(f"client exited abnormally with {proc.returncode}")

    scan_for_fatal(log_path, work / "game.out")
    if (game / "crash-reports").exists() and list((game / "crash-reports").glob("*")):
        raise SmokeFailure("client wrote a crash report")

    report_metrics(work, {
        "mode": "client", "mod": not args.no_mod, "sodium_iris": args.with_sodium_iris,
        "portal": wants_portal(args),
        "render_distance": args.render_distance,
        "rss_mb": rss_mb, "peak_rss_mb": peak_mb, "gpu_mb": gpu_mb, "cpu_cores_at_60fps_cap": round(cpu_cores, 3),
        "fps": min((int(f[0]) for f in frame_stats), default=None),
        "frame_p99_ms": max((int(f[1]) for f in frame_stats), default=None),
        "frame_max_ms": max((int(f[2]) for f in frame_stats), default=None),
        "gc_pauses": pause_count, "gc_pause_ms": pause_ms, "idle_gpu_mb": idle_gpu_mb, **rss_parts,
        **profile,
    })


def run_multiplayer(args, props):
    """Two real clients join a real dedicated server over the network and cross a portal."""
    work = fresh_dir(args.work)
    server_dir = work / "server"
    server_dir.mkdir()
    launcher = setup_server(args, props, server_dir, port=MULTIPLAYER_PORT)
    server_log = server_dir / "server.log"
    with open(server_log, "w") as out:
        server = subprocess.Popen(
            [args.java, "-Xmx2G", "-XX:NativeMemoryTracking=summary", "-jar", str(launcher), "nogui"],
            cwd=server_dir, stdin=subprocess.PIPE, stdout=out, stderr=subprocess.STDOUT, text=True,
        )

    def send(command):
        server.stdin.write(command + "\n")
        server.stdin.flush()

    join = ["--quickPlayMultiplayer", f"127.0.0.1:{MULTIPLAYER_PORT}"]
    players = []

    def add_client(name, with_mod):
        game = fresh_dir(work / name)
        setup_game_dir(args, props, game, with_mod=with_mod)
        proc = start_client(work, game, client_command(args, props, game, work / f"{name}-gc.log", name, join))
        players.append((name, game, proc))
        return game, proc

    try:
        wait_for(r"\bDone \(\d", server_log, server, SERVER_BOOT_TIMEOUT, "server startup")
        log("dedicated server started")
        add_client("SmokeOne", True)
        add_client("SmokeTwo", True)
        first_game, first_client = players[0][1], players[0][2]

        for name, game, proc in players:
            wait_for(rf"{name} joined the game", server_log, server, CLIENT_STAGE_TIMEOUT, f"{name} to join")
        log("both players joined")
        for name, game, proc in players:
            wait_for(r"SMOKE_READY", game / "logs" / "latest.log", proc, CLIENT_STAGE_TIMEOUT, f"{name} to load in")
        time.sleep(CLIENT_SAMPLE_SECONDS)
        screenshot(first_game, work / "portal-view.png", first_client.pid)
        r, g, b = mean_color(work / "portal-view.png", "iw*0.07:ih*0.06:iw*0.465:ih*0.26")
        log(f"colour through the portal on the server: rgb({r}, {g}, {b})")
        if not looks_like_nether(args, r, g, b):
            raise SmokeFailure("the Nether is not visible through the portal on the server")

        server_rss = current_rss_mb(server.pid)
        send("tick query")
        tick = wait_for(r"Average time per tick: ([\d.]+)ms", server_log, server, 30, "tick stats")

        wait_for(r"SMOKE_RELEASE", server_log, server, CLIENT_STAGE_TIMEOUT, "the players to be released")
        for name, game, proc in players:
            wait_for(rf"\[{name}\] SMOKE_CROSSED", server_log, server, 120, f"{name} to cross on the server")
            wait_for(r"Client Teleported", game / "logs" / "latest.log", proc, 30, f"{name}'s seamless teleport")
        log("both players crossed into the Nether")
        wait_for(r"SMOKE_DONE", server_log, server, CLIENT_STAGE_TIMEOUT, "the players to stay in the Nether")
        screenshot(first_game, work / "after-crossing.png", first_client.pid)

        # A client without the mod must get a clear refusal, and must not disturb the server.
        before = len(server_log.read_text(errors="replace"))
        _, vanilla_client = add_client("SmokeNoMod", False)
        vanilla_log = work / "SmokeNoMod" / "logs" / "latest.log"
        refused = wait_for(
            r"(?:Disconnected|disconnect|Connection lost|Mod Not Installed|missing|incompatible)[^\n]*",
            vanilla_log, vanilla_client, CLIENT_STAGE_TIMEOUT, "the client without the mod to be refused"
        )
        log(f"client without the mod: {refused.group(0)[:150]}")
        time.sleep(5)
        if "SmokeNoMod joined the game" in server_log.read_text(errors="replace")[before:]:
            raise SmokeFailure("a client without the mod joined the server")
        send("list")
        wait_for(r"There are 2 of a max", server_log, server, 30, "the two players to still be online")
        log("the server refused it and the two players are still online")

        send("stop")
        if server.wait(120) != 0:
            raise SmokeFailure(f"server exited with {server.returncode} on stop")
    finally:
        stop_process(server)
        for _, _, proc in players:
            stop_process(proc)

    scan_for_fatal(server_log)
    for name, game, _ in players:
        scan_for_fatal(game / "logs" / "latest.log", work / f"{name}.out")
        if (game / "crash-reports").exists() and list((game / "crash-reports").glob("*")):
            raise SmokeFailure(f"{name} wrote a crash report")
    if (server_dir / "crash-reports").exists() and list((server_dir / "crash-reports").glob("*")):
        raise SmokeFailure("the server wrote a crash report")

    report_metrics(work, {
        "mode": "multiplayer", "players": 2, "server_rss_mb": server_rss, "server_tick_ms": float(tick.group(1)),
    })


def compare(args):
    """Fails when the mod costs more than the budgets above; prints a summary table."""
    with_mod = json.loads(Path(args.with_mod).read_text())
    baseline = json.loads(Path(args.baseline).read_text()) if args.baseline else None
    rows = []
    failures = []
    for key, value in with_mod.items():
        if isinstance(value, (int, float)) and not isinstance(value, bool):
            rows.append(f"| {key} | {baseline.get(key, '-') if baseline else '-'} | {value} |")
    if with_mod["mode"] == "server":
        if with_mod["rss_mb"] - baseline["rss_mb"] > MAX_SERVER_EXTRA_RSS_MB:
            failures.append(f"server memory overhead above {MAX_SERVER_EXTRA_RSS_MB} MB")
        if with_mod["startup_s"] - baseline["startup_s"] > MAX_SERVER_EXTRA_STARTUP_S:
            failures.append(f"server startup overhead above {MAX_SERVER_EXTRA_STARTUP_S} s")
        if with_mod["idle_tick_ms"] > MAX_SERVER_IDLE_TICK_MS:
            failures.append(f"idle tick above {MAX_SERVER_IDLE_TICK_MS} ms")
    elif with_mod["rss_mb"] - baseline["rss_mb"] > MAX_CLIENT_EXTRA_RSS_MB:
        failures.append(f"client memory overhead above {MAX_CLIENT_EXTRA_RSS_MB} MB")

    table = f"### {args.title}\n\n| metric | no mod | with mod |\n|---|---|---|\n" + "\n".join(rows) + "\n"
    print(table)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as summary:
            summary.write(table + "\n")
    if failures:
        raise SmokeFailure("; ".join(failures))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="mode", required=True)
    for mode in ("server", "client", "multiplayer"):
        p = sub.add_parser(mode)
        p.add_argument("--jar", help="mod jar to test (required unless --no-mod)")
        p.add_argument("--work", required=True, help="scratch directory; deleted and recreated")
        p.add_argument("--cache", default=str(Path.home() / ".cache" / "immersive-portals-smoke"))
        p.add_argument("--java", default=shutil.which("java") or "java")
        p.add_argument("--no-mod", action="store_true", help="measure a mod-free baseline")
        p.add_argument("--no-portal", action="store_true", help="the mod is installed, but no portal exists")
        if mode != "server":
            p.add_argument("--with-sodium-iris", action="store_true")
            p.add_argument("--shader-pack", help="shader pack zip or folder to use; implies --with-sodium-iris")
            p.add_argument("--render-distance", type=int, default=6)
            p.add_argument("--extra-mods", help="folder of other mods to load too, to test a real mod list")
            p.add_argument("--window", default="854x480", help="window size, WIDTHxHEIGHT")
            p.add_argument("--skip-return", action="store_true", help="stop after crossing, for quicker measuring")
            p.add_argument("--max-fps", type=int, default=60, help="260 means uncapped, for measuring frame rate")
    p = sub.add_parser("compare")
    p.add_argument("--with-mod", required=True)
    p.add_argument("--baseline")
    p.add_argument("--title", default="Resource use")
    args = parser.parse_args()

    try:
        if args.mode == "compare":
            compare(args)
        else:
            if not args.no_mod and not args.jar:
                parser.error("--jar is required unless --no-mod is set")
            props = gradle_properties()
            if args.mode == "server":
                run_server(args, props)
            elif args.mode == "multiplayer":
                run_multiplayer(args, props)
            else:
                run_client(args, props)
    except SmokeFailure as failure:
        log(f"FAILED: {failure}")
        return 1
    log("PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
