package com.github.gl46core.api.render;

/**
 * Hardware and feature capability flags for the current session.
 *
 * Queried once at startup from GL capabilities. Immutable after init.
 * Used by the pass graph and shader compiler to select code paths.
 */
public final class RenderCapabilityState {

    // GL version/extension capabilities
    private boolean hasBindlessTextures;
    private boolean hasComputeShaders;
    private boolean hasIndirectDraw;
    private boolean hasMultiDrawIndirect;
    private boolean hasSSBO;
    private boolean hasPersistentMapping;
    private boolean hasAnisotropicFiltering;
    private boolean hasSparseTextures;

    // Hardware limits
    private int maxTextureUnits;
    private int maxUniformBufferBindings;
    private int maxSSBOBindings;
    private int maxComputeWorkGroupSize;
    private int maxTextureSize;
    private int maxArrayTextureLayers;
    private long maxSSBOSize;
    private long maxUBOSize;

    // Feature toggles (user/config driven)
    private boolean shadowsEnabled;
    private boolean postProcessingEnabled;
    private boolean shaderpackActive;

    public RenderCapabilityState() {}

    /**
     * Query capabilities from current GL context. Call once at startup.
     */
    public void queryFromGL() {
        org.lwjgl.opengl.GLCapabilities caps = org.lwjgl.opengl.GL.getCapabilities();

        hasBindlessTextures    = caps.GL_ARB_bindless_texture;
        hasComputeShaders      = caps.OpenGL43 || caps.GL_ARB_compute_shader;
        hasIndirectDraw        = caps.OpenGL40 || caps.GL_ARB_draw_indirect;
        hasMultiDrawIndirect   = caps.OpenGL43 || caps.GL_ARB_multi_draw_indirect;
        hasSSBO                = caps.OpenGL43 || caps.GL_ARB_shader_storage_buffer_object;
        hasPersistentMapping   = caps.OpenGL44 || caps.GL_ARB_buffer_storage;
        hasAnisotropicFiltering= caps.GL_EXT_texture_filter_anisotropic;
        hasSparseTextures      = caps.GL_ARB_sparse_texture;

        maxTextureUnits         = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL20.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS);
        maxUniformBufferBindings= org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL31.GL_MAX_UNIFORM_BUFFER_BINDINGS);
        maxSSBOBindings         = hasSSBO ? org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL43.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS) : 0;
        maxComputeWorkGroupSize = hasComputeShaders ? org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL43.GL_MAX_COMPUTE_WORK_GROUP_SIZE) : 0;
        maxTextureSize          = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_MAX_TEXTURE_SIZE);
        maxArrayTextureLayers   = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_MAX_ARRAY_TEXTURE_LAYERS);
        maxSSBOSize             = hasSSBO ? org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL43.GL_MAX_SHADER_STORAGE_BLOCK_SIZE) : 0;
        maxUBOSize              = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL31.GL_MAX_UNIFORM_BLOCK_SIZE);
    }

    // ── GL capabilities ──

    public boolean hasBindlessTextures()    { return hasBindlessTextures; }
    public boolean hasComputeShaders()      { return hasComputeShaders; }
    public boolean hasIndirectDraw()        { return hasIndirectDraw; }
    public boolean hasMultiDrawIndirect()   { return hasMultiDrawIndirect; }
    public boolean hasSSBO()                { return hasSSBO; }
    public boolean hasPersistentMapping()   { return hasPersistentMapping; }
    public boolean hasAnisotropicFiltering(){ return hasAnisotropicFiltering; }
    public boolean hasSparseTextures()      { return hasSparseTextures; }

    // ── Hardware limits ──

    public int  getMaxTextureUnits()          { return maxTextureUnits; }
    public int  getMaxUniformBufferBindings() { return maxUniformBufferBindings; }
    public int  getMaxSSBOBindings()          { return maxSSBOBindings; }
    public int  getMaxComputeWorkGroupSize()  { return maxComputeWorkGroupSize; }
    public int  getMaxTextureSize()           { return maxTextureSize; }
    public int  getMaxArrayTextureLayers()    { return maxArrayTextureLayers; }
    public long getMaxSSBOSize()              { return maxSSBOSize; }
    public long getMaxUBOSize()               { return maxUBOSize; }

    // ── Feature toggles ──

    public boolean isShadowsEnabled()        { return shadowsEnabled; }
    public boolean isPostProcessingEnabled() { return postProcessingEnabled; }
    public boolean isShaderpackActive()      { return shaderpackActive; }

    public void setShadowsEnabled(boolean v)        { this.shadowsEnabled = v; }
    public void setPostProcessingEnabled(boolean v) { this.postProcessingEnabled = v; }
    public void setShaderpackActive(boolean v)      { this.shaderpackActive = v; }
}
