package com.github.gl46core.api.render;

import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Global scene lighting state — sun/moon directional lights + ambient.
 *
 * MC 1.12.2 uses two fixed lights (GL_LIGHT0, GL_LIGHT1) for entity/item
 * lighting, plus a global ambient term. This captures that into a clean
 * data model that can be extended for shaderpacks (shadow-casting sun, etc.).
 */
public final class GlobalLightState {

    // Light 0 (primary — typically sun/upper)
    private final Vector4f light0Position = new Vector4f();
    private final Vector4f light0Diffuse  = new Vector4f();

    // Light 1 (secondary — typically fill/lower)
    private final Vector4f light1Position = new Vector4f();
    private final Vector4f light1Diffuse  = new Vector4f();

    // Global ambient
    private final Vector3f ambient = new Vector3f();

    // Extended: sun/moon direction + color for shaderpacks
    private final Vector3f sunDirection  = new Vector3f(0, 1, 0);
    private final Vector3f moonDirection = new Vector3f(0, -1, 0);
    private final Vector3f sunColor  = new Vector3f(1, 1, 1);
    private final Vector3f moonColor = new Vector3f(0.6f, 0.7f, 1.0f);
    private float sunAngle;
    private float skylightStrength = 1.0f;
    private float blockLightGlobalScale = 1.0f;
    private float weatherDarken;
    private int lightingFlags;  // bitfield: dimension, time-of-day, etc.

    // Lighting flag bits
    public static final int FLAG_HAS_SKY      = 1;
    public static final int FLAG_NETHER       = 1 << 1;
    public static final int FLAG_END          = 1 << 2;
    public static final int FLAG_NIGHT        = 1 << 3;
    public static final int FLAG_RAINING      = 1 << 4;
    public static final int FLAG_THUNDERING   = 1 << 5;

    public GlobalLightState() {}

    /**
     * Capture from CoreStateTracker light arrays.
     */
    public void capture(float[] l0Pos, float[] l0Diff,
                        float[] l1Pos, float[] l1Diff,
                        float ambR, float ambG, float ambB) {
        light0Position.set(l0Pos[0], l0Pos[1], l0Pos[2], l0Pos[3]);
        light0Diffuse.set(l0Diff[0], l0Diff[1], l0Diff[2], l0Diff[3]);
        light1Position.set(l1Pos[0], l1Pos[1], l1Pos[2], l1Pos[3]);
        light1Diffuse.set(l1Diff[0], l1Diff[1], l1Diff[2], l1Diff[3]);
        ambient.set(ambR, ambG, ambB);
    }

    /**
     * Set extended sun/moon data for shaderpack support.
     */
    public void setSunMoon(float sunAngle, Vector3f sunDir, Vector3f moonDir, float skylightStrength) {
        this.sunAngle = sunAngle;
        this.sunDirection.set(sunDir);
        this.moonDirection.set(moonDir);
        this.skylightStrength = skylightStrength;
    }

    /**
     * Set extended environment lighting state.
     */
    public void setEnvironment(Vector3f sunCol, Vector3f moonCol,
                               float blockLightScale, float weatherDarken, int flags) {
        this.sunColor.set(sunCol);
        this.moonColor.set(moonCol);
        this.blockLightGlobalScale = blockLightScale;
        this.weatherDarken = weatherDarken;
        this.lightingFlags = flags;
    }

    // ── Accessors ──

    public Vector4f getLight0Position() { return light0Position; }
    public Vector4f getLight0Diffuse()  { return light0Diffuse; }
    public Vector4f getLight1Position() { return light1Position; }
    public Vector4f getLight1Diffuse()  { return light1Diffuse; }
    public Vector3f getAmbient()        { return ambient; }

    public Vector3f getSunDirection()   { return sunDirection; }
    public Vector3f getMoonDirection()  { return moonDirection; }
    public Vector3f getSunColor()       { return sunColor; }
    public Vector3f getMoonColor()      { return moonColor; }
    public float    getSunAngle()       { return sunAngle; }
    public float    getSkylightStrength()     { return skylightStrength; }
    public float    getBlockLightGlobalScale() { return blockLightGlobalScale; }
    public float    getWeatherDarken()  { return weatherDarken; }
    public int      getLightingFlags()  { return lightingFlags; }
}
