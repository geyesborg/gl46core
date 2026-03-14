package com.github.gl46core.api.compat;

import com.github.gl46core.api.render.ChunkData;
import com.github.gl46core.api.render.FrameContext;
import com.github.gl46core.api.render.MaterialData;
import com.github.gl46core.api.render.RenderQueue;
import com.github.gl46core.api.render.gpu.FramebufferObject;
import com.github.gl46core.api.render.gpu.RenderTarget;

import java.util.List;
import java.util.Map;

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

    // ═══════════════════════════════════════════════════════════════════
    // Phase A: Material SSBO integration
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Provide material data for a block ID / render layer combination.
     * Celeritas can supply PBR properties (roughness, metallic, emissive)
     * that get uploaded to the Material SSBO for shader access.
     *
     * @param blockId   numeric block ID
     * @param meta      metadata / damage value
     * @return material data, or null to use default
     */
    default MaterialData getBlockMaterial(int blockId, int meta) { return null; }

    /**
     * Provide a batch of terrain material overrides.
     * Called once per frame during collectScene. The map keys are
     * material hash values (same as MaterialData.getMaterialId()).
     *
     * @return map of materialId → MaterialData, or empty map
     */
    default Map<Integer, MaterialData> getTerrainMaterials() { return Map.of(); }

    // ═══════════════════════════════════════════════════════════════════
    // Phase B: Render target access
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Called after render targets are created/resized. The bridge can
     * cache texture IDs for Celeritas-side shader access.
     *
     * @param targets named render targets ("colortex0", "depthtex0", etc.)
     */
    default void onRenderTargetsReady(Map<String, RenderTarget> targets) {}

    /**
     * Whether Celeritas wants to use our G-buffer FBO for terrain drawing
     * (instead of MC's default framebuffer).
     */
    default boolean wantsGBufferFbo() { return false; }

    /**
     * Called before terrain draw submission. If wantsGBufferFbo() returns
     * true, the adapter binds the G-buffer FBO and passes it here for
     * Celeritas to target.
     */
    default void onGBufferBound(FramebufferObject gbufferFbo) {}

    // ═══════════════════════════════════════════════════════════════════
    // Phase C: Deferred draw integration
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Whether Celeritas wants to participate in deferred draw execution.
     * When true, terrain draws are recorded into the DrawCommandBuffer
     * instead of issued immediately.
     */
    default boolean wantsDeferredDraw() { return false; }

    /**
     * Called during deferred replay, before terrain commands are executed.
     * Celeritas can set up its own state (VAO, shader) before replay begins.
     */
    default void onBeforeDeferredReplay() {}

    /**
     * Called after deferred replay completes for terrain passes.
     */
    default void onAfterDeferredReplay() {}

    // ═══════════════════════════════════════════════════════════════════
    // Shaderpack awareness
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Notify Celeritas that a shaderpack has been loaded/unloaded.
     * Celeritas may need to adjust its terrain shader or rendering path.
     *
     * @param active     true if a shaderpack is now active
     * @param packName   name of the shaderpack, or null if deactivated
     */
    default void onShaderpackChanged(boolean active, String packName) {}

    /**
     * Whether Celeritas should yield terrain rendering to the shaderpack's
     * gbuffers_terrain program. When true, the adapter disables Celeritas's
     * own terrain shader and lets the shaderpack handle it.
     */
    default boolean shouldYieldToShaderpack() { return true; }
}
