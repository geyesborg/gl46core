package com.github.gl46core.gl;

import com.github.gl46core.mixin.AccessorModelBox;
import net.minecraft.client.model.ModelBox;
import net.minecraft.client.model.TexturedQuad;

/**
 * Bridge for accessing private ModelBox fields via mixin accessors.
 * Centralizes the cast so only this class depends on the accessor mixin.
 *
 * This is the extraction point for the legacy model geometry pipeline:
 *   1. Legacy model → geometry extraction (this class)
 *   2. Mesh cache → compiled VBO (ModelGeometryCache)
 *   3. Renderer submission → material IDs, instancing, per-pass (future)
 *
 * Future additions to this bridge:
 *   - Extract UV bounds for texture atlas material ID assignment
 *   - Extract bone hierarchy for skeletal instancing
 *   - Provide mesh fingerprinting for deduplication (same model shape = shared VBO)
 */
public final class ModelGeometryBridge {

    private ModelGeometryBridge() {}

    public static TexturedQuad[] getQuads(ModelBox box) {
        return ((AccessorModelBox) (Object) box).gl46core$getQuadList();
    }
}
