package com.github.gl46core.api.debug;

import com.github.gl46core.api.render.PassType;
import com.github.gl46core.api.render.gpu.GpuBufferPool;

import java.util.EnumMap;

/**
 * Frame-level render profiler.
 *
 * Collects timing, draw counts, buffer upload stats, and compatibility
 * warnings each frame. Data is available for debug overlays and logging.
 *
 * Uses GL timer queries (GL_TIME_ELAPSED) for GPU-side pass timing
 * when available, with CPU nanoTime fallback.
 *
 * Usage:
 *   profiler.beginFrame();
 *   profiler.beginPass("terrain_opaque");
 *   // ... execute pass ...
 *   profiler.endPass("terrain_opaque");
 *   profiler.endFrame();
 *   double terrainMs = profiler.getPassTimeMs("terrain_opaque");
 */
public final class RenderProfiler {

    public static final RenderProfiler INSTANCE = new RenderProfiler();

    private static final int HISTORY_SIZE = 60; // 1 second at 60fps

    // ── Per-frame stats ──

    private long frameStartNanos;
    private long frameEndNanos;
    private int totalDrawCalls;
    private int totalVertices;
    private int totalSubmissions;
    private int passesExecuted;
    private int shaderSwitches;
    private int variantsCompiled;
    private long bytesUploaded;

    // Per-pass timing
    private final EnumMap<PassType, Long> passStartNanos = new EnumMap<>(PassType.class);
    private final EnumMap<PassType, Long> passElapsedNanos = new EnumMap<>(PassType.class);
    private final EnumMap<PassType, Integer> passDrawCounts = new EnumMap<>(PassType.class);

    // Named pass timing (for custom/shaderpack passes)
    private final java.util.HashMap<String, Long> namedPassStart = new java.util.HashMap<>();
    private final java.util.HashMap<String, Long> namedPassElapsed = new java.util.HashMap<>();

    // ── Rolling history ──

    private final double[] frameTimeHistory = new double[HISTORY_SIZE];
    private final int[] drawCallHistory = new int[HISTORY_SIZE];
    private int historyIndex;

    // ── Compatibility counters ──

    private int legacyDrawsTranslated;
    private int fallbackMaterialsCreated;
    private int stateGenerationBumps;
    private int redundantGLCallsSkipped;

    private RenderProfiler() {}

    // ═══════════════════════════════════════════════════════════════════
    // Frame lifecycle
    // ═══════════════════════════════════════════════════════════════════

    public void beginFrame() {
        frameStartNanos = System.nanoTime();
        totalDrawCalls = 0;
        totalVertices = 0;
        totalSubmissions = 0;
        passesExecuted = 0;
        shaderSwitches = 0;
        variantsCompiled = 0;
        bytesUploaded = 0;
        legacyDrawsTranslated = 0;
        fallbackMaterialsCreated = 0;
        stateGenerationBumps = 0;
        redundantGLCallsSkipped = 0;
        passElapsedNanos.clear();
        passDrawCounts.clear();
        namedPassElapsed.clear();
    }

    public void endFrame() {
        frameEndNanos = System.nanoTime();
        double frameMs = (frameEndNanos - frameStartNanos) / 1_000_000.0;

        // Update history ring buffer
        frameTimeHistory[historyIndex] = frameMs;
        drawCallHistory[historyIndex] = totalDrawCalls;
        historyIndex = (historyIndex + 1) % HISTORY_SIZE;

        // Capture buffer upload stats
        bytesUploaded = GpuBufferPool.INSTANCE.getTotalBytesUploaded();
        GpuBufferPool.INSTANCE.resetAllStats();
    }

    // ═══════════════════════════════════════════════════════════════════
    // Pass timing
    // ═══════════════════════════════════════════════════════════════════

    public void beginPass(PassType type) {
        passStartNanos.put(type, System.nanoTime());
    }

    public void endPass(PassType type) {
        Long start = passStartNanos.get(type);
        if (start != null) {
            passElapsedNanos.put(type, System.nanoTime() - start);
        }
        passesExecuted++;
    }

