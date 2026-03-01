package com.github.gl46core.gl;

import java.nio.FloatBuffer;

/**
 * Static redirect targets for legacy GL11/GLU calls that bypass GlStateManager.
 * The LegacyGLTransformer ASM-redirects direct GL11 calls in Minecraft/Forge code
 * to these methods, which delegate to CoreMatrixStack and CoreStateTracker.
 */
public final class LegacyGLRedirects {

    private LegacyGLRedirects() {}

    public static void glMultMatrix(FloatBuffer matrix) {
        CoreMatrixStack.INSTANCE.multMatrix(matrix);
    }

    public static void glLoadIdentity() {
        CoreMatrixStack.INSTANCE.loadIdentity();
    }

    public static void glMatrixMode(int mode) {
        CoreMatrixStack.INSTANCE.matrixMode(mode);
    }

    public static void glPushMatrix() {
        CoreMatrixStack.INSTANCE.pushMatrix();
    }

    public static void glPopMatrix() {
        CoreMatrixStack.INSTANCE.popMatrix();
    }

    public static void glRotatef(float angle, float x, float y, float z) {
        CoreMatrixStack.INSTANCE.rotate(angle, x, y, z);
    }

    public static void glScalef(float x, float y, float z) {
        CoreMatrixStack.INSTANCE.scale(x, y, z);
    }

    public static void glTranslatef(float x, float y, float z) {
        CoreMatrixStack.INSTANCE.translate(x, y, z);
    }

    public static void glTranslated(double x, double y, double z) {
        CoreMatrixStack.INSTANCE.translate(x, y, z);
    }

    public static void glOrtho(double left, double right, double bottom, double top, double zNear, double zFar) {
        CoreMatrixStack.INSTANCE.ortho(left, right, bottom, top, zNear, zFar);
    }

    public static void glColor4f(float r, float g, float b, float a) {
        CoreStateTracker.INSTANCE.color(r, g, b, a);
    }

    public static void glColor3f(float r, float g, float b) {
        CoreStateTracker.INSTANCE.color(r, g, b, 1.0f);
    }

    // ── Immediate mode redirects (glBegin/glEnd removed in core profile) ──

    public static void glBegin(int mode) {
        ImmediateModeEmulator.INSTANCE.syncColorFromState();
        ImmediateModeEmulator.INSTANCE.begin(mode);
    }

    public static void glEnd() {
        ImmediateModeEmulator.INSTANCE.end();
    }

    public static void glVertex3f(float x, float y, float z) {
        ImmediateModeEmulator.INSTANCE.vertex3f(x, y, z);
    }

    public static void glVertex2f(float x, float y) {
        ImmediateModeEmulator.INSTANCE.vertex3f(x, y, 0.0f);
    }

    public static void glTexCoord2f(float u, float v) {
        ImmediateModeEmulator.INSTANCE.texCoord2f(u, v);
    }

    public static void glNormal3f(float x, float y, float z) {
        ImmediateModeEmulator.INSTANCE.normal3f(x, y, z);
    }

    // ── No-ops for legacy features removed in core profile ──

    public static void glEnable_legacy(int cap) {
        // No-op for legacy caps: GL_ALPHA_TEST, GL_LIGHTING, GL_FOG, etc.
        // These are tracked in CoreStateTracker, not set in real GL.
    }

    public static void glDisable_legacy(int cap) {
        // No-op for legacy caps
    }

    // Reusable matrix instances to avoid GC pressure in hot path
    private static final org.joml.Matrix4f tempMatrix = new org.joml.Matrix4f();

    /**
     * Core-profile replacement for gluPerspective.
     * Builds the perspective matrix and multiplies it into the current matrix stack.
     */
    public static void gluPerspective(float fovy, float aspect, float zNear, float zFar) {
        float radians = (float) Math.toRadians(fovy / 2.0f);
        float deltaZ = zFar - zNear;
        float sine = (float) Math.sin(radians);
        if (deltaZ == 0 || sine == 0 || aspect == 0) return;

        float cotangent = (float) Math.cos(radians) / sine;

        // Build perspective matrix in column-major order
        org.joml.Matrix4f persp = tempMatrix;
        persp.m00(cotangent / aspect);
        persp.m11(cotangent);
        persp.m22(-(zFar + zNear) / deltaZ);
        persp.m23(-1.0f);
        persp.m32(-2.0f * zNear * zFar / deltaZ);
        persp.m33(0.0f);
        // Clear the rest
        persp.m01(0); persp.m02(0); persp.m03(0);
        persp.m10(0); persp.m12(0); persp.m13(0);
        persp.m20(0); persp.m21(0);
        persp.m30(0); persp.m31(0);

        CoreMatrixStack.INSTANCE.multMatrix(persp);
    }

    /**
     * Core-profile replacement for gluLookAt.
     * Builds the lookAt matrix and multiplies it into the current matrix stack.
     */
    public static void gluLookAt(float eyeX, float eyeY, float eyeZ,
                                  float centerX, float centerY, float centerZ,
                                  float upX, float upY, float upZ) {
        org.joml.Matrix4f lookAt = tempMatrix.identity().lookAt(
                eyeX, eyeY, eyeZ,
                centerX, centerY, centerZ,
                upX, upY, upZ);
        CoreMatrixStack.INSTANCE.multMatrix(lookAt);
    }
}
