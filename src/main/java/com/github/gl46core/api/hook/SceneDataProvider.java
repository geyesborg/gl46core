package com.github.gl46core.api.hook;

import com.github.gl46core.api.render.FrameContext;

/**
 * Provider that contributes scene-level data each frame.
 *
 * Implementations can override or augment camera, lighting, fog, weather,
 * or dimension state. Called during the collectScene phase.
 *
 * Example uses:
 *   - Dimension mod overriding sky color and celestial angle
 *   - Shaderpack injecting previous-frame matrices
 *   - Debug tool overriding camera position
 */
public interface SceneDataProvider {

    /**
     * Unique identifier for this provider.
     */
    String getId();

    /**
     * Priority — lower values run first. Default providers use 0;
     * overrides should use positive values to run after defaults.
     */
    default int getPriority() { return 0; }

    /**
     * Populate or override scene data in the frame context.
     * Called once per frame during collectScene.
     */
    void collectSceneData(FrameContext frame);
}
