package com.github.gl46core.api.render;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * GPU-uploadable per-pass data.
 *
 * Each render pass may override scene-level defaults (e.g. fog mode for
 * underwater, shadow cascade index, exposure). This is the PerPass UBO.
 *
 * std140 layout (binding 3, 64 bytes):
 *   int   passType             offset 0    (PassType ordinal)
 *   int   passFlags            offset 4    (bitfield)
 *   int   fogOverrideMode      offset 8    (0=use scene, 1=disabled, 2=custom)
 *   int   shadowCascadeIndex   offset 12
 *   ivec2 targetSize           offset 16
 *   float exposure             offset 24
 *   float alphaRefOverride     offset 28   (0 = use material default)
 *   int   renderLayerMask      offset 32   (bitmask of enabled render layers)
 *   int   mediumOverride       offset 36   (FogState.Medium ordinal, -1 = no override)
 *   int   postEffectFlags      offset 40
 *   int   _pad0                offset 44
 *   vec4  fogColorOverride     offset 48   (only if fogOverrideMode == 2)
 * Total: 64 bytes
 */
public final class PassData {

    public static final int GPU_SIZE = 64;

    // Pass flag bits
    public static final int FLAG_DEPTH_WRITE    = 1;
    public static final int FLAG_DEPTH_TEST     = 1 << 1;
    public static final int FLAG_BACKFACE_CULL  = 1 << 2;
    public static final int FLAG_ALPHA_TEST     = 1 << 3;
    public static final int FLAG_BLENDING       = 1 << 4;
    public static final int FLAG_WIREFRAME      = 1 << 5;

    private final ByteBuffer buffer = ByteBuffer.allocateDirect(GPU_SIZE).order(ByteOrder.nativeOrder());

    private PassType passType = PassType.TERRAIN_OPAQUE;
    private int passFlags = FLAG_DEPTH_WRITE | FLAG_DEPTH_TEST | FLAG_BACKFACE_CULL;
    private int fogOverrideMode;        // 0=scene, 1=disabled, 2=custom
    private int shadowCascadeIndex;
    private int targetWidth, targetHeight;
    private float exposure = 1.0f;
    private float alphaRefOverride;
    private int renderLayerMask = 0xFFFFFFFF;
    private int mediumOverride = -1;
    private int postEffectFlags;
    private float fogOverR, fogOverG, fogOverB, fogOverA;

    public PassData() {}

    public void configure(PassType type, int flags, int targetW, int targetH) {
        this.passType = type;
        this.passFlags = flags;
        this.targetWidth = targetW;
        this.targetHeight = targetH;
    }

    public void setFogOverride(int mode, float r, float g, float b, float a) {
        this.fogOverrideMode = mode;
        this.fogOverR = r; this.fogOverG = g; this.fogOverB = b; this.fogOverA = a;
    }

    public void setShadowCascade(int index)       { this.shadowCascadeIndex = index; }
    public void setExposure(float exposure)        { this.exposure = exposure; }
    public void setAlphaRefOverride(float ref)     { this.alphaRefOverride = ref; }
    public void setRenderLayerMask(int mask)       { this.renderLayerMask = mask; }
    public void setMediumOverride(int medium)      { this.mediumOverride = medium; }
    public void setPostEffectFlags(int flags)      { this.postEffectFlags = flags; }

    /**
     * Pack into GPU buffer for UBO upload.
     */
    public ByteBuffer pack() {
        buffer.putInt(0, passType.ordinal());
        buffer.putInt(4, passFlags);
        buffer.putInt(8, fogOverrideMode);
        buffer.putInt(12, shadowCascadeIndex);
        buffer.putInt(16, targetWidth);
        buffer.putInt(20, targetHeight);
        buffer.putFloat(24, exposure);
        buffer.putFloat(28, alphaRefOverride);
        buffer.putInt(32, renderLayerMask);
        buffer.putInt(36, mediumOverride);
        buffer.putInt(40, postEffectFlags);
        buffer.putInt(44, 0); // padding
        buffer.putFloat(48, fogOverR);
        buffer.putFloat(52, fogOverG);
        buffer.putFloat(56, fogOverB);
        buffer.putFloat(60, fogOverA);

        buffer.position(0).limit(GPU_SIZE);
        return buffer;
    }

    // ── Accessors ──

    public PassType getPassType()          { return passType; }
    public int      getPassFlags()         { return passFlags; }
    public boolean  hasFlag(int flag)      { return (passFlags & flag) != 0; }
    public int      getTargetWidth()       { return targetWidth; }
    public int      getTargetHeight()      { return targetHeight; }
    public float    getExposure()          { return exposure; }
    public int      getRenderLayerMask()   { return renderLayerMask; }
}
