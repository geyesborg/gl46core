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

        com.github.gl46core.gl.ObjectBuffer ob = com.github.gl46core.gl.ObjectBuffer.INSTANCE;
        if (ob.getAlignedStride() > 0) {
            int objCount = t.getFrameChunksQueued();
            long bulkBytes = (long) objCount * ob.getAlignedStride();
            String bulkStr = bulkBytes < 1024 ? bulkBytes + "B"
                    : String.format("%.1fKB", bulkBytes / 1024.0);
            String mode = ob.isSsboMode() ? "SSBO" : "UBO-range";
            event.getRight().add(String.format("ObjectBuffer [%s]: stride=%d | bulk=%s | 2 GL/chunk",
                    mode, ob.getAlignedStride(), bulkStr));
        }
    }
}
