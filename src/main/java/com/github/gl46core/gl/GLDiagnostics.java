package com.github.gl46core.gl;

import com.github.gl46core.GL46Core;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GL45;
import org.lwjgl.opengl.GLCapabilities;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Centralized GL diagnostics system for gl46core.
 *
 * <h3>Three tiers:</h3>
 * <ol>
 *   <li><b>Startup checks</b> — run once at init, probe GL limits and common pitfalls</li>
 *   <li><b>Per-call checks</b> — glGetError after every passthrough call (only enabled
 *       if startup finds problems or during the first N frames)</li>
 *   <li><b>Error dedup</b> — per-label rate limiting so repeated errors don't spam the log</li>
 * </ol>
 */
public final class GLDiagnostics {

    private static final Logger LOG = GL46Core.LOGGER;

    // ── Singleton ────────────────────────────────────────────────────
    public static final GLDiagnostics INSTANCE = new GLDiagnostics();
    private GLDiagnostics() {}

    // ── Detail level ─────────────────────────────────────────────────
    public enum DetailLevel {
        /** No per-call checks — everything passed startup cleanly */
        OFF,
        /** Per-call checks active for a warm-up period */
        WARMUP,
        /** Per-call checks permanently active — startup found issues */
        FULL
    }

    private volatile DetailLevel detailLevel = DetailLevel.WARMUP;

    /**
     * Non-volatile fast-path flag — true when detailLevel is OFF.
     * Avoids a volatile read on every glCheck() call in the hot path.
     * Only transitions from false→true (never back), so no fence needed.
     */
    private boolean diagnosticsOff = false;

    /** Number of warm-up frames before auto-disabling per-call checks */
    private static final int WARMUP_FRAMES = 300; // ~5 seconds at 60fps
    private final AtomicInteger frameCount = new AtomicInteger(0);

    /** Max times a single label will log a full stack trace */
    private static final int MAX_TRACES_PER_LABEL = 3;
    /** Max times a single label will log at all (after this, only counted) */
    private static final int MAX_LOGS_PER_LABEL = 10;
    /** How often (in frames) to print a suppressed-error summary */
    private static final int SUMMARY_INTERVAL_FRAMES = 600; // ~10 seconds

    // ── Per-label error tracking ─────────────────────────────────────
    private final ConcurrentHashMap<String, ErrorEntry> errorMap = new ConcurrentHashMap<>();

    private static class ErrorEntry {
        final String label;
        final int firstError;       // GL error code from first occurrence
        final AtomicInteger count = new AtomicInteger(0);
        volatile boolean summarized = false;

        ErrorEntry(String label, int firstError) {
            this.label = label;
            this.firstError = firstError;
        }
    }

    // ── Startup issues found ─────────────────────────────────────────
    private int startupIssueCount = 0;

    /** When true, debug callback messages are suppressed (during deliberate probe calls) */
    private volatile boolean probing = false;

    /** Counter for non-error debug warnings (not stored in errorMap) */
    private final AtomicInteger debugWarnCount = new AtomicInteger(0);

    /** When true, config has forced a specific level — skip adaptive transitions */
    private boolean configOverride = false;

    // ═════════════════════════════════════════════════════════════════
    // Startup checks — call once from GL46Core.preInit()
    // ═════════════════════════════════════════════════════════════════

