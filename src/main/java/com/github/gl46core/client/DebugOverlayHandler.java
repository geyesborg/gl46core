package com.github.gl46core.client;

import com.github.gl46core.api.debug.RenderProfiler;
import com.github.gl46core.api.render.FrameOrchestrator;
import com.github.gl46core.api.render.GlobalLightState;
import com.github.gl46core.api.render.PassType;
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

        FrameOrchestrator.INSTANCE.setActivePass(PassType.DEBUG_OVERLAY);

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

        GlobalLightState gl = FrameOrchestrator.INSTANCE.getFrameContext().getGlobalLight();
        int lf = gl.getLightingFlags();
        StringBuilder flags = new StringBuilder();
        if ((lf & GlobalLightState.FLAG_HAS_SKY) != 0) flags.append("SKY ");
        if ((lf & GlobalLightState.FLAG_NIGHT) != 0) flags.append("NIGHT ");
        if ((lf & GlobalLightState.FLAG_NETHER) != 0) flags.append("NETHER ");
        if ((lf & GlobalLightState.FLAG_END) != 0) flags.append("END ");
        if ((lf & GlobalLightState.FLAG_RAINING) != 0) flags.append("RAIN ");
        if ((lf & GlobalLightState.FLAG_THUNDERING) != 0) flags.append("THUNDER ");
        event.getRight().add(String.format("Light: sky=%.2f blk=%.1f wDark=%.2f [%s]",
                gl.getSkylightStrength(), gl.getBlockLightGlobalScale(),
                gl.getWeatherDarken(), flags.toString().trim()));

        com.github.gl46core.api.render.DynamicLightCollector dlc = com.github.gl46core.api.render.DynamicLightCollector.INSTANCE;
        event.getRight().add(String.format("DynLights: %d | Providers: %d",
                dlc.getLastFrameLightCount(), dlc.getTotalProvidersPolled()));

        FrameOrchestrator orch = FrameOrchestrator.INSTANCE;
        String activePass = orch.getActivePassType() != null ? orch.getActivePassType().getId() : "none";
        int passCount = orch.getPassGraph().getPassCount();
        int submissions = com.github.gl46core.api.translate.LegacyDrawTranslator.INSTANCE.getSubmissionCount();
        event.getRight().add(String.format("Pass: %s | Graph: %d | Exec: %d | Subs: %d",
                activePass, passCount, orch.getPassesExecuted(), submissions));

        // Per-pass draw breakdown (only passes with draws > 0)
        StringBuilder passBreak = new StringBuilder("Draws/pass:");
        for (com.github.gl46core.api.render.PassType pt : com.github.gl46core.api.render.PassType.values()) {
            int dc = p.getPassDrawCount(pt);
            if (dc > 0) {
                passBreak.append(' ').append(pt.getId()).append('=').append(dc);
            }
        }
        event.getRight().add(passBreak.toString());

        com.github.gl46core.api.translate.LegacyDrawTranslator ldt = com.github.gl46core.api.translate.LegacyDrawTranslator.INSTANCE;
        com.github.gl46core.api.render.gpu.MaterialBuffer matBuf = orch.getMaterialBuffer();
        int matCapacity = matBuf != null ? matBuf.getCapacity() : 0;
        event.getRight().add(String.format("Materials: %d unique | %d capacity",
                ldt.getUniqueMaterialCount(), matCapacity));

        com.github.gl46core.api.render.gpu.RenderTargetManager rtm = com.github.gl46core.api.render.gpu.RenderTargetManager.INSTANCE;
        if (rtm.isInitialized()) {
            long vram = rtm.estimateTotalVram();
            String vramStr = vram < 1024*1024 ? String.format("%.0fKB", vram/1024.0) : String.format("%.1fMB", vram/(1024.0*1024.0));
            event.getRight().add(String.format("RT: %d targets | %s VRAM | %dx%d | shadow=%d",
                    rtm.getTargetCount(), vramStr, rtm.getScreenWidth(), rtm.getScreenHeight(), rtm.getShadowResolution()));
        }

        com.github.gl46core.shaderpack.ShaderpackManager spm = com.github.gl46core.shaderpack.ShaderpackManager.INSTANCE;
        if (spm.isActive()) {
            event.getRight().add(String.format("Shaderpack: %s | %d programs | %d uniforms",
                    spm.getPackName(), spm.getProgramCount(), spm.getTotalUniforms()));
        } else {
            event.getRight().add("Shaderpack: none");
        }

        if (orch.isDeferredMode()) {
            com.github.gl46core.api.render.deferred.DrawCommandBuffer cmdBuf = orch.getDrawCommandBuffer();
            com.github.gl46core.api.render.deferred.DrawCommandExecutor exec = orch.getDrawCommandExecutor();
            int cmdCount = cmdBuf != null ? cmdBuf.getCount() : 0;
            com.github.gl46core.api.render.deferred.DeferredVboAllocator dvbo = orch.getDeferredVbo();
            String vboUsed = dvbo != null ? String.format("%.1fMB", dvbo.getUsedBytes() / (1024.0*1024.0)) : "?";
            double replayMs = exec != null ? exec.getReplayTimeMs() : 0;
            event.getRight().add(String.format("Deferred: %d cmds | %s VBO | %.1fms replay",
                    cmdCount, vboUsed, replayMs));
        } else {
            event.getRight().add("Deferred: off");
        }

        com.github.gl46core.api.render.FogState fog = FrameOrchestrator.INSTANCE.getFrameContext().getFog();
        String fogMode = fog.getMode() == 0x2601 ? "LINEAR" : fog.getMode() == 0x0800 ? "EXP" : fog.getMode() == 0x0801 ? "EXP2" : "0x" + Integer.toHexString(fog.getMode());
        event.getRight().add(String.format("Fog: %s start=%.0f end=%.0f d=%.4f col=(%.2f,%.2f,%.2f)",
                fogMode, fog.getStart(), fog.getEnd(), fog.getDensity(),
                fog.getR(), fog.getG(), fog.getB()));
    }
}
