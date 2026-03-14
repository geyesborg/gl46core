package com.github.gl46core.api.render;

/**
 * A single renderable submission into the render queue.
 *
 * Represents one draw call's worth of data: what to draw (mesh reference),
 * how to draw it (material), where to draw it (object transforms), and
 * a sort key for ordering within a queue.
 *
 * Sort key encoding (64-bit):
 *   Bits 63-56: pass layer (opaque=0, cutout=1, translucent=2)
 *   Bits 55-48: material ID (for state-change batching)
 *   Bits 47-32: depth bits (front-to-back for opaque, back-to-front for translucent)
 *   Bits 31-0:  submission index (stable sort tiebreaker)
 *
 * For translucent passes, depth bits are inverted to achieve back-to-front order.
 */
public final class RenderSubmission {

    private long sortKey;

    // References — not owned, just pointers into pooled data
    private int objectIndex;        // index into Object SSBO / ObjectData pool
    private int materialIndex;      // index into Material SSBO / MaterialData pool
    private int meshBufferOffset;   // byte offset into vertex buffer
    private int meshVertexCount;    // number of vertices (or indices)
    private int meshIndexOffset;    // byte offset into index buffer (-1 = non-indexed)
    private int drawMode;           // GL_TRIANGLES, GL_TRIANGLE_STRIP, etc.

    // For indirect draw path
    private int indirectCommandOffset = -1;  // offset into indirect draw buffer (-1 = direct draw)

    public RenderSubmission() {}

    // ── Sort key construction ──

    /**
     * Build sort key for opaque rendering (front-to-back by depth).
     */
    public void buildOpaqueKey(int layer, int materialId, float depth, int index) {
        // Quantize depth to 16 bits (0 = near, 0xFFFF = far)
        int depthBits = Math.min(0xFFFF, Math.max(0, (int)(depth * 256.0f)));
        sortKey = ((long)(layer & 0xFF) << 56)
                | ((long)(materialId & 0xFF) << 48)
                | ((long)(depthBits & 0xFFFF) << 32)
                | (index & 0xFFFFFFFFL);
    }

    /**
     * Build sort key for translucent rendering (back-to-front by depth).
     */
    public void buildTranslucentKey(int layer, int materialId, float depth, int index) {
        // Invert depth for back-to-front
        int depthBits = 0xFFFF - Math.min(0xFFFF, Math.max(0, (int)(depth * 256.0f)));
        sortKey = ((long)(layer & 0xFF) << 56)
                | ((long)(materialId & 0xFF) << 48)
                | ((long)(depthBits & 0xFFFF) << 32)
                | (index & 0xFFFFFFFFL);
    }

    public void setSortKey(long key) { this.sortKey = key; }
    public long getSortKey()         { return sortKey; }

    // ── Draw data ──

    public void setObjectIndex(int idx)        { this.objectIndex = idx; }
    public void setMaterialIndex(int idx)      { this.materialIndex = idx; }
    public void setMesh(int bufferOffset, int vertexCount, int indexOffset, int drawMode) {
        this.meshBufferOffset = bufferOffset;
        this.meshVertexCount = vertexCount;
        this.meshIndexOffset = indexOffset;
        this.drawMode = drawMode;
    }
    public void setIndirectCommandOffset(int off) { this.indirectCommandOffset = off; }

    public int  getObjectIndex()         { return objectIndex; }
    public int  getMaterialIndex()       { return materialIndex; }
    public int  getMeshBufferOffset()    { return meshBufferOffset; }
    public int  getMeshVertexCount()     { return meshVertexCount; }
    public int  getMeshIndexOffset()     { return meshIndexOffset; }
    public int  getDrawMode()            { return drawMode; }
    public int  getIndirectCommandOffset() { return indirectCommandOffset; }
    public boolean isIndexed()           { return meshIndexOffset >= 0; }
    public boolean isIndirect()          { return indirectCommandOffset >= 0; }
}
