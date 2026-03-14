package com.github.gl46core.api.render;

/**
 * Dimension-specific rendering state.
 *
 * Captured from WorldProvider at frame start. Affects sky rendering,
 * fog behavior, celestial angle, and ambient lighting model.
 */
public final class DimensionState {

    /** Known dimension types for rendering decisions. */
    public enum DimensionType {
        OVERWORLD,
        NETHER,
        END,
        CUSTOM
    }

    private int dimensionId;
    private DimensionType type = DimensionType.OVERWORLD;
    private boolean hasSkyLight;
    private boolean hasCeiling;         // Nether-like ceiling
    private float celestialAngle;       // 0..1 day cycle
    private float sunBrightness;        // WorldProvider.getSunBrightness
    private float starBrightness;       // WorldProvider.getStarBrightness
    private float skyColorR, skyColorG, skyColorB;

    public DimensionState() {}

    public void capture(int dimensionId, boolean hasSkyLight, boolean hasCeiling,
                        float celestialAngle, float sunBrightness, float starBrightness,
                        float skyR, float skyG, float skyB) {
        this.dimensionId = dimensionId;
        this.hasSkyLight = hasSkyLight;
        this.hasCeiling = hasCeiling;
        this.celestialAngle = celestialAngle;
        this.sunBrightness = sunBrightness;
        this.starBrightness = starBrightness;
        this.skyColorR = skyR;
        this.skyColorG = skyG;
        this.skyColorB = skyB;

        // Infer type from dimension ID
        if (dimensionId == 0) type = DimensionType.OVERWORLD;
        else if (dimensionId == -1) type = DimensionType.NETHER;
        else if (dimensionId == 1) type = DimensionType.END;
        else type = DimensionType.CUSTOM;
    }

    // ── Accessors ──

    public int           getDimensionId()   { return dimensionId; }
    public DimensionType getType()          { return type; }
    public boolean       hasSkyLight()      { return hasSkyLight; }
    public boolean       hasCeiling()       { return hasCeiling; }
    public float         getCelestialAngle(){ return celestialAngle; }
    public float         getSunBrightness() { return sunBrightness; }
    public float         getStarBrightness(){ return starBrightness; }
    public float         getSkyColorR()     { return skyColorR; }
    public float         getSkyColorG()     { return skyColorG; }
    public float         getSkyColorB()     { return skyColorB; }

    public boolean isOverworld() { return type == DimensionType.OVERWORLD; }
    public boolean isNether()    { return type == DimensionType.NETHER; }
    public boolean isEnd()       { return type == DimensionType.END; }
}
