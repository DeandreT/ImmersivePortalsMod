# Portal rendering on Minecraft 26.3

This describes the current sequential offscreen renderer. Production rendering code is under
`src/main/java/qouteall/imm_ptl/core/render`, with renderer mixins under `core/mixin/client/render`
and compatibility mixins under `core/compat/mixin`. Portal shaders are in
`src/main/resources/assets/immersive_portals/shaders/core/`.

## Rendering constraints

- Minecraft renders through `com.mojang.renderpearl`, with OpenGL and Vulkan backends. The common
  portal renderer uses that API, not raw OpenGL stencil, clipping or occlusion-query calls.
- A frame runs `GameRenderer.update` -> `extract` -> `render` -> GPU submission. Extraction produces
  `GameRenderState`/`LevelRenderState`; the feature dispatcher has one `PreparedFrame`, and entity
  submissions are consumed by rendering. Another world render cannot be nested inside an active
  `LevelRenderer` frame-graph pass.
- Depth is reversed-Z: clear to `0.0`, nearer fragments have larger values, and the normal depth test
  is `GREATER_THAN_OR_EQUAL`.
- Mapped ring-buffer writes hold one value per buffer instance per frame. Rewriting fog or cloud
  data for another view would overwrite earlier draws; rotating buffers mid-frame can wait on the
  current, unsubmitted frame. Projection and global-setting writes use command-ordered buffer
  uploads and can be updated between views.
- `LevelRenderer` uses `GameRenderer.mainRenderTarget()`. The override in `MixinGameRenderer` and
  target redirection in `MixinSkyRenderer_Target` make offscreen world rendering possible.

## Frame flow: children first, then the main view

`MixinGameRenderer` calls `PortalViewRenderer.renderPortalViews` at the head of `GameRenderer.extract`,
after the main camera update, when the loaded level/player state is consistent and game time advances.

1. Create the main `PortalViewRenderer.ViewNode`. `renderChildViews` collects ordinary and global
   portals, sorts them nearest-first, and applies visibility, distance, frustum, rendering-predicate,
   recursion and layer limits. Main-view portals also use `PortalOcclusionCulling`.
2. For each eligible portal, compute a crop and acquire an RGBA8_UNORM + D32_FLOAT `TextureTarget`
   from `PortalViewTargetPool`, subject to `IPGlobal.portalRenderLimit`. Record the child node and
   target in the parent's identity-keyed maps.
3. `renderViewAndChildren` selects a `DimensionRenderHelper`, pushes `PortalRendering` and
   `WorldRenderInfo`, and switches the client level, renderer/extractor, particle world, camera,
   lightmap, fog, clouds, directional lighting and hit result. It recursively handles children
   **before** extracting and rendering this view. This is recursive traversal, not nested world draws.
4. `renderCurrentView` clears the target, updates the helper's lightmap as needed, extracts the camera,
   applies crop/mirror projection, extracts the level, sets the clipping plane and globals, then calls
   `GameRenderer.renderLevel`. Hand/HUD rendering is skipped for these views. Swapped state is restored
   in `finally`.
5. Cross-portal camera views and queued GUI world views use the same offscreen path. The main view
   then extracts and renders normally. `PortalSurfaceRendering` draws each portal's mesh with the
   already-rendered child texture, projective screen-space sampling, depth testing and depth writes.

All world draws run sequentially on the render thread. They share `GameRenderer.gameRenderState()`;
each view overwrites it and the main extraction runs last. Background readers need snapshots:
`MixinSectionOcclusionGraph` copies camera state for asynchronous full updates.

## Components and resource ownership

| Component | Responsibility |
|---|---|
| `PortalViewRenderer` / `ViewNode` | Traversal, child targets, transformed cameras, context switching and sequential extraction/rendering. |
| `PortalViewTargetPool` | Reusable color/depth targets, resizing, far-view keepers and idle eviction. |
| `PortalSurfaceRendering` / `PortalEntityRenderer` | Portal mesh submission, per-target textures/render types and Iris final-image replacement. |
| `ClientWorldLoader` / `DimensionRenderHelper` | Client dimensions and paired `LevelRenderer`/`LevelExtractor` resources, lightmaps, extra grids and lifecycle. |
| `WorldRenderInfo` / `PortalRendering` | View options, camera transforms, portal layers and active clipping plane. |
| `PortalClipping` / `EntityClipping` | Shader front clipping for a view and CPU clipping for crossing entities. |

The first client dimension retains vanilla's rendering instances; other dimensions are created lazily
with their own renderer, extractor and lightmap. Changing the player's dimension switches the current
instances. `ClientWorldLoader` forwards resize, reload and end-of-frame handling to non-current helpers;
cleanup restores vanilla's original instances before closing secondary resources.

