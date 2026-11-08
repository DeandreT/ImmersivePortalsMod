package qouteall.imm_ptl.gametest;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.gl.texture.DepthBufferFormat;
import net.irisshaders.iris.mixinterface.GpuTextureInterface;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.targets.Blaze3dRenderTargetExt;
import net.irisshaders.iris.targets.RenderTargets;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL21C;
import org.lwjgl.opengl.GL30C;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.miscellaneous.IPortalInitialScreen;
import qouteall.imm_ptl.core.portal.Mirror;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.PortalViewRenderer;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Opt-in OpenGL regression: {@code -PwithShaders -Pimm_ptl.irisDepthTest=true}.
 * Requires an isolated/headless display, like the other client rendering tests.
 * Checks applied Iris mixins and actual pixels, not just screenshot creation. The pack's diagnostic
 * transform does not model arbitrary packs, translucent foregrounds or temporal postprocessing.
 */
public class IrisDepthRegressionTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        if (!Boolean.getBoolean("imm_ptl.irisDepthTest")) {
            return;
        }
        check(FabricLoader.getInstance().isModLoaded("iris"), "Iris depth regression requires -PwithShaders");
        check(context.computeOnClient(client -> RenderSystem.getDevice().getDeviceInfo().backendName()
            .equalsIgnoreCase("OpenGL")), "Iris depth regression requires the OpenGL backend");
        // Keep Iris and OpenGL-specific class resolution out of the normal, Iris-absent test path.
        WithIris.run(context);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class WithIris {
        private static final String PACK = "ImmPtlDepthRegressionPack";
        private static final Vec3 WHITE_REFERENCE = new Vec3(-14, 114, -19.99);
        private static final Vec3 BLUE_REFERENCE = new Vec3(-8.5, 103.5, 3.01);

        private record SavedPack(String name, boolean enabled, Path configuration, byte[] contents) {}

        private record SavedState(
            SavedPack pack, boolean disableTeleportation, int maxLayer, int renderLimit,
            boolean bob, int fov, int renderDistance, boolean hudHidden
        ) {}

        private record Frame(int width, int height, int[] pixels, Matrix4f clip, Vec3 camera) {}

        static void run(ClientGameTestContext context) {
            context.runOnClient(client -> {
                if (client.gui.screen() instanceof IPortalInitialScreen screen) {
                    screen.onClose();
                }
            });
            context.waitForScreen(TitleScreen.class);
            SavedState saved = context.computeOnClient(client -> new SavedState(
                savePack(),
                IPGlobal.disableTeleportation, IPGlobal.maxPortalLayer, IPGlobal.portalRenderLimit,
                client.options.bobView().get(), client.options.fov().get(),
                client.options.renderDistance().get(), client.gui.hud.isHidden()
            ));
            try {
                context.runOnClient(client -> {
                    installPack();
                    IPGlobal.disableTeleportation = true;
                    IPGlobal.maxPortalLayer = 2;
                    IPGlobal.portalRenderLimit = 16;
                    client.options.bobView().set(false);
                    client.options.fov().set(70);
                    client.options.renderDistance().set(8);
                    if (!client.gui.hud.isHidden()) {
                        client.gui.hud.toggle();
                    }
                });
                try (var singleplayer = context.worldBuilder()
                    .adjustSettings(settings -> settings.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE))
                    .create()) {
                    TestServerContext server = singleplayer.getServer();
                    setUpScene(server);
                    waitForScene(context);
                    context.runOnClient(client -> exerciseAttachments());
                    assertScene(context, "initial render");

                    context.runOnClient(client -> reload());
                    waitForScene(context);
                    context.runOnClient(client -> exerciseAttachments());
                    assertScene(context, "shader reload");
                }
            }
            finally {
                context.runOnClient(client -> {
                    IPGlobal.disableTeleportation = saved.disableTeleportation();
                    IPGlobal.maxPortalLayer = saved.maxLayer();
                    IPGlobal.portalRenderLimit = saved.renderLimit();
                    client.options.bobView().set(saved.bob());
                    client.options.fov().set(saved.fov());
                    client.options.renderDistance().set(saved.renderDistance());
                    if (client.gui.hud.isHidden() != saved.hudHidden()) {
                        client.gui.hud.toggle();
                    }
                    restorePack(saved.pack());
                });
            }
        }

        private static void installPack() {
            Path directory = Iris.getShaderpacksDirectory().resolve(PACK);
            try {
                for (String resource : IrisTestPack.FILES) {
                    Path target = directory.resolve(resource);
                    Files.createDirectories(target.getParent());
                    try (InputStream input = IrisDepthRegressionTest.class.getResourceAsStream(
                        "/immptl_test_shaderpack/" + resource
                    )) {
                        check(input != null, "Missing test shader " + resource);
                        String source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                        Files.writeString(target, source.replace(
                            "#define IMMPTL_DEPTH_REGRESSION 0", "#define IMMPTL_DEPTH_REGRESSION 1"
                        ));
                    }
                }
                // Only this test-owned pack is touched. The selected pack/configuration is restored in finally.
                Files.deleteIfExists(Iris.getShaderpacksDirectory().resolve(PACK + ".txt"));
                Iris.getIrisConfig().setShaderPackName(PACK);
                Iris.getIrisConfig().setShadersEnabled(true);
                reload();
            }
            catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

        private static void reload() {
            try {
                // Iris.reload() initializes its config from disk, discarding unsaved selection changes.
                Iris.getIrisConfig().save();
                Iris.reload();
            }
            catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

        private static SavedPack savePack() {
            Path configuration = (Path) field(Iris.getIrisConfig(), "propertiesPath");
            try {
                return new SavedPack(Iris.getIrisConfig().getShaderPackName().orElse(null),
                    Iris.getIrisConfig().areShadersEnabled(), configuration,
                    Files.exists(configuration) ? Files.readAllBytes(configuration) : null);
            }
            catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

        private static void restorePack(SavedPack saved) {
            try {
                Iris.getIrisConfig().setShaderPackName(saved.name());
                Iris.getIrisConfig().setShadersEnabled(saved.enabled());
                reload();
            }
            finally {
                try {
                    if (saved.contents() == null) {
                        Files.deleteIfExists(saved.configuration());
                    }
                    else {
                        Files.write(saved.configuration(), saved.contents());
                    }
                }
                catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            }
        }

        private static void setUpScene(TestServerContext server) {
            server.runCommand("time set noon");
            server.runCommand("weather clear");
            server.runCommand("gamerule minecraft:advance_time false");
            server.runCommand("gamerule minecraft:advance_weather false");
            // Remote rooms are outside the root frustum but close enough to share terrain renderers;
            // this fixture must not exhaust the separate renderer-grid budget of a single dimension.
            for (int x : new int[]{0, 1000, 1064, 1128, 1192}) {
                server.runCommand("forceload add " + (x - 32) + " -32 " + (x + 32) + " 32");
                server.runCommand("fill " + (x - 16) + " 101 -19 " + (x + 16) + " 124 19 minecraft:air");
                for (int z : new int[]{-20, 20}) {
                    server.runCommand("fill " + (x - 16) + " 101 " + z + " " + (x + 16)
                        + " 144 " + z + " minecraft:white_concrete");
                }
            }
            // Matching blue faces: one obscures the right portal, one is an unobstructed reference.
            server.runCommand("fill 2 102 2 3 104 2 minecraft:blue_concrete");
            server.runCommand("fill -9 102 2 -8 104 2 minecraft:blue_concrete");
            server.runOnServer(instance -> {
                ServerLevel world = instance.getLevel(Level.OVERWORLD);
                portal(world, new Vec3(-3, 104, 0), new Vec3(1000, 104, 0), false, false);
                portal(world, new Vec3(3, 104, 0), new Vec3(1064, 104, 0), false, false);
                portal(world, new Vec3(1000, 104, -3), new Vec3(1128, 104, 0), false, false);
                portal(world, new Vec3(0, 110, 0), new Vec3(0, 110, 0), true, false);
                // Seen only in the mirror: the central reflected ray reaches y about 118.5 at z=16.
                portal(world, new Vec3(0, 118.5, 16), new Vec3(1192, 118.5, 0), false, true);
                var player = instance.getPlayerList().getPlayers().getFirst();
                player.getAbilities().flying = true;
                player.onUpdateAbilities();
            });
            server.runCommand("tp Player0 0.0 102.0 12.0 180.0 0.0");
        }

        private static void portal(
            ServerLevel world, Vec3 origin, Vec3 destination, boolean mirror, boolean faceBackwards
        ) {
            Portal portal = mirror ? Mirror.ENTITY_TYPE.create(world, EntitySpawnReason.COMMAND)
                : Portal.ENTITY_TYPE.create(world, EntitySpawnReason.COMMAND);
            check(portal != null, "Cannot create Iris regression portal");
            portal.setOriginPos(origin);
            portal.setOrientationAndSize(new Vec3(faceBackwards ? -1 : 1, 0, 0), new Vec3(0, 1, 0),
                faceBackwards ? 8 : 5, faceBackwards ? 8 : 5);
            portal.setDestinationDimension(Level.OVERWORLD);
            portal.setDestination(destination);
            check(world.addFreshEntity(portal), "Cannot spawn Iris regression portal");
        }

        private static void waitForScene(ClientGameTestContext context) {
            context.waitFor(client -> client.level != null && client.level.dimension() == Level.OVERWORLD
                && Iris.getPipelineManager().getPipelineNullable() instanceof IrisRenderingPipeline, 1200);
            context.waitFor(client -> {
                for (int x : new int[]{1000, 1064, 1128, 1192}) {
                    if (!client.level.getChunkSource().hasChunk(x >> 4, 0)) {
                        return false;
                    }
                }
                return true;
            }, 2400);
            PortalVisualTest.waitIdle(context);
            context.waitTicks(40);
        }

        /** Exercise the real transformed Iris class without disturbing the active world's pipeline. */
        private static void exerciseAttachments() {
            Object pipeline = Iris.getPipelineManager().getPipelineNullable();
            check(pipeline instanceof IrisRenderingPipeline, "Diagnostic shaderpack did not activate");
            PackDirectives directives = (PackDirectives) field(pipeline, "packDirectives");
            TextureTarget first = target("Iris regression first", 64, 64);
            TextureTarget second = target("Iris regression second", 64, 64);
            RenderTargets targets = null;
            try {
                int version = ((Blaze3dRenderTargetExt) first).iris$getDepthBufferVersion();
                check(version == ((Blaze3dRenderTargetExt) second).iris$getDepthBufferVersion(),
                    "Fixture must exercise different textures with identical per-target versions");
                targets = new RenderTargets(64, 64, first.getDepthTexture(), version, DepthBufferFormat.DEPTH32F,
                    directives.getRenderTargetDirectives().getRenderTargetSettings(), directives);
                targets.createFramebufferWritingToMain(new int[]{0});
                Object colorTarget = targets.getOrCreate(0);
                GpuTexture opaqueCopy = targets.getDepthTextureNoTranslucents();
                GpuTexture handCopy = targets.getDepthTextureNoHand();
                // Siblings and a nested child -> parent transition, all with identical size/version.
                for (TextureTarget destination : List.of(second, first, second, first)) {
                    boolean resized = targets.resizeIfNeeded(version, destination.getDepthTexture(), 64, 64,
                        DepthBufferFormat.DEPTH32F, directives);
                    check(!resized, "A depth-only switch must not resize shaderpack color targets");
                    check(targets.getOrCreate(0) == colorTarget && targets.getDepthTextureNoTranslucents() == opaqueCopy
                        && targets.getDepthTextureNoHand() == handCopy, "A depth-only switch reallocated pack resources");
                    assertAttachments(targets, destination.getDepthTexture());
                    assertCopiedDepth(targets, destination.getDepthTexture(), destination == first ? 0.25F : 0.75F);
                }

                GpuTexture oldTexture = first.getDepthTexture();
                first.resize(64, 64);
                check(first.getDepthTexture() != oldTexture, "Same-size recreation must replace the texture object");
                // Deliberately reuse the cached version: this also works if the driver reuses the old GL name.
                targets.resizeIfNeeded(version, first.getDepthTexture(), 64, 64, DepthBufferFormat.DEPTH32F, directives);
                assertAttachments(targets, first.getDepthTexture());
                assertCopiedDepth(targets, first.getDepthTexture(), 0.375F);
                targets.resizeIfNeeded(version + 1, first.getDepthTexture(), 64, 64,
                    DepthBufferFormat.DEPTH32F, directives);
                assertAttachments(targets, first.getDepthTexture());
                assertCopiedDepth(targets, first.getDepthTexture(), 0.625F);

                first.resize(96, 80);
                check(targets.resizeIfNeeded(version + 1, first.getDepthTexture(), 96, 80,
                    DepthBufferFormat.DEPTH32F, directives), "A real resize must still be reported");
                check(targets.getCurrentWidth() == 96 && targets.getCurrentHeight() == 80,
                    "Iris did not update its target dimensions");
                check(targets.getDepthTextureNoTranslucents() != opaqueCopy && targets.getDepthTextureNoHand() != handCopy,
                    "A real resize must recreate copied-depth textures");
                assertAttachments(targets, first.getDepthTexture());
                assertCopiedDepth(targets, first.getDepthTexture(), 0.875F);
            }
            finally {
                if (targets != null) {
                    targets.destroy();
                }
                first.destroyBuffers();
                second.destroyBuffers();
            }
        }

        private static TextureTarget target(String label, int width, int height) {
            return new TextureTarget(label, width, height, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
        }

        private static void assertAttachments(RenderTargets targets, GpuTexture expected) {
            check(targets.getDepthTexture() == expected, "Iris retained another view's depth texture object");
            Object opaqueFramebuffer = field(targets, "noTranslucentsDestFb");
            Object handFramebuffer = field(targets, "noHandDestFb");
            List<?> framebuffers = (List<?>) field(targets, "ownedFramebuffers");
            int previous = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
            try {
                for (Object entry : framebuffers) {
                    GlFramebuffer framebuffer = (GlFramebuffer) entry;
                    if (!framebuffer.hasDepthAttachment()) {
                        continue;
                    }
                    GpuTexture attachment = framebuffer == opaqueFramebuffer ? targets.getDepthTextureNoTranslucents()
                        : framebuffer == handFramebuffer ? targets.getDepthTextureNoHand() : expected;
                    GlStateManager._glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, framebuffer.getId());
                    int actual = GL30C.glGetFramebufferAttachmentParameteri(GL30C.GL_READ_FRAMEBUFFER,
                        GL30C.GL_DEPTH_ATTACHMENT, GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
                    check(actual == ((GpuTextureInterface) attachment).iris$getGlId(),
                        "Iris framebuffer has the wrong actual depth attachment");
                }
            }
            finally {
                GlStateManager._glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, previous);
            }
        }

        private static void assertCopiedDepth(RenderTargets targets, GpuTexture source, float value) {
            RenderSystem.getDevice().createCommandEncoder().clearDepthTexture(source, value);
            targets.copyPreTranslucentDepth();
            targets.copyPreHandDepth();
            int previousFramebuffer = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
            int previousPackBuffer = GL11C.glGetInteger(GL21C.GL_PIXEL_PACK_BUFFER_BINDING);
            try {
                GlStateManager._glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, 0);
                for (String name : List.of("noTranslucentsDestFb", "noHandDestFb")) {
                    GlFramebuffer framebuffer = (GlFramebuffer) field(targets, name);
                    GlStateManager._glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, framebuffer.getId());
                    float[] pixel = new float[1];
                    GL11C.glReadPixels(0, 0, 1, 1, GL11C.GL_DEPTH_COMPONENT, GL11C.GL_FLOAT, pixel);
                    check(Math.abs(pixel[0] - value) < 0.00001F,
                        name + " did not copy the selected view's depth: " + pixel[0] + ", expected " + value);
                }
            }
            finally {
                GlStateManager._glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, previousPackBuffer);
                GlStateManager._glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, previousFramebuffer);
            }
        }

        private static void assertScene(ClientGameTestContext context, String label) {
            // The attachment fixture uses its own Iris targets; let a complete world frame restore GL state.
            context.waitTicks(10);
            context.runOnClient(client -> {
                PortalViewRenderer.ViewNode root = (PortalViewRenderer.ViewNode) staticField(PortalViewRenderer.class, "rootNode");
                check(root != null && root.children.size() == 3, label + ": same-size sibling/mirror views are missing");
                Set<GpuTexture> depths = Collections.newSetFromMap(new IdentityHashMap<>());
                int nested = 0;
                boolean nestedMirror = false;
                for (var child : root.children.values()) {
                    if (!child.children.isEmpty()) {
                        nested++;
                        nestedMirror |= child.portal instanceof Mirror;
                    }
                }
                collectDepths(root, depths, client.gameRenderer.mainRenderTarget());
                check(depths.size() >= 5 && nested >= 2 && nestedMirror,
                    label + ": fixture did not render both portal nesting and a portal inside a mirror");
                RenderTargets targets = (RenderTargets) field(Iris.getPipelineManager().getPipelineNullable(), "renderTargets");
                assertAttachments(targets, client.gameRenderer.mainRenderTarget().getDepthTexture());
            });
            Frame frame = capture(context);
            double[] white = sample(frame, WHITE_REFERENCE);
            for (double channel : white) {
                check(channel > 110 && channel < 170, label + ": non-idempotent diagnostic final shader is not active");
            }
            assertColor(sample(frame, new Vec3(1.5, 105.5, 0)), white, label + ": sibling portal processed twice");
            assertColor(sample(frame, new Vec3(-3, 104, 0)), white, label + ": nested portal processed twice");
            assertColor(sample(frame, new Vec3(0, 110, 0)), white, label + ": mirrored nested portal processed twice");
            double[] blue = sample(frame, BLUE_REFERENCE);
            check(blue[2] > blue[0] + 30, label + ": blue foreground reference is missing");
            assertColor(sample(frame, new Vec3(2.5, 103.5, 3.01)), blue,
                label + ": final portal replacement overwrote the foreground occluder");
        }

        private static void collectDepths(PortalViewRenderer.ViewNode node, Set<GpuTexture> depths, RenderTarget main) {
            for (var target : node.childTargets.values()) {
                check(target.width == main.width && target.height == main.height, "Shaderpack fixture unexpectedly cropped a view");
                check(depths.add(target.getDepthTexture()), "Two rendered portal views share a depth texture");
            }
            for (var child : node.children.values()) {
                collectDepths(child, depths, main);
            }
        }

        private static Frame capture(ClientGameTestContext context) {
            CompletableFuture<Frame> result = new CompletableFuture<>();
            context.runOnClient(client -> {
                var camera = client.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
                Matrix4f clip = new Matrix4f(camera.projectionMatrix).mul(camera.viewRotationMatrix);
                Vec3 position = camera.pos;
                Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
                    try (image) {
                        int[] pixels = new int[image.getWidth() * image.getHeight()];
                        for (int y = 0; y < image.getHeight(); y++) {
                            for (int x = 0; x < image.getWidth(); x++) {
                                pixels[x + y * image.getWidth()] = image.getPixel(x, y);
                            }
                        }
                        result.complete(new Frame(image.getWidth(), image.getHeight(), pixels, clip, position));
                    }
                    catch (Throwable exception) {
                        result.completeExceptionally(exception);
                    }
                });
            });
            context.waitFor(client -> result.isDone(), 100);
            return result.join();
        }

        private static double[] sample(Frame frame, Vec3 point) {
            Vec3 relative = point.subtract(frame.camera());
            Vector4f projected = frame.clip().transform(new Vector4f((float) relative.x, (float) relative.y, (float) relative.z, 1));
            check(projected.w > 0, "Regression sample lies behind the camera");
            int x = Math.round((projected.x / projected.w * 0.5F + 0.5F) * frame.width());
            int y = Math.round((0.5F - projected.y / projected.w * 0.5F) * frame.height());
            check(x >= 4 && y >= 4 && x < frame.width() - 4 && y < frame.height() - 4,
                "Regression sample lies outside the frame");
            double[] mean = new double[3];
            for (int dy = -3; dy <= 3; dy++) {
                for (int dx = -3; dx <= 3; dx++) {
                    int pixel = frame.pixels()[x + dx + (y + dy) * frame.width()];
                    mean[0] += (pixel >> 16) & 255;
                    mean[1] += (pixel >> 8) & 255;
                    mean[2] += pixel & 255;
                }
            }
            for (int channel = 0; channel < 3; channel++) {
                mean[channel] /= 49;
            }
            return mean;
        }

        private static void assertColor(double[] actual, double[] expected, String message) {
            for (int channel = 0; channel < 3; channel++) {
                check(Math.abs(actual[channel] - expected[channel]) <= 8,
                    message + ": channel " + channel + " was " + actual[channel] + ", expected " + expected[channel]);
            }
        }

        private static Object staticField(Class<?> owner, String name) {
            return readField(owner, null, name);
        }

        private static Object field(Object owner, String name) {
            return readField(owner.getClass(), owner, name);
        }

        private static Object readField(Class<?> owner, Object instance, String name) {
            try {
                Field field = owner.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(instance);
            }
            catch (ReflectiveOperationException exception) {
                throw new AssertionError("Cannot inspect pinned Iris regression state " + name, exception);
            }
        }
    }
}
