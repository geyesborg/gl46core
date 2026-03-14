package com.github.gl46core.api.hook;

import com.github.gl46core.api.render.MaterialData;

/**
 * Provider for custom material definitions.
 *
 * Mods can register materials with custom PBR parameters, emissive
 * properties, transparency modes, or shader feature requirements.
 * The material registry resolves materials by ID during rendering.
 *
 * Example uses:
 *   - Custom block with emissive glow
 *   - PBR material pack defining roughness/metallic per block
 *   - Mod adding a custom transparency mode
 */
public interface MaterialProvider {

    String getId();

    default int getPriority() { return 0; }

    /**
     * Register materials into the material registry.
     * Called once at startup and on resource reload.
     */
    void registerMaterials(MaterialRegistry registry);

    /**
     * Simple callback interface for material registration.
     */
    interface MaterialRegistry {
        void register(int materialId, MaterialData data);
        void registerOverride(int materialId, MaterialData data);
    }
}
