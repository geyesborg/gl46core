package com.github.gl46core.api.render;

/**
 * Interface for a render pass in the pass graph.
 *
 * Each pass declares what resources it reads/writes, sets up GPU state,
 * and executes draw submissions. The pass graph sorts passes by dependency
 * and type, then executes them in order.
 *
 * Implementations can be:
 *   - Built-in passes (terrain, entity, sky, etc.)
 *   - Shaderpack-defined passes (custom post effects, shadow passes)
 *   - Mod-registered passes (dynamic lights overlay, custom effects)
 */
public interface RenderPass {

    /**
     * Unique name for this pass (e.g. "terrain_opaque", "shadow_cascade_0").
     */
    String getName();

    /**
     * The pass type — determines default ordering and behavior.
     */
    PassType getType();

    /**
     * Declare resource requirements: which textures/buffers this pass
     * reads and which render targets it writes.
     */
    void declareResources(PassResourceDeclaration declaration);

    /**
     * Set up GPU state before execution (bind framebuffer, set viewport,
     * configure depth/blend, bind pass UBO).
     */
    void setup(FrameContext frame, PassData passData);

    /**
     * Execute the pass — process submission queues, issue draw calls.
     */
    void execute(FrameContext frame);

    /**
     * Whether this pass should run this frame.
     * Checked by the pass graph before setup/execute.
     */
    default boolean isEnabled(FrameContext frame) { return true; }

    /**
     * Priority within the same PassType for ordering.
     * Lower values execute first. Default is 0.
     */
    default int getPriority() { return 0; }
}