    public void runStartupChecks() {
        LOG.info("═══ GL Diagnostics: Running startup checks ═══");

        // Drain any pre-existing errors
        while (GL11.glGetError() != 0) {}

        checkGLLimits();
        checkCoreProfileCaps();
        checkExtensions();

        // Pitfall probes deliberately trigger GL errors — suppress debug callback
        probing = true;
        checkCommonPitfalls();
        probing = false;

        // Clear any error entries that the probes generated via the debug callback
        errorMap.clear();

        // Apply config override if set
        String configLevel = com.github.gl46core.core.GL46CoreConfig.diagnosticsLevel();
        if ("off".equals(configLevel)) {
            LOG.info("═══ GL Diagnostics: Config forced OFF — per-call checks disabled ═══");
            detailLevel = DetailLevel.OFF;
            diagnosticsOff = true;
            configOverride = true;
            com.github.gl46core.GL46Core.disableDebugOutput();
        } else if ("full".equals(configLevel)) {
            LOG.info("═══ GL Diagnostics: Config forced FULL — per-call checks always active ═══");
            detailLevel = DetailLevel.FULL;
            configOverride = true;
        } else {
            // "auto" — adaptive warm-up
            if (startupIssueCount == 0) {
                LOG.info("═══ GL Diagnostics: All startup checks passed — warm-up mode ({} frames) ═══", WARMUP_FRAMES);
                detailLevel = DetailLevel.WARMUP;
            } else {
                LOG.warn("═══ GL Diagnostics: {} issue(s) found — enabling FULL per-call diagnostics ═══", startupIssueCount);
                detailLevel = DetailLevel.FULL;
            }
        }
    }

    private void checkGLLimits() {
        LOG.info("[Diag] Checking GL limits...");

        // Line width range
        float[] range = new float[2];
        GL11.glGetFloatv(0x0B22, range); // GL_LINE_WIDTH_RANGE (aliased GL_SMOOTH_LINE_WIDTH_RANGE)
        LOG.info("[Diag]   Line width range: [{}, {}]", range[0], range[1]);
        if (range[1] < 2.0f) {
            LOG.info("[Diag]   → Max line width < 2.0 — gl46core clamps to 1.0 (OK)");
        }

        // Point size range
        GL11.glGetFloatv(0x0B12, range); // GL_POINT_SIZE_RANGE (aliased GL_SMOOTH_POINT_SIZE_RANGE)
        LOG.info("[Diag]   Point size range: [{}, {}]", range[0], range[1]);

        // Max texture size
        int maxTexSize = GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE);
        LOG.info("[Diag]   Max texture size: {}", maxTexSize);
        if (maxTexSize < 4096) {
            logIssue("Max texture size {} is unusually low — may cause texture upload failures", maxTexSize);
        }

        // Max vertex attribs
        int maxAttribs = GL11.glGetInteger(GL20.GL_MAX_VERTEX_ATTRIBS);
        LOG.info("[Diag]   Max vertex attribs: {}", maxAttribs);
        if (maxAttribs < 8) {
            logIssue("Max vertex attribs {} < 8 — gl46core needs at least 5", maxAttribs);
        }

        // Max uniform buffer bindings
        int maxUBOBindings = GL11.glGetInteger(GL31.GL_MAX_UNIFORM_BUFFER_BINDINGS);
        LOG.info("[Diag]   Max UBO bindings: {}", maxUBOBindings);
        if (maxUBOBindings < 4) {
            logIssue("Max UBO bindings {} < 4 — gl46core needs at least 2", maxUBOBindings);
        }

        // Max combined texture units
        int maxTexUnits = GL11.glGetInteger(GL20.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS);
        LOG.info("[Diag]   Max combined texture units: {}", maxTexUnits);

