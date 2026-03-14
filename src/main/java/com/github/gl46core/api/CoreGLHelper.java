package com.github.gl46core.api;

import com.github.gl46core.gl.CoreMatrixStack;
import com.github.gl46core.gl.CoreStateTracker;
import org.joml.Matrix4f;

/**
 * High-level utility methods for mods that have been forked and updated
 * to use core-profile OpenGL directly. These helpers replace common
 * legacy GL patterns (like glClipPlane with automatic modelview transform)
 * so that each forked mod doesn't need to reimplement the same logic.
 *
 * Unlike the ASM transformer (which intercepts legacy calls at the bytecode level),
 * these methods are called explicitly by core-profile-aware mod code.
 */
public final class CoreGLHelper {

    private CoreGLHelper() {}

    // ═══════════════════════════════════════════════════════════════════
    // Clip planes
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Set a clip plane from a normal vector and a point on the plane.
     * Automatically transforms the plane equation from object space to eye space
     * using the current modelview matrix (replicating legacy glClipPlane behavior).
     *
     * @param planeIndex 0-5 (maps to GL_CLIP_PLANE0 through GL_CLIP_PLANE5)
     * @param nx         plane normal X (object space)
     * @param ny         plane normal Y (object space)
     * @param nz         plane normal Z (object space)
     * @param px         point on plane X (object space)
     * @param py         point on plane Y (object space)
     * @param pz         point on plane Z (object space)
     */
    public static void setClipPlane(int planeIndex, float nx, float ny, float nz,
                                    float px, float py, float pz) {
        // Plane equation: ax + by + cz + d = 0
        float d = -(nx * px + ny * py + nz * pz);
        setClipPlaneEquation(planeIndex, nx, ny, nz, d);
    }

    /**
     * Set a clip plane from a raw plane equation (a, b, c, d) where ax+by+cz+d=0.
     * Automatically transforms from object space to eye space using the current
     * modelview matrix inverse (replicating legacy glClipPlane behavior).
     *
     * @param planeIndex 0-5
     * @param a          plane equation coefficient
     * @param b          plane equation coefficient
     * @param c          plane equation coefficient
     * @param d          plane equation coefficient
     */
    public static void setClipPlaneEquation(int planeIndex, float a, float b, float c, float d) {
        if (planeIndex < 0 || planeIndex >= 6) return;

        // Legacy glClipPlane transforms plane by MV^{-1} to store in eye space
        Matrix4f mv = CoreMatrixStack.INSTANCE.getModelView();
        Matrix4f mvInv = new Matrix4f(mv).invert();

        // Transform: eyePlane = objPlane * MV^{-1} (row-vector * column-major matrix)
        float ea = mvInv.m00() * a + mvInv.m10() * b + mvInv.m20() * c + mvInv.m30() * d;
        float eb = mvInv.m01() * a + mvInv.m11() * b + mvInv.m21() * c + mvInv.m31() * d;
        float ec = mvInv.m02() * a + mvInv.m12() * b + mvInv.m22() * c + mvInv.m32() * d;
        float ed = mvInv.m03() * a + mvInv.m13() * b + mvInv.m23() * c + mvInv.m33() * d;

        CoreStateTracker.INSTANCE.setClipPlaneEquation(planeIndex, ea, eb, ec, ed);
    }

    /**
     * Set a clip plane from a GL_CLIP_PLANEn constant, normal vector, and point on plane.
     * Convenience overload that accepts the GL constant directly (0x3000-0x3005).
     *
     * @param glPlane GL_CLIP_PLANE0 (0x3000) through GL_CLIP_PLANE5 (0x3005)
     * @param nx      plane normal X
     * @param ny      plane normal Y
     * @param nz      plane normal Z
     * @param px      point on plane X
     * @param py      point on plane Y
     * @param pz      point on plane Z
     */
    public static void setClipPlaneGL(int glPlane, float nx, float ny, float nz,
                                      float px, float py, float pz) {
        setClipPlane(glPlane - 0x3000, nx, ny, nz, px, py, pz);
    }

    /**
     * Enable a clip plane by index (0-5).
     */
    public static void enableClipPlane(int planeIndex) {
        CoreStateTracker.INSTANCE.enableClipPlane(planeIndex);
    }

    /**
     * Disable a clip plane by index (0-5).
     */
    public static void disableClipPlane(int planeIndex) {
        CoreStateTracker.INSTANCE.disableClipPlane(planeIndex);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Fog helpers
    // ═══════════════════════════════════════════════════════════════════

    public static void enableFog() {
        CoreStateTracker.INSTANCE.enableFog();
    }

    public static void disableFog() {
        CoreStateTracker.INSTANCE.disableFog();
    }

    /**
     * Set both fog start and end distances at once.
     */
    public static void setFogRange(float start, float end) {
        CoreStateTracker.INSTANCE.setFogStart(start);
        CoreStateTracker.INSTANCE.setFogEnd(end);
    }

    public static void setFogStart(float start) {
        CoreStateTracker.INSTANCE.setFogStart(start);
    }

    public static void setFogEnd(float end) {
        CoreStateTracker.INSTANCE.setFogEnd(end);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Lighting helpers
    // ═══════════════════════════════════════════════════════════════════

    public static void enableLighting() {
        CoreStateTracker.INSTANCE.enableLighting();
    }

    public static void disableLighting() {
        CoreStateTracker.INSTANCE.disableLighting();
    }

    // ═══════════════════════════════════════════════════════════════════
    // Matrix helpers
    // ═══════════════════════════════════════════════════════════════════

    public static void pushMatrix() {
        CoreMatrixStack.INSTANCE.pushMatrix();
    }

    public static void popMatrix() {
        CoreMatrixStack.INSTANCE.popMatrix();
    }

    /**
     * Get a copy of the current modelview matrix (safe to modify).
     */
    public static Matrix4f getModelViewCopy() {
        return new Matrix4f(CoreMatrixStack.INSTANCE.getModelView());
    }

    /**
     * Get a copy of the current projection matrix (safe to modify).
     */
    public static Matrix4f getProjectionCopy() {
        return new Matrix4f(CoreMatrixStack.INSTANCE.getProjection());
    }
}
