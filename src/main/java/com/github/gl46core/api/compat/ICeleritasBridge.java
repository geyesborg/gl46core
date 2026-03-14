package com.github.gl46core.api.compat;

import com.github.gl46core.api.render.ChunkData;
import com.github.gl46core.api.render.FrameContext;
import com.github.gl46core.api.render.RenderQueue;

import java.util.List;

/**
 * Bridge interface for Celeritas integration.
 *
 * Celeritas (or any alternative chunk renderer) implements this to plug
 * into gl46core's rendering architecture as a client, not an owner.
 *
 * gl46core owns the pass graph, frame lifecycle, and GPU buffer contracts.
 * Celeritas provides chunk visibility, mesh submission, and state translation.
 *
 * Implemented by gl46-celeritas-compat module, not by gl46core itself.
 */
public interface ICeleritasBridge {

    /**
     * Unique identifier for this bridge implementation.
     */
    String getId();

    /**
     * Called once during mod initialization to install hooks.
     * The bridge should register itself with RenderRegistry here.
     */
    void installHooks();

    /**
     * Called once when the bridge is being unloaded.
     */
    void uninstallHooks();

    /**
     * Collect visible chunk sections from Celeritas's visibility system.
     *
     * @param frame          current frame context (camera, frustum, etc.)
     * @param visibleChunks  output list to populate with visible sections
     */
    void collectVisibleChunks(FrameContext frame, List<ChunkData> visibleChunks);

    /**
     * Submit chunk draw commands into the terrain render queues.
     *
     * @param frame            current frame context
     * @param opaqueQueue      queue for opaque terrain
     * @param cutoutQueue      queue for cutout terrain (alpha-tested)
     * @param translucentQueue queue for translucent terrain (water, ice, etc.)
     */
    void submitChunkDraws(FrameContext frame, RenderQueue opaqueQueue,
                          RenderQueue cutoutQueue, RenderQueue translucentQueue);

    /**
     * Translate Celeritas-specific render state into gl46core's state model.
     * Called during collectScene to sync any Celeritas-managed state.
     */
    void translateState(FrameContext frame);

    /**
     * Notify Celeritas that a chunk section needs rebuild.
     */
    void onChunkDirty(int sectionX, int sectionY, int sectionZ);

    /**
     * Whether Celeritas supports multi-draw indirect for terrain.
     */
    boolean supportsIndirectDraw();

    /**
     * Whether this bridge is currently active and functional.
     */
    boolean isActive();

    /**
     * Get the name/version of the Celeritas build for logging.
     */
    String getVersion();
}
