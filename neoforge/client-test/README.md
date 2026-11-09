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
`7.1.0-dev` parent and matching DimLib/early-Iris-bootstrap artifacts, Gradle/NeoForm assets, `ffmpeg`,
and the maintained private-display wrapper and its existing prerequisites.
Nothing installs a JDK or system tools. Stale production inputs/bundles are
rejected; the runner never builds/substitutes the main mod.

The bundled early processor must be the current standalone `iris-bootstrap` jar,
with its FML `LIBRARY` manifest and raw `ClassProcessor` service intact. Native
`buildAll` builds it; published optional-mod files and dependency checks are unchanged.

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

## Optional Riftwoven realm scenario

Add `--realm` to select the separate survival realm qualification. Omitting it
keeps the original Nether scenario, all three original screenshots/color crops,
both original predicted-crossing assertions, and the same rendering/profile
settings. The default parent filename is
`neoforge/build/libs/riftwoven-7.1.0-dev-mc26.3-neoforge.jar`.

```sh
JAVA_HOME="$HOME/.cache/immersive-portals/toolchains/jdk-25" \
GRADLE_USER_HOME="$HOME/.cache/immersive-portals/gradle" \
python3 neoforge/client-test/test_client.py --accept-eula --offline --realm \
  --display-wrapper /home/deandre/projects/rust/OpenChamber/scripts/parity/virtual_display.py \
  --profile vanilla --away-ticks 200
```

Use the same `--profile performance`, `--profile performance-iris`, and explicit
`--shader-pack` inputs to qualify those actual packaged native profiles. The Iris
gate is unchanged. `--away-ticks` now applies to the realm; 200 ticks is a short
smoke interval, while the unchanged default is 2600 ticks.

This scenario requires its own evidence, never a fabricated Nether prediction:

- A genuine survival integrated-server player and real planted seed/valid stone,
  crying-obsidian and amethyst formation. Fixture setup supplies blocks/items;
  it does not qualify mining, crafting, or player block placement.
- Public core offering/withdrawal/re-offering with actual saved player inventory;
  the recipe is 16 moss, 16 glowstone and one echo shard. Public ledger awakening
  spends exactly four of eight diamonds, creates one finite realm, and commits
  two suns/one moon. The main mod and DimLib run from their exact packaged jars.
- Actual independent `ServerLevel.getSeed()`, generator/profile and finite border;
  a normal world save followed by durable journal close/reopen and paid retry
  preserves the same world, seed and four remaining diamonds. This is **not** a
  shutdown/restart or reconnect test.
- A manually configured **real private `RealmPortal`**, with a durable departure
  and the production view/entry/platform admission gates intact. The client must
  receive that UUID-addressed entity and real generated destination chunks,
  match a natural terrain block from the server, and extract the realm's own sky
  while still outside. No private reflection or new production hook is used.
- The normal forward movement key enters through the production portal predictor;
  the real server request and registered, position-matched client/server landing
  must settle with no pending teleport. There is no command dimension transfer.
- Dimension-addressed environment RPC delivery must yield the committed layout,
  independent real server/client clock rates/times, and advancing physical time.
  A read-only sky observer requires actual two-sun extraction and two ordinary
  sun draws in a realm sky render. After genuine arrival, photographic setup
  clears weather through the normal realm sleep state (`sleep(cycle, false, true)`),
  without changing the frozen weather recipe, advancing the clock to a chosen
  time, or altering Overworld weather. Both actual server/client rain and thunder
  must fade to zero for sixty settled ticks, with fresh two-sun draws during that
  clear interval, before the east-facing sky photograph. Screenshots do **not**
  count visible sun pixels; body legibility still needs image inspection.
