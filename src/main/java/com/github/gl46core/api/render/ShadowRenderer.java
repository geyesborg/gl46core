package com.github.gl46core.api.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.Entity;
import net.minecraft.util.BlockRenderLayer;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;

/**
 * Executes the shadow mapping render pass.
 *
 * Re-renders terrain geometry from the sun's perspective into the shadow
 * FBO before the main scene renders. Uses the same chunk render list
 * that MC built during setupTerrain() — chunks visible to the camera
 * are also rendered from the light's point of view.
 *
 * The shadow pass sequence is:
 *   1. Save current GL matrices (camera view/projection)
 *   2. Load shadow view/projection matrices onto GL stack
 *   3. Activate SHADOW_OPAQUE pass (binds shadow FBO, clears depth)
 *   4. Render SOLID terrain
 *   5. Activate SHADOW_CUTOUT pass
 *   6. Render CUTOUT_MIPPED and CUTOUT terrain
 *   7. Restore GL matrices
 *
 * The shadow FBO unbinding happens automatically when the next
 * non-shadow pass (TERRAIN_OPAQUE) is activated via setActivePass().
 *
 * During shadow rendering, mixin hooks in RenderGlobal are guarded
 * by {@link #isShadowPassActive()} to prevent pass type changes
 * from the re-entrant renderBlockLayer calls.
 */
public final class ShadowRenderer {

    public static final ShadowRenderer INSTANCE = new ShadowRenderer();

    private volatile boolean shadowPassActive = false;
    private final FloatBuffer glMatBuf = BufferUtils.createFloatBuffer(16);
    private final Matrix4f scratchMat = new Matrix4f();

    private ShadowRenderer() {}

    /**
     * Execute the shadow pass — re-render terrain from the light's perspective.
     *
     * Call after setupTerrain() has built the chunk render list, but before
     * the first renderBlockLayer() call in the main scene.
     *
     * @param partialTicks MC partial tick interpolation factor
     */
    public void executeShadowPass(float partialTicks) {
        FrameOrchestrator orch = FrameOrchestrator.INSTANCE;
        FrameContext ctx = orch.getFrameContext();
        ShadowState shadow = ctx.getShadow();

        if (!shadow.isValid() || !ctx.isShadowsActive()) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.renderGlobal == null || mc.world == null) return;

        shadowPassActive = true;

        try {
            // Save projection matrix
            GlStateManager.matrixMode(5889); // GL_PROJECTION
            GlStateManager.pushMatrix();
            loadMatrix(shadow.getShadowProjectionMatrix());

            // Save modelview matrix and load shadow view with camera offset
            // MC renders vertices at (worldPos - camPos), so to get back to
            // world space we need: shadowView * translate(camPos) * vertex
            GlStateManager.matrixMode(5888); // GL_MODELVIEW
            GlStateManager.pushMatrix();

            Vector3d camPos = ctx.getCamera().getPosition();
            scratchMat.set(shadow.getShadowViewMatrix());
            // Matrix4f.translate post-multiplies: result = shadowView * T(camPos)
            scratchMat.translate((float) camPos.x, (float) camPos.y, (float) camPos.z);
            loadMatrix(scratchMat);

            // Enable polygon offset to reduce shadow acne
            GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
            GL11.glPolygonOffset(1.1f, 4.0f);

            // Activate shadow opaque pass (binds shadow FBO, sets viewport, clears depth)
            orch.setActivePass(PassType.SHADOW_OPAQUE);

            // Render opaque terrain from light perspective
            Entity entity = mc.getRenderViewEntity();
            mc.renderGlobal.renderBlockLayer(BlockRenderLayer.SOLID, (double) partialTicks, 0, entity);

            // Switch to cutout shadow pass (same FBO, no re-clear)
            orch.setActivePass(PassType.SHADOW_CUTOUT);
            mc.renderGlobal.renderBlockLayer(BlockRenderLayer.CUTOUT_MIPPED, (double) partialTicks, 0, entity);
            mc.renderGlobal.renderBlockLayer(BlockRenderLayer.CUTOUT, (double) partialTicks, 0, entity);

            // Disable polygon offset
            GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
            GL11.glPolygonOffset(0.0f, 0.0f);

        } finally {
            // Restore GL matrices (camera view/projection)
            GlStateManager.matrixMode(5889); // GL_PROJECTION
            GlStateManager.popMatrix();
            GlStateManager.matrixMode(5888); // GL_MODELVIEW
            GlStateManager.popMatrix();

            shadowPassActive = false;
        }
        // Shadow FBO unbinding happens in setActivePass() when the next
        // non-shadow pass (TERRAIN_OPAQUE) is activated by MixinRenderGlobal
    }

    /**
     * Returns true while the shadow pass is executing.
     * Used by mixin guards to prevent re-entrant pass type changes.
     */
    public boolean isShadowPassActive() {
        return shadowPassActive;
    }

    /**
     * Load a JOML Matrix4f onto the current GL matrix stack.
     * Uses loadIdentity() + multMatrix() since GlStateManager has no loadMatrix().
     */
    private void loadMatrix(Matrix4f mat) {
        mat.get(glMatBuf);
        glMatBuf.rewind();
        GlStateManager.loadIdentity();
        GlStateManager.multMatrix(glMatBuf);
    }
}
