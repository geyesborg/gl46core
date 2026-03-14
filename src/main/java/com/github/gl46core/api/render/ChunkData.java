package com.github.gl46core.api.render;

import org.joml.Vector3i;

/**
 * Per-chunk rendering metadata for visible chunk sections.
 *
 * Tracks a chunk section's world position, visibility, mesh state,
 * and draw command offsets. Used by the terrain submission system
 * and stored in the Chunk SSBO for GPU-driven rendering.
 *
 * MC 1.12.2 chunk sections are 16x16x16 blocks.
 *
 * GPU layout (std140, 64 bytes):
 *   ivec4 chunkOrigin           offset 0    (xyz=section origin in blocks, w=regionId)
 *   ivec4 bounds                offset 16   (xyz=section size always 16, w=biomeIndex)
 *   int   visibilityFlags       offset 32   (per-face visibility from PVS)
 *   int   meshSectionOffset     offset 36   (offset into vertex buffer)
 *   int   drawCommandOffset     offset 40   (offset into indirect draw buffer)
 *   int   lightVolumeIndex      offset 44   (index into light volume array)
 *   int   biomeLightIndex       offset 48   (biome-specific lighting/tint index)
 *   int   localLightListOffset  offset 52   (offset into Light Index SSBO)
 *   int   localLightCount       offset 56   (number of dynamic lights in this section)
 *   int   chunkLightFlags       offset 60   (lighting state bitfield)
 * Total: 64 bytes
 */
public final class ChunkData {

    public static final int GPU_SIZE = 64;

    // Chunk lighting flag bits
    public static final int CLIGHT_HAS_SKYLIGHT     = 1;
    public static final int CLIGHT_HAS_BLOCKLIGHT   = 1 << 1;
    public static final int CLIGHT_UNDERGROUND       = 1 << 2;
    public static final int CLIGHT_EMISSIVE_BLOCKS   = 1 << 3;

    // Visibility flag bits (which faces are potentially visible from camera)
    public static final int VIS_DOWN  = 1;
    public static final int VIS_UP    = 1 << 1;
    public static final int VIS_NORTH = 1 << 2;
    public static final int VIS_SOUTH = 1 << 3;
    public static final int VIS_WEST  = 1 << 4;
    public static final int VIS_EAST  = 1 << 5;
    public static final int VIS_ALL   = 0x3F;

    // Render layer flags (which layers have geometry)
    public static final int LAYER_OPAQUE      = 1;
    public static final int LAYER_CUTOUT      = 1 << 1;
    public static final int LAYER_CUTOUT_MIPPED = 1 << 2;
    public static final int LAYER_TRANSLUCENT = 1 << 3;

    private int sectionX, sectionY, sectionZ;   // section coordinates (block >> 4)
    private int regionId;
    private int biomeIndex;

    private int visibilityFlags = VIS_ALL;
    private int renderLayerFlags;
    private int meshSectionOffset;
    private int drawCommandOffset;
    private int lightVolumeIndex = -1;
    private int biomeLightIndex;
    private int localLightListOffset;
    private int localLightCount;
    private int chunkLightFlags;

    // CPU-side tracking
    private boolean dirty;          // needs rebuild
    private boolean empty;          // no geometry at all
    private long lastBuildFrame;    // frame index when last rebuilt

    public ChunkData() {}

    // ── Configuration ──

    public void setSection(int sx, int sy, int sz) {
        this.sectionX = sx; this.sectionY = sy; this.sectionZ = sz;
    }
    public void setRegionId(int id)              { this.regionId = id; }
    public void setBiomeIndex(int idx)           { this.biomeIndex = idx; }
    public void setVisibilityFlags(int flags)    { this.visibilityFlags = flags; }
    public void setRenderLayerFlags(int flags)   { this.renderLayerFlags = flags; }
    public void setMeshSectionOffset(int off)    { this.meshSectionOffset = off; }
    public void setDrawCommandOffset(int off)    { this.drawCommandOffset = off; }
    public void setLightVolumeIndex(int idx)     { this.lightVolumeIndex = idx; }
    public void setBiomeLightIndex(int idx)       { this.biomeLightIndex = idx; }
    public void setLocalLightList(int offset, int count) {
        this.localLightListOffset = offset;
        this.localLightCount = count;
    }
    public void setChunkLightFlags(int flags)     { this.chunkLightFlags = flags; }
    public void setDirty(boolean dirty)          { this.dirty = dirty; }
    public void setEmpty(boolean empty)          { this.empty = empty; }
    public void setLastBuildFrame(long frame)    { this.lastBuildFrame = frame; }

    // ── Accessors ──

    public int  getSectionX()          { return sectionX; }
    public int  getSectionY()          { return sectionY; }
    public int  getSectionZ()          { return sectionZ; }
    public int  getBlockOriginX()      { return sectionX << 4; }
    public int  getBlockOriginY()      { return sectionY << 4; }
    public int  getBlockOriginZ()      { return sectionZ << 4; }
    public int  getRegionId()          { return regionId; }
    public int  getBiomeIndex()        { return biomeIndex; }
    public int  getVisibilityFlags()   { return visibilityFlags; }
    public int  getRenderLayerFlags()  { return renderLayerFlags; }
    public int  getMeshSectionOffset() { return meshSectionOffset; }
    public int  getDrawCommandOffset() { return drawCommandOffset; }
    public int  getLightVolumeIndex()  { return lightVolumeIndex; }
    public int  getBiomeLightIndex()   { return biomeLightIndex; }
    public int  getLocalLightListOffset() { return localLightListOffset; }
    public int  getLocalLightCount()   { return localLightCount; }
    public int  getChunkLightFlags()   { return chunkLightFlags; }
    public boolean isDirty()           { return dirty; }
    public boolean isEmpty()           { return empty; }
    public long getLastBuildFrame()    { return lastBuildFrame; }

    public boolean hasLayer(int layerFlag) { return (renderLayerFlags & layerFlag) != 0; }
    public boolean isVisible(int face)     { return (visibilityFlags & face) != 0; }
}
