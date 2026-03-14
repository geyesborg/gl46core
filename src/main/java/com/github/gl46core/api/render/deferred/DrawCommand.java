package com.github.gl46core.api.render.deferred;

import com.github.gl46core.api.render.PassType;

/**
 * Captures all state needed to replay a single draw call.
 *
 * Recorded during the collection phase when deferred mode is active.
 * Replayed during {@code executePasses()} in sorted pass order.
 *
 * Pool-allocated to avoid GC pressure — fields are set via
 * {@link #configure} and recycled each frame.
 *
 * State captured:
 *   - Geometry: VAO, VBO offset in frame allocator, vertex/index count, draw mode
 *   - Shader: variant key (determines which compiled program to use)
 *   - Textures: main texture ID, lightmap texture ID
 *   - Material: SSBO index from material registry
 *   - Sort: pass type, sort key (opaque front-to-back, translucent back-to-front)
 *   - GL state: blend enabled, depth test, depth mask, cull face
 */
public final class DrawCommand {

    // Geometry
    int vao;
    int vboOffset;         // byte offset into DeferredVboAllocator
    int vertexCount;
    int indexCount;         // 0 = non-indexed
    int drawMode;           // GL_TRIANGLES, etc.
    int eboHandle;          // 0 = no EBO

    // Shader
    int shaderVariantKey;

    // Textures
    int textureId;          // main diffuse texture
    int lightmapTextureId;  // lightmap texture (0 = none)

    // Material
    int materialIndex;      // SSBO index from material registry

    // Pass + sorting
    PassType passType;
    public long sortKey;

    // GL state snapshot
    boolean blendEnabled;
    int blendSrcRgb;
    int blendDstRgb;
    int blendSrcAlpha;
    int blendDstAlpha;
    boolean depthTest;
    boolean depthMask;
    boolean cullFace;
    int cullMode;

    // Format flags (for VAO attribute binding during replay)
    boolean hasColor;
    boolean hasTexCoord;
    boolean hasNormal;
    boolean hasLightMap;
    int stride;

    // Pool management
    boolean active;

    DrawCommand() {}

    /**
     * Configure this command for a draw call.
     */
    public void configure(int vao, int vboOffset, int vertexCount, int indexCount,
                          int drawMode, int eboHandle, int shaderVariantKey,
                          int textureId, int lightmapTextureId,
                          int materialIndex, PassType passType, long sortKey,
                          boolean hasColor, boolean hasTexCoord,
                          boolean hasNormal, boolean hasLightMap, int stride) {
        this.vao = vao;
        this.vboOffset = vboOffset;
        this.vertexCount = vertexCount;
        this.indexCount = indexCount;
        this.drawMode = drawMode;
        this.eboHandle = eboHandle;
        this.shaderVariantKey = shaderVariantKey;
        this.textureId = textureId;
        this.lightmapTextureId = lightmapTextureId;
        this.materialIndex = materialIndex;
        this.passType = passType;
        this.sortKey = sortKey;
        this.hasColor = hasColor;
        this.hasTexCoord = hasTexCoord;
        this.hasNormal = hasNormal;
        this.hasLightMap = hasLightMap;
        this.stride = stride;
        this.active = true;
    }

    /**
     * Capture current GL blend state.
     */
    public void captureBlendState(boolean enabled, int srcRgb, int dstRgb,
                                   int srcAlpha, int dstAlpha) {
        this.blendEnabled = enabled;
        this.blendSrcRgb = srcRgb;
        this.blendDstRgb = dstRgb;
        this.blendSrcAlpha = srcAlpha;
        this.blendDstAlpha = dstAlpha;
    }

    /**
     * Capture current GL depth/cull state.
     */
    public void captureDepthCullState(boolean depthTest, boolean depthMask,
                                       boolean cullFace, int cullMode) {
        this.depthTest = depthTest;
        this.depthMask = depthMask;
        this.cullFace = cullFace;
        this.cullMode = cullMode;
    }

    /**
     * Reset for pool reuse.
     */
    void reset() {
        active = false;
        passType = null;
    }

    public PassType getPassType() { return passType; }
    public long     getSortKey()  { return sortKey; }
    public boolean  isActive()    { return active; }
}
