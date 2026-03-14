package com.github.gl46core.api.hook;

import com.github.gl46core.api.render.FrameContext;
import com.github.gl46core.api.render.FrameOrchestrator;

/**
 * Interface for systems that submit renderables into queues.
 *
 * Each submitter is called during the submit phase to populate
 * render queues with draw submissions. This is the primary way
 * mods inject custom renderables into the pipeline.
 *
 * Example uses:
 *   - Entity renderer submitting entity draw calls
 *   - Particle system submitting particle batches
 *   - Mod adding custom renderable objects (holograms, beams, etc.)
 */
public interface RenderableSubmitter {

    String getId();

    default int getPriority() { return 0; }

    /**
     * Submit renderables into the appropriate queues.
     * Called once per frame during the submit phase.
     *
     * @param frame        current frame context
     * @param orchestrator provides access to typed render queues
     */
    void submitRenderables(FrameContext frame, FrameOrchestrator orchestrator);
}
