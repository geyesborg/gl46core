package com.github.gl46core.api.render;

import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;

/**
 * Immutable snapshot of camera state for the current frame.
 *
 * Captured once per frame from EntityRenderer and CoreMatrixStack.
 * All matrices are copies — safe to read from any thread after capture.
 */
public final class CameraState {

    private final Matrix4f viewMatrix       = new Matrix4f();
    private final Matrix4f projectionMatrix = new Matrix4f();
    private final Matrix4f viewProjection   = new Matrix4f();
    private final Matrix4f invView          = new Matrix4f();
    private final Matrix4f invProjection    = new Matrix4f();
    private final Matrix4f invViewProj      = new Matrix4f();

    // Previous frame matrices for motion vectors / TAA
    private final Matrix4f prevViewMatrix   = new Matrix4f();
    private final Matrix4f prevProjection   = new Matrix4f();

    private final Vector3d position     = new Vector3d();
    private final Vector3d prevPosition = new Vector3d();
    private final Vector3f lookDir      = new Vector3f();

    private float partialTicks;
    private float fov;
    private float nearPlane;
    private float farPlane;
    private int viewportWidth;
    private int viewportHeight;

    public CameraState() {}

    /**
     * Capture current camera state. Call once at frame start.
     */
    public void capture(Matrix4f view, Matrix4f projection,
                        double camX, double camY, double camZ,
                        float partialTicks, float fov,
                        float near, float far,
                        int vpWidth, int vpHeight) {
        // Save previous frame
        this.prevViewMatrix.set(this.viewMatrix);
        this.prevProjection.set(this.projectionMatrix);

        // Set current
        this.viewMatrix.set(view);
        this.projectionMatrix.set(projection);
        this.projectionMatrix.mul(this.viewMatrix, this.viewProjection);
        this.viewMatrix.invert(this.invView);
        this.projectionMatrix.invert(this.invProjection);
        this.viewProjection.invert(this.invViewProj);

        this.prevPosition.set(this.position);
        this.position.set(camX, camY, camZ);

        // Extract look direction from view matrix (negative Z column)
        this.lookDir.set(-view.m02(), -view.m12(), -view.m22()).normalize();

        this.partialTicks = partialTicks;
        this.fov = fov;
        this.nearPlane = near;
        this.farPlane = far;
        this.viewportWidth = vpWidth;
        this.viewportHeight = vpHeight;
    }

    // ── Accessors ──

    public Matrix4f getViewMatrix()       { return viewMatrix; }
    public Matrix4f getProjectionMatrix() { return projectionMatrix; }
    public Matrix4f getViewProjection()   { return viewProjection; }
    public Matrix4f getInvView()          { return invView; }
    public Matrix4f getInvProjection()    { return invProjection; }
    public Matrix4f getInvViewProj()      { return invViewProj; }
    public Matrix4f getPrevViewMatrix()   { return prevViewMatrix; }
    public Matrix4f getPrevProjection()   { return prevProjection; }

    public Vector3d getPosition()      { return position; }
    public Vector3d getPrevPosition()   { return prevPosition; }
    public Vector3f getLookDir()        { return lookDir; }
    public float    getPartialTicks()  { return partialTicks; }
    public float    getFov()           { return fov; }
    public float    getNearPlane()     { return nearPlane; }
    public float    getFarPlane()      { return farPlane; }
    public int      getViewportWidth() { return viewportWidth; }
    public int      getViewportHeight(){ return viewportHeight; }
}
