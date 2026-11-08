# Packaged native real-client smoke runner

This is an explicit, standalone **NeoForge 26.3.0.101 physical-client** development
harness. No production build/settings, release jar, recovery-test run, or CI job
includes it. It starts one real Minecraft client, creates its own flat world, and
joins its actual integrated server through the ordinary FML/configuration/local
network lifecycle. It does not start a dedicated server or connect to an existing
world/server.

When NeoForge shows nonfatal load warnings, the helper activates its actual
Continue button, which NeoForge creates only when there are no loading errors.
Warnings remain in the logs. A loading-error screen without that button fails;
the helper never substitutes a title screen or suppresses fatal errors.

## Run after the build/runtime slot is released

Prerequisites: the existing Java 25 installation, current packaged native
`7.0.1-dev` parent and matching DimLib artifact, Gradle/NeoForm assets, `ffmpeg`,
and the maintained private-display wrapper and its existing prerequisites.
Nothing installs a JDK or system tools. Stale production inputs/bundles are
rejected; the runner never builds/substitutes the main mod.

After changing a compile-only compatibility pin, Gradle can leave a byte-identical
parent jar up-to-date while the source-freshness check still requires repackaging.
Use the real native Jar task's `--rerun` option, never a timestamp touch or a
disabled provenance check:

```sh
./gradlew --no-daemon --max-workers=2 buildAll testAll compileGametestJava \
  :immersive-portals-neoforge:jar --rerun
```

From the repository root, this is the complete vanilla-profile launch command:

```sh
JAVA_HOME="$HOME/.cache/immersive-portals/toolchains/jdk-25" \
GRADLE_USER_HOME="$HOME/.cache/immersive-portals/gradle" \
python3 neoforge/client-test/test_client.py --accept-eula --offline \
  --display-wrapper /home/deandre/projects/rust/OpenChamber/scripts/parity/virtual_display.py \
  --profile vanilla
```

The wrapper path is an explicit host input, not a source dependency on that
repository. `IP_NATIVE_DISPLAY_WRAPPER=/absolute/path/virtual_display.py` can
replace the option. The runner starts that wrapper if outside its display, then
**verifies the owned Xvfb again inside the display and immediately at native game
dispatch**. A stale/missing owner, desktop display, Wayland handles, non-private
runtime, or missing private authority/bus fails closed. Do not substitute an
ordinary desktop `DISPLAY` or a bare `xvfb-run` marker.

Use `--profile performance` for the pinned native Sodium, Lithium, FerriteCore,
EntityCulling and ImmediatelyFast artifacts; use `--profile performance-iris` to
add the pinned native Iris artifact. These are the loader-specific version IDs
in the root `gradle.properties`; Iris profiles select the separate compatible
`sodium_iris_neoforge_version` pin. `--offline` fails when required cached artifacts
are absent; omit it to allow normal Gradle dependency/asset retrieval when that
is authorized. Sodium runs from its original published **outer** jar, never the
compile-only extracted inner jar.

For a real active shader-pack check, add both:

```sh
--profile performance-iris \
--shader-pack "$PWD/src/gametest/resources/immptl_test_shaderpack"
```

`--shader-pack` accepts an explicit pack directory or zip, copied to the disposable
profile. The runtime must report an actually active shader pack. Marker color
checks remain strict; a pack that recolors those markers can fail the visual
check and needs image inspection, not relaxed success criteria.

Other options:

- `--artifact /absolute/path/native-parent.jar`: an actual current packaged native
  parent, not shared development sources. Runtime FML paths/hashes must match.
- `--away-ticks 2600`: default 130 seconds in the Nether before returning, allowing
  the normal idle-renderer lifecycle to run. Range 200–4000. Reports record the
  actual interval; a shorter explicit interval is not long-idle coverage.

The internal `runSmokeClient` task is guarded by an owned-world nonce and another
display verification. Do not invoke it directly. If a separate compile-only check
is wanted after the build slot is released, this starts **no game/display**:

