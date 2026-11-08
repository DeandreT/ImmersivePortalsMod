# Native recovery event qualification

This standalone development mod qualifies the native **NeoForge 26.3.0.101 dedicated server**.
It is not included by either production Gradle settings file, bundled in the release jar, or
activated by the production `runServer` or `runClient` tasks. It has no CI/pipeline registration.

## Run explicitly

Use an existing Java 25 installation and the repository's existing Gradle/NeoForm caches.
From the repository root, first refresh the production jar in the assigned build slot:

```sh
JAVA_HOME="$HOME/.cache/immersive-portals/toolchains/jdk-25" \
GRADLE_USER_HOME="$HOME/.cache/immersive-portals/gradle" \
JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=2 -Djava.awt.headless=true' \
./gradlew --no-daemon --max-workers=2 buildAll testAll compileGametestJava
```

Then explicitly accept the EULA for the new disposable server and run the harness:

```sh
JAVA_HOME="$HOME/.cache/immersive-portals/toolchains/jdk-25" \
GRADLE_USER_HOME="$HOME/.cache/immersive-portals/gradle" \
JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=2 -Djava.awt.headless=true' \
./gradlew -p neoforge/recovery-test --no-daemon --max-workers=2 recoveryTest -PacceptEula=true
```

The equivalent explicit launcher is `python3 neoforge/recovery-test/test_server.py --accept-eula`.
Its `--timeout` bounds the whole inner build/server process (900 seconds by default).
`--artifact` selects a packaged native parent jar; its metadata must identify version `7.0.1-dev`.
There is no automatic source substitution or parent build. Stale production inputs or a stale
bundled DimLib jar are rejected before launch.

The launcher owns a fresh `/tmp/opencode/ip-native-recovery-world-*` directory, binds only to
loopback with an unused port, disables RCON/query, and requires a whitelist. It never connects to
an existing server/world. The internal `runRecoveryServer` task requires the launcher's ownership
marker and nonce. Ordinary compilation/build tasks do not launch a game.

## Conditions checked

- Actual dedicated physical side, main-mod server initialization, loaded NeoForge version, exact
  packaged parent jar path/hash, and loaded DimLib/Cloth jar hashes matching its native bundles.
  Production source directories cannot masquerade as the packaged mod.
- A retained JVM class-initialization log must show that the client-only entrypoint and selected
  core client statics stayed cold. This checks the exercised dedicated paths, not annotation
  stripping or every possible client method in a mixed-side class.
- Real `EntityJoinLevelEvent` cancellation at first/middle/last admission for both recreation and
  reuse; an actual test-only registered `EntityType` throws from `onAddedToLevel` **after** normal
  full registration at those same slots. Every failure has a corrected retry.
- Source EJL admission stays closed during compensation. Captured originals must still recover
  through the real callback constructor/accessors; an unrelated source probe remains refused.
- Real native dismount/mount cancellation, an explicit `force=true` mount refusal/retry, and
  ordered source-hierarchy repair without opening normal destination permissions.
- Real `EntityTravelToDimensionEvent` exactly once per original, before any source-world,
  position or hierarchy mutation, including same-world refusals and corrected retries.
- Original Villager HOME/JOB_SITE memory, native `isAddedToLevel`, exact registrations,
  preexisting visible/hidden candidate ownership, hidden ID/UUID foreign objects and lease release.
- Two nonglobal concrete `ServerPlayer` objects with vanilla listeners and real Netty
  `EmbeddedChannel` connections, second-carried-player EJL refusal, world/global membership,
   unaffected third player, outgoing dimension corrections, and the restored ordered passenger
   graph **after both** corrections. The corrected retry moves their exact registrations.
   Exact traversal order and a check inside the refusal event prove the first player is already
   fully registered; four destination join events occur before refusal and four more on retry.

Server fixtures are **not actual logged-in clients**. No incoming teleport acknowledgements,
client settlement, global online-player admission, or native tracker-watcher delivery are faked
or claimed. These gaps remain explicit in every successful report, whose status is
`PASS_WITH_LIMITATIONS`, not an unqualified all-pass result.
The concrete connections use NeoForge's public `NetworkRegistry.configureMockConnection`
game-test API solely to supply outbound channel compatibility. That is test metadata, not a
real client negotiation, packet decoder or incoming teleport acknowledgement.

## Results and safe shutdown

The mod emits `IP_NATIVE_RECOVERY_BEGIN`, per-case `...CASE_PASS` / `...CASE_FAIL`, and a final
`IP_NATIVE_RECOVERY_DONE` marker, writes JSON, and asks the real server to stop normally.
The launcher requires every named case, nonzero assertions, no required skips/failures, clean
completion markers, and matching loaded artifacts. Any missing case/report, startup failure,
timeout or incomplete compensation fails the run.

Logs, crash reports, `runtime-results.json` and `summary.json` remain in a separate
`/tmp/opencode/ip-native-recovery-report-*` directory after the owned world is removed.
Cleanup requires Linux PID handles (`pidfd` support), checked before launch. It signals only
the acquired launch process and JVMs verified by the current user, exact Java executable,
process start identity, and either the fresh world directory or its exact saved Gradle lease.
Ownership reads are bound to a live handle; detached JVMs never authorize group-wide signals.
SIGTERM/SIGINT run cleanup, with cancellation deferred during child acquisition and cleanup
without masking signals inherited by the child. If safe termination fails, the world and its
ownership evidence remain intact and `summary.json` records `preserved_world`. No existing
desktop, server or another session's JVM is selected by name.

Launcher-only acceptance tests (no Gradle/game launch):

```sh
python3 neoforge/recovery-test/test_launcher.py
```
