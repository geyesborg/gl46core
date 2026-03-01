package com.github.gl46core.api;

import com.github.gl46core.gl.CoreMatrixStack;
import com.github.gl46core.gl.CoreStateTracker;
import org.joml.Matrix4f;

/**
 * Public API for other mods to interact with GL46 Core.
 *
 * Since gl46core replaces the fixed-function pipeline with software-tracked state,
 * mods that need to read GL state (e.g., glIsEnabled(GL_ALPHA_TEST), glGetFloat(GL_MODELVIEW_MATRIX))
 * must use this API instead — those GL queries will not return correct values in core profile.
 *
 * All methods are static and safe to call from any thread that has the GL context.
 *
 * Example usage:
 * <pre>
 *   // Read the current modelview matrix
 *   Matrix4f mv = GL46CoreAPI.getModelViewMatrix();
 *
 *   // Check if alpha test is enabled
 *   if (GL46CoreAPI.isAlphaTestEnabled()) { ... }
 *
 *   // Get the current fog color
 *   float[] fog = GL46CoreAPI.getFogColor();
 * </pre>
 */
public final class GL46CoreAPI {

    private GL46CoreAPI() {}

    // ═══════════════════════════════════════════════════════════════════
    // Matrix state (replaces glGetFloat(GL_MODELVIEW_MATRIX) etc.)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Get the current modelview matrix.
     * This is a live reference — do not modify it. Copy if you need to store it.
     */
    public static Matrix4f getModelViewMatrix() {
        return CoreMatrixStack.INSTANCE.getModelView();
    }

    /**
     * Get the current projection matrix.
     * This is a live reference — do not modify it. Copy if you need to store it.
     */
    public static Matrix4f getProjectionMatrix() {
        return CoreMatrixStack.INSTANCE.getProjection();
    }

    /**
     * Get the current texture matrix.
     * This is a live reference — do not modify it. Copy if you need to store it.
     */
    public static Matrix4f getTextureMatrix() {
        return CoreMatrixStack.INSTANCE.getTextureMatrix();
    }

    /**
     * Get the current matrix mode (GL_MODELVIEW, GL_PROJECTION, or GL_TEXTURE).
     */
    public static int getMatrixMode() {
        return CoreMatrixStack.INSTANCE.getMatrixMode();
    }

    // ═══════════════════════════════════════════════════════════════════
    // Alpha test state (replaces glIsEnabled(GL_ALPHA_TEST))
    // ═══════════════════════════════════════════════════════════════════

    public static boolean isAlphaTestEnabled() {
        return CoreStateTracker.INSTANCE.isAlphaTestEnabled();
    }

    /**
     * Get the alpha test function (e.g., GL_GREATER, GL_ALWAYS).
     */
    public static int getAlphaFunc() {
        return CoreStateTracker.INSTANCE.getAlphaFunc();
    }

    /**
     * Get the alpha test reference value.
     */
    public static float getAlphaRef() {
        return CoreStateTracker.INSTANCE.getAlphaRef();
    }

    // ═══════════════════════════════════════════════════════════════════
    // Lighting state (replaces glIsEnabled(GL_LIGHTING))
    // ═══════════════════════════════════════════════════════════════════

    public static boolean isLightingEnabled() {
        return CoreStateTracker.INSTANCE.isLightingEnabled();
    }

    public static boolean isLightEnabled(int light) {
        return CoreStateTracker.INSTANCE.isLightEnabled(light);
    }

    /**
     * Get the eye-space position of a light (0 or 1).
     * Returns a 4-element array [x, y, z, w].
     */
    public static float[] getLightPosition(int light) {
        return CoreStateTracker.INSTANCE.getLightPosition(light);
    }

    /**
     * Get the diffuse color of a light (0 or 1).
     * Returns a 4-element array [r, g, b, a].
     */
    public static float[] getLightDiffuse(int light) {
        return CoreStateTracker.INSTANCE.getLightDiffuse(light);
    }

    /**
     * Get the global ambient light color (from glLightModel(GL_LIGHT_MODEL_AMBIENT)).
     * Returns a 3-element array [r, g, b].
     */
    public static float[] getLightModelAmbient() {
        CoreStateTracker s = CoreStateTracker.INSTANCE;
        return new float[]{ s.getLightModelAmbientR(), s.getLightModelAmbientG(), s.getLightModelAmbientB() };
    }

    // ═══════════════════════════════════════════════════════════════════
    // Fog state (replaces glIsEnabled(GL_FOG))
    // ═══════════════════════════════════════════════════════════════════

    public static boolean isFogEnabled() {
        return CoreStateTracker.INSTANCE.isFogEnabled();
    }

    /**
     * Get the fog mode (GL_EXP, GL_EXP2, or GL_LINEAR).
     */
    public static int getFogMode() {
        return CoreStateTracker.INSTANCE.getFogMode();
    }

    public static float getFogDensity() {
        return CoreStateTracker.INSTANCE.getFogDensity();
    }

    public static float getFogStart() {
        return CoreStateTracker.INSTANCE.getFogStart();
    }

    public static float getFogEnd() {
        return CoreStateTracker.INSTANCE.getFogEnd();
    }

    /**
     * Get the fog color as [r, g, b, a].
     */
    public static float[] getFogColor() {
        CoreStateTracker s = CoreStateTracker.INSTANCE;
        return new float[]{ s.getFogR(), s.getFogG(), s.getFogB(), s.getFogA() };
    }

    // ═══════════════════════════════════════════════════════════════════
    // Color state (replaces glGetFloat(GL_CURRENT_COLOR))
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Get the current glColor4f state as [r, g, b, a].
     */
    public static float[] getCurrentColor() {
        CoreStateTracker s = CoreStateTracker.INSTANCE;
        return new float[]{ s.getColorR(), s.getColorG(), s.getColorB(), s.getColorA() };
    }

    // ═══════════════════════════════════════════════════════════════════
    // Texture state
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Check if GL_TEXTURE_2D is enabled for a given texture unit (0-7).
     * Replaces glIsEnabled(GL_TEXTURE_2D) after glActiveTexture.
     */
    public static boolean isTexture2DEnabled(int unit) {
        return CoreStateTracker.INSTANCE.isTexture2DEnabled(unit);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Lightmap state
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Get the current lightmap coordinates set by OpenGlHelper.setLightmapTextureCoords.
     * Returns [x, y] in lightmap UV space (typically 0-240).
     */
    public static float[] getLightmapCoords() {
        CoreStateTracker s = CoreStateTracker.INSTANCE;
        return new float[]{ s.getLightmapX(), s.getLightmapY() };
    }

    // ═══════════════════════════════════════════════════════════════════
    // Misc state
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Get the shade model (GL_SMOOTH or GL_FLAT).
     */
    public static int getShadeModel() {
        return CoreStateTracker.INSTANCE.getShadeModel();
    }
}