```sh
JAVA_HOME="$HOME/.cache/immersive-portals/toolchains/jdk-25" \
GRADLE_USER_HOME="$HOME/.cache/immersive-portals/gradle" \
./gradlew -p neoforge/client-test --no-daemon --max-workers=2 --console=plain \
  -Porg.gradle.java.installations.auto-download=false compileJava
```

## Positive evidence required

The test-only mod is physically client-gated. Its mixins are read-only observers
at returns from actual configuration/payload/prediction handlers; they do not
replace callbacks, cancel methods, mutate protocol state, fabricate incoming
teleport acknowledgements, or use mock connections/EmbeddedChannels.

1. Actual FML-loaded packaged parent path/hash, matching loaded DimLib/Cloth bundle
   hashes, requested optional native mod set, and unchanged original optional
   outer jars. Loaded nested Sodium bytes must belong to its untouched outer jar.
2. One real integrated-server login, actual local connection, observed native
   config start/completion, consumed DimLib dimension sync, and real portal sync.
3. A horizontal portal over a green source-terrain marker. The actual framebuffer
   must show the red Nether scene **through** the portal and green local terrain
   outside it. The runner releases a glass support block; normal gravity and the
   production portal predictor must cross into the Nether. Only initial placement
   uses a same-world teleport; neither dimension transition uses a command `tp`.
4. Positive `IP_NATIVE_CLIENT_CROSSED minecraft:the_nether` evidence, actual client
   prediction and received server teleport request, correct client/server landing,
   full registration, no pending teleport, and a red Nether-floor screenshot.
5. After the away interval a second support release crosses a real return portal.
   Actual client/server Overworld landing and settlement must agree. The ordinary
   terrain rebuild API runs, then a new framebuffer must again show the remote
   red portal scene and green local terrain. No network ACK is synthesized.

The Java stage result is only `RUNTIME_PASS`. The Python supervisor additionally
requires exit 0, all screenshots and color crops, log/provenance/landing evidence,
and no fatal/crash output before writing **`PASS_SINGLE_CLIENT_LOCAL`**.

This qualifies one native physical client plus its real integrated server and
local in-memory networking. It does **not** qualify remote/TCP or two-player
networking, dedicated-server client admission, multiplayer tracker-watcher
delivery, or performance budgets. The normal rebuild and final terrain are
checked; actual idle renderer eviction is not separately asserted.

## Isolation, lifetime and results

Explicit `--accept-eula` applies only to a new owned
`/tmp/opencode/ip-native-client-world-*` directory. The client uses a private
game/profile/home/config/cache, muted sound and the null OpenAL driver, never the
user's audio/profile. No RCON/password is used. Java 25, CPU count 2, two Gradle
workers, a 2 GiB shared client/integrated-server heap, and headless AWT are fixed.
The whole wrapper/build/game is supervised under one 900-second deadline with
fail-fast fatal-output handling and bounded shutdown grace. The first fatal
headline permanently fails the run; stdout/stderr continue draining for at most
three seconds inside that same deadline so the nested Java cause is retained
before owned processes are stopped.

Cleanup selects only the launch process group and exact user/Java/start-identity
processes tied to the unique owned nonce/cwd/Gradle lease, including detached
build daemons. Ownership discovery is bound to the same process identity before
and after reading its nonce, and detached JVMs are signaled through Linux PID
handles after another identity check, never by a recycled numeric PID. SIGTERM
and SIGINT also clean up the outer display launcher; cancellation during process
creation is deferred until its cleanup guard exists. It never kills a process by
name. The world is removed only after
owned JVMs stop; if safe termination fails, its ownership marker/world is preserved
and the run fails. Logs, runtime JSON, original optional-jar metadata, framebuffer
PNGs, crop RGB values and `summary.json` remain under the separately retained
`/tmp/opencode/ip-native-client-report-*` directory.

Lightweight acceptance checks (no Gradle, game, display, or audio launch):

```sh
PYTHONDONTWRITEBYTECODE=1 python3 neoforge/client-test/test_launcher.py -v
```
