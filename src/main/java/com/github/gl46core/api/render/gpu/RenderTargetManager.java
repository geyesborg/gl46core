package com.github.gl46core.api.render.gpu;

import com.github.gl46core.GL46Core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Central manager for all render targets and framebuffer objects.
 *
 * Owns the named render target pool (colortex0-15, depthtex0-2,
 * shadowtex0-1, shadowcolor0-1, noisetex) and the FBOs that
 * reference them (gbuffer, shadow, composite, final).
 *
 * Provides:
 *   - Named target lookup for shaderpack uniform binding
 *   - Automatic resize when window dimensions change
 *   - G-buffer FBO with default attachments for vanilla rendering
 *   - Shadow FBO for shadow map passes
 *   - Composite FBO chain for post-processing
 *   - VRAM tracking for F3 overlay
 *
 * The manager is lazily initialized — no GPU resources are allocated
 * until {@link #initialize(int, int)} is called. Shaderpacks can
 * request additional targets or change formats before initialization.
 *
 * Default configuration (no shaderpack active):
 *   gbuffer FBO:   colortex0 (RGBA8), depthtex0 (DEPTH24)
 *   shadow FBO:    shadowtex0 (DEPTH24), shadowcolor0 (RGBA8)
 *   composite FBO: colortex0 reused as input, colortex4 (RGBA16F) as output
 */
public final class RenderTargetManager {

    public static final RenderTargetManager INSTANCE = new RenderTargetManager();

    // Maximum targets per category
    private static final int MAX_COLOR_TARGETS = 16;  // colortex0-15
    private static final int MAX_DEPTH_TARGETS = 3;   // depthtex0-2
    private static final int MAX_SHADOW_DEPTH  = 2;   // shadowtex0-1
    private static final int MAX_SHADOW_COLOR  = 2;   // shadowcolor0-1

    // Default shadow map resolution
    private static final int DEFAULT_SHADOW_RES = 1024;

    // Named render targets
    private final RenderTarget[] colorTargets = new RenderTarget[MAX_COLOR_TARGETS];
    private final RenderTarget[] depthTargets = new RenderTarget[MAX_DEPTH_TARGETS];
    private final RenderTarget[] shadowDepthTargets = new RenderTarget[MAX_SHADOW_DEPTH];
    private final RenderTarget[] shadowColorTargets = new RenderTarget[MAX_SHADOW_COLOR];

    // All targets by name for fast lookup
    private final Map<String, RenderTarget> targetsByName = new LinkedHashMap<>();

    // FBOs
    private FramebufferObject gbufferFbo;
    private FramebufferObject shadowFbo;
    private FramebufferObject compositeFbo;

    // State
    private int screenWidth;
    private int screenHeight;
    private int shadowResolution = DEFAULT_SHADOW_RES;
    private boolean initialized;

    private RenderTargetManager() {}

    /**
     * Initialize with default render targets and create FBOs.
     * Call once after GL context is ready.
     */
    public void initialize(int width, int height) {
        if (initialized) {
            resize(width, height);
            return;
        }

        this.screenWidth = width;
        this.screenHeight = height;

        // Create default targets
        createDefaultTargets();

        // Create FBOs
        createDefaultFbos();

        initialized = true;
        GL46Core.LOGGER.info("RenderTargetManager initialized: {}x{}, shadow={}x{}, {} targets, {} VRAM",
                width, height, shadowResolution, shadowResolution,
                targetsByName.size(), formatBytes(estimateTotalVram()));
    }

    /**
     * Resize all screen-resolution targets. Called on window resize.
     */
    public void resize(int width, int height) {
        if (width == screenWidth && height == screenHeight) return;
        this.screenWidth = width;
        this.screenHeight = height;

        if (!initialized) return;

        // Resize screen-resolution FBOs
        if (gbufferFbo != null) gbufferFbo.resize(width, height);
        if (compositeFbo != null) compositeFbo.resize(width, height);

        GL46Core.LOGGER.info("RenderTargetManager resized: {}x{}", width, height);
    }

    /**
     * Set shadow map resolution. Call before initialize or triggers re-create.
     */
    public void setShadowResolution(int res) {
        this.shadowResolution = res;
        if (initialized && shadowFbo != null) {
            shadowFbo.resize(res, res);
        }
    }

    /**
     * Register a custom render target by name.
     * Used by shaderpacks that declare non-default targets or formats.
     */
    public void registerTarget(String name, RenderTarget target) {
        targetsByName.put(name, target);
    }

    /**
     * Look up a render target by shaderpack name.
     */
    public RenderTarget getTarget(String name) {
        return targetsByName.get(name);
    }

    /**
     * Get a color target by index (colortex0-15).
     */
    public RenderTarget getColorTarget(int index) {
        return (index >= 0 && index < MAX_COLOR_TARGETS) ? colorTargets[index] : null;
    }

    /**
     * Get a depth target by index (depthtex0-2).
     */
    public RenderTarget getDepthTarget(int index) {
        return (index >= 0 && index < MAX_DEPTH_TARGETS) ? depthTargets[index] : null;
    }

    /**
     * Get a shadow depth target by index (shadowtex0-1).
     */
    public RenderTarget getShadowDepthTarget(int index) {
        return (index >= 0 && index < MAX_SHADOW_DEPTH) ? shadowDepthTargets[index] : null;
    }

    /**
     * Get a shadow color target by index (shadowcolor0-1).
     */
    public RenderTarget getShadowColorTarget(int index) {
        return (index >= 0 && index < MAX_SHADOW_COLOR) ? shadowColorTargets[index] : null;
    }

    // ── FBO Access ──

    public FramebufferObject getGbufferFbo()   { return gbufferFbo; }
    public FramebufferObject getShadowFbo()    { return shadowFbo; }
    public FramebufferObject getCompositeFbo() { return compositeFbo; }

    // ── State ──

    public boolean isInitialized()   { return initialized; }
    public int     getScreenWidth()  { return screenWidth; }
    public int     getScreenHeight() { return screenHeight; }
    public int     getShadowResolution() { return shadowResolution; }
    public int     getTargetCount()  { return targetsByName.size(); }

    /** Get all named targets as an unmodifiable map. */
    public java.util.Map<String, RenderTarget> getAllTargets() {
        return java.util.Collections.unmodifiableMap(targetsByName);
    }

    /** Look up a named FBO. Valid names: "gbuffer", "shadow", "composite". */
    public FramebufferObject getFbo(String name) {
        return switch (name) {
            case "gbuffer"   -> gbufferFbo;
            case "shadow"    -> shadowFbo;
            case "composite" -> compositeFbo;
            default          -> null;
        };
    }

    /**
     * Estimate total VRAM used by all render targets.
     */
    public long estimateTotalVram() {
        long total = 0;
        for (RenderTarget rt : targetsByName.values()) {
            total += rt.estimateVram();
        }
        return total;
    }

    /**
     * Destroy all render targets and FBOs.
     */
    public void destroy() {
        if (gbufferFbo != null)   { gbufferFbo.destroy();   gbufferFbo = null; }
        if (shadowFbo != null)    { shadowFbo.destroy();     shadowFbo = null; }
        if (compositeFbo != null) { compositeFbo.destroy();  compositeFbo = null; }

        for (RenderTarget rt : targetsByName.values()) {
            rt.destroy();
        }
        targetsByName.clear();

        for (int i = 0; i < MAX_COLOR_TARGETS; i++) colorTargets[i] = null;
        for (int i = 0; i < MAX_DEPTH_TARGETS; i++) depthTargets[i] = null;
        for (int i = 0; i < MAX_SHADOW_DEPTH; i++)  shadowDepthTargets[i] = null;
        for (int i = 0; i < MAX_SHADOW_COLOR; i++)  shadowColorTargets[i] = null;

        initialized = false;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Internal — default target creation
    // ═══════════════════════════════════════════════════════════════════

    private void createDefaultTargets() {
        // G-buffer color targets
        colorTargets[0] = createAndRegister("colortex0", RenderTarget.RGBA8);
        colorTargets[1] = createAndRegister("colortex1", RenderTarget.RGBA16F);
        colorTargets[2] = createAndRegister("colortex2", RenderTarget.RGBA8);
        colorTargets[3] = createAndRegister("colortex3", RenderTarget.RGBA8);

        // Composite output target
        colorTargets[4] = createAndRegister("colortex4", RenderTarget.RGBA16F);

        // Main depth targets
        depthTargets[0] = createAndRegister("depthtex0", RenderTarget.DEPTH24);
        depthTargets[1] = createAndRegister("depthtex1", RenderTarget.DEPTH24);

        // Shadow targets
        shadowDepthTargets[0] = createAndRegister("shadowtex0", RenderTarget.DEPTH24);
        shadowDepthTargets[1] = createAndRegister("shadowtex1", RenderTarget.DEPTH24);
        shadowColorTargets[0] = createAndRegister("shadowcolor0", RenderTarget.RGBA8);
        shadowColorTargets[1] = createAndRegister("shadowcolor1", RenderTarget.RGBA8);

        // Legacy shaderpack aliases
        targetsByName.put("gcolor",    colorTargets[0]);
        targetsByName.put("gdepth",    colorTargets[1]);
        targetsByName.put("gnormal",   colorTargets[2]);
        targetsByName.put("composite", colorTargets[3]);
        targetsByName.put("gaux1",     colorTargets[4]);
    }

    private RenderTarget createAndRegister(String name, int format) {
        RenderTarget rt = new RenderTarget(name, format);
        targetsByName.put(name, rt);
        return rt;
    }

    private void createDefaultFbos() {
        // G-buffer FBO: colortex0-3 + depthtex0
        gbufferFbo = new FramebufferObject("gbuffer");
        gbufferFbo.setColorAttachment(0, colorTargets[0]);
        gbufferFbo.setColorAttachment(1, colorTargets[1]);
        gbufferFbo.setColorAttachment(2, colorTargets[2]);
        gbufferFbo.setColorAttachment(3, colorTargets[3]);
        gbufferFbo.setDepthAttachment(depthTargets[0]);
        gbufferFbo.create(screenWidth, screenHeight);

        String gbufStatus = gbufferFbo.checkStatus();
        if (gbufStatus != null) {
            GL46Core.LOGGER.error("G-buffer FBO incomplete: {}", gbufStatus);
        }

        // Shadow FBO: shadowtex0 + shadowcolor0
        shadowFbo = new FramebufferObject("shadow");
        shadowFbo.setColorAttachment(0, shadowColorTargets[0]);
        shadowFbo.setDepthAttachment(shadowDepthTargets[0]);
        shadowFbo.create(shadowResolution, shadowResolution);

        String shadowStatus = shadowFbo.checkStatus();
        if (shadowStatus != null) {
            GL46Core.LOGGER.error("Shadow FBO incomplete: {}", shadowStatus);
        }

        // Composite FBO: colortex4 as output
        compositeFbo = new FramebufferObject("composite");
        compositeFbo.setColorAttachment(0, colorTargets[4]);
        compositeFbo.setDepthAttachment(depthTargets[0]); // share depth
        compositeFbo.create(screenWidth, screenHeight);

        String compStatus = compositeFbo.checkStatus();
        if (compStatus != null) {
            GL46Core.LOGGER.error("Composite FBO incomplete: {}", compStatus);
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0);
        return String.format("%.1fMB", bytes / (1024.0 * 1024.0));
    }
}
