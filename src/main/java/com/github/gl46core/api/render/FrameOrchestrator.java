package com.github.gl46core.api.render;

import com.github.gl46core.api.hook.RenderEventListener;
import com.github.gl46core.api.hook.RenderRegistry;
import com.github.gl46core.api.render.gpu.GpuBuffer;
import com.github.gl46core.api.render.gpu.GpuBufferPool;
import com.github.gl46core.api.translate.LegacyDrawTranslator;
import org.lwjgl.opengl.GL31;

import java.util.EnumMap;
import java.util.List;

/**
 * Drives the full frame rendering lifecycle.
 *
 * Owns the {@link FrameContext}, {@link PassGraph}, and per-pass
 * {@link RenderQueue}s. Coordinates the frame stages:
 *
 *   1. beginFrame()    — reset transient state, capture timing
 *   2. collectScene()  — snapshot world/camera/weather/lighting into FrameContext
 *   3. buildPassGraph() — register passes, sort by type/priority
 *   4. submit()        — systems submit renderables into queues
 *   5. execute()       — bind pass resources, sort queues, issue draw calls
 *   6. endFrame()      — release transient buffers, publish stats
 *
 * The orchestrator does NOT own MC-specific capture logic. External code
 * (mixins, hooks) calls collectScene helpers to populate the FrameContext.
 * The orchestrator just drives the lifecycle and provides the plumbing.
 */
public final class FrameOrchestrator {

    public static final FrameOrchestrator INSTANCE = new FrameOrchestrator();

    private final FrameContext frameContext = new FrameContext();
    private final PassGraph passGraph = new PassGraph();
    private final SceneData sceneData = new SceneData();

    // Full SceneData UBO — binding 5 (separate from legacy PerScene at binding 0)
    // Uploaded once per frame after scene collection. Shaders can opt into this
    // for the full 560-byte layout vs the legacy 112-byte subset.
    public static final int SCENE_UBO_BINDING = 5;
    private GpuBuffer sceneUbo;

    // PerPass UBO — binding 3, 96 bytes. Uploaded when the active pass changes.
    public static final int PASS_UBO_BINDING = 3;
    private GpuBuffer passUbo;
    private PassType lastUploadedPassType;

    // Per-pass-type queues — one queue per PassType
    private final EnumMap<PassType, RenderQueue> queues = new EnumMap<>(PassType.class);

    // Frame statistics
    private int totalSubmissions;
    private int totalDrawCalls;
    private int passesExecuted;
    private long frameStartNanos;
    private long frameEndNanos;

    // Lifecycle state
    private FrameStage currentStage = FrameStage.IDLE;

    public enum FrameStage {
        IDLE,
        BEGIN,
        COLLECT_SCENE,
        BUILD_PASS_GRAPH,
        SUBMIT,
        EXECUTE,
        END
    }

