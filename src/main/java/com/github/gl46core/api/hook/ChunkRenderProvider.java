package com.github.gl46core.api.hook;

import com.github.gl46core.api.render.ChunkData;
import com.github.gl46core.api.render.FrameContext;
import com.github.gl46core.api.render.RenderQueue;

/**
 * Provider for chunk rendering — visibility, mesh data, and draw submission.
 *
 * The default implementation uses vanilla MC's chunk rendering system
 * (translated through the legacy path). Celeritas can register its own
 * provider to supply chunk visibility and mesh submission directly.
 *
 * Only one ChunkRenderProvider is active at a time — the highest-priority
 * registered provider wins.
 */
public interface ChunkRenderProvider {

    String getId();

    default int getPriority() { return 0; }

    /**
     * Collect visible chunks for this frame. Populate the provided list
     * with ChunkData for each visible section.
     */
    void collectVisibleChunks(FrameContext frame, java.util.List<ChunkData> visibleChunks);

    /**
     * Submit chunk draw commands into the terrain queues.
     * Called after collectVisibleChunks, during the submit phase.
     */
    void submitChunkDraws(FrameContext frame, RenderQueue opaqueQueue,
                          RenderQueue cutoutQueue, RenderQueue translucentQueue);

    /**
     * Notify that a chunk section needs rebuild (block change, light update).
     */
    default void onChunkDirty(int sectionX, int sectionY, int sectionZ) {}

    /**
     * Whether this provider supports indirect draw for terrain.
     */
    default boolean supportsIndirectDraw() { return false; }
}
