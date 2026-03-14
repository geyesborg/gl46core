package com.github.gl46core.gl;

import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

import java.nio.FloatBuffer;

/**
 * Software replacement for the OpenGL fixed-function matrix stack.
 * Replaces glMatrixMode, glPushMatrix, glPopMatrix, glLoadIdentity,
 * glTranslatef, glRotatef, glScalef, glOrtho, glMultMatrix, glLoadMatrix.
 *
 * Maintains separate MODELVIEW and PROJECTION stacks (32 deep each,
 * matching the GL spec minimum). The current matrix mode selects which
 * stack operations affect.
 *
 * All state is per-thread — the splash thread (Modern Splash) and the
 * client thread each get their own independent matrix stacks, preventing
 * concurrent mutation from corrupting the panorama rendering.
 *
 * Call {@link #getModelView()} and {@link #getProjection()} to retrieve
 * the current matrices for uploading as shader uniforms.
 */
public final class CoreMatrixStack {

    // GL constants
    public static final int GL_MODELVIEW = 0x1700;
    public static final int GL_PROJECTION = 0x1701;
    public static final int GL_TEXTURE = 0x1702;

    private static final int STACK_DEPTH = 32;

    // Per-thread matrix state — prevents splash thread from corrupting client thread
    private static class ThreadState {
        final Matrix4fStack modelViewStack = new Matrix4fStack(STACK_DEPTH);
        final Matrix4fStack projectionStack = new Matrix4fStack(STACK_DEPTH);
        final Matrix4fStack textureStack = new Matrix4fStack(STACK_DEPTH);
        int currentMode = GL_MODELVIEW;
        boolean modelViewDirty = true;
        boolean projectionDirty = true;
        final Matrix4f tempMatrix = new Matrix4f();
    }

    private final ThreadLocal<ThreadState> threadState =
            ThreadLocal.withInitial(ThreadState::new);

    public static final CoreMatrixStack INSTANCE = new CoreMatrixStack();

    private CoreMatrixStack() {}

    private ThreadState state() { return threadState.get(); }

    /**
     * Select which matrix stack to operate on.
     * Replaces glMatrixMode().
     */
    public void matrixMode(int mode) {
        state().currentMode = mode;
    }

    public int getMatrixMode() {
        return state().currentMode;
    }

    /**
     * Push the current matrix onto the active stack.
     * Replaces glPushMatrix().
     * Must mark dirty so that any subsequent modifications + pop are detected.
     */
    public void pushMatrix() {
        ThreadState s = state();
        activeStack(s).pushMatrix();
        markDirty(s);
    }

    /**
     * Pop the top matrix from the active stack.
     * Replaces glPopMatrix().
     */
    public void popMatrix() {
        ThreadState s = state();
        Matrix4fStack stack = activeStack(s);
        // Guard against stack underflow — vanilla GL just generates GL_STACK_UNDERFLOW,
        // but JOML's Matrix4fStack throws IllegalStateException. Modern Splash and
        // other mods may pop without a matching push.
        try {
            stack.popMatrix();
        } catch (IllegalStateException e) {
            // Already at bottom — ignore, matching vanilla GL behavior
            return;
        }
        markDirty(s);
    }

    /**
     * Load the identity matrix.
     * Replaces glLoadIdentity().
     */
    public void loadIdentity() {
        ThreadState s = state();
        activeStack(s).identity();
        markDirty(s);
    }

    /**
     * Post-multiply by an orthographic projection matrix.
     * Replaces glOrtho().
     */
    public void ortho(double left, double right, double bottom, double top, double zNear, double zFar) {
        ThreadState s = state();
        activeStack(s).ortho((float) left, (float) right, (float) bottom, (float) top, (float) zNear, (float) zFar);
        markDirty(s);
    }

    /**
     * Post-multiply by a rotation matrix.
     * Replaces glRotatef().
     * Legacy glRotatef normalizes the axis vector internally; JOML does not,
     * so we must normalize here to avoid incorrect rotations (e.g. portal nausea
     * passes axis (0,1,1) which has length √2).
     */
    public void rotate(float angle, float x, float y, float z) {
        float len = (float) Math.sqrt(x * x + y * y + z * z);
        if (len > 1e-8f) {
            float inv = 1.0f / len;
            x *= inv;
            y *= inv;
            z *= inv;
        }
        ThreadState s = state();
        activeStack(s).rotate((float) Math.toRadians(angle), x, y, z);
        markDirty(s);
    }

    /**
     * Post-multiply by a scale matrix.
     * Replaces glScalef().
     */
    public void scale(float x, float y, float z) {
        ThreadState s = state();
        activeStack(s).scale(x, y, z);
        markDirty(s);
    }

