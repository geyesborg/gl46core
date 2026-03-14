package com.github.gl46core.api.render;

/**
 * The submission contract — a clean data record capturing render intent.
 *
 * DrawPacket captures WHAT to draw (geometry), HOW to draw it (material/pipeline),
 * WHERE to draw it (transform), and WHEN (pass classification + sort key).
 *
 * This is NOT a GL command. It is render intent that an executor interprets
 * to issue the appropriate GL calls. This separation enables:
 *   - Pass sorting (front-to-back opaque, back-to-front translucent)
 *   - Material batching (minimize state changes)
 *   - State deduplication (skip redundant pipeline changes)
 *   - Future multi-draw indirect
 *
 * Pooled and reused per frame — no per-frame allocation.
 */
public final class DrawPacket {

    // ── Source system constants ──
    public static final int SOURCE_TERRAIN  = 0;
    public static final int SOURCE_ENTITY   = 1;
    public static final int SOURCE_PARTICLE = 2;
    public static final int SOURCE_WEATHER  = 3;
    public static final int SOURCE_SKY      = 4;
    public static final int SOURCE_HAND     = 5;
    public static final int SOURCE_UI       = 6;

    // ── Pass classification ──
    private PassType passType;

    // ── Material identity ──
    private int shaderVariantKey;    // 8-bit key from GL state combination
    private int materialHash;        // combined hash for batching (variant + textures + blend)

    // ── Pipeline state ──
    private int pipelineStateKey;    // hash of depth/blend/cull configuration

    // ── Geometry reference ──
    private int geometrySourceId;    // GL buffer handle (VBO)
    private int vertexCount;
    private int vertexOffset;        // first vertex (usually 0)
    private int drawMode;            // GL_QUADS, GL_TRIANGLES, etc.

    // ── Transform ──
    private float translateX;        // world-space offset from camera
    private float translateY;
    private float translateZ;

    // ── Sorting ──
    private long sortKey;
    private float distanceSq;        // squared distance to camera

    // ── Source tracking ──
    private int sourceSystem;        // SOURCE_TERRAIN, SOURCE_ENTITY, etc.

    // ── Chunk reference (terrain-specific, 0 for non-terrain) ──
    private int chunkX, chunkY, chunkZ;

    // ── Mega-buffer (terrain MDI) ──
    private int baseVertex = -1;  // vertex offset in MegaTerrainBuffer, or -1 if not available

    public DrawPacket() {}

    /** Reset all fields for reuse from pool. */
    public void reset() {
        passType = null;
        shaderVariantKey = 0;
        materialHash = 0;
        pipelineStateKey = 0;
        geometrySourceId = 0;
        vertexCount = 0;
        vertexOffset = 0;
        drawMode = 0;
        translateX = 0; translateY = 0; translateZ = 0;
        sortKey = 0;
        distanceSq = 0;
        sourceSystem = 0;
        chunkX = 0; chunkY = 0; chunkZ = 0;
        baseVertex = -1;
    }

    // ── Setters ──

    public void setPassType(PassType type)           { this.passType = type; }
    public void setShaderVariantKey(int key)          { this.shaderVariantKey = key; }
    public void setMaterialHash(int hash)             { this.materialHash = hash; }
    public void setPipelineStateKey(int key)          { this.pipelineStateKey = key; }

    public void setGeometrySource(int vboId, int offset, int count, int mode) {
        this.geometrySourceId = vboId;
        this.vertexOffset = offset;
        this.vertexCount = count;
        this.drawMode = mode;
    }

    public void setTranslation(float x, float y, float z) {
        this.translateX = x;
        this.translateY = y;
        this.translateZ = z;
    }

    public void setSortKey(long key)                  { this.sortKey = key; }
    public void setDistanceSq(float d)                { this.distanceSq = d; }
    public void setSourceSystem(int src)              { this.sourceSystem = src; }
    public void setChunkPos(int x, int y, int z)      { this.chunkX = x; this.chunkY = y; this.chunkZ = z; }
    public void setBaseVertex(int bv)                    { this.baseVertex = bv; }

    /** Build front-to-back sort key for opaque geometry. */
    public void buildOpaqueSortKey(int layer, int materialId) {
        int depthBits = Math.min(0xFFFF, Math.max(0, (int)(distanceSq * 4.0f)));
        sortKey = ((long)(layer & 0xFF) << 56)
                | ((long)(materialId & 0xFF) << 48)
                | ((long)(depthBits & 0xFFFF) << 32);
    }

    /** Build back-to-front sort key for translucent geometry. */
    public void buildTranslucentSortKey(int layer, int materialId) {
        int depthBits = 0xFFFF - Math.min(0xFFFF, Math.max(0, (int)(distanceSq * 4.0f)));
        sortKey = ((long)(layer & 0xFF) << 56)
                | ((long)(materialId & 0xFF) << 48)
                | ((long)(depthBits & 0xFFFF) << 32);
    }

    // ── Getters ──

    public PassType getPassType()        { return passType; }
    public int getShaderVariantKey()     { return shaderVariantKey; }
    public int getMaterialHash()         { return materialHash; }
    public int getPipelineStateKey()     { return pipelineStateKey; }
    public int getGeometrySourceId()     { return geometrySourceId; }
    public int getVertexCount()          { return vertexCount; }
    public int getVertexOffset()         { return vertexOffset; }
    public int getDrawMode()             { return drawMode; }
    public float getTranslateX()         { return translateX; }
    public float getTranslateY()         { return translateY; }
    public float getTranslateZ()         { return translateZ; }
    public long getSortKey()             { return sortKey; }
    public float getDistanceSq()         { return distanceSq; }
    public int getSourceSystem()         { return sourceSystem; }
    public int getChunkX()               { return chunkX; }
    public int getChunkY()               { return chunkY; }
    public int getChunkZ()               { return chunkZ; }
    public int getBaseVertex()            { return baseVertex; }
    public boolean hasMegaRegion()        { return baseVertex >= 0; }
}
