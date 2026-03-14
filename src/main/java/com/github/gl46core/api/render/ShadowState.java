package com.github.gl46core.api.render;

import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;

/**
 * Computes and stores shadow mapping matrices for the current frame.
 *
 * Produces a light-space view matrix (looking from the sun/moon toward
 * the camera) and an orthographic projection matrix sized to cover the
 * shadow render distance around the camera.
 *
 * The shadow coordinate system follows the OptiFine/Iris convention:
 *   - Shadow view matrix: lookAt from sun position toward camera
 *   - Shadow projection: orthographic, centered on camera, sized by shadow distance
 *   - Shadow clip space: [-1,1] maps to the shadow map texture [0,1] via bias
 *
 * Shadow matrices are uploaded to shaderpack programs via {@link
 * com.github.gl46core.shaderpack.UniformBridge} as shadowModelView,
 * shadowProjection, and their inverses.
 */
public final class ShadowState {

    // Shadow rendering distance in blocks (configurable)
    private float shadowDistance = 128.0f;

    // Shadow map resolution (from RenderTargetManager)
    private int shadowResolution = 1024;

    // Light-space matrices
    private final Matrix4f shadowViewMatrix       = new Matrix4f();
    private final Matrix4f shadowProjectionMatrix = new Matrix4f();
    private final Matrix4f shadowViewInverse       = new Matrix4f();
    private final Matrix4f shadowProjectionInverse = new Matrix4f();

    // Scratch vectors
    private final Vector3f lightDir = new Vector3f();
    private final Vector3f up       = new Vector3f();
    private final Vector3f center   = new Vector3f();
    private final Vector3f eye      = new Vector3f();

    // State
    private boolean valid;

    public ShadowState() {}

    /**
     * Compute shadow matrices for this frame.
     *
     * @param sunDirection  normalized sun direction vector from GlobalLightState
     * @param cameraPos     camera world position
     * @param celestialAngle 0..1 day cycle (0.25 = noon, 0.75 = midnight)
     * @param shadowDist    shadow render distance in blocks
     * @param shadowRes     shadow map resolution in pixels
     */
    public void compute(Vector3f sunDirection, Vector3d cameraPos,
                        float celestialAngle, float shadowDist, int shadowRes) {
        this.shadowDistance = shadowDist;
        this.shadowResolution = shadowRes;

        // Determine the effective light direction:
        // During daytime (celestialAngle < 0.5), use sun direction.
        // During nighttime, use moon direction (negated sun).
        // Near horizon transitions, sun dir naturally approaches horizontal.
        boolean isNight = celestialAngle > 0.5f;
        if (isNight) {
            lightDir.set(-sunDirection.x, -sunDirection.y, -sunDirection.z);
        } else {
            lightDir.set(sunDirection);
        }

        // If the light is too close to horizontal, shadows are invalid
        // (sunrise/sunset transition — shadow map would be extremely stretched)
        if (Math.abs(lightDir.y) < 0.01f) {
            valid = false;
            shadowViewMatrix.identity();
            shadowProjectionMatrix.identity();
            shadowViewInverse.identity();
            shadowProjectionInverse.identity();
            return;
        }

        // Normalize
        lightDir.normalize();

        // Shadow view matrix: lookAt from light position toward camera
        // Place the "eye" far along the light direction from the camera
        float lightDistance = shadowDist * 2.0f;
        center.set((float) cameraPos.x, (float) cameraPos.y, (float) cameraPos.z);
        eye.set(center).add(
                lightDir.x * lightDistance,
                lightDir.y * lightDistance,
                lightDir.z * lightDistance
        );

        // Choose up vector perpendicular to light direction
        // If light is nearly vertical, use forward as up; otherwise use world up
        if (Math.abs(lightDir.y) > 0.99f) {
            up.set(0, 0, 1); // light is nearly vertical, use Z-forward as up
        } else {
            up.set(0, 1, 0); // standard world up
        }

        shadowViewMatrix.setLookAt(eye, center, up);

        // Shadow projection: orthographic covering shadowDistance around camera
        // The half-size defines the visible area in light space
        float halfSize = shadowDist;
        float nearClip = 0.05f;
        float farClip = lightDistance * 2.0f;
        shadowProjectionMatrix.setOrtho(
                -halfSize, halfSize,
                -halfSize, halfSize,
                nearClip, farClip
        );

        // Compute inverses
        shadowViewMatrix.invert(shadowViewInverse);
        shadowProjectionMatrix.invert(shadowProjectionInverse);

        valid = true;
    }

    // ── Accessors ──

    public Matrix4f getShadowViewMatrix()         { return shadowViewMatrix; }
    public Matrix4f getShadowProjectionMatrix()   { return shadowProjectionMatrix; }
    public Matrix4f getShadowViewInverse()        { return shadowViewInverse; }
    public Matrix4f getShadowProjectionInverse()  { return shadowProjectionInverse; }

    public boolean isValid()           { return valid; }
    public float   getShadowDistance() { return shadowDistance; }
    public int     getShadowResolution() { return shadowResolution; }
}
