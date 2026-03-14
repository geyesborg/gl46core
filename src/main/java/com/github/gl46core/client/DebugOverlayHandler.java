package com.github.gl46core.client;

import com.github.gl46core.api.debug.RenderProfiler;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Appends gl46core render stats to the F3 debug screen (right side).
 *
 * Shows draw calls, shader switches, variant compilations, buffer uploads,
 * and compat layer stats when F3 is open — no extra keybind needed.
 */
public final class DebugOverlayHandler {

    private DebugOverlayHandler() {}

    public static void register() {
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new DebugOverlayHandler());
    }

    @SubscribeEvent
    public void onDebugText(RenderGameOverlayEvent.Text event) {
        if (!net.minecraft.client.Minecraft.getMinecraft().gameSettings.showDebugInfo) return;

        RenderProfiler p = RenderProfiler.INSTANCE;

        event.getRight().add("");
        event.getRight().add("[gl46core]");
        event.getRight().add(String.format("Draws: %d | Verts: %d | Switches: %d",
                p.getTotalDrawCalls(), p.getTotalVertices(), p.getShaderSwitches()));
        event.getRight().add(String.format("Frame: %.2f ms | Avg: %.1f fps",
                p.getFrameTimeMs(), p.getAverageFps()));
        event.getRight().add(String.format("State bumps: %d | Variants compiled: %d",
                p.getStateGenerationBumps(), p.getVariantsCompiled()));

        long uploaded = p.getBytesUploaded();
        String uploadStr = uploaded < 1024 ? uploaded + "B"
                : uploaded < 1024 * 1024 ? String.format("%.1fKB", uploaded / 1024.0)
                : String.format("%.1fMB", uploaded / (1024.0 * 1024.0));
        event.getRight().add(String.format("GPU upload: %s | Buffers: %d",
                uploadStr,
                com.github.gl46core.api.render.gpu.GpuBufferPool.INSTANCE.getBufferCount()));

        com.github.gl46core.gl.TerrainDrawCollector t = com.github.gl46core.gl.TerrainDrawCollector.INSTANCE;
        event.getRight().add(String.format("Terrain queue: %d chunks | %d verts | %d layers sorted",
                t.getFrameChunksQueued(), t.getFrameVerticesQueued(), t.getFrameSortedLayers()));

        int mdiLayers = t.getFrameMdiLayers();
        int ssboLayers = t.getFrameSsboLayers();
        int uboLayers = t.getFrameSortedLayers() - mdiLayers - ssboLayers;
        event.getRight().add(String.format("Draw path: MDI=%d | SSBO=%d | UBO=%d layers",
                mdiLayers, ssboLayers, uboLayers));

        com.github.gl46core.gl.MegaTerrainBuffer mega = com.github.gl46core.gl.MegaTerrainBuffer.INSTANCE;
        if (mega.isInitialized()) {
            event.getRight().add(String.format("MegaBuffer: %.1f%% full | %d regions | %d free segs",
                    mega.getFillPercent(), mega.getActiveRegions(), mega.getFreeRegionCount()));
        }

        com.github.gl46core.gl.ModelGeometryCache mc = com.github.gl46core.gl.ModelGeometryCache.INSTANCE;
        event.getRight().add(String.format("ModelCache: %d cached | %d hits | %d misses | %d draws",
                mc.getCacheSize(), mc.getLastHits(), mc.getLastMisses(), mc.getLastDraws()));
    }
}
