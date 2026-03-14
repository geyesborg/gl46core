package com.github.gl46core.api.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * GPU-uploadable scene-level data — everything a shader needs that is
 * constant across all draw calls within a frame (or pass).
 *
 * This is the authoritative scene data layout. The current PerScene UBO
 * in CoreShaderProgram uploads a subset of this; future passes and
 * shaderpacks will use the full set.
 *
 * std140 layout (binding 0, 256 bytes):
 *   mat4  viewMatrix            offset 0
 *   mat4  projectionMatrix      offset 64
 *   mat4  viewProjection        offset 128
 *   vec4  cameraPosition        offset 192   (xyz = pos, w = 0)
 *   vec4  sunDirection          offset 208   (xyz = dir, w = sunAngle)
 *   vec4  moonDirection         offset 224   (xyz = dir, w = skylightStrength)
 *   vec4  fogColor              offset 240
 *   vec4  ambientLight          offset 256   (rgb = ambient, a = 0)
 *   vec4  light0Position        offset 272
 *   vec4  light0Diffuse         offset 288
 *   vec4  light1Position        offset 304
 *   vec4  light1Diffuse         offset 320
 *   float fogDensity            offset 336
 *   float fogStart              offset 340
 *   float fogEnd                offset 344
 *   int   fogMode               offset 348
 *   float worldTime             offset 352
 *   float partialTicks          offset 356
 *   float rainStrength          offset 360
 *   float thunderStrength       offset 364
 *   ivec2 viewportSize          offset 368
 *   float nearPlane             offset 376
 *   float farPlane              offset 380
 *   float celestialAngle        offset 384
 *   float sunBrightness         offset 388
 *   int   frameIndex            offset 392
 *   int   _pad0                 offset 396
 *   mat4  prevViewMatrix        offset 400
 *   mat4  prevProjection        offset 464
 * Total: 528 bytes
 */
public final class SceneData {

    public static final int GPU_SIZE = 528;

    private final ByteBuffer buffer = ByteBuffer.allocateDirect(GPU_SIZE).order(ByteOrder.nativeOrder());

    // Cached matrices for GPU upload
    private final Matrix4f tempMat = new Matrix4f();

    public SceneData() {}

    /**
     * Pack all scene data from a FrameContext into the GPU buffer.
     * Call once per frame after all state is captured.
     */
    public void pack(FrameContext frame) {
        CameraState cam = frame.getCamera();
        GlobalLightState light = frame.getGlobalLight();
        FogState fog = frame.getFog();
        WeatherState weather = frame.getWeather();
        DimensionState dim = frame.getDimension();

        // Matrices
        cam.getViewMatrix().get(0, buffer);
        cam.getProjectionMatrix().get(64, buffer);
        cam.getViewProjection().get(128, buffer);

        // Camera position (vec4, w=0)
        buffer.putFloat(192, (float) cam.getPosition().x);
        buffer.putFloat(196, (float) cam.getPosition().y);
        buffer.putFloat(200, (float) cam.getPosition().z);
        buffer.putFloat(204, 0.0f);

        // Sun direction (vec4, w=sunAngle)
        Vector3f sunDir = light.getSunDirection();
        buffer.putFloat(208, sunDir.x);
        buffer.putFloat(212, sunDir.y);
        buffer.putFloat(216, sunDir.z);
        buffer.putFloat(220, light.getSunAngle());

        // Moon direction (vec4, w=skylightStrength)
        Vector3f moonDir = light.getMoonDirection();
        buffer.putFloat(224, moonDir.x);
        buffer.putFloat(228, moonDir.y);
        buffer.putFloat(232, moonDir.z);
        buffer.putFloat(236, light.getSkylightStrength());

        // Fog color
        buffer.putFloat(240, fog.getR());
        buffer.putFloat(244, fog.getG());
        buffer.putFloat(248, fog.getB());
        buffer.putFloat(252, fog.getA());

        // Ambient light (vec4, a=0)
        Vector3f amb = light.getAmbient();
        buffer.putFloat(256, amb.x);
        buffer.putFloat(260, amb.y);
        buffer.putFloat(264, amb.z);
        buffer.putFloat(268, 0.0f);

        // Light 0
        Vector4f l0p = light.getLight0Position();
        buffer.putFloat(272, l0p.x); buffer.putFloat(276, l0p.y);
        buffer.putFloat(280, l0p.z); buffer.putFloat(284, l0p.w);
        Vector4f l0d = light.getLight0Diffuse();
        buffer.putFloat(288, l0d.x); buffer.putFloat(292, l0d.y);
        buffer.putFloat(296, l0d.z); buffer.putFloat(300, l0d.w);

        // Light 1
        Vector4f l1p = light.getLight1Position();
        buffer.putFloat(304, l1p.x); buffer.putFloat(308, l1p.y);
        buffer.putFloat(312, l1p.z); buffer.putFloat(316, l1p.w);
        Vector4f l1d = light.getLight1Diffuse();
        buffer.putFloat(320, l1d.x); buffer.putFloat(324, l1d.y);
        buffer.putFloat(328, l1d.z); buffer.putFloat(332, l1d.w);

        // Fog params
        buffer.putFloat(336, fog.getDensity());
        buffer.putFloat(340, fog.getStart());
        buffer.putFloat(344, fog.getEnd());
        buffer.putInt(348, fog.getMode());

        // Time
        buffer.putFloat(352, (float) frame.getWorldTime());
        buffer.putFloat(356, frame.getPartialTicks());

        // Weather
        buffer.putFloat(360, weather.getRainStrength());
        buffer.putFloat(364, weather.getThunderStrength());

        // Viewport
        buffer.putInt(368, cam.getViewportWidth());
        buffer.putInt(372, cam.getViewportHeight());
        buffer.putFloat(376, cam.getNearPlane());
        buffer.putFloat(380, cam.getFarPlane());

        // Dimension
        buffer.putFloat(384, dim.getCelestialAngle());
        buffer.putFloat(388, dim.getSunBrightness());

        // Frame
        buffer.putInt(392, (int)(frame.getFrameIndex() & 0xFFFFFFFFL));
        buffer.putInt(396, 0); // padding

        // Previous frame matrices
        cam.getPrevViewMatrix().get(400, buffer);
        cam.getPrevProjection().get(464, buffer);
    }

    /**
     * Get the packed buffer ready for GPU upload.
     */
    public ByteBuffer getBuffer() {
        buffer.position(0).limit(GPU_SIZE);
        return buffer;
    }

    public int getGpuSize() { return GPU_SIZE; }
}