Fog and cloud renderers are pooled per view and receive `endFrame()` individually. A dimension owns
its lightmap and shares it with its extra helpers; each helper tracks `lightmapRenderedFrame` to avoid
repeated updates through that helper in one frame. Projection and globals remain shared, with ordered
writes before each view's draws.

## Front clipping

`PortalClipping` transforms the active destination plane to view space after camera extraction. A zero
vector disables clipping. The plane is offset by `0.01` toward the camera to retain faces exactly on
the portal plane and avoid edge cracks.

- `MixinProjectionMatrixBuffer` extends the **Projection** uniform block by one vec4,
  `ImmPtlClipPlane`. Only the level projection writes an active plane; other projections write zero.
- `MixinCachedIncludeSource` patches `minecraft:projection.glsl`; `MixinPipelineBuilder_Clipping`
  also handles inline Projection declarations and transforms matching vertex/fragment shader pairs.
- World vertex shaders compute `immptl_ClipDistance = dot(ImmPtlClipPlane, viewPosition)` after a
  recognized projection assignment. Fragment shaders discard negative distances. The common shader
  path uses varying location 15; sky, stars, panorama, GUI and screen-quad shaders are excluded.
- Sodium's terrain plane is carried separately in its `u_Globals` block as `ImmPtlSodiumClipPlane`.
  Iris-generated programs have their own transformation hooks, described below.

This is plane clipping, not a stencil mask inside each offscreen render. The final portal mesh defines
the visible opening; cropped targets are rectangular and the section-culling frustum is not cropped.

## Culling and section visibility for views

`MixinLevelRenderer` keeps separate main/view section lists. In views, `sectionOcclusionGraph()` returns
`VisibleSectionDiscovery.PortalViewOcclusionGraph`, so `LevelExtractor.applyFrustum` uses synchronous
breadth-first discovery rather than re-rooting the main view's asynchronous cave-culling graph. Views
disable smart cave culling because their camera may sit inside blocks behind the destination plane.
The main view also temporarily uses synchronous discovery immediately after teleportation.

The real graph still receives loaded-chunk and empty-section changes consumed by extraction. Extra
helpers forward those changes to their dimension's base renderer too. A secondary renderer's stale
graph is invalidated when it becomes the main renderer. Views sharing the main renderer do not move
its section-update tracker or resort its translucent sections; other view renderers can resort normally.

### Renderers and grids for portal views

A vanilla `LevelRenderer.ViewArea` is a fixed section grid. `DimensionRenderHelper.selectForView` selects
it before extraction:

- Reuse a grid covering the view camera, with a four-section margin where possible.
- Otherwise move the nearest movable helper not used by another view this frame, or create an extra
  helper, up to three extras per dimension. Each has its own renderer/extractor on the same level and
  shares the dimension lightmap. The level forwards block changes to extra extractors.
- If no helper can move or be created, use the nearest existing grid unchanged. This can show partial
  terrain rather than resetting grids back and forth every frame.

The player's base grid is never moved for a portal view. `LevelRenderer.repositionCamera` is skipped
in views; other grids move before extraction. Dirty sections outside the selected grid are skipped
when compiling and rebuilt when the grid reaches them. Extra helpers are released after 600 unused
frames. Sodium uses its own terrain storage instead of these extra vanilla grids.

`PortalRenderer.getPortalRenderDistance` also limits non-global views to loaded destination terrain.
`LoadedTerrainRadius` checks successive chunk rings for at least 90% loading every ten frames, with
a minimum radius of two chunks. View fog uses the resulting render distance. Server-side
`ChunkVisibility` controls loading according to portal distance, loading caps and performance level;
rendering cannot display chunks that have not arrived. Global portals use their separate loading policy.

For missing terrain, run `/imm_ptl_client_debug report_portal_views` twice. `ViewDiagnostics` distinguishes
unloaded chunks, unbuilt sections and grid moves. `FeatureSmokeTest` includes far-portal terrain and
block-change scenarios. Its `imm_ptl.featureTest.userWorld` and `userWorldPos` properties can open a
copy of a reported save at a specified `dimension x y z yaw pitch`.

### Hidden portals

`PortalOcclusionCulling` only culls portals seen from the main view. With vanilla, a portal is hidden
when none of the sections touched by its inflated bounding box is reachable in the cave-culling graph.
It renders when uncertain: graph rebuilds, recent teleports, missing grids, global/large portals,
portals within eight blocks, or sections outside the grid. A visible portal stays rendered for ten
additional frames.

With Sodium, it uses `SodiumWorldRenderer.isBoxVisible`. Because those asynchronous results can lag,
a portal must remain in the frustum for ten frames before being skipped. The switch is
`IPCGlobal.cullHiddenPortals`.

