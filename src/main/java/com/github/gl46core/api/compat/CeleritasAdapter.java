package com.github.gl46core.api.compat;

import com.github.gl46core.GL46Core;
import com.github.gl46core.api.hook.ChunkRenderProvider;
import com.github.gl46core.api.hook.RenderEventListener;
import com.github.gl46core.api.hook.RenderRegistry;
import com.github.gl46core.api.hook.SceneDataProvider;
import com.github.gl46core.api.render.*;
import com.github.gl46core.api.render.gpu.MaterialBuffer;
import com.github.gl46core.api.render.gpu.RenderTarget;
import com.github.gl46core.api.render.gpu.RenderTargetManager;
import com.github.gl46core.shaderpack.ShaderpackManager;

import java.util.List;
import java.util.Map;

/**
 * Adapter that wraps an {@link ICeleritasBridge} and registers it with
 * gl46core's rendering architecture as a ChunkRenderProvider and SceneDataProvider.
 *
 * This is the glue between the Celeritas bridge (provided by the compat module)
 * and gl46core's hook system. It translates Celeritas's chunk renderer into
 * the standard provider interfaces.
 *
 * Usage (in gl46-celeritas-compat):
 *   ICeleritasBridge bridge = new MyCeleritasBridge();
 *   CeleritasAdapter.install(bridge);
 */
public final class CeleritasAdapter {

    private static ICeleritasBridge activeBridge;

    private CeleritasAdapter() {}

    /**
     * Install a Celeritas bridge. Registers it with RenderRegistry
     * as the chunk render provider and scene data provider.
     */
    public static void install(ICeleritasBridge bridge) {
        if (activeBridge != null) {
            uninstall();
        }
        activeBridge = bridge;
        bridge.installHooks();

        RenderRegistry reg = RenderRegistry.INSTANCE;
        reg.registerChunkRenderProvider(new CeleritasChunkProvider(bridge));
        reg.registerSceneDataProvider(new CeleritasSceneProvider(bridge));
        reg.registerEventListener(new CeleritasEventListener(bridge));
    }

    /**
     * Uninstall the current bridge.
     */
    public static void uninstall() {
        if (activeBridge != null) {
            activeBridge.uninstallHooks();
            RenderRegistry reg = RenderRegistry.INSTANCE;
            reg.unregisterChunkRenderProvider("celeritas_chunks");
            reg.unregisterSceneDataProvider("celeritas_scene");
            reg.unregisterEventListener("celeritas_events");
            activeBridge = null;
        }
    }

    public static boolean isInstalled()         { return activeBridge != null; }
    public static ICeleritasBridge getBridge()   { return activeBridge; }

    /**
     * Forward render target resize events to the bridge.
     * Called by RenderTargetManager after init or resize.
     */
    public static void notifyRenderTargetsReady() {
        if (activeBridge != null && activeBridge.isActive()) {
            RenderTargetManager rtm = RenderTargetManager.INSTANCE;
            if (rtm.isInitialized()) {
                activeBridge.onRenderTargetsReady(rtm.getAllTargets());
            }
        }
    }

    /**
     * Forward shaderpack change events to the bridge.
     */
    public static void notifyShaderpackChanged(boolean active, String packName) {
        if (activeBridge != null && activeBridge.isActive()) {
            activeBridge.onShaderpackChanged(active, packName);
        }
    }

    /**
     * Register terrain material overrides from the bridge into the
     * MaterialBuffer SSBO. Called during collectScene.
     */
    static void registerBridgeMaterials(ICeleritasBridge bridge) {
        Map<Integer, MaterialData> materials = bridge.getTerrainMaterials();
        if (materials.isEmpty()) return;

        MaterialBuffer matBuf = FrameOrchestrator.INSTANCE.getMaterialBuffer();
        if (matBuf == null) return;

        com.github.gl46core.api.translate.LegacyDrawTranslator ldt =
                com.github.gl46core.api.translate.LegacyDrawTranslator.INSTANCE;
        for (Map.Entry<Integer, MaterialData> entry : materials.entrySet()) {
            ldt.registerMaterialPublic(entry.getKey(), entry.getValue());
        }
    }