- After entry, a documented **same-dimension** camera reposition uses unmodified
  generated dry terrain for terrain/sky captures; no realm terrain is painted as
  a screenshot marker. The terrain photograph keeps its minimum hundred-tick
  settlement and additionally waits for five consecutive actual same-dimension,
  same-camera draws
  with the expected camera/orientation, positive compiled camera-floor geometry,
  compiled nearby visible nonair foreground, loaded/light-enabled neighbors and
  no pending compile work. Empty air needs no geometry; compiled occluded solid
  sections need no positive mesh. A bounded stall fails rather than rebuilding
  terrain or changing rendering settings. Initial pre-ready diagnostics remain
  separate from the final photograph's exact frozen draw state.
  Render pre/post frame identity also requires a fresh main solid-draw observation
  in the exact frame captured; a retained ready snapshot cannot qualify a frame
  with no main draw. Return calls the
  permanent core's public `useCore` API,
  which uses the maintained return admission/rollback path. Return is **not**
  reported as another predicted portal crossing or a physical core click.
  The departure must survive until the genuine ACK and clear after settled exit.
- The returned Overworld must have no realm clock attachment. Normal terrain
  rebuild, four original framebuffer captures, active shader-pack checks when
  requested, and a fresh-JVM Mixin audit are required. Audit checks application
  of applicable targets, not every gameplay path or absent optional mod.

The realm scenario requires five captures. The four original captures remain
`realm-portal-view.png`, `realm-terrain.png`,
`realm-sky.png`, and `realm-return.png`. Since natural terrain has no fixed marker
colors, the realm supervisor checks their actual size, decoded nonblank RGB
variation and exposure; it retains the images for inspection. This does not
establish visual quality by itself: nonblank terrain can still have missing
geometry. Inspect the images in addition to the mesh/readiness evidence. This does
not weaken or replace the baseline's strict red/green crop oracle. A shader pack that
suppresses the ordinary two-sun rendering path fails this qualification instead
of receiving an unverified celestial pass.

After those original entry/return checks, the realm runner also captures
`realm-terrain-replay.png` in a separate, explicitly diagnostic dimension. Public
`RealmChunkGenerator.create` and `DimensionAPI.addDimensionDynamically` reproduce
seed `-8484714376220950612` with the same recipe. Only block states from the real
protected arrival enclosure are copied; no paid record, journal, RNG state or
block-entity identity is copied or changed. Dimension-addressed obfuscated biome
metadata is published before requesting replay chunks. An ordinary public Portal
uses the same doorway geometry and real forward-key prediction, followed by the
recorded same-dimension camera position `(24.5, 68, 24.5)`, yaw 135, pitch 12.
Weather clears through the normal realm sleep policy. General `PortalAPI` travel
returns the player safely, with real client/server ACK settlement.

Supplemental evidence is under `terrain_replay`, separate from the four original
ordered landing snapshots and original pre-replay prediction list. The full raw
prediction list retains both actual crossings. This does not qualify private
`RealmPortal`/ledger admission and runs later in the JVM than the first crossing.
The diagnostic photograph requires matching natural grass/support, settled
server/client positions, actual draw camera/orientation, disabled main-view portal
clipping and nearby compiled ground geometry. Replay also waits through temporary
not-ready frames, with the same five-fresh-draw, bounded capture gate. Sodium
uses coordinate-addressed built state, current render-list membership and a
positive SOLID vertex allocation for the actual camera-floor section, plus
nearby visible foreground built state and no pending work. Read-only reflection
is tied to the pinned native Sodium APIs; missing evidence fails closed instead
of substituting an aggregate visible-geometry count. Client light readiness uses
the light engine's enabled columns, not the server-generation `isLightCorrect`
flag. Diagnostics retain block columns, independently scanned heights,
section populations, light readiness and visible mesh counts. These checks do not
prove every terrain face is correct: inspect the actual image, and do not treat a
different random seed's clean image as resolving a defect in the frozen seed.

Successful realm supervision writes **`PASS_REALM_SINGLE_CLIENT_LOCAL`**, not
the baseline status. Full physical sixty-tick awakening/open rituals, real TCP,
reconnect/restart, concurrent players, dedicated admission, renderer eviction and
performance budgets remain outside this scenario; the physical ritual has its
separate Fabric TCP regression.

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

The test-only mod is physically client-gated. Protocol mixins are read-only observers
at returns from actual configuration/payload/prediction handlers. Separate
read-only render observers inspect the ordinary opaque draw, and an accessor
reads the actual client biome seed. These observers do not
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