    public void beginPass(String name) {
        namedPassStart.put(name, System.nanoTime());
    }

    public void endPass(String name) {
        Long start = namedPassStart.get(name);
        if (start != null) {
            namedPassElapsed.put(name, System.nanoTime() - start);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Increment counters (called from hot paths)
    // ═══════════════════════════════════════════════════════════════════

    public void recordDrawCall(int vertexCount) {
        totalDrawCalls++;
        totalVertices += vertexCount;
    }

    public void recordSubmission()              { totalSubmissions++; }
    public void recordShaderSwitch()            { shaderSwitches++; }
    public void recordVariantCompiled()         { variantsCompiled++; }
    public void recordLegacyDrawTranslated()    { legacyDrawsTranslated++; }
    public void recordFallbackMaterialCreated() { fallbackMaterialsCreated++; }
    public void recordStateGenBump()            { stateGenerationBumps++; }
    public void recordRedundantGLSkipped()      { redundantGLCallsSkipped++; }

    public void recordPassDrawCount(PassType type, int count) {
        passDrawCounts.merge(type, count, Integer::sum);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Query — current frame
    // ═══════════════════════════════════════════════════════════════════

    public double getFrameTimeMs() {
        return (frameEndNanos - frameStartNanos) / 1_000_000.0;
    }

    public double getPassTimeMs(PassType type) {
        Long ns = passElapsedNanos.get(type);
        return ns != null ? ns / 1_000_000.0 : 0.0;
    }

    public double getPassTimeMs(String name) {
        Long ns = namedPassElapsed.get(name);
        return ns != null ? ns / 1_000_000.0 : 0.0;
    }

    public int getPassDrawCount(PassType type) {
        return passDrawCounts.getOrDefault(type, 0);
    }

    public int    getTotalDrawCalls()          { return totalDrawCalls; }
    public int    getTotalVertices()            { return totalVertices; }
    public int    getTotalSubmissions()         { return totalSubmissions; }
    public int    getPassesExecuted()           { return passesExecuted; }
    public int    getShaderSwitches()           { return shaderSwitches; }
    public int    getVariantsCompiled()         { return variantsCompiled; }
    public long   getBytesUploaded()            { return bytesUploaded; }
    public int    getLegacyDrawsTranslated()    { return legacyDrawsTranslated; }
    public int    getFallbackMaterialsCreated() { return fallbackMaterialsCreated; }
    public int    getStateGenerationBumps()     { return stateGenerationBumps; }
    public int    getRedundantGLCallsSkipped()  { return redundantGLCallsSkipped; }

    // ═══════════════════════════════════════════════════════════════════
    // Query — rolling averages
    // ═══════════════════════════════════════════════════════════════════

    public double getAverageFrameTimeMs() {
        double sum = 0;
        for (double v : frameTimeHistory) sum += v;
        return sum / HISTORY_SIZE;
    }

    public double getAverageFps() {
        double avg = getAverageFrameTimeMs();
        return avg > 0 ? 1000.0 / avg : 0;
    }

    public int getAverageDrawCalls() {
        long sum = 0;
        for (int v : drawCallHistory) sum += v;
        return (int)(sum / HISTORY_SIZE);
    }

    public double getMinFrameTimeMs() {
        double min = Double.MAX_VALUE;
        for (double v : frameTimeHistory) { if (v > 0 && v < min) min = v; }
        return min == Double.MAX_VALUE ? 0 : min;
    }

    public double getMaxFrameTimeMs() {
        double max = 0;
        for (double v : frameTimeHistory) { if (v > max) max = v; }
        return max;
    }

    /**
     * Format a compact summary string for debug overlay.
     */
    public String getSummary() {
        return String.format(
            "%.1f fps | %.2f ms | %d draws | %d verts | %d subs | %d passes | %d switches | %s uploaded",
            getAverageFps(), getFrameTimeMs(), totalDrawCalls, totalVertices,
            totalSubmissions, passesExecuted, shaderSwitches,
            formatBytes(bytesUploaded)
        );
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0);
        return String.format("%.1fMB", bytes / (1024.0 * 1024.0));
    }
}