    // ── ChunkRenderProvider adapter ──

    private static final class CeleritasChunkProvider implements ChunkRenderProvider {

        private final ICeleritasBridge bridge;

        CeleritasChunkProvider(ICeleritasBridge bridge) { this.bridge = bridge; }

        @Override public String getId()       { return "celeritas_chunks"; }
        @Override public int getPriority()    { return -100; } // High priority — wins over default

        @Override
        public void collectVisibleChunks(FrameContext frame, List<ChunkData> visibleChunks) {
            if (bridge.isActive()) {
                bridge.collectVisibleChunks(frame, visibleChunks);
            }
        }

        @Override
        public void submitChunkDraws(FrameContext frame, RenderQueue opaqueQueue,
                                     RenderQueue cutoutQueue, RenderQueue translucentQueue) {
            if (bridge.isActive()) {
                bridge.submitChunkDraws(frame, opaqueQueue, cutoutQueue, translucentQueue);
            }
        }

        @Override
        public void onChunkDirty(int sectionX, int sectionY, int sectionZ) {
            if (bridge.isActive()) {
                bridge.onChunkDirty(sectionX, sectionY, sectionZ);
            }
        }

        @Override
        public boolean supportsIndirectDraw() {
            return bridge.isActive() && bridge.supportsIndirectDraw();
        }
    }

    // ── SceneDataProvider adapter ──

    private static final class CeleritasSceneProvider implements SceneDataProvider {

        private final ICeleritasBridge bridge;

        CeleritasSceneProvider(ICeleritasBridge bridge) { this.bridge = bridge; }

        @Override public String getId()    { return "celeritas_scene"; }
        @Override public int getPriority() { return 10; } // After default scene capture

        @Override
        public void collectSceneData(FrameContext frame) {
            if (bridge.isActive()) {
                bridge.translateState(frame);
                registerBridgeMaterials(bridge);
            }
        }
    }

    // ── RenderEventListener adapter ──

    private static final class CeleritasEventListener implements RenderEventListener {

        private final ICeleritasBridge bridge;

        CeleritasEventListener(ICeleritasBridge bridge) { this.bridge = bridge; }

        @Override public String getId()    { return "celeritas_events"; }
        @Override public int getPriority() { return -100; }

        @Override
        public void onFrameBegin(FrameContext frame) {
            if (!bridge.isActive()) return;
            // Enable deferred mode if bridge requests it
            if (bridge.wantsDeferredDraw()) {
                FrameOrchestrator.INSTANCE.setDeferredMode(true);
            }
        }

        @Override
        public boolean onBeforePass(FrameContext frame, RenderPass pass, PassData passData) {
            if (!bridge.isActive()) return true;
            // Bind G-buffer FBO for terrain passes if requested
            if (bridge.wantsGBufferFbo() && isTerrainPass(pass)) {
                RenderTargetManager rtm = RenderTargetManager.INSTANCE;
                if (rtm.isInitialized()) {
                    var gbuffer = rtm.getFbo("gbuffer");
                    if (gbuffer != null) {
                        gbuffer.bind();
                        bridge.onGBufferBound(gbuffer);
                    }
                }
            }
            return true;
        }

        @Override
        public void onAfterPass(FrameContext frame, RenderPass pass) {
            if (!bridge.isActive()) return;
            // Unbind G-buffer FBO after terrain passes
            if (bridge.wantsGBufferFbo() && isTerrainPass(pass)) {
                org.lwjgl.opengl.GL30.glBindFramebuffer(
                        org.lwjgl.opengl.GL30.GL_FRAMEBUFFER, 0);
            }
        }

        @Override
        public void onFrameEnd(FrameContext frame) {
            // Disable deferred mode at end of frame (re-evaluated next frame)
            if (bridge.wantsDeferredDraw()) {
                FrameOrchestrator.INSTANCE.setDeferredMode(false);
            }
        }

        private boolean isTerrainPass(RenderPass pass) {
            PassType type = pass.getType();
            return type == PassType.TERRAIN_OPAQUE
                    || type == PassType.TERRAIN_CUTOUT;
        }
    }
}
