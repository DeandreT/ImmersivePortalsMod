# Bundled DimLib

The Fabric and NeoForge artifacts compile the same dimension creation, removal,
registry persistence, client synchronization, commands and mixins. Loader-specific
code supplies initialization, payload registration, configuration and level
lifecycle notifications. Both artifacts use mod ID `dimlib`; install only the
artifact for the selected loader.

## Build and integration

Use Java 25 and the repository's Gradle wrapper. From the repository root:

```sh
./gradlew --max-workers=2 -p dimlib test build
./gradlew --max-workers=2 -p dimlib/neoforge test build
```

For a disposable, loopback-only dedicated-server add/remove/persistence test,
run `python dimlib/test_server.py --accept-eula` after independently
accepting Minecraft's EULA. It creates and deletes its own test worlds and
checks dimension persistence across two restarts without touching existing
server directories. Add `--loader fabric` to exercise the shared implementation
on Fabric instead. `JAVA_HOME`, `JAVA_TOOL_OPTIONS` and `GRADLE_USER_HOME` are
inherited normally.

On memory-constrained hosts, set `JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=2`
for build commands so game-source tooling also limits its worker pools. For the
server smoke helper, use `JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=2 -Xmx1G'`;
the helper also bounds the Gradle daemon to 1 GiB and two processors. Run the two
server-test profiles sequentially. Their fresh test worlds live under
`/tmp/opencode` and are removed when each profile finishes.
The helper retains its server logs, crash reports and a JSON check summary in a
separate private `dimlib-<loader>-report-*` directory there. It never copies the
temporary server properties or generated RCON password into the report.

The Fabric module exports `qouteall.dimlib:dimlib:1.1.0+mc26.3` and writes
`dimlib/build/libs/dimlib-1.1.0+mc26.3.jar`. It includes MidnightLib for its
configuration screen.

The standalone NeoForge module exports
`qouteall.dimlib:dimlib-neoforge:1.1.0+mc26.3` and writes
`dimlib/neoforge/build/libs/dimlib-neoforge-1.1.0+mc26.3.jar`. Consumers can use
`includeBuild('../dimlib/neoforge')` in their settings and depend on that coordinate.
The normal Gradle Java component exposes the compiled shared API. The artifact is
a separate mod and must also be installed or included as a nested dependency.
It does not require Fabric Loader, Fabric API, MidnightLib or Mod Menu.
Its NeoForm Runtime utility tasks also use the selected Java 25 toolchain, so
asset preparation does not require a separate Java 21 installation.

NeoForge payload registration uses protocol version `1`. The synchronization
channel is optional, and the native adapter only sends to peers advertising
support, so the library does not impose a new mandatory client dependency.
Fabric retains its existing early-login ordering and unknown-payload behavior. Live client
dimension information and its update callbacks require client-side DimLib.
Its client entry point is distribution-filtered, and the shared
mixin configuration keeps client mixins in the `client` list. Configuration uses
NeoForge's local config at `config/dimlib-local.toml`. Dynamic dimensions publish
NeoForge `LevelEvent.Load` and `LevelEvent.Unload`, in addition to DimLib's own
callbacks. Starting any Minecraft test or server still requires the operator's
normal EULA acceptance; the build does not accept it automatically.

Minecraft 26.3 saves world generation settings as `SavedData`, including a
separate snapshot of the dimension map. Each dimension edit updates that
snapshot and marks it dirty, as well as changing the live `LEVEL_STEM` registry.
Snapshots copy live keyed entries rather than numeric-ID iteration, because
removed dimensions leave null holes in the registry's numeric-ID list.
Vanilla dynamically registered holders are explicitly bound without refreezing
unrelated registry tags. These steps are shared by both loaders and are required
for newly added dimensions to survive saving and restarting.

## Dimension callbacks

`DimensionAPI` retains its dimension operations and callback names. Callbacks use
the loader-neutral `DimensionEvent<T>`, supporting `register(listener)`,
`register(Identifier, listener)`, `addPhaseOrdering(first, second)` and `invoker()`.
Use `DimensionEvent.DEFAULT_PHASE` for default-phase ordering. Its identifier
remains `fabric:default` so existing Fabric phase identifiers still match.
Callbacks within a phase run in registration order. Cyclic phase ordering is
rejected. Registering a callback during dispatch takes effect on the next
dispatch. Dimension callbacks isolate listener exceptions as before.

Changing the event field type is a binary API change: rebuild consumers against
this bundled DimLib rather than mixing previously compiled DimLib consumers
with it. Source callers using callback registration and phase ordering retain
the same operations.

The shared phased-event tests run in both builds. The NeoForge `check` task also
inspects the produced artifact for Fabric metadata and loader-specific class
references that must not appear in a native NeoForge runtime.
