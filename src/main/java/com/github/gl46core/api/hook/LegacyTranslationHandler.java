package com.github.gl46core.api.hook;

import com.github.gl46core.api.render.FrameContext;
import com.github.gl46core.api.render.MaterialData;

/**
 * Handler for translating legacy GL rendering into modern submissions.
 *
 * The default implementation converts fixed-function GL state into
 * MaterialData and ObjectData, then submits through the standard queues.
 * Mods can register custom handlers for specific render paths.
 *
 * Example uses:
 *   - Custom tessellator output handler for a specific mod
 *   - Override material inference for specific block renders
 *   - Intercept specific legacy render calls for special treatment
 */
public interface LegacyTranslationHandler {

    String getId();

    default int getPriority() { return 0; }

    /**
     * Attempt to translate a legacy draw call. Returns true if handled,
     * false to pass to the next handler in the chain.
     *
     * @param frame      current frame context
     * @param drawMode   GL draw mode (GL_TRIANGLES, GL_QUADS, etc.)
     * @param vertexCount number of vertices
     * @param hasTexture whether texture is enabled
     * @param hasLightmap whether lightmap is active
     * @return true if this handler consumed the draw call
     */
    boolean handleLegacyDraw(FrameContext frame, int drawMode, int vertexCount,
                             boolean hasTexture, boolean hasLightmap);

    /**
     * Infer a MaterialData from current GL state. Returns null to use default.
     */
    default MaterialData inferMaterial(FrameContext frame) { return null; }
}
