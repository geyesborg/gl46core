package com.github.gl46core.api.translate;

import com.github.gl46core.api.render.*;
import com.github.gl46core.gl.CoreStateTracker;

/**
 * Interprets current CoreStateTracker state into API-level data structures.
 *
 * Bridges the gap between the legacy GL state machine (tracked by CoreStateTracker)
 * and the modern data model (MaterialData, ObjectData, FogState, GlobalLightState).
 *
 * This is the core of the translation layer — it reads software-tracked state
 * and produces clean, typed data that the renderer and shader system consume.
 *
 * Thread-safe: reads thread-local state from CoreStateTracker where applicable.
 */
public final class LegacyStateInterpreter {

    public static final LegacyStateInterpreter INSTANCE = new LegacyStateInterpreter();

    // Reusable scratch objects to avoid per-frame allocation
    private final MaterialData scratchMaterial = new MaterialData();

    private LegacyStateInterpreter() {}

    /**
     * Capture current fog state from CoreStateTracker into a FogState.
     */
    public void captureFog(FogState fog) {
        CoreStateTracker st = CoreStateTracker.INSTANCE;
        fog.capture(
            st.isFogEnabled(),
            st.getFogMode(),
            st.getFogDensity(),
            st.getFogStart(),
            st.getFogEnd(),
            st.getFogR(), st.getFogG(), st.getFogB(), st.getFogA()
        );
    }

    /**
     * Capture current global lighting state from CoreStateTracker.
     */
    public void captureGlobalLight(GlobalLightState light) {
        CoreStateTracker st = CoreStateTracker.INSTANCE;
        light.capture(
            st.getLightPosition(0), st.getLightDiffuse(0),
            st.getLightPosition(1), st.getLightDiffuse(1),
            st.getLightModelAmbientR(),
            st.getLightModelAmbientG(),
            st.getLightModelAmbientB()
        );
    }

    /**
     * Infer a MaterialData from current GL state.
     *
     * Maps the combination of texture enable, alpha test, fog, lighting,
     * lightmap, texgen, texenv mode, and clip planes into a MaterialData.
     *
     * @param hasColor    vertex format has color attribute
     * @param hasTexCoord vertex format has texture coordinates
     * @param hasNormal   vertex format has normals
     * @param hasLightMap vertex format has lightmap coordinates
     * @return inferred material (reusable scratch — copy if you need to keep it)
     */
    public MaterialData inferMaterial(boolean hasColor, boolean hasTexCoord,
                                      boolean hasNormal, boolean hasLightMap) {
        CoreStateTracker st = CoreStateTracker.INSTANCE;
        MaterialData mat = scratchMaterial;

        // Texture
        boolean texEnabled = st.isTexture2DEnabled(0);
        int features = 0;
        if (texEnabled && hasTexCoord) features |= MaterialData.FEAT_TEXTURE;
        if (st.isAlphaTestEnabled())   features |= MaterialData.FEAT_ALPHA_TEST;
        if (hasLightMap || st.isTexture2DEnabled(1)) features |= MaterialData.FEAT_LIGHTMAP;
        mat.setShaderFeatureFlags(features);

        // Alpha mode
        if (st.isAlphaTestEnabled()) {
            mat.setAlphaMode(MaterialData.AlphaMode.CUTOUT);
            mat.setAlphaCutoff(st.getAlphaRef());
        } else if (st.isBlendEnabled()) {
            mat.setAlphaMode(MaterialData.AlphaMode.BLEND);
            mat.setAlphaCutoff(0.0f);
        } else {
            mat.setAlphaMode(MaterialData.AlphaMode.OPAQUE);
            mat.setAlphaCutoff(0.0f);
        }

        // TexEnv mode
        mat.setTexEnvMode(st.getTexEnvMode());

        // Color multiplier from glColor4f
        mat.setColorMultiplier(st.getColorR(), st.getColorG(),
                               st.getColorB(), st.getColorA());

        // Light response
        int lightFlags = MaterialData.LIGHT_RECEIVES_DIFFUSE;
        if (st.isLightingEnabled()) {
            lightFlags |= MaterialData.LIGHT_RECEIVES_SHADOW;
        }
        mat.setLightResponseFlags(lightFlags);

        // Material ID — hash of the state combination for batching
        int matId = computeMaterialHash(texEnabled, st.isAlphaTestEnabled(),
            st.isFogEnabled(), st.isLightingEnabled(), hasLightMap,
            st.getTexEnvMode(), st.isBlendEnabled());
        mat.setMaterialId(matId);

        return mat;
    }