        drainErrors("GL limits query");
    }

    private void checkCoreProfileCaps() {
        LOG.info("[Diag] Checking core profile capabilities...");

        // Verify profile mask
        int profileMask = GL11.glGetInteger(0x9126); // GL_CONTEXT_PROFILE_MASK
        boolean isCore = (profileMask & 0x1) != 0;
        boolean isCompat = (profileMask & 0x2) != 0;
        LOG.info("[Diag]   Profile: {}", isCore ? "CORE" : isCompat ? "COMPAT" : "UNKNOWN(0x" + Integer.toHexString(profileMask) + ")");

        if (!isCore) {
            logIssue("Running in {} profile — gl46core expects CORE profile",
                    isCompat ? "COMPAT" : "UNKNOWN");
        }

        // Context flags
        int ctxFlags = GL11.glGetInteger(0x821E); // GL_CONTEXT_FLAGS
        boolean debugBit = (ctxFlags & 0x2) != 0; // GL_CONTEXT_FLAG_DEBUG_BIT
        LOG.info("[Diag]   Context flags: 0x{} (debug={})", Integer.toHexString(ctxFlags), debugBit);

        drainErrors("core profile caps query");
    }

    private void checkExtensions() {
        LOG.info("[Diag] Checking required extensions...");
        GLCapabilities caps = GL.getCapabilities();

        // Extensions we rely on
        checkExtension(caps.GL_ARB_direct_state_access, "GL_ARB_direct_state_access", true);
        checkExtension(caps.GL_ARB_buffer_storage, "GL_ARB_buffer_storage", true);
        checkExtension(caps.GL_ARB_vertex_attrib_binding, "GL_ARB_vertex_attrib_binding", true);
        checkExtension(caps.GL_ARB_uniform_buffer_object, "GL_ARB_uniform_buffer_object", true);
        checkExtension(caps.GL_ARB_texture_swizzle, "GL_ARB_texture_swizzle", false);
        checkExtension(caps.GL_KHR_debug, "GL_KHR_debug", false);

        drainErrors("extension check");
    }

    private void checkExtension(boolean present, String name, boolean required) {
        if (present) {
            LOG.info("[Diag]   {} — OK", name);
        } else if (required) {
            logIssue("MISSING required extension: {}", name);
        } else {
            LOG.warn("[Diag]   {} — not present (optional)", name);
        }
    }

    private void checkCommonPitfalls() {
        LOG.info("[Diag] Running common pitfall probes...");

        // Test: glLineWidth(2.0) — should generate GL_INVALID_VALUE in strict core
        GL11.glLineWidth(2.0f);
        int err = GL11.glGetError();
        if (err != 0) {
            LOG.info("[Diag]   glLineWidth(2.0) → GL error {} — gl46core clamps to 1.0 (OK)", err);
        } else {
            LOG.info("[Diag]   glLineWidth(2.0) → no error (driver accepts >1.0)");
        }
        GL11.glLineWidth(1.0f); // restore

        // Test: glPointSize(64.0) — check if driver restricts
        GL11.glPointSize(64.0f);
        err = GL11.glGetError();
        if (err != 0) {
            LOG.info("[Diag]   glPointSize(64.0) → GL error {} — gl46core clamps to 1.0 (OK)", err);
        } else {
            LOG.info("[Diag]   glPointSize(64.0) → no error (driver accepts large sizes)");
        }
        GL11.glPointSize(1.0f); // restore

        // Test: glEnable on a removed cap (GL_ALPHA_TEST = 0x0BC0)
        GL11.glEnable(0x0BC0);
        err = GL11.glGetError();
        if (err != 0) {
            LOG.info("[Diag]   glEnable(GL_ALPHA_TEST) → GL error {} — expected in core profile (intercepted)", err);
        } else {
            LOG.info("[Diag]   glEnable(GL_ALPHA_TEST) → no error (compat context or driver lenient)");
        }

        drainErrors("common pitfalls");
    }

    // ═════════════════════════════════════════════════════════════════
    // Per-call diagnostic — called from GL46Core.glCheck()
    // ═════════════════════════════════════════════════════════════════

    /**
     * Check for GL errors after a direct GL call.
     * Respects the current detail level and per-label rate limits.
     *
     * @param label descriptive label for the call site (e.g. "CST.depthFunc(515)")
     */
    public void check(String label) {
        // Fast path: non-volatile read — zero overhead when diagnostics are off
        if (diagnosticsOff) return;

        int err = GL11.glGetError();
        if (err == 0) return;

        // Drain any additional errors on this call
        int extraErrors = 0;
        while (GL11.glGetError() != 0) extraErrors++;

        recordError(label, err, extraErrors);
    }

    private void recordError(String label, int errCode, int extraErrors) {
        ErrorEntry entry = errorMap.computeIfAbsent(label, k -> new ErrorEntry(k, errCode));
        int n = entry.count.incrementAndGet();

        if (n <= MAX_TRACES_PER_LABEL) {
            // Full log with stack trace
            LOG.error("[GL-DIAG] GL error {} (0x{}) after {} (occurrence #{}){}",
                    glErrorName(errCode), Integer.toHexString(errCode), label, n,
                    extraErrors > 0 ? " [+" + extraErrors + " more errors]" : "",
                    new RuntimeException("GL-DIAG TRACE"));
        } else if (n <= MAX_LOGS_PER_LABEL) {
            // Log without trace
            LOG.error("[GL-DIAG] GL error {} after {} (#{} — trace suppressed)",
                    glErrorName(errCode), label, n);
        } else if (n == MAX_LOGS_PER_LABEL + 1) {
            // One-time suppression notice
            LOG.warn("[GL-DIAG] Suppressing further logs for '{}' ({}+ occurrences)", label, n);
        }
        // After MAX_LOGS_PER_LABEL: silently counted, reported in summary
    }

    // ═════════════════════════════════════════════════════════════════
    // GL debug callback dedup — route debug messages through same system
    // ═════════════════════════════════════════════════════════════════

    /**
     * Record a GL debug callback message with dedup.
     * Called from the GL_DEBUG_OUTPUT callback in GL46Core.
     *
     * @param id       driver message id
     * @param severity GL severity constant
     * @param type     GL type constant (0x824C = error)
     * @param msg      the message text
     */
    public void recordDebugMessage(int source, int type, int id, int severity, String msg) {
        // Skip notifications and low-severity messages (driver info/hints — noise)
        // GL_DEBUG_SEVERITY_NOTIFICATION = 0x826B, GL_DEBUG_SEVERITY_LOW = 0x9148
        if (severity == 0x826B || severity == 0x9148) return;

        // Suppress during deliberate startup probes
        if (probing) return;

        boolean isError = (type == 0x824C); // GL_DEBUG_TYPE_ERROR

        if (isError) {
            // Track errors in errorMap — these affect warm-up decisions
            String label = "GL_DEBUG_id=" + id;
            ErrorEntry entry = errorMap.computeIfAbsent(label, k -> new ErrorEntry(k, 0x0502));
            int n = entry.count.incrementAndGet();

            if (n <= MAX_TRACES_PER_LABEL) {
                LOG.error("[GL-DEBUG ERROR #{} id={}] src=0x{} sev=0x{}: {}",
                        n, id, Integer.toHexString(source),
                        Integer.toHexString(severity), msg,
                        new RuntimeException("GL DEBUG ERROR TRACE"));
            } else if (n <= MAX_LOGS_PER_LABEL) {
                LOG.error("[GL-DEBUG ERROR id={}] {} (#{} — trace suppressed)", id, msg, n);
            } else if (n == MAX_LOGS_PER_LABEL + 1) {
                LOG.warn("[GL-DEBUG] Suppressing further logs for debug id={} ({}+ occurrences)", id, n);
            }
            // If diagnostic level is OFF and not config-forced, escalate back to FULL
            if (detailLevel == DetailLevel.OFF && !configOverride) {
                LOG.warn("[GL-DIAG] GL debug error detected — re-enabling per-call diagnostics");
                detailLevel = DetailLevel.FULL;
            }
        } else {
            // Non-error warnings (medium/high severity only) — log a few, don't track in errorMap
            int n = debugWarnCount.incrementAndGet();
            if (n <= 5) {
                LOG.warn("[GL-DEBUG #{} id={}] src=0x{} type=0x{} sev=0x{}: {}",
                        n, id, Integer.toHexString(source), Integer.toHexString(type),
                        Integer.toHexString(severity), msg);
            } else if (n == 6) {
                LOG.warn("[GL-DEBUG] Suppressing further non-error debug messages ({}+ total)", n);
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // Frame tick — call once per frame from endFrame or clear()
    // ═════════════════════════════════════════════════════════════════

    /**
     * Called once per frame. Handles warm-up countdown and periodic summaries.
     */
    public void onFrameTick() {
        int frame = frameCount.incrementAndGet();

        // Auto-disable after warm-up if no errors were found (skip if config forced a level)
        if (!configOverride && detailLevel == DetailLevel.WARMUP && frame >= WARMUP_FRAMES) {
            if (errorMap.isEmpty()) {
                LOG.info("[GL-DIAG] Warm-up complete ({} frames) — no errors found, disabling per-call checks", frame);
                detailLevel = DetailLevel.OFF;
                diagnosticsOff = true;
                com.github.gl46core.GL46Core.disableDebugOutput();
            } else {
                LOG.warn("[GL-DIAG] Warm-up complete — {} error type(s) found, switching to FULL diagnostics", errorMap.size());
                detailLevel = DetailLevel.FULL;
            }
        }

        // Periodic summary of suppressed errors
        if (frame % SUMMARY_INTERVAL_FRAMES == 0 && !errorMap.isEmpty()) {
            printSummary(frame);
        }
    }

    private void printSummary(int frame) {
        boolean anyNew = false;
        StringBuilder sb = new StringBuilder();
        sb.append("[GL-DIAG] ══ Error summary at frame ").append(frame).append(" ══\n");

        for (ErrorEntry entry : errorMap.values()) {
            int count = entry.count.get();
            if (count > 0) {
                sb.append("  ").append(entry.label)
                  .append(" → ").append(glErrorName(entry.firstError))
                  .append(" × ").append(count).append('\n');
                if (!entry.summarized) {
                    entry.summarized = true;
                    anyNew = true;
                }
            }
        }

        if (anyNew) {
            LOG.warn(sb.toString());
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // Shutdown summary — call on game exit
    // ═════════════════════════════════════════════════════════════════

    public void printFinalSummary() {
        if (errorMap.isEmpty()) {
            LOG.info("[GL-DIAG] ══ Session clean — no GL errors detected ══");
            return;
        }

        int totalErrors = 0;
        StringBuilder sb = new StringBuilder();
        sb.append("[GL-DIAG] ══ Final error summary ══\n");
        for (ErrorEntry entry : errorMap.values()) {
            int count = entry.count.get();
            totalErrors += count;
            sb.append("  ").append(entry.label)
              .append(" → ").append(glErrorName(entry.firstError))
              .append(" × ").append(count).append('\n');
        }
        sb.append("  Total: ").append(totalErrors).append(" error(s) across ")
          .append(errorMap.size()).append(" call site(s)");
        LOG.warn(sb.toString());
    }

    // ═════════════════════════════════════════════════════════════════
    // Utilities
    // ═════════════════════════════════════════════════════════════════

    public DetailLevel getDetailLevel() { return detailLevel; }

    /** Force detail level (e.g. from config) */
    public void setDetailLevel(DetailLevel level) { this.detailLevel = level; }

    private void logIssue(String msg, Object... args) {
        LOG.warn("[Diag] ⚠ " + msg, args);
        startupIssueCount++;
    }

    /** Drain all pending GL errors (prevents cascading false positives) */
    private void drainErrors(String phase) {
        int drained = 0;
        while (GL11.glGetError() != 0) drained++;
        if (drained > 0) {
            LOG.info("[Diag]   (drained {} error(s) from {})", drained, phase);
        }
    }

    private static String glErrorName(int err) {
        return switch (err) {
            case 0x0500 -> "GL_INVALID_ENUM";
            case 0x0501 -> "GL_INVALID_VALUE";
            case 0x0502 -> "GL_INVALID_OPERATION";
            case 0x0503 -> "GL_STACK_OVERFLOW";
            case 0x0504 -> "GL_STACK_UNDERFLOW";
            case 0x0505 -> "GL_OUT_OF_MEMORY";
            case 0x0506 -> "GL_INVALID_FRAMEBUFFER_OPERATION";
            default -> "GL_ERROR_" + err;
        };
    }
}
