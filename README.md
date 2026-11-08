# Immersive Portals — Minecraft 26.3

See through portals and cross them without a loading screen. This development fork targets **Fabric and native NeoForge**, sharing portal, rendering, networking and dimension logic. NeoForge does not load a Fabric jar or require a compatibility loader.

The starting point is [Immersive Portals Revived](https://github.com/riyadmondol2006/Immersive-portals-mod-Revived-), incorporating the [ImmersivelyVibedPortals 26.3 port](https://github.com/nyannoying1337/ImmersivelyVibedPortals/tree/port/26.3). The [original project](https://github.com/iPortalTeam/ImmersivePortalsMod) is archived. Preserve the attribution in [NOTICE](NOTICE) and [DimLib's NOTICE](dimlib/NOTICE).

> Back up worlds before installing a development build. Use the same loader and mod build on the server and every client. A successful build is not proof that every mod combination or shader pack works.

## Runtime targets

Both loaders use Minecraft **26.3** and **Java 25**.

| Loader | Development target | Additional installation |
|---|---|---|
| Fabric | Loader 0.20.1, Fabric API 0.165.2+26.3 | Fabric API on server and clients |
| NeoForge | 26.3.0.101 | No Fabric dependencies |

Matching-loader DimLib and Cloth Config are bundled. Performance mods are optional and are **not** bundled.

Build a normal obsidian portal frame, light it, and walk through. The mod also provides End portals, mirrors, rotated/scaled portals, world wrapping, dimension stacks, custom portal generation and administrative tools. Some tools are accessed through commands rather than the creative inventory; mirror creation is controlled by `enableMirrorCreation`.

## Build

From the repository root, with `JAVA_HOME` pointing to JDK 25:

```sh
sh ./gradlew --no-daemon --max-workers=2 buildAll testAll
```

Use `gradlew.bat` on Windows. The wrapper uses Gradle 9.8.2; no system Gradle is needed. Fabric uses Loom 1.19.4, and NeoForge uses ModDevGradle 2.0.148. Minecraft 26.3 is unobfuscated: neither build uses legacy remapping.

Runtime artifacts:

- Fabric: `build/libs/immersive-portals-7.0.1-dev-mc26.3-fabric.jar`
- NeoForge: `neoforge/build/libs/immersive-portals-7.0.1-dev-mc26.3-neoforge.jar`

`build` and `test` select the root Fabric project; `buildAll` and `testAll` include the native composite. See [native loader development](neoforge/README.md) and [bundled DimLib](dimlib/README.md) for separate builds and dimension persistence checks.

Rebuild the outer artifacts after changing a bundled library. Testing a standalone DimLib jar does not validate an older copy embedded in the mod.

## Compatibility profiles

Development runs accept `-PcompatProfile=<name>`:

- `vanilla`: no optional performance mods.
- `sodium`: Sodium.
- `iris`: Sodium and Iris, without enabling an external shader pack.
- `performance`: Sodium, Lithium, FerriteCore, Entity Culling and ImmediatelyFast.
- `shaders`: the performance profile plus Iris. Fabric client gametests can enable the bundled diagnostic shader pack; native runs need a separately selected pack.

Fabric pins Sodium 0.9.2. NeoForge pins Sodium 0.9.3-beta.4 for Sodium/performance profiles and the original 0.9.3-beta.1 for Iris/shader profiles with Iris 1.11.7. Beta.4 requires Iris 1.11.8 or newer and must not be paired with 1.11.7; beta.1 is a separate published API-compatible pairing, not a metadata override. All runtime jars retain their original dependency checks and bytes.

The other pinned targets are Iris 1.11.7, Lithium 0.26.3, FerriteCore 9.0.0, Entity Culling 1.11.3 and ImmediatelyFast 1.17.1, using their **26.3, loader-specific** artifacts. Exact coordinates are in [gradle.properties](gradle.properties). Profile selection supplies development dependencies; it is not a certification of arbitrary versions or additional mods.

Iris requires the OpenGL backend. Portal views use the shader pack's finished image and are redrawn after its final world pass. Nearer opaque geometry must still occlude them. Iris world-depth attachments track actual texture identity across sequential portal views, while copied-depth targets retain their separate attachments.

Shader-pack limits:

- Glass, water, nested views and mirrors can interact with a pack's exposure and postprocessing. There is no universal pre-tonemap color-buffer contract across shader packs.
- Lighting, temporal history and non-sRGB presentation behavior need pack-specific checks. Do not infer support for every pack from the diagnostic pack.
- Each visible portal adds another world view, so shader work grows with the number of views.

The inherited Flywheel, Cardinal Components and Gravity Changer integrations are excluded from these builds. They are not declared compatible with 26.3.

## Verification

Non-game runner safety tests:

```sh
python -m unittest discover -s tests -v
```

Fabric's client gametest suite includes feature, rendering, teleport validation, private tracking, cut/paste and passenger-tree regressions:

```sh
sh ./gradlew compileGametestJava
sh ./gradlew runClientGameTest -PcompatProfile=performance
```

Run visual tests only on an **owned private display**, never an existing desktop display. Isolate the runtime directory and remove inherited Wayland/session variables. On software-rendered Linux, use `SDL_VIDEO_FORCE_EGL=1`, `LIBGL_ALWAYS_SOFTWARE=1` and `SDL_VIDEODRIVER=x11`. Set `-Djava.awt.headless=true` for test JVMs so a failed test cannot hang on a Java crash dialog; Minecraft's SDL window still uses the private display. Bound test-process lifetimes and retain failure logs.

The opt-in Iris attachment/depth regression uses the diagnostic shader pack:

```sh
sh ./gradlew runClientGameTest -PwithShaders \
  -Pimm_ptl.irisDepthTest=true \
  -Pimm_ptl.visualTest.skip=true -Pimm_ptl.featureTest.skip=true
```

For the focused custom-datapack portal regression, use `-Pimm_ptl.featureTest.only=customportal -Pimm_ptl.visualTest.skip=true`. It creates a disposable creative world, generates a separate Nether chunk in the destination's region, then reopens the save with the test datapack. This exercises the normal existing-region frame search rather than only the cheaper cold-destination path.

`.github/scripts/smoke_test.py` exercises a packaged Fabric jar with real client/server processes. Its scratch directory must be new or marked as owned by that runner. Offline test servers bind to localhost. Do not point test runs at existing saves; obtain the operator's Minecraft EULA acceptance before starting disposable servers.

Keep the smoke runner's default render distance of 6 for its fixed local-terrain crop. Non-shader runs require that same crop to contain green terrain before crossing and after returning; a crop outside the initially loaded terrain is a fixture error, not evidence that return terrain disappeared.

Development-classpath tests, packaged-artifact checks, dedicated-server launches and real client tests cover different failure modes. Native `verifyLoaderIsolation` checks the produced jar's descriptors, access transformer and dependency coordinates, and rejects Fabric/Mod Menu class references.

The standalone [native real-client runner](neoforge/client-test/README.md) exercises the packaged jar with a real client and integrated server, authentic portal prediction, settled crossings and return-framebuffer checks. The [native recovery harness](neoforge/recovery-test/README.md) separately exercises server admission and recovery failures. Its synthetic connections do not establish connected multiplayer behavior; neither harness qualifies remote TCP multiplayer or hardware performance.

## Configuration and API

Configuration is in `config/immersive_portals.json`. Lower simulation/render distances and portal depth when many views are active. Compact terrain buffers, idle-renderer release and heap trimming are configurable; Sodium manages its own terrain memory.

The mod API manages portals, dynamic dimensions, remote chunks and GUI world views. Public callbacks now use loader-neutral `platform_shared.Event` and DimLib `DimensionEvent`. Registration and phase-ordering operations remain available, but **precompiled consumers of the old Fabric event field descriptors must be rebuilt**. See [the native API notes](neoforge/README.md#platform-boundaries).

The original [API guide](https://qouteall.fun/immptl/wiki/API-for-Other-Mods.html) and [implementation details](https://qouteall.fun/immptl/wiki/Implementation-Details) describe older Minecraft versions; consult the current source for exact signatures.

## Credits

- qouteall and the original Immersive Portals contributors.
- nyannoying1337, for the 26.3 renderer and DimLib ports.
- riyadmondol2006 and the Revived contributors, for the reused development baseline.

Licensed under [Apache-2.0](LICENSE). DimLib's license and notices are retained separately.
