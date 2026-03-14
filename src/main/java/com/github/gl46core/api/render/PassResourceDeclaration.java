package com.github.gl46core.api.render;

/**
 * Builder for declaring render pass resource requirements.
 *
 * Passed to {@link RenderPass#declareResources} so the pass graph knows
 * what each pass reads and writes. This enables automatic resource
 * management, barrier insertion, and dependency resolution.
 */
public final class PassResourceDeclaration {

    /** Resource access mode. */
    public enum Access { READ, WRITE, READ_WRITE }

    /** Well-known resource names. */
    public static final String COLOR_BUFFER    = "color";
    public static final String DEPTH_BUFFER    = "depth";
    public static final String SHADOW_MAP      = "shadow_map";
    public static final String LIGHTMAP_TEX    = "lightmap";
    public static final String BLOCK_ATLAS     = "block_atlas";
    public static final String SCENE_UBO       = "scene_ubo";
    public static final String PASS_UBO        = "pass_ubo";
    public static final String MATERIAL_SSBO   = "material_ssbo";
    public static final String LIGHT_SSBO      = "light_ssbo";

    // Internal lists — will be replaced with proper collections in Phase 2
    private static final int MAX_RESOURCES = 16;
    private final String[] resourceNames = new String[MAX_RESOURCES];
    private final Access[] resourceAccess = new Access[MAX_RESOURCES];
    private int count;

    private boolean needsDepth;
    private boolean needsSceneData;
    private boolean needsPassData;
    private boolean needsMaterialData;
    private boolean needsLightData;
    private boolean allowsInjection;

    public PassResourceDeclaration() {}

    /**
     * Declare a named resource this pass uses.
     */
    public PassResourceDeclaration resource(String name, Access access) {
        if (count < MAX_RESOURCES) {
            resourceNames[count] = name;
            resourceAccess[count] = access;
            count++;
        }
        return this;
    }

    public PassResourceDeclaration readsColor()     { return resource(COLOR_BUFFER, Access.READ); }
    public PassResourceDeclaration writesColor()    { return resource(COLOR_BUFFER, Access.WRITE); }
    public PassResourceDeclaration readsDepth()     { needsDepth = true; return resource(DEPTH_BUFFER, Access.READ); }
    public PassResourceDeclaration writesDepth()    { needsDepth = true; return resource(DEPTH_BUFFER, Access.WRITE); }
    public PassResourceDeclaration readsShadowMap() { return resource(SHADOW_MAP, Access.READ); }
    public PassResourceDeclaration writesShadowMap(){ return resource(SHADOW_MAP, Access.WRITE); }

    public PassResourceDeclaration needsSceneData()   { this.needsSceneData = true; return this; }
    public PassResourceDeclaration needsPassData()    { this.needsPassData = true; return this; }
    public PassResourceDeclaration needsMaterialData(){ this.needsMaterialData = true; return this; }
    public PassResourceDeclaration needsLightData()   { this.needsLightData = true; return this; }
    public PassResourceDeclaration allowsInjection()  { this.allowsInjection = true; return this; }

    // ── Query ──

    public int     getResourceCount()               { return count; }
    public String  getResourceName(int i)            { return resourceNames[i]; }
    public Access  getResourceAccess(int i)          { return resourceAccess[i]; }
    public boolean isDepthRequired()                 { return needsDepth; }
    public boolean isSceneDataRequired()             { return needsSceneData; }
    public boolean isPassDataRequired()              { return needsPassData; }
    public boolean isMaterialDataRequired()          { return needsMaterialData; }
    public boolean isLightDataRequired()             { return needsLightData; }
    public boolean isInjectionAllowed()              { return allowsInjection; }
}
