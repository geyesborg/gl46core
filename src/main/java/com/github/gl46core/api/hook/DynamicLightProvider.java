package com.github.gl46core.api.hook;

import com.github.gl46core.api.render.FrameContext;
import com.github.gl46core.api.render.LightData;

/**
 * Provider for dynamic/local light sources.
 *
 * Mods register light providers that contribute lights each frame.
 * All lights are collected, culled against the view frustum, and
 * uploaded to the Light SSBO for shader access.
 *
 * Example uses:
 *   - Torch in hand (entity-attached light)
 *   - Glowing entities
 *   - Block-based local lights (furnace, redstone, etc.)
 *   - Custom light sources from mods
 */
public interface DynamicLightProvider {

    String getId();

    default int getPriority() { return 0; }

    /**
     * Collect dynamic lights for this frame. Add lights to the collector.
     * Called during collectScene phase.
     *
     * @param frame   current frame context
     * @param collector callback to submit individual lights
     */
    void collectLights(FrameContext frame, LightCollector collector);

    /**
     * Callback for submitting lights during collection.
     */
    interface LightCollector {
        /**
         * Submit a dynamic light. Returns the LightData to populate.
         */
        LightData addLight();

        /**
         * Current number of lights collected so far.
         */
        int getLightCount();

        /**
         * Maximum lights supported by the current GPU buffer.
         */
        int getMaxLights();
    }
}
