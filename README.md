# Immersive Portals Mod (Revived)

A Minecraft mod that provides see-through portals and seamless teleportation. It can also create "Non-Euclidean" space effects.

![immptl.png](https://i.loli.net/2021/09/30/chHMG45dsnZNqep.png)

This is a community fork, updated to **Minecraft 26.3** and tuned to use less memory. The [original project](https://github.com/iPortalTeam/ImmersivePortalsMod) by qouteall is archived and no longer maintained, so please report problems here rather than to the original author.

**[Download the latest build](https://github.com/riyadmondol2006/Immersive-portals-mod-Revived-/releases/latest)**

> [!WARNING]
> Portal rendering was redesigned for Minecraft's new graphics layer. Back up your worlds before installing, and don't use this on worlds you can't afford to lose.

## Requirements

* Minecraft 26.3 with [Fabric Loader](https://fabricmc.net/use/) 0.19.5 or newer (tested with 0.20.1)
* Java 25
* [Fabric API](https://modrinth.com/mod/fabric-api) 0.161.0 or newer (tested with 0.165.2)

DimLib and Cloth Config are bundled in the jar. [Sodium](https://modrinth.com/mod/sodium) and [Iris](https://modrinth.com/mod/iris) are optional, and were tested with `mc26.3-0.9.2-fabric` and `1.11.7+26.3-fabric`.

Saved data and add-ons keep working as far as they target 26.3, because the mod ID is still `immersive_portals`. Builds for older Minecraft versions are in the [`v6.0.6-mc1.21.1` releases](https://github.com/riyadmondol2006/Immersive-portals-mod-Revived-/releases).

## What works

Each of these is checked automatically in the real game on every push:

* See-through Nether portals, crossed without a loading screen, in singleplayer and on a dedicated server
* End portals, the End and the return trip
* Portals to far places in the same dimension, rotating portals and mirrors
* World wrapping and dimension stacks
* The `/portal` commands, portal wand, portal helper, command stick and custom portal generation from data packs
* Sodium and Iris (without a shader pack)

Shader packs are experimental and aren't part of the automated checks. Iris needs the OpenGL backend.

## Memory use

Most of what this mod used to cost was memory it wasn't using, so it now uses less than the unmodded game in the scenes below. These were measured with one portal in view on an AMD Renoir APU, using the official launcher's Java flags, at 480p in singleplayer:

| Render distance 12 | Unmodded game | Mod, optimizations off | **Mod** |
|---|---:|---:|---:|
| Process memory | 1,897 MB | 1,870 MB | **1,468 MB** |
| GPU memory | 424 MB | 688 MB | **279 MB** |

| Other scenes | Unmodded game | Mod, optimizations off | **Mod** |
|---|---:|---:|---:|
| Render distance 6: process memory | 1,414 MB | 1,521 MB | **1,276 MB** |
| Render distance 6: GPU memory | 423 MB | 688 MB | **277 MB** |
| Render distance 6, no portal nearby: GPU memory | 423 MB | 425 MB | **209 MB** |
| Sodium and Iris, render distance 6: process memory | not measured | 2,001 MB | **1,500 MB** |
| Dedicated server, no players: process memory | 756 MB | 815 MB | **715 MB** |

These runs last a few minutes with a single portal, and each was mostly run once, so treat small differences as noise. Long sessions, many portals and busy servers aren't covered, and more portals in view will use more than this.

### What was using the memory, and what changed

| Cost | Change | Config option |
|---|---|---|
| Each dimension's renderer reserved terrain buffers in 128 MiB blocks, mostly empty | Blocks are now 32 MiB (8 MiB for indices), and grow if one mesh needs more. This helps the player's main renderer too | `compactTerrainBuffers` |
| Java kept the free memory left over from loading: about 300 MB of live data could hold 800 MB | After joining a world, Java is told to return free memory, and it does so once while the loading screen hides the pause | `trimJavaHeap` |
| On Linux, the C library kept about 200 MB of freed native memory after loading | Returned after joining, and then once a minute on a background thread | `trimJavaHeap` |
| Dimensions you visited once kept their terrain data | Released after 30 seconds unseen, and rebuilt when you see them again, so terrain can take a moment to appear | `releaseIdleRenderers` |
| Portal images stayed allocated after you looked away | Freed after 600 frames with no portal rendered | Always on |

The options are in the mod's config screen under the client tab, or in `config/immersive_portals.json`. Some limits:

* **Garbage collection:** a smaller Java heap collects more often. In testing this went from 113 ms to about 170–250 ms of pauses over two minutes, with a similar longest pause.
* **Frame rate:** it hasn't been measured directly, because the benchmark runs at a fixed pace. The CPU time spent rendering 8 portal views didn't change (about 2.5 ms per frame with the optimizations on or off). Smaller terrain buffers could add some draw batches at very high render distances.
* **Heap trimming:** it only applies to the G1 collector, which the official launcher uses. If you set `-Xms`, Java won't go below that value.
* **Sodium:** it manages its own terrain memory, so the terrain buffer and renderer options don't reduce its GPU memory.

To save more, lower your render distance, or choose the Performance preset in the mod's config screen.

## Releases

Every push runs [this workflow](.github/workflows/release.yml), and a release is only created if all of it passes:

1. Build the jar and run the unit tests.
2. Start a real Fabric server with the jar, and check that a portal stays alive.
3. Start a real Fabric client with the jar, with and without Sodium and Iris. It checks that the Nether is visible through a portal, drops the player through, waits for the idle overworld to be released, returns, and checks that the terrain and portal view came back.
4. Compare memory with an unmodded baseline, and fail if the mod costs more than the [budgets](.github/scripts/smoke_test.py).
5. Run the in-game feature suite.
6. Publish the jar and its checksum as a [GitHub release](https://github.com/riyadmondol2006/Immersive-portals-mod-Revived-/releases).

Pushes to the default branch become the latest release, and other branches become pre-releases. Add `[skip ci]` to a commit message to skip a run. Each run keeps its screenshots, logs and memory numbers in the `smoke-test-evidence` artifact.

## Building and testing

You need JDK 25. DimLib has no official 26.x release, so its port lives in [`dimlib/`](dimlib) and is built along with the mod.

```sh
./gradlew build
```

The jar is written to `build/libs/`. To run the same checks as the release workflow, you need Python 3, `xdotool`, `ffmpeg` and a display:

```sh
JAR=$(ls build/libs/*-fabric.jar)
python3 .github/scripts/smoke_test.py server --jar "$JAR" --work /tmp/smoke-server
xvfb-run -a -s "-screen 0 854x480x24" python3 .github/scripts/smoke_test.py client --jar "$JAR" --work /tmp/smoke-client
xvfb-run -a -s "-screen 0 1280x720x24" ./gradlew runClientGameTest -Pimm_ptl.visualTest.skip=true
```

If you don't have `xvfb-run`, `gamescope --backend headless -W 854 -H 480 -w 854 -h 480 --` works in its place, but it hides the exit code, so check the output for `[smoke] PASSED`. The feature suite writes its results to `build/feature-test/report.txt`.

The rendering design is described in [docs/rendering-26.3.md](docs/rendering-26.3.md). For general mod development setup, see the [Fabric documentation](https://docs.fabricmc.net/develop/).

## API

This mod also provides an API for:

* Managing see-through portals
* Dynamically adding dimensions
* Synchronizing remote chunks to the client
* Rendering the world into a GUI
* Other utilities

See the [API description](https://qouteall.fun/immptl/wiki/API-for-Other-Mods.html) and [implementation details](https://qouteall.fun/immptl/wiki/Implementation-Details). These pages describe the original 1.21 version, so some details differ.

## Credits

* Created by [qouteall](https://www.patreon.com/qouteall) and contributors, licensed under [Apache-2.0](LICENSE)
* The port to 26.3 is based on [ImmersivelyVibedPortals](https://github.com/nyannoying1337/ImmersivelyVibedPortals) and its [DimLib port](https://github.com/nyannoying1337/DimLib) by nyannoying1337. See [NOTICE](NOTICE) for the changes
* [Wiki](https://qouteall.fun/immptl/wiki/) and [Discord server](https://discord.gg/BZxgURK) of the original project