    /**
     * Build the 8-bit shader variant key from current state.
     * Matches ShaderVariants.computeKey() logic.
     */
    public int computeVariantKey() {
        CoreStateTracker st = CoreStateTracker.INSTANCE;
        int key = 0;
        if (st.isTexture2DEnabled(0))      key |= 0x01;
        if (st.isAlphaTestEnabled())        key |= 0x02;
        if (st.isFogEnabled())              key |= 0x04;
        if (st.isLightingEnabled())         key |= 0x08;
        // Lightmap per-vertex vs global
        if (st.isTexture2DEnabled(1))       key |= 0x10;
        else if (st.getLightmapX() != 0 || st.getLightmapY() != 0) key |= 0x20;
        // TexGen
        if (st.isTexGenEnabled(0) || st.isTexGenEnabled(1)) key |= 0x40;
        // Clip planes
        for (int i = 0; i < 6; i++) {
            if (st.isClipPlaneEnabled(i)) { key |= 0x80; break; }
        }
        return key;
    }

    /**
     * Determine the appropriate PassType for the current draw call.
     *
     * Uses the active pass from BuiltinPasses as the primary source.
     * For geometry passes (terrain/entity), refines using GL state:
     *   - blend enabled → translucent variant
     *   - alpha test enabled → cutout variant
     *   - else → opaque variant
     */
    public PassType inferPassType() {
        PassType active = BuiltinPasses.getActivePassType();

        // Non-geometry passes: use the active pass directly
        if (active == PassType.SKY || active == PassType.HAND
                || active == PassType.PARTICLES || active == PassType.WEATHER
                || active == PassType.UI || active == PassType.DEBUG_OVERLAY
                || active == PassType.BLOCK_ENTITY || active == PassType.WATER
                || active == PassType.OUTLINE || active == PassType.POST_CHAIN
                || active.isShadowPass()) {
            return active;
        }

        // Geometry passes: refine based on GL blend/alpha state
        CoreStateTracker st = CoreStateTracker.INSTANCE;
        boolean isTerrain = (active == PassType.TERRAIN_OPAQUE
                || active == PassType.TERRAIN_CUTOUT
                || active == PassType.TERRAIN_TRANSLUCENT);

        if (st.isBlendEnabled()) {
            return isTerrain ? PassType.TERRAIN_TRANSLUCENT : PassType.ENTITY_TRANSLUCENT;
        }
        if (st.isAlphaTestEnabled()) {
            return isTerrain ? PassType.TERRAIN_CUTOUT : PassType.ENTITY_OPAQUE;
        }
        return isTerrain ? PassType.TERRAIN_OPAQUE : PassType.ENTITY_OPAQUE;
    }

    /**
     * Check if current state has any texgen enabled.
     */
    public boolean hasTexGen() {
        CoreStateTracker st = CoreStateTracker.INSTANCE;
        return st.isTexGenEnabled(0) || st.isTexGenEnabled(1);
    }

    /**
     * Check if current state has any clip planes enabled.
     */
    public boolean hasClipPlanes() {
        CoreStateTracker st = CoreStateTracker.INSTANCE;
        for (int i = 0; i < 6; i++) {
            if (st.isClipPlaneEnabled(i)) return true;
        }
        return false;
    }

    /**
     * Compute a simple hash of material-affecting state for batching.
     */
    private int computeMaterialHash(boolean tex, boolean alpha, boolean fog,
                                     boolean lighting, boolean lightmap,
                                     int texEnvMode, boolean blend) {
        int h = (tex ? 1 : 0)
              | (alpha ? 2 : 0)
              | (fog ? 4 : 0)
              | (lighting ? 8 : 0)
              | (lightmap ? 16 : 0)
              | (blend ? 32 : 0)
              | ((texEnvMode & 0xFF) << 8);
        return h;
    }
}
