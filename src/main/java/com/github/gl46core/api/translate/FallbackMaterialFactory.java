package com.github.gl46core.api.translate;

import com.github.gl46core.api.render.MaterialData;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Factory that creates and caches default materials for legacy GL state combos.
 *
 * When the translation layer encounters a GL state combination that doesn't
 * have a registered material, it uses this factory to create a fallback.
 * Materials are cached by their state hash to avoid repeated allocation.
 *
 * This ensures correctness-first behavior: even unmapped state combos
 * get a reasonable material instead of rendering with garbage parameters.
 *
 * Common MC 1.12.2 material archetypes:
 *   - Solid block: tex + no alpha + no blend + lighting
 *   - Cutout block: tex + alpha test + no blend + lighting
 *   - Translucent block: tex + no alpha + blend + lighting
 *   - Entity: tex + alpha test + lighting + lightmap per-vertex
 *   - Particle: tex + blend + no lighting
 *   - Sky: no tex + no alpha + no lighting
 *   - UI: tex + blend + no lighting + texenv MODULATE
 */
public final class FallbackMaterialFactory {

    public static final FallbackMaterialFactory INSTANCE = new FallbackMaterialFactory();

    private final ConcurrentHashMap<Integer, MaterialData> cache = new ConcurrentHashMap<>();

    // Pre-built common materials
    private final MaterialData solidBlock;
    private final MaterialData cutoutBlock;
    private final MaterialData translucentBlock;
    private final MaterialData entity;
    private final MaterialData particle;
    private final MaterialData sky;
    private final MaterialData ui;

    private FallbackMaterialFactory() {
        solidBlock = createMaterial(0, true, false, false, true, false, 0x2100);
        cutoutBlock = createMaterial(1, true, true, false, true, false, 0x2100);
        translucentBlock = createMaterial(2, true, false, true, true, false, 0x2100);
        entity = createMaterial(3, true, true, false, true, true, 0x2100);
        particle = createMaterial(4, true, false, true, false, false, 0x2100);
        sky = createMaterial(5, false, false, false, false, false, 0x2100);
        ui = createMaterial(6, true, false, true, false, false, 0x2100);
    }

    /**
     * Get or create a material for the given state combination.
     *
     * @param stateHash hash from LegacyStateInterpreter.computeMaterialHash
     * @param tex       texture enabled
     * @param alpha     alpha test enabled
     * @param blend     blend enabled
     * @param lighting  lighting enabled
     * @param lightmap  lightmap active
     * @param texEnv    texenv mode
     */
    public MaterialData getOrCreate(int stateHash, boolean tex, boolean alpha,
                                     boolean blend, boolean lighting,
                                     boolean lightmap, int texEnv) {
        return cache.computeIfAbsent(stateHash,
            k -> createMaterial(k, tex, alpha, blend, lighting, lightmap, texEnv));
    }

    /**
     * Get a pre-built common material by archetype.
     */
    public MaterialData getSolidBlock()       { return solidBlock; }
    public MaterialData getCutoutBlock()      { return cutoutBlock; }
    public MaterialData getTranslucentBlock() { return translucentBlock; }
    public MaterialData getEntity()           { return entity; }
    public MaterialData getParticle()         { return particle; }
    public MaterialData getSky()              { return sky; }
    public MaterialData getUi()               { return ui; }

    /**
     * Clear the cache (e.g. on resource reload).
     */
    public void clearCache() {
        cache.clear();
    }

    public int getCacheSize() { return cache.size(); }

    private MaterialData createMaterial(int id, boolean tex, boolean alpha,
                                         boolean blend, boolean lighting,
                                         boolean lightmap, int texEnv) {
        MaterialData mat = new MaterialData();
        mat.setMaterialId(id);

        // Feature flags
        int features = 0;
        if (tex) features |= MaterialData.FEAT_TEXTURE;
        if (alpha) features |= MaterialData.FEAT_ALPHA_TEST;
        if (lightmap) features |= MaterialData.FEAT_LIGHTMAP;
        mat.setShaderFeatureFlags(features);

        // Alpha mode
        if (alpha) {
            mat.setAlphaMode(MaterialData.AlphaMode.CUTOUT);
            mat.setAlphaCutoff(0.1f); // MC default
        } else if (blend) {
            mat.setAlphaMode(MaterialData.AlphaMode.BLEND);
        } else {
            mat.setAlphaMode(MaterialData.AlphaMode.OPAQUE);
        }

        // TexEnv
        mat.setTexEnvMode(texEnv);

        // Light response
        int lightFlags = MaterialData.LIGHT_RECEIVES_DIFFUSE;
        if (lighting) {
            lightFlags |= MaterialData.LIGHT_RECEIVES_SHADOW
                        | MaterialData.LIGHT_AMBIENT_OCCLUSION;
        }
        mat.setLightResponseFlags(lightFlags);

        // Default PBR values (reasonable for MC blocks)
        mat.setRoughness(0.85f);
        mat.setMetallic(0.0f);
        mat.setEmissiveStrength(0.0f);
        mat.setColorMultiplier(1, 1, 1, 1);

        return mat;
    }
}