## Cropped views and far-view reuse

`PortalViewCrop` projects the portal mesh using projection, view bobbing/damage tilt and rotation,
intersects it with the parent's visible rectangle, adds a two-pixel margin and snaps to the window's
pixel grid. `Mapping` converts uncropped screen coordinates to target coordinates, including mirror
flips. `PortalViewCrop.samplingMatrix(parent, child)` supplies the portal surface's `TextureMat`.

Targets grow immediately, use 64-pixel size steps capped at the window size, and shrink on a 60-frame
check. Large rectangles (over 60% of the window) use full-size targets. If projection crosses the camera
plane, bounds conservatively cover the parent's visible region. Cropping is disabled with shaderpacks,
panorama mode and nausea/portal screen distortion. Cross-portal/GUI root targets are window-sized.
The switch is `IPCGlobal.cropPortalViews`.

With `IPGlobal.reduceFarPortalUpdates`, `FarPortalViewReuse` can reuse a main-view portal image every
second frame beyond 32 blocks and below 5% screen coverage, or every third frame beyond 64 blocks and
below 2%. This requires cropping and exact matches for camera position, screen clip matrix, crop and
the checked portal geometry/destination. The pool retains the target and sampling mapping. It saves
work while stationary, but animated contents update less often. `FeatureSmokeTest` covers hidden
portals, cropped pixels and far-view reuse.

## Portal shapes, entities and camera views

- **At the portal plane:** `portal_view.vsh` sets rasterized z to `0.5w`, retaining the original z/w
  for `portal_view.fsh` to clamp and write through `gl_FragDepth`. This emulates depth clamp for the
  actual portal mesh as the camera approaches or crosses the opening. View cameras use near plane
  `0.005`, or `0.05` with shaderpacks to match Iris' fixed `near` uniform. The portal layer is pushed
  before camera/frustum setup because `FrustumCuller` reads it.
- **Crossing entities:** `CrossPortalEntityRenderer` adds transformed entity render states in the
  destination and tags original/projection states with outer/inner planes. `EntityClipping` tags
  submissions and clips geometry on the CPU through wrapped vertex consumers (quads and triangle
  strips), independently of Sodium/Iris shader choice. Projections retain source lighting.
- **Mirrors:** a negative determinant in the accumulated camera transformation sets `ViewNode.isMirrored`.
  Its extracted projection is flipped horizontally to restore triangle winding, including for Sodium.
  Parent/child sampling mappings undo the image flip, also for nested mirrors and portals in mirrors.
- **Global portals:** their views use the same traversal, but their surfaces are extracted separately
  at the end of `LevelExtractor.extractVisibleEntities`. `GlobalPortalStorage` assigns unique negative
  client entity IDs; view maps use portal identity.
- **Cross-portal camera:** `CrossPortalViewRendering` handles a camera beyond a portal while the player
  remains on the source side, such as third person. Its offscreen color/depth replace the main level
  image before the main hand/3D HUD.
- **GUI worlds:** `GuiPortalRendering.submitNextFrameRendering` queues window-sized depth-enabled
  targets for the next frame's offscreen phase, including their portal children. New submissions for
  the same target replace older ones; omitting the sky leaves a transparent background.

## Mid-frame invalidation and memory

Geometry invalidation can recreate buffers and, with Sodium, wait for previous use. If a renderer
already drew a view in the current frame, `MixinLevelExtractor.modifyShouldInvalidate` postpones that
invalidation until `DimensionRenderHelper.processPostponedInvalidation` at the next frame's start.
A renderer with no ViewArea can initialize immediately. This avoids waiting on the current submit.

Optional memory controls account for multiple dimension renderers:

- `compactTerrainBuffers` reduces vanilla terrain allocation slabs to 32 MiB vertex / 8 MiB index
  and staging to 48 MiB per dispatcher. Large allocations can grow later slabs; a section exceeding
  staging capacity is skipped by `MixinStagingBuffer_SkipOversized`.
- `releaseIdleRenderers` resets terrain data for non-current dimensions unused for two minutes.
  Renderer/extractor objects remain and request a rebuild on reuse. This does not release Sodium's
  terrain memory.
- The target pool is freed as a whole after 600 frames without use; target indices are also texture
  and render-type indices.
- `HeapTrimmer`, when enabled, requests collection during loading and adjusts G1 free-heap ratios.
  On supported Linux JVM/libc combinations it also requests native-heap trimming in a background
  thread every minute. These are memory-management options, not fixed memory guarantees.

## Sodium

