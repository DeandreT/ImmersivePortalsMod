package qouteall.imm_ptl.core.render;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.TextureTransform;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;
import qouteall.imm_ptl.core.portal.Portal;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Draws a portal's surface, textured with the view that was rendered for it
 * (see {@link PortalViewRenderer} and docs/rendering-26.3.md).
 * <p>
 * Each pooled view target is registered as a texture {@code immersive_portals:portal_view/N}
 * and has its own render type, so the surface can be submitted like normal entity geometry.
 */
public class PortalSurfaceRendering {
    public static final RenderPipeline PORTAL_VIEW_PIPELINE = createPipeline("pipeline/portal_view");

    private static RenderPipeline createPipeline(String path) {
        RenderPipeline.Builder builder = RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
        .withLocation(Identifier.fromNamespaceAndPath("immersive_portals", path))
        .withBindGroupLayout(BindGroupLayouts.PROJECTION)
        .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
        // the surface is fogged like terrain (lava/water fog, far away portals), see portal_view.fsh
        .withBindGroupLayout(BindGroupLayouts.FOG)
        .withVertexShader(Identifier.fromNamespaceAndPath("immersive_portals", "core/portal_view"))
        .withFragmentShader(Identifier.fromNamespaceAndPath("immersive_portals", "core/portal_view"))
        .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
        .withVertexBinding(0, DefaultVertexFormat.POSITION)
        .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
        .withColorTargetState(ColorTargetState.DEFAULT)
        .withDepthStencilState(DepthStencilState.DEFAULT)
        // the portal can be seen from both sides (e.g. mirrors are culled by other means)
        .withCull(false);
        return builder.build();
    }

    private record DeferredSurface(Portal portal, RenderType renderType, Matrix4f pose) {}

    private static final List<DeferredSurface> deferredSurfaces = new ArrayList<>();
    // the transformation that the world pass drew with, see captureWorldTransformation
    private static @Nullable Matrix4f worldModelView;
    private static @Nullable GpuBufferSlice worldProjection;
    private static @Nullable ProjectionType worldProjectionType;
    private static final List<RenderType> renderTypes = new ArrayList<>();
    // per target: the texture matrix that maps the drawing view's screen uv to the target's uv
    // (the views' crops and mirror flips, PortalViewCrop.samplingMatrix); set when the target is handed out
    private static final List<Matrix4f> samplingMatrices = new ArrayList<>();

    /**
     * A texture whose image is a portal view target's color attachment.
     * The target is owned by {@link PortalViewTargetPool}, this doesn't close it.
     */
    private static class PortalViewTexture extends AbstractTexture {
        private final TextureTarget target;

        PortalViewTexture(TextureTarget target) {
            this.target = target;
            this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        }

        @Override
        public GpuTexture getTexture() {
            return target.getColorTexture();
        }

        @Override
        public GpuTextureView getTextureView() {
            return target.getColorTextureView();
        }

        @Override
        public void close() {
            // the pool owns the target
        }
    }

    /**
     * Called by the pool when it creates its {@code index}-th target.
     */
    static void onTargetCreated(int index, TextureTarget target) {
        Identifier id = Identifier.fromNamespaceAndPath("immersive_portals", "portal_view/" + index);
        Minecraft.getInstance().getTextureManager().register(id, new PortalViewTexture(target));

        while (renderTypes.size() <= index) {
            renderTypes.add(null);
            samplingMatrices.add(new Matrix4f());
        }
        Matrix4f samplingMatrix = samplingMatrices.get(index);
        renderTypes.set(index, RenderType.create(
            "immersive_portals:portal_view_" + index,
            RenderSetup.builder(PORTAL_VIEW_PIPELINE)
                .withTexture("Sampler0", id)
                .setTextureTransform(new TextureTransform(
                    "immersive_portals:portal_view_" + index, () -> new Matrix4f(samplingMatrix)
                ))
                .createRenderSetup()
        ));
    }

    /**
     * Set how the target with that index is sampled in this frame (it's drawn in one view per frame).
     */
    static void setSamplingMatrix(int index, Matrix4f matrix) {
        samplingMatrices.get(index).set(matrix);
    }

    static void onPoolCleared() {
        for (int i = 0; i < renderTypes.size(); i++) {
            Minecraft.getInstance().getTextureManager().release(
                Identifier.fromNamespaceAndPath("immersive_portals", "portal_view/" + i)
            );
        }
        renderTypes.clear();
        samplingMatrices.clear();
    }

    /**
     * Submit the portal's surface. The pose stack must be at the portal's origin (relative to the camera).
     * Does nothing if the portal's view was not rendered this frame.
     */
    public static void submitPortalSurface(
        Portal portal, PoseStack poseStack, SubmitNodeCollector submitNodeCollector
    ) {
        @Nullable RenderType renderType = getRenderTypeForPortal(portal);
        if (renderType == null) {
            return;
        }

        if (IrisInterface.invoker.isShaderpackInUse()) {
            // a portal must not appear in the shadow map
            if (IrisInterface.invoker.isRenderingShadowMap()) {
                return;
            }
            deferredSurfaces.add(new DeferredSurface(portal, renderType, new Matrix4f(poseStack.last().pose())));
        }

        submitNodeCollector.submitCustomGeometry(
            poseStack, renderType,
            (pose, buffer) -> outputPortalMesh(portal, pose.pose(), buffer)
        );
    }
    
    /**
     * For the depth to be the same in {@link #drawDeferredSurfaces}, it needs the transformation that the world pass
     * drew with. It's not yet in place when the surface is submitted, and the pack changes it later.
     * So it's read during the world pass.
     */
    public static void captureWorldTransformation() {
        worldModelView = RenderSystem.getModelViewMatrixCopy();
        worldProjection = RenderSystem.getProjectionMatrixBuffer();
        worldProjectionType = RenderSystem.getProjectionType();
    }

