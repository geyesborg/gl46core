package com.github.gl46core.api.render;

/**
 * Canonical fog model — CPU-side representation of fog parameters.
 *
 * Captures the full fog configuration from CoreStateTracker into a clean
 * data structure that passes and shaderpacks can interpret or override.
 *
 * GL fog mode constants:
 *   GL_LINEAR = 0x2601, GL_EXP = 0x0800, GL_EXP2 = 0x0801
 */
public final class FogState {

    /** Fog medium type — helps passes interpret fog behavior. */
    public enum Medium {
        AIR,
        WATER,
        LAVA
    }

    private boolean enabled;
    private int mode;           // GL_LINEAR, GL_EXP, GL_EXP2
    private float density;
    private float start;
    private float end;
    private float r, g, b, a;  // fog color

    // Extended fog model for future shaderpack support
    private Medium medium = Medium.AIR;
    private float weatherMultiplier = 1.0f;
    private float skyBlendFactor = 1.0f;

    // Height fog (future)
    private boolean heightFogEnabled;
    private float heightFogBase;
    private float heightFogDensity;
    private float heightFogFalloff;

    public FogState() {}

    /**
     * Capture fog state from CoreStateTracker. Call when state generation changes.
     */
    public void capture(boolean enabled, int mode, float density,
                        float start, float end,
                        float r, float g, float b, float a) {
        this.enabled = enabled;
        this.mode = mode;
        this.density = density;
        this.start = start;
        this.end = end;
        this.r = r;
        this.g = g;
        this.b = b;
        this.a = a;
    }

    public void setMedium(Medium medium)                 { this.medium = medium; }
    public void setWeatherMultiplier(float multiplier)   { this.weatherMultiplier = multiplier; }
    public void setSkyBlendFactor(float factor)          { this.skyBlendFactor = factor; }

    public void setHeightFog(boolean enabled, float base, float density, float falloff) {
        this.heightFogEnabled = enabled;
        this.heightFogBase = base;
        this.heightFogDensity = density;
        this.heightFogFalloff = falloff;
    }

    // ── Accessors ──

    public boolean isEnabled()           { return enabled; }
    public int     getMode()             { return mode; }
    public float   getDensity()          { return density; }
    public float   getStart()            { return start; }
    public float   getEnd()              { return end; }
    public float   getR()                { return r; }
    public float   getG()                { return g; }
    public float   getB()                { return b; }
    public float   getA()                { return a; }
    public Medium  getMedium()           { return medium; }
    public float   getWeatherMultiplier(){ return weatherMultiplier; }
    public float   getSkyBlendFactor()   { return skyBlendFactor; }

    public boolean isHeightFogEnabled()  { return heightFogEnabled; }
    public float   getHeightFogBase()    { return heightFogBase; }
    public float   getHeightFogDensity() { return heightFogDensity; }
    public float   getHeightFogFalloff() { return heightFogFalloff; }
}
