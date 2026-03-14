package com.github.gl46core.api.render;

/**
 * Per-material rendering parameters.
 *
 * Describes how a surface should be shaded — texture references, alpha mode,
 * color multipliers, and PBR-ready fields for future shaderpack support.
 *
 * For the legacy translation path, materials are inferred from GL state
 * (texture binding, alpha test, texenv mode, etc.). The material registry
 * will map these inferred materials to MaterialData instances.
 *
 * GPU layout (std140, 64 bytes):
 *   int   materialId           offset 0
 *   int   textureIndex         offset 4    (atlas page or bindless handle low bits)
 *   int   lightmapIndex        offset 8
 *   int   shaderFeatureFlags   offset 12
 *   vec4  colorMultiplier      offset 16   (RGBA tint)
 *   float emissiveStrength     offset 32
 *   float roughness            offset 36
 *   float metallic             offset 40
 *   float alphaCutoff          offset 44
 *   int   alphaMode            offset 48   (0=opaque, 1=cutout, 2=blend)
 *   int   texEnvMode           offset 52   (GL texenv mode for legacy compat)
 *   int   lightResponseFlags   offset 56   (bitfield: receives shadows, ambient occlusion, etc.)
 *   int   _pad0                offset 60
 * Total: 64 bytes
 */
public final class MaterialData {

    public static final int GPU_SIZE = 64;

    /** Alpha blending modes. */
    public enum AlphaMode {
        OPAQUE,
        CUTOUT,
        BLEND
    }

    // Light response flag bits
    public static final int LIGHT_RECEIVES_SHADOW   = 1;
    public static final int LIGHT_AMBIENT_OCCLUSION  = 1 << 1;
    public static final int LIGHT_RECEIVES_DIFFUSE   = 1 << 2;
    public static final int LIGHT_RECEIVES_SPECULAR  = 1 << 3;
    public static final int LIGHT_SELF_ILLUMINATED   = 1 << 4;

    // Shader feature flag bits (which shader features this material needs)
    public static final int FEAT_TEXTURE        = 1;
    public static final int FEAT_ALPHA_TEST     = 1 << 1;
    public static final int FEAT_LIGHTMAP       = 1 << 2;
    public static final int FEAT_NORMAL_MAP     = 1 << 3;
    public static final int FEAT_SPECULAR_MAP   = 1 << 4;
    public static final int FEAT_EMISSIVE_MAP   = 1 << 5;

    private int materialId;
    private int textureIndex;
    private int lightmapIndex;
    private int shaderFeatureFlags;

    private float colorR = 1, colorG = 1, colorB = 1, colorA = 1;
    private float emissiveStrength;
    private float roughness = 0.8f;
    private float metallic;
    private float alphaCutoff = 0.1f;

    private AlphaMode alphaMode = AlphaMode.OPAQUE;
    private int texEnvMode = 0x2100; // GL_MODULATE
    private int lightResponseFlags = LIGHT_RECEIVES_SHADOW | LIGHT_RECEIVES_DIFFUSE | LIGHT_AMBIENT_OCCLUSION;

    public MaterialData() {}

    // ── Configuration ──

    public void setMaterialId(int id)            { this.materialId = id; }
    public void setTextureIndex(int idx)          { this.textureIndex = idx; }
    public void setLightmapIndex(int idx)         { this.lightmapIndex = idx; }
    public void setShaderFeatureFlags(int flags)  { this.shaderFeatureFlags = flags; }
    public void setColorMultiplier(float r, float g, float b, float a) {
        this.colorR = r; this.colorG = g; this.colorB = b; this.colorA = a;
    }
    public void setEmissiveStrength(float v)      { this.emissiveStrength = v; }
    public void setRoughness(float v)             { this.roughness = v; }
    public void setMetallic(float v)              { this.metallic = v; }
    public void setAlphaCutoff(float v)           { this.alphaCutoff = v; }
    public void setAlphaMode(AlphaMode mode)      { this.alphaMode = mode; }
    public void setTexEnvMode(int mode)           { this.texEnvMode = mode; }
    public void setLightResponseFlags(int flags)  { this.lightResponseFlags = flags; }

    // ── Accessors ──

    public int       getMaterialId()         { return materialId; }
    public int       getTextureIndex()       { return textureIndex; }
    public int       getLightmapIndex()      { return lightmapIndex; }
    public int       getShaderFeatureFlags() { return shaderFeatureFlags; }
    public float     getColorR()             { return colorR; }
    public float     getColorG()             { return colorG; }
    public float     getColorB()             { return colorB; }
    public float     getColorA()             { return colorA; }
    public float     getEmissiveStrength()   { return emissiveStrength; }
    public float     getRoughness()          { return roughness; }
    public float     getMetallic()           { return metallic; }
    public float     getAlphaCutoff()        { return alphaCutoff; }
    public AlphaMode getAlphaMode()          { return alphaMode; }
    public int       getTexEnvMode()         { return texEnvMode; }
    public int       getLightResponseFlags() { return lightResponseFlags; }

    public boolean isOpaque()      { return alphaMode == AlphaMode.OPAQUE; }
    public boolean isCutout()      { return alphaMode == AlphaMode.CUTOUT; }
    public boolean isTranslucent() { return alphaMode == AlphaMode.BLEND; }
}
