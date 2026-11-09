# Native NeoForge loader

This module targets Minecraft 26.3, NeoForge 26.3.0.101 and Java 25. It compiles
the portal and renderer implementation directly from `../src/main/java`; it
does not load a Fabric jar or use a compatibility loader. DimLib's native
module is included as a composite build. Both DimLib and Cloth Config's native
artifacts are bundled using NeoForge jar-in-jar metadata.
The separate [early Iris processor library](iris-bootstrap/README.md) is bundled
as an FML `LIBRARY` and is also a runtime-only dependency for source-set launches.
JarJar packaging alone does not put that early service on ModDev's development
runtime classpath. Keep the library separate from ordinary mod classes.
NeoForm utility tasks, including asset downloads, use the same Java 25 toolchain
as compilation and game launches; a separate Java 21 installation is not required.

From the repository root, with `JAVA_HOME` pointing to a JDK 25 installation:

```sh
./gradlew -p neoforge --max-workers=2 build
./gradlew -p neoforge --max-workers=2 runClient -PcompatProfile=sodium
./gradlew -p neoforge --max-workers=2 runServer
```

The server run uses its own `neoforge/build/run-server` directory. Read and
accept Minecraft's EULA yourself before running it. `-PipRunDir=<directory>`
selects a different development run directory; do not point disposable tests at
an existing world. `-PipBackend=opengl|vulkan` selects the client backend.

The compatibility profiles are `vanilla`, `sodium`, `iris`, `performance` and
`shaders`. Performance includes Sodium, Lithium, FerriteCore, EntityCulling and
ImmediatelyFast; shaders adds Iris. Mod versions come from the root project's
properties. Iris profiles install the shader loader, not an external shader
pack. Supply and select a compatible pack separately.
Iris profiles use the separately pinned `sodium_iris_neoforge_version` runtime;
the native loader API and Iris's Sodium API are separate compatibility constraints.
Published dependency metadata remains enforced and runtime jars are not rewritten.

NeoForge Sodium publishes a small outer launcher jar with the actual renderer
and configuration API in its nested mod jar. `extractSodiumCompileJar` extracts
that exact dependency for javac only. Development runtime profiles still use
the original published outer jar; no dependency is rewritten or bundled into
Immersive Portals by that task.

## Platform boundaries

`platform_shared` provides ordered portal callbacks, lifecycle bridges, vanilla
packet construction and explicit loader implementations for registry, payload,
metadata and client hooks. Fabric's four hook implementations and entrypoints
are excluded from this module and replaced by native NeoForge implementations.
The Fabric networking/render lifecycle mixins are not loaded here; the common
vanilla mixins remain required with their original injection requirements.

The native forced-main-thread section-rebuild mixin targets NeoForge's section
compilation overloads. Both the synchronous and asynchronous paths retain the
`AddSectionGeometryEvent` renderer list, including when a portal view forces a
synchronous rebuild. The null-section guard remains necessary for views outside
the current `ViewArea` grid.

The native Iris compatibility mixin gives Iris's custom quad-pass placeholder a
sampler-free, color-only managed shader/layout. Those passes bind their actual GL programs
and samplers through Iris's `CustomPass` backend path; the placeholder therefore
does not declare a vanilla blit sampler that is never bound through `RenderPass`.
Frontend validation stays enabled, and Iris's real depth/composite programs and
draw calls remain responsible for rendering.
Iris's vertex-format extension also retains the full native binding-slot list,
including additional nonzero bindings; it does not replace the list with only
the extended slot zero. Native draw validation remains enabled for every slot.

Public portal callback fields use `platform_shared.Event` on both loaders, with
the existing registration, invocation and phase-ordering operations. Add-ons
linking directly to the former Fabric `Event` field descriptors must be rebuilt
against this API. DimLib likewise exposes its loader-neutral `DimensionEvent`.

`prepareSharedSources` translates Fabric's physical-side annotation metadata to
NeoForge's `OnlyIn` without introducing a Fabric runtime dependency. NeoForge
26.3 does not strip members carrying `OnlyIn`; those annotations do not establish
side safety. Client initialization uses a separate `@Mod(dist = Dist.CLIENT)`
entrypoint and client setup event, while common call sites retain explicit side
guards. Dedicated-server qualification must exercise the common initialization
and server paths without invoking client initialization. Other shared source
is compiled unchanged. The native access
transformer mirrors the Fabric access widener and is packaged in the jar.
It also provides the debug-entry and noise-router method access that Fabric
API's transitive wideners supply to the Fabric build.

Wand input, rendering, display updates and keybinding-dependent tooltip/settings
text live in `PortalWandItemClient`. The common item keeps common `Player`, `Level`
and item signatures, with physical-side guards before its client helper calls.
Client chunk-manager/possession operations, packet consumers, global-portal client
state, collision HUD/world iteration and camera scaling likewise live in cold
client helpers. Common packet records, saved-data storage and collision/scaling
services do not require physical client types during server class verification.

The native adapter declares custom portal-generation datapack registries during
`NewDatapackRegistryEvent`. Shared block/item/entity construction is deferred
until the first `RegisterEvent`, after NeoForge opens the built-in registries,
and runs once before the registration queue is processed. Repeated declarations
of the same datapack key and codec are deduplicated; late new keys are rejected.

The NeoForge bridges publish dimension-travel and chunk lifecycle events,
including the chunk-sent event needed to synchronize native attachments through
portal views. Chunk packets include NeoForge's auxiliary-light data, and watcher
events cover remote views as well as the player's current dimension. The shared configuration handshake retains the missing-mod and
version-tolerance settings; native channel registration does not replace that
policy. Alternate dimension generation, dynamic dimensions, dimension stacks,
portal commands and the Sodium/Iris render integration use the shared code.

`verifyLoaderIsolation` scans native class files and metadata for accidental
Fabric or Mod Menu dependencies. It runs as part of `check`. Dedicated-server
and client launches are still required to validate side safety and mixins.
