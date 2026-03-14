package com.github.gl46core.api.render;

import org.joml.Vector3f;

/**
 * Per-light data for dynamic/local light sources.
 *
 * Represents a single point, spot, or area light. Collected per frame from
 * registered DynamicLightProviders and uploaded to the Light SSBO.
 *
 * GPU layout (std140, 48 bytes per light):
 *   vec4  positionAndRadius     offset 0    (xyz=worldPos, w=radius)
 *   vec4  colorAndIntensity     offset 16   (rgb=color, a=intensity)
 *   int   lightType             offset 32   (0=point, 1=spot, 2=area)
 *   int   shadowFlags           offset 36   (bitfield)
 *   float falloffExponent       offset 40
 *   float spotAngle             offset 44   (cos of half-angle, spot lights only)
 * Total: 48 bytes
 */
public final class LightData {

    public static final int GPU_SIZE = 48;

    /** Light source types. */
    public enum LightType {
        POINT,
        SPOT,
        AREA
    }

    // Shadow flag bits
    public static final int SHADOW_CAST     = 1;
    public static final int SHADOW_SOFT     = 1 << 1;
    public static final int SHADOW_DYNAMIC  = 1 << 2;

    private final Vector3f position = new Vector3f();
    private float radius = 16.0f;

    private float r = 1, g = 1, b = 1;
    private float intensity = 1.0f;

    private LightType lightType = LightType.POINT;
    private int shadowFlags;
    private float falloffExponent = 2.0f;
    private float spotAngle = 0.5f;  // cos(60°)

    // Source tracking (not uploaded to GPU)
    private int entityId = -1;
    private int blockX, blockY, blockZ;

    public LightData() {}

    // ── Configuration ──

    public void setPosition(float x, float y, float z) { position.set(x, y, z); }
    public void setRadius(float r)               { this.radius = r; }
    public void setColor(float r, float g, float b) { this.r = r; this.g = g; this.b = b; }
    public void setIntensity(float v)            { this.intensity = v; }
    public void setLightType(LightType type)     { this.lightType = type; }
    public void setShadowFlags(int flags)        { this.shadowFlags = flags; }
    public void setFalloffExponent(float v)      { this.falloffExponent = v; }
    public void setSpotAngle(float cosHalfAngle) { this.spotAngle = cosHalfAngle; }
    public void setEntityId(int id)              { this.entityId = id; }
    public void setBlockPos(int x, int y, int z) { blockX = x; blockY = y; blockZ = z; }

    // ── Accessors ──

    public Vector3f  getPosition()        { return position; }
    public float     getRadius()          { return radius; }
    public float     getR()               { return r; }
    public float     getG()               { return g; }
    public float     getB()               { return b; }
    public float     getIntensity()       { return intensity; }
    public LightType getLightType()       { return lightType; }
    public int       getShadowFlags()     { return shadowFlags; }
    public float     getFalloffExponent() { return falloffExponent; }
    public float     getSpotAngle()       { return spotAngle; }
    public int       getEntityId()        { return entityId; }
    public int       getBlockX()          { return blockX; }
    public int       getBlockY()          { return blockY; }
    public int       getBlockZ()          { return blockZ; }
    public boolean   castsShadow()        { return (shadowFlags & SHADOW_CAST) != 0; }
}