    private FrameOrchestrator() {
        // Create queues with appropriate sort modes per pass type
        for (PassType type : PassType.values()) {
            RenderQueue.SortMode mode;
            if (type.isShadowPass()) {
                mode = RenderQueue.SortMode.BY_MATERIAL;
            } else if (type.isTranslucent()) {
                mode = RenderQueue.SortMode.BACK_TO_FRONT;
            } else if (type == PassType.UI || type == PassType.DEBUG_OVERLAY) {
                mode = RenderQueue.SortMode.NONE;
            } else {
                mode = RenderQueue.SortMode.FRONT_TO_BACK;
            }
            queues.put(type, new RenderQueue(mode));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Stage 1: Begin Frame
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Start a new frame. Resets all transient state.
     *
     * @param partialTicks MC partial tick interpolation factor
     * @param worldTime    total world time + partial ticks
     */
    public void beginFrame(float partialTicks, double worldTime) {
        currentStage = FrameStage.BEGIN;
        frameStartNanos = System.nanoTime();

        frameContext.beginFrame(partialTicks, worldTime);
        passGraph.clear();
        for (RenderQueue queue : queues.values()) {
            queue.clear();
        }

        totalSubmissions = 0;
        totalDrawCalls = 0;
        passesExecuted = 0;
        lastUploadedPassType = null;
        BuiltinPasses.setActive(PassType.TERRAIN_OPAQUE);
        LegacyDrawTranslator.INSTANCE.beginFrame();

        // Fire onFrameBegin for all registered event listeners
        fireFrameBegin();
    }

    // ═══════════════════════════════════════════════════════════════════
    // Stage 2: Collect Scene
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Begin scene collection phase. External code populates FrameContext
     * sub-objects (camera, fog, lighting, weather, dimension) via their
     * capture() methods.
     */
    public void beginCollectScene() {
        currentStage = FrameStage.COLLECT_SCENE;
    }

    /**
     * Finalize scene collection. Packs scene data, uploads to GPU,
     * registers built-in passes, and builds the pass graph.
     */
    public void endCollectScene() {
        sceneData.pack(frameContext);

        // Lazily create the SceneData UBO on first use
        if (sceneUbo == null) {
            sceneUbo = GpuBufferPool.INSTANCE.createDynamicUBO(SceneData.GPU_SIZE);
        }

        // Lazily create the PerPass UBO
        if (passUbo == null) {
            passUbo = GpuBufferPool.INSTANCE.createDynamicUBO(PassData.GPU_SIZE);
        }

        // Upload packed scene data to GPU
        sceneUbo.upload(sceneData.getBuffer(), 0, SceneData.GPU_SIZE);

        // Register built-in passes
        BuiltinPasses.register(passGraph);

        // Register mod-submitted passes from RenderRegistry
        for (RenderPass modPass : RenderRegistry.INSTANCE.getRegisteredPasses()) {
            passGraph.addPass(modPass);
        }

        buildPassGraph();

        // Fire lifecycle events
        fireSceneCollected();
        firePassGraphBuilt();
    }

    /**
     * Bind the full SceneData UBO for shader access.
     * Call from pass setup when the shader supports the extended layout.
     */
    public void bindSceneData() {
        if (sceneUbo != null) {
            sceneUbo.bindBase(GL31.GL_UNIFORM_BUFFER, SCENE_UBO_BINDING);
        }
    }

    /**
     * Notify the orchestrator that the active rendering stage has changed.
     * Uploads the corresponding PassData to the PerPass UBO.
     *
     * Called by mixin hooks at MC rendering stage boundaries.
     */
    public void setActivePass(PassType type) {
        // Fire onAfterPass for the outgoing pass
        if (lastUploadedPassType != null && lastUploadedPassType != type) {
            fireAfterPass(BuiltinPasses.getActivePass());
            com.github.gl46core.api.debug.RenderProfiler.INSTANCE.endPass(lastUploadedPassType);
        }

        BuiltinPasses.setActive(type);

        // Only re-upload if pass actually changed
        if (type == lastUploadedPassType) return;
        lastUploadedPassType = type;
        com.github.gl46core.api.debug.RenderProfiler.INSTANCE.beginPass(type);

        BuiltinPasses.TranslationPass pass = BuiltinPasses.getActivePass();

        // Fire onBeforePass — listeners can modify PassData
        fireBeforePass(pass, pass.getPassData());

        pass.setup(frameContext, pass.getPassData());

        if (passUbo != null) {
            passUbo.upload(pass.getPassData().pack(), 0, PassData.GPU_SIZE);
            passUbo.bindBase(GL31.GL_UNIFORM_BUFFER, PASS_UBO_BINDING);
        }
        // Ensure SceneData UBO is bound for extended scene access
        if (sceneUbo != null) {
            sceneUbo.bindBase(GL31.GL_UNIFORM_BUFFER, SCENE_UBO_BINDING);
        }
        passesExecuted++;
    }

    /**
     * Get the currently active pass type.
     */
    public PassType getActivePassType() {
        return BuiltinPasses.getActivePassType();
    }

    // ═══════════════════════════════════════════════════════════════════
    // Stage 3: Build Pass Graph
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Build the pass graph for this frame. Passes are registered by
     * built-in systems and mod hooks, then sorted into execution order.
     */
    public void buildPassGraph() {
        currentStage = FrameStage.BUILD_PASS_GRAPH;
        passGraph.build(frameContext);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Stage 4: Submit Renderables
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Begin submission phase. Systems submit renderables into queues.
     */
    public void beginSubmit() {
        currentStage = FrameStage.SUBMIT;
    }

    /**
     * Get the queue for a specific pass type to submit renderables into.
     */
    public RenderQueue getQueue(PassType type) {
        return queues.get(type);
    }

    /**
     * Acquire a submission slot in the queue for the given pass type.
     * Convenience method combining getQueue + acquire.
     */
    public RenderSubmission submit(PassType type) {
        totalSubmissions++;
        return queues.get(type).acquire();
    }

    // ═══════════════════════════════════════════════════════════════════
    // Stage 5: Execute Passes
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Execute all passes in graph order.
     *
     * For each pass:
     *   1. Sort its queue
     *   2. Call pass.setup() to bind framebuffer/viewport/state
     *   3. Call pass.execute() to process submissions
     *
     * Subclasses or future versions may insert barrier/sync points
     * between passes based on resource declarations.
     */
    public void executePasses() {
        currentStage = FrameStage.EXECUTE;

        List<RenderPass> passes = passGraph.getExecutionOrder();
        PassData passData = new PassData();

        for (int i = 0; i < passes.size(); i++) {
            RenderPass pass = passes.get(i);
            PassType type = pass.getType();
            RenderQueue queue = queues.get(type);

            // Sort the queue for this pass type (idempotent if already sorted)
            queue.sort();

            // Configure pass data
            passData.configure(type,
                PassData.FLAG_DEPTH_WRITE | PassData.FLAG_DEPTH_TEST | PassData.FLAG_BACKFACE_CULL,
                frameContext.getCamera().getViewportWidth(),
                frameContext.getCamera().getViewportHeight());

            // Setup and execute
            pass.setup(frameContext, passData);
            pass.execute(frameContext);

            totalDrawCalls += queue.getCount();
            passesExecuted++;
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Stage 6: End Frame
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Finalize the frame. Publish stats, release transient resources.
     */
    public void endFrame() {
        currentStage = FrameStage.END;
        frameEndNanos = System.nanoTime();

        // End timing for the last active pass
        if (lastUploadedPassType != null) {
            com.github.gl46core.api.debug.RenderProfiler.INSTANCE.endPass(lastUploadedPassType);
        }

        // Fire onFrameEnd for all registered event listeners
        fireFrameEnd();

        currentStage = FrameStage.IDLE;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Accessors
    // ═══════════════════════════════════════════════════════════════════

    public FrameContext getFrameContext() { return frameContext; }
    public PassGraph    getPassGraph()    { return passGraph; }
    public SceneData    getSceneData()    { return sceneData; }
    public FrameStage   getCurrentStage() { return currentStage; }

    // ── Stats ──

    public int  getTotalSubmissions() { return totalSubmissions; }
    public int  getTotalDrawCalls()   { return totalDrawCalls; }
    public int  getPassesExecuted()   { return passesExecuted; }
    public long getFrameTimeNanos()   { return frameEndNanos - frameStartNanos; }
    public double getFrameTimeMs()    { return (frameEndNanos - frameStartNanos) / 1_000_000.0; }

    // ═══════════════════════════════════════════════════════════════════
    // Event Dispatch
    // ═══════════════════════════════════════════════════════════════════

    private void fireFrameBegin() {
        List<RenderEventListener> listeners = RenderRegistry.INSTANCE.getEventListeners();
        for (int i = 0; i < listeners.size(); i++) {
            listeners.get(i).onFrameBegin(frameContext);
        }
    }

    private void fireSceneCollected() {
        List<RenderEventListener> listeners = RenderRegistry.INSTANCE.getEventListeners();
        for (int i = 0; i < listeners.size(); i++) {
            listeners.get(i).onSceneCollected(frameContext);
        }
    }

    private void firePassGraphBuilt() {
        List<RenderEventListener> listeners = RenderRegistry.INSTANCE.getEventListeners();
        for (int i = 0; i < listeners.size(); i++) {
            listeners.get(i).onPassGraphBuilt(frameContext);
        }
    }

    private void fireBeforePass(RenderPass pass, PassData passData) {
        List<RenderEventListener> listeners = RenderRegistry.INSTANCE.getEventListeners();
        for (int i = 0; i < listeners.size(); i++) {
            listeners.get(i).onBeforePass(frameContext, pass, passData);
        }
    }

    private void fireAfterPass(RenderPass pass) {
        List<RenderEventListener> listeners = RenderRegistry.INSTANCE.getEventListeners();
        for (int i = 0; i < listeners.size(); i++) {
            listeners.get(i).onAfterPass(frameContext, pass);
        }
    }

    private void fireFrameEnd() {
        List<RenderEventListener> listeners = RenderRegistry.INSTANCE.getEventListeners();
        for (int i = 0; i < listeners.size(); i++) {
            listeners.get(i).onFrameEnd(frameContext);
        }
    }
}
