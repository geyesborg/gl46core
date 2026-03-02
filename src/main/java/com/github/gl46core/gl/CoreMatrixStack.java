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
 * Call {@link #getModelView()} and {@link #getProjection()} to retrieve
 * the current matrices for uploading as shader uniforms.
 */
public final class CoreMatrixStack {

    // GL constants
    public static final int GL_MODELVIEW = 0x1700;
    public static final int GL_PROJECTION = 0x1701;
    public static final int GL_TEXTURE = 0x1702;

    private static final int STACK_DEPTH = 32;

    private final Matrix4fStack modelViewStack = new Matrix4fStack(STACK_DEPTH);
    private final Matrix4fStack projectionStack = new Matrix4fStack(STACK_DEPTH);
    private final Matrix4fStack textureStack = new Matrix4fStack(STACK_DEPTH);

    private int currentMode = GL_MODELVIEW;

    // Dirty flags — set when a matrix changes, cleared when read
    private boolean modelViewDirty = true;
    private boolean projectionDirty = true;

    public static final CoreMatrixStack INSTANCE = new CoreMatrixStack();

    private CoreMatrixStack() {}

    /**
     * Select which matrix stack to operate on.
     * Replaces glMatrixMode().
     */
    public void matrixMode(int mode) {
        currentMode = mode;
    }

    public int getMatrixMode() {
        return currentMode;
    }

    /**
     * Push the current matrix onto the active stack.
     * Replaces glPushMatrix().
     */
    public void pushMatrix() {
        activeStack().pushMatrix();
    }

    /**
     * Pop the top matrix from the active stack.
     * Replaces glPopMatrix().
     */
    public void popMatrix() {
        activeStack().popMatrix();
        markDirty();
    }

    /**
     * Load the identity matrix.
     * Replaces glLoadIdentity().
     */
    public void loadIdentity() {
        activeStack().identity();
        markDirty();
    }

    /**
     * Post-multiply by an orthographic projection matrix.
     * Replaces glOrtho().
     */
    public void ortho(double left, double right, double bottom, double top, double zNear, double zFar) {
        activeStack().ortho((float) left, (float) right, (float) bottom, (float) top, (float) zNear, (float) zFar);
        markDirty();
    }

    /**
     * Post-multiply by a rotation matrix.
     * Replaces glRotatef().
     */
    public void rotate(float angle, float x, float y, float z) {
        activeStack().rotate((float) Math.toRadians(angle), x, y, z);
        markDirty();
    }

    /**
     * Post-multiply by a scale matrix.
     * Replaces glScalef().
     */
    public void scale(float x, float y, float z) {
        activeStack().scale(x, y, z);
        markDirty();
    }

    /**
     * Post-multiply by a scale matrix (double variant).
     * Replaces glScaled().
     */
    public void scale(double x, double y, double z) {
        activeStack().scale((float) x, (float) y, (float) z);
        markDirty();
    }

    /**
     * Post-multiply by a translation matrix.
     * Replaces glTranslatef().
     */
    public void translate(float x, float y, float z) {
        activeStack().translate(x, y, z);
        markDirty();
    }

    /**
     * Post-multiply by a translation matrix (double variant).
     * Replaces glTranslated(). Uses double-precision arithmetic to match
     * the fixed-function pipeline's behavior — avoids sub-pixel jitter
     * when translating by large coordinates (e.g., cloud rendering).
     */
    public void translate(double x, double y, double z) {
        Matrix4fStack s = activeStack();
        // Perform translation in double precision to avoid float truncation
        // of large coordinates before the multiply. This matches how vanilla
        // glTranslated works internally.
        s.m30((float)(s.m00() * x + s.m10() * y + s.m20() * z + s.m30()));
        s.m31((float)(s.m01() * x + s.m11() * y + s.m21() * z + s.m31()));
        s.m32((float)(s.m02() * x + s.m12() * y + s.m22() * z + s.m32()));
        s.m33((float)(s.m03() * x + s.m13() * y + s.m23() * z + s.m33()));
        markDirty();
    }

    /**
     * Post-multiply the active matrix by the given 4x4 column-major matrix.
     * Replaces glMultMatrix().
     */
    private static final Matrix4f tempMultMatrix = new Matrix4f();

    public void multMatrix(FloatBuffer matrix) {
        tempMultMatrix.set(matrix);
        activeStack().mul(tempMultMatrix);
        markDirty();
    }

    /**
     * Post-multiply the active matrix by the given Matrix4f.
     */
    public void multMatrix(Matrix4f matrix) {
        activeStack().mul(matrix);
        markDirty();
    }

    /**
     * Load a 4x4 column-major matrix, replacing the current one.
     * Replaces glLoadMatrix().
     */
    public void loadMatrix(FloatBuffer matrix) {
        tempMultMatrix.set(matrix);
        activeStack().set(tempMultMatrix);
        markDirty();
    }

    /**
     * Read the current matrix into a FloatBuffer (column-major).
     * Replaces glGetFloat(GL_MODELVIEW_MATRIX/GL_PROJECTION_MATRIX).
     */
    public void getFloat(int pname, FloatBuffer params) {
        switch (pname) {
            case 0x0BA6 -> modelViewStack.get(params);  // GL_MODELVIEW_MATRIX
            case 0x0BA7 -> projectionStack.get(params); // GL_PROJECTION_MATRIX
            case 0x0BA8 -> textureStack.get(params);    // GL_TEXTURE_MATRIX
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
        return modelViewStack;
    }

    /**
     * Get the current projection matrix (read-only reference).
     */
    public Matrix4f getProjection() {
        return projectionStack;
    }

    /**
     * Get the current texture matrix (read-only reference).
     */
    public Matrix4f getTextureMatrix() {
        return textureStack;
    }

    public boolean isModelViewDirty() {
        return modelViewDirty;
    }

    public boolean isProjectionDirty() {
        return projectionDirty;
    }

    public void clearModelViewDirty() {
        modelViewDirty = false;
    }

    public void clearProjectionDirty() {
        projectionDirty = false;
    }

    private Matrix4fStack activeStack() {
        return switch (currentMode) {
            case GL_PROJECTION -> projectionStack;
            case GL_TEXTURE -> textureStack;
            default -> modelViewStack;
        };
    }

    private void markDirty() {
        switch (currentMode) {
            case GL_PROJECTION -> projectionDirty = true;
            case GL_MODELVIEW -> modelViewDirty = true;
            default -> {}
        }
    }
}