Sodium attaches a `SodiumWorldRenderer` to each `LevelRenderer`. `DimensionRenderHelper.setExtractorLevel`
makes the correct renderer current while binding its extractor, and `OnSodiumPresent` forwards chunk
loads from ImmPtl's client chunk cache. `MixinSodiumGlobalUniforms` includes the front plane in the
uniform data and its equality comparison, preventing reuse of a slot with another view's plane.

Portal views use synchronous fallback traversal instead of Sodium's asynchronous main-view culling,
and do not register their camera as main-view movement. Pending main-view cull results are consumed
when a dimension renderer becomes a portal renderer, so queued section changes can proceed.

## Iris (shaderpacks)

Shaderpacks use Iris' OpenGL pipeline, sequentially for each view. Vulkan shaderpack rendering is not
supported by this path. Without a pack, terrain follows the Sodium path.

### Shader programs and view uniforms

`MixinIrisTransformPatcher` transforms non-shadow terrain output from `TransformPatcher.patchSodium`:
it appends the clipping plane to the generated `u_Globals` declaration and computes distance from
`u_ModelViewMatrix * getVertexPosition()` after the pack's vertex `main`. Vanilla-render-type pack
programs from `patchVanilla` use `iris_Projection` and the Iris model-view/position fields. Shadow, sky
and hand programs are excluded. `MixinIrisShaderCreator` also transforms compatible synthesized
fallback programs when a pack omits a render type. Geometry/tessellation programs and unrecognized
source layouts are not transformed; clipping is not guaranteed for arbitrary pack programs.

`PortalViewEyeLight` supplies `eyeBrightness` and the hardcoded eye-sky-brightness value from the view
camera in its current level, rather than the player's coordinates in that level. Biome uniforms still
use the player. These adjustments do not establish independent exposure or temporal history per view.

### Depth targets and final portal surfaces

`MixinIrisRenderTargets` makes `RenderTargets.resizeIfNeeded` consider Java depth-texture identity as
well as Iris' cached version. Versions belong to individual Minecraft targets: same-sized sibling,
nested and main targets can have equal versions but different depth textures. Identity checks also
handle same-size texture recreation without relying on reusable OpenGL names. A depth-only switch
reattaches world-depth framebuffers without reallocating pack color targets. The pre-translucent and
pre-hand copy destinations retain their own depth attachments, including replacements during a real
resize; their copy sources must follow the selected view.

A portal texture already contains its child's finished shaderpack image. The world-pass portal
surface establishes depth, but pack postprocessing would process its color again.
`PortalSurfaceRendering` therefore queues surfaces outside shadow rendering and redraws them after
each view's `IrisRenderingPipeline.finalizeLevelRendering`, before the parent samples that target.
`beginTranslucents` captures the world transform for matching depth. The final redraw uses the view's
current depth attachment, preserving nearer depth-writing foreground geometry. This applies to
sibling portals, nested portals and portals seen in mirrors. It does not establish correct blending
through every translucent foreground or every pack's postprocessing.

## Rendering tests and limits

Fabric client gametests live in `src/gametest`. Run visual tests only headlessly or on an isolated
display, never on the user's active desktop.

- `./gradlew runClientGameTest [-PipBackend=opengl|vulkan]` runs the portal scene in `PortalVisualTest`:
  fixed camera poses around the plane, crossing, entity clipping, mirrors and global portals.
  Screenshots and diagnostics go to `build/visual-test/<backend>/`; poses and expected scene colors
  are described in the test. `-PwithSodium` selects Sodium and the `-sodium` output suffix.
- `-PwithShaders` selects Iris and the bundled diagnostic pack in
  `src/gametest/resources/immptl_test_shaderpack` (OpenGL only). `-PshaderPack=<path to a zip>` selects
  an external pack; merely providing this option is not evidence of compatibility.
- `./gradlew runClientGameTest -PwithShaders -Pimm_ptl.irisDepthTest=true` enables
  `IrisDepthRegressionTest`. It exercises the actual transformed Iris `RenderTargets`, checks real
  framebuffer attachments and copied-depth pixels across equal-version target switches, recreation
  and resizing, and checks scene pixels before and after shader reload. Its non-idempotent diagnostic
  final shader detects repeated processing in siblings, nested portals and a portal inside a mirror;
  an opaque foreground reference checks that replacement respects depth.
- `-Pperf` enables the rendering benchmark; `-Pimm_ptl.perf.memoryOpts=false` disables its memory
  options for comparison. A benchmark is not a visual-correctness or cross-hardware qualification.

The targeted Iris regression covers the nested final-image/depth case, not universal shaderpack
compatibility. It does not validate external Complementary or BSL packs, arbitrary translucency,
exposure/temporal effects, all portal-shape/fuse-view/isometric combinations, every GPU/driver, or
Vulkan parity. Those require separate, scoped validation rather than inference from diagnostic-pack
pixels or screenshot creation.
