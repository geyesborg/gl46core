package com.github.gl46core.api.compat;

import com.github.gl46core.api.hook.ChunkRenderProvider;
import com.github.gl46core.api.hook.RenderRegistry;
import com.github.gl46core.api.hook.SceneDataProvider;
import com.github.gl46core.api.render.ChunkData;
import com.github.gl46core.api.render.FrameContext;
import com.github.gl46core.api.render.RenderQueue;

import java.util.List;

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
            activeBridge = null;
        }
    }

    public static boolean isInstalled()         { return activeBridge != null; }
    public static ICeleritasBridge getBridge()   { return activeBridge; }

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
            }
        }
    }
}