    /**
     * With a shaderpack, a view's target holds the pack's finished image. A surface drawn in the world pass is treated
     * as an unlit block: the pack's lighting, bloom and tone mapping process that image a second time, which
     * overexposes it. So the surface is drawn again on the pack's final image, which replaces the processed pixels.
     * The world pass's surface is still needed, for its depth: this draw has the same depth, so it only passes where
     * nothing nearer was drawn over the surface. Players, mobs, hands and glass in front of a portal stay on top
     * (seen through glass, the portal is still processed by the pack). A portal in the view of another portal or a
     * mirror doesn't get replaced yet, so it's still overexposed there.
     * Called at the end of each view, before the view around it starts using its target.
     */
    public static void drawDeferredSurfaces() {
        if (deferredSurfaces.isEmpty()) {
            return;
        }
        if (worldModelView == null) {
            deferredSurfaces.clear();
            return;
        }

        int vertexCount = 0;
        List<float[]> meshes = new ArrayList<>(deferredSurfaces.size());
        for (DeferredSurface surface : deferredSurfaces) {
            float[] mesh = createMesh(surface);
            meshes.add(mesh);
            vertexCount += mesh.length / 3;
        }
        if (vertexCount == 0) {
            deferredSurfaces.clear();
            return;
        }

        ByteBuffer vertices = MemoryUtil.memAlloc(vertexCount * 3 * Float.BYTES);
        try {
            for (float[] mesh : meshes) {
                vertices.asFloatBuffer().put(mesh);
                vertices.position(vertices.position() + mesh.length * Float.BYTES);
            }
            vertices.flip();
            draw(vertices, meshes);
        }
        finally {
            MemoryUtil.memFree(vertices);
            deferredSurfaces.clear();
        }
    }

    private static void draw(ByteBuffer vertices, List<float[]> meshes) {
        RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.TRIANGLES);
        GpuBuffer indexBuffer = indices.getBuffer(vertices.remaining() / (3 * Float.BYTES));
        RenderTarget target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        Matrix4fStack modelViewStack = RenderSystem.getModelViewStack();

        try (GpuBuffer vertexBuffer = RenderSystem.getDevice().createBuffer(
            () -> "ImmPtl portal surfaces", GpuBuffer.USAGE_VERTEX, vertices
        )) {
            RenderSystem.backupProjectionMatrix();
            try {
                int firstVertex = 0;
                for (int i = 0; i < meshes.size(); i++) {
                    DeferredSurface surface = deferredSurfaces.get(i);
                    int count = meshes.get(i).length / 3;

                    modelViewStack.pushMatrix();
                    modelViewStack.set(worldModelView);
                    PreparedRenderType prepared;
                    try {
                        prepared = surface.renderType().prepare();
                    }
                    finally {
                        modelViewStack.popMatrix();
                    }
                    RenderSystem.setProjectionMatrix(worldProjection, worldProjectionType);

                    try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                        () -> "ImmPtl portal surface",
                        target.getColorTextureView(), Optional.empty(),
                        target.getDepthTextureView(), OptionalDouble.empty()
                    )) {
                        prepared.drawFromBuffer(new StagedVertexBuffer.ExecuteInfo(
                            vertexBuffer, indexBuffer, indices.type(),
                            firstVertex, 0, count, PrimitiveTopology.TRIANGLES
                        ), pass);
                    }
                    firstVertex += count;
                }
            }
            finally {
                RenderSystem.restoreProjectionMatrix();
            }
        }
    }

    private static float[] createMesh(DeferredSurface surface) {
        FloatArrayList result = new FloatArrayList();
        Vector3f position = new Vector3f();
        surface.portal().renderViewAreaMesh(
            Vec3.ZERO,
            (p0x, p0y, p0z, p1x, p1y, p1z, p2x, p2y, p2z) -> {
                for (double[] p : new double[][]{{p0x, p0y, p0z}, {p1x, p1y, p1z}, {p2x, p2y, p2z}}) {
                    surface.pose().transformPosition((float) p[0], (float) p[1], (float) p[2], position);
                    result.add(position.x);
                    result.add(position.y);
                    result.add(position.z);
                }
            }
        );
        return result.toFloatArray();
    }

    private static @Nullable RenderType getRenderTypeForPortal(Portal portal) {
        TextureTarget target = PortalViewRenderer.getViewTarget(portal);
        if (target == null) {
            return null;
        }
        int index = PortalViewRenderer.getTargetIndex(target);
        if (index < 0 || index >= renderTypes.size()) {
            return null;
        }
        return renderTypes.get(index);
    }

    /**
     * The portal's own mesh. When the camera is very close to or inside the portal,
     * parts of it are behind the camera or nearer than the near plane; the shader emulates
     * depth clamp, so it covers exactly the rays that go through the portal (like the original mod).
     */
    private static void outputPortalMesh(Portal portal, Matrix4f pose, VertexConsumer buffer) {
        // the pose is already at the portal origin
        portal.renderViewAreaMesh(
            Vec3.ZERO,
            (p0x, p0y, p0z, p1x, p1y, p1z, p2x, p2y, p2z) -> {
                buffer.addVertex(pose, (float) p0x, (float) p0y, (float) p0z);
                buffer.addVertex(pose, (float) p1x, (float) p1y, (float) p1z);
                buffer.addVertex(pose, (float) p2x, (float) p2y, (float) p2z);
            }
        );
    }
}
