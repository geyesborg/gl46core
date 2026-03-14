package com.github.gl46core.api.hook;

import com.github.gl46core.api.render.FrameContext;
import com.github.gl46core.api.render.PassData;
import com.github.gl46core.api.render.PassType;
import com.github.gl46core.api.render.RenderPass;

/**
 * Listener for render lifecycle events.
 *
 * Mods implement this to hook into specific points of the frame lifecycle
 * without owning a full RenderPass or provider. All methods have default
 * no-op implementations — override only the events you care about.
 *
 * Events fire in this order each frame:
 *   1. onFrameBegin
 *   2. onSceneCollected
 *   3. onPassGraphBuilt
 *   4. onBeforePass (per pass)
 *   5. onAfterPass (per pass)
 *   6. onFrameEnd
 */
public interface RenderEventListener {

    String getId();

    default int getPriority() { return 0; }

    /**
     * Frame has started, transient state reset. FrameContext timing is set
     * but scene data is not yet collected.
     */
    default void onFrameBegin(FrameContext frame) {}

    /**
     * Scene data has been collected and packed. Camera, lighting, fog,
     * weather, and dimension state are all populated.
     */
    default void onSceneCollected(FrameContext frame) {}

    /**
     * Pass graph has been built and sorted. Passes can be inspected
     * but not modified at this point.
     */
    default void onPassGraphBuilt(FrameContext frame) {}

    /**
     * About to execute a specific pass. GPU state is not yet bound.
     * Return false to skip this pass entirely.
     */
    default boolean onBeforePass(FrameContext frame, RenderPass pass, PassData passData) {
        return true;
    }

    /**
     * A pass has finished executing. Useful for post-pass cleanup
     * or injecting additional rendering between passes.
     */
    default void onAfterPass(FrameContext frame, RenderPass pass) {}

    /**
     * About to begin submission phase. Queues are empty.
     */
    default void onBeforeSubmit(FrameContext frame) {}

    /**
     * Submission phase complete. Queues are populated but not yet sorted.
     */
    default void onAfterSubmit(FrameContext frame) {}

    /**
     * Frame has ended. Stats are available. Transient resources
     * are about to be released.
     */
    default void onFrameEnd(FrameContext frame) {}
}