    /**
     * Post-multiply by a scale matrix (double variant).
     * Replaces glScaled().
     */
    public void scale(double x, double y, double z) {
        ThreadState s = state();
        activeStack(s).scale((float) x, (float) y, (float) z);
        markDirty(s);
    }

    /**
     * Post-multiply by a translation matrix.
     * Replaces glTranslatef().
     */
    public void translate(float x, float y, float z) {
        ThreadState s = state();
        activeStack(s).translate(x, y, z);
        markDirty(s);
    }

    /**
     * Post-multiply by a translation matrix (double variant).
     * Replaces glTranslated(). Uses double-precision arithmetic to match
     * the fixed-function pipeline's behavior — avoids sub-pixel jitter
     * when translating by large coordinates (e.g., cloud rendering).
     */
    public void translate(double x, double y, double z) {
        ThreadState st = state();
        Matrix4fStack s = activeStack(st);
        // Perform translation in double precision to avoid float truncation
        // of large coordinates before the multiply. This matches how vanilla
        // glTranslated works internally.
        s.m30((float)(s.m00() * x + s.m10() * y + s.m20() * z + s.m30()));
        s.m31((float)(s.m01() * x + s.m11() * y + s.m21() * z + s.m31()));
        s.m32((float)(s.m02() * x + s.m12() * y + s.m22() * z + s.m32()));
        s.m33((float)(s.m03() * x + s.m13() * y + s.m23() * z + s.m33()));
        markDirty(st);
    }

    /**
     * Post-multiply the active matrix by the given 4x4 column-major matrix.
     * Replaces glMultMatrix().
     */
    public void multMatrix(FloatBuffer matrix) {
        ThreadState s = state();
        s.tempMatrix.set(matrix);
        activeStack(s).mul(s.tempMatrix);
        markDirty(s);
    }

    /**
     * Post-multiply the active matrix by the given Matrix4f.
     */
    public void multMatrix(Matrix4f matrix) {
        ThreadState s = state();
        activeStack(s).mul(matrix);
        markDirty(s);
    }

    /**
     * Load a 4x4 column-major matrix, replacing the current one.
     * Replaces glLoadMatrix().
     */
    public void loadMatrix(FloatBuffer matrix) {
        ThreadState s = state();
        s.tempMatrix.set(matrix);
        activeStack(s).set(s.tempMatrix);
        markDirty(s);
    }

    /**
     * Read the current matrix into a FloatBuffer (column-major).
     * Replaces glGetFloat(GL_MODELVIEW_MATRIX/GL_PROJECTION_MATRIX).
     */
    public void getFloat(int pname, FloatBuffer params) {
        ThreadState s = state();
        switch (pname) {
            case 0x0BA6 -> s.modelViewStack.get(params);  // GL_MODELVIEW_MATRIX
            case 0x0BA7 -> s.projectionStack.get(params); // GL_PROJECTION_MATRIX
            case 0x0BA8 -> s.textureStack.get(params);    // GL_TEXTURE_MATRIX
            default -> {} // Not a matrix query — fall through (caller handles)
        }
    }

    /**
     * Check if the given pname is a matrix query we handle.
     */
    public boolean isMatrixQuery(int pname) {
        return pname == 0x0BA6 || pname == 0x0BA7 || pname == 0x0BA8;
    }

    /**
     * Get the current modelview matrix (read-only reference).
     */
    public Matrix4f getModelView() {
        return state().modelViewStack;
    }

    /**
     * Get the current projection matrix (read-only reference).
     */
    public Matrix4f getProjection() {
        return state().projectionStack;
    }

    /**
     * Get the current texture matrix (read-only reference).
     */
    public Matrix4f getTextureMatrix() {
        return state().textureStack;
    }

    public boolean isModelViewDirty() {
        return state().modelViewDirty;
    }

    public boolean isProjectionDirty() {
        return state().projectionDirty;
    }

    public void clearModelViewDirty() {
        state().modelViewDirty = false;
    }

    public void clearProjectionDirty() {
        state().projectionDirty = false;
    }

    private Matrix4fStack activeStack(ThreadState s) {
        return switch (s.currentMode) {
            case GL_PROJECTION -> s.projectionStack;
            case GL_TEXTURE -> s.textureStack;
            default -> s.modelViewStack;
        };
    }

    private void markDirty(ThreadState s) {
        // Flush any pending immediate-mode vertices BEFORE the matrix changes.
        // Deferred batching accumulates vertices that were positioned for the
        // current matrix — if we change the matrix first, the flush would draw
        // those vertices with the wrong transform (e.g., Mojang logo drawn
        // with progress bar projection).
        ImmediateModeEmulator.INSTANCE.flush();
        switch (s.currentMode) {
            case GL_PROJECTION -> s.projectionDirty = true;
            case GL_MODELVIEW -> s.modelViewDirty = true;
            default -> {}
        }
    }

}
