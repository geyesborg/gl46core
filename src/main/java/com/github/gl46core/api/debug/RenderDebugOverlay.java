package com.github.gl46core.api.debug;

import com.github.gl46core.api.render.PassType;
import com.github.gl46core.api.render.gpu.GpuBufferPool;
import com.github.gl46core.api.translate.FallbackMaterialFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects debug text lines for on-screen rendering.
 *
 * Does NOT draw anything itself — just builds a list of strings each
 * frame. The actual text rendering is done by whatever font renderer
 * is available (MC's FontRenderer, or a custom debug font).
 *
 * Toggle sections independently for focused debugging.
 */
public final class RenderDebugOverlay {

    public static final RenderDebugOverlay INSTANCE = new RenderDebugOverlay();

    private boolean enabled;
    private boolean showFrameTiming = true;
    private boolean showPassBreakdown = true;
    private boolean showBufferStats = true;
    private boolean showCompatStats = true;
    private boolean showGpuInfo = true;

    private final List<String> lines = new ArrayList<>();

    private RenderDebugOverlay() {}

    /**
     * Build debug lines for this frame. Call after endFrame().
     */
    public void update() {
        lines.clear();
        if (!enabled) return;

        RenderProfiler p = RenderProfiler.INSTANCE;

        lines.add("§e[gl46core Debug]");

        if (showFrameTiming) {
            lines.add(String.format("§fFPS: §a%.0f §7(%.2f ms avg, %.2f-%.2f ms)",
                p.getAverageFps(), p.getAverageFrameTimeMs(),
                p.getMinFrameTimeMs(), p.getMaxFrameTimeMs()));
            lines.add(String.format("§fFrame: §a%.2f ms §7| Draws: §a%d §7| Verts: §a%d",
                p.getFrameTimeMs(), p.getTotalDrawCalls(), p.getTotalVertices()));
            lines.add(String.format("§fSubmissions: §a%d §7| Passes: §a%d §7| Shader switches: §a%d",
                p.getTotalSubmissions(), p.getPassesExecuted(), p.getShaderSwitches()));
        }

        if (showPassBreakdown) {
            lines.add("§7--- Pass Breakdown ---");
            for (PassType type : PassType.values()) {
                double ms = p.getPassTimeMs(type);
                int draws = p.getPassDrawCount(type);
                if (ms > 0.001 || draws > 0) {
                    lines.add(String.format("§7  %s: §a%.2f ms §7(%d draws)",
                        type.name(), ms, draws));
                }
            }
        }

        if (showBufferStats) {
            lines.add("§7--- GPU Buffers ---");
            lines.add(String.format("§fUploaded: §a%s §7| Buffers: §a%d",
                formatBytes(p.getBytesUploaded()),
                GpuBufferPool.INSTANCE.getBufferCount()));
        }

        if (showCompatStats) {
            lines.add("§7--- Compat Layer ---");
            lines.add(String.format("§fLegacy draws: §a%d §7| Fallback mats: §a%d §7| State bumps: §a%d",
                p.getLegacyDrawsTranslated(),
                p.getFallbackMaterialsCreated(),
                p.getStateGenerationBumps()));
            lines.add(String.format("§fRedundant GL skipped: §a%d §7| Material cache: §a%d",
                p.getRedundantGLCallsSkipped(),
                FallbackMaterialFactory.INSTANCE.getCacheSize()));
            if (p.getVariantsCompiled() > 0) {
                lines.add(String.format("§eVariants compiled this frame: §c%d",
                    p.getVariantsCompiled()));
            }
        }
    }

    /**
     * Get the built lines for rendering. Empty if overlay is disabled.
     */
    public List<String> getLines() { return lines; }

    // ── Toggle controls ──

    public void toggle()                { enabled = !enabled; }
    public void setEnabled(boolean v)   { enabled = v; }
    public boolean isEnabled()          { return enabled; }

    public void toggleFrameTiming()     { showFrameTiming = !showFrameTiming; }
    public void togglePassBreakdown()   { showPassBreakdown = !showPassBreakdown; }
    public void toggleBufferStats()     { showBufferStats = !showBufferStats; }
    public void toggleCompatStats()     { showCompatStats = !showCompatStats; }
    public void toggleGpuInfo()         { showGpuInfo = !showGpuInfo; }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0);
        return String.format("%.1fMB", bytes / (1024.0 * 1024.0));
    }
}
