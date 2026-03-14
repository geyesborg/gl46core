package com.github.gl46core.api.hook;

import com.github.gl46core.api.render.FogState;
import com.github.gl46core.api.render.FrameContext;

/**
 * Modifier that can override or augment fog parameters per frame.
 *
 * Multiple modifiers can be active — they are applied in priority order.
 * Each modifier receives the current fog state and can adjust it.
 *
 * Example uses:
 *   - Dimension mod with custom fog rules
 *   - Weather mod increasing fog density during storms
 *   - Underwater/lava fog override
 *   - Height fog gradient
 */
public interface FogModifier {

    String getId();

    default int getPriority() { return 0; }

    /**
     * Modify the fog state for this frame.
     * Called during collectScene, after base fog is captured from GL state.
     *
     * @param frame current frame context
     * @param fog   mutable fog state to modify in-place
     */
    void modifyFog(FrameContext frame, FogState fog);

    /**
     * Whether this modifier is currently active.
     */
    default boolean isActive(FrameContext frame) { return true; }
}
