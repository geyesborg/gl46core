package com.github.gl46core.api.render;

/**
 * Weather state snapshot for the current frame.
 *
 * Captured from World.getRainStrength() / getThunderStrength() at frame start.
 * Used by fog modifiers, particle systems, and shaderpack wetness uniforms.
 */
public final class WeatherState {

    private float rainStrength;
    private float thunderStrength;
    private float temperature;      // biome temperature at camera
    private boolean isSnowing;      // derived from biome + temperature

    public WeatherState() {}

    public void capture(float rainStrength, float thunderStrength,
                        float temperature, boolean isSnowing) {
        this.rainStrength = rainStrength;
        this.thunderStrength = thunderStrength;
        this.temperature = temperature;
        this.isSnowing = isSnowing;
    }

    // ── Accessors ──

    public float   getRainStrength()    { return rainStrength; }
    public float   getThunderStrength() { return thunderStrength; }
    public float   getTemperature()     { return temperature; }
    public boolean isSnowing()          { return isSnowing; }
    public boolean isRaining()          { return rainStrength > 0.0f; }
    public float   getWetness()         { return isSnowing ? 0.0f : rainStrength; }
}
