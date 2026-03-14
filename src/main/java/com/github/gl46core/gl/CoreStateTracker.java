package com.github.gl46core.gl;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;

/**
 * Tracks ALL GL state used by gl46core — both removed fixed-function state
 * (alpha test, fog, lighting, etc.) and core-profile state (depth, blend,
 * cull, polygon offset, color mask).
 *
 * <p>Removed state is purely software-tracked and uploaded via UBOs.
 * Core state issues real GL calls but skips redundant ones via dirty-flagging,
 * and is saved/restored by pushAttrib/popAttrib.</p>
 */
public final class CoreStateTracker {

    public static final CoreStateTracker INSTANCE = new CoreStateTracker();

    // ── Attrib stack (pushAttrib/popAttrib emulation) ─────────────────
    private static final int ATTRIB_STACK_DEPTH = 8;
    private final AttribSnapshot[] attribStack;
    private int attribStackPointer = 0;

    private static class AttribSnapshot {
        // Removed state (software-only)
        boolean alphaTestEnabled;
        int alphaFunc;
        float alphaRef;
        boolean lightingEnabled;
        boolean[] lightEnabled = new boolean[8];
        boolean fogEnabled;
        int fogMode;
        float fogDensity, fogStart, fogEnd;
        float fogR, fogG, fogB, fogA;
        float colorR, colorG, colorB, colorA;
        boolean[] texture2DEnabled = new boolean[8];
        boolean normalizeEnabled, rescaleNormalEnabled;
        boolean colorMaterialEnabled;
        int shadeModel;
        int texEnvMode;
        boolean[] texGenEnabled = new boolean[4];
        int[] texGenMode = new int[4];
        boolean[] clipPlaneEnabled = new boolean[6];
        float[][] clipPlaneEquation = new float[6][4];
        // Core state (tracked + real GL)
        boolean depthTestEnabled;
        int depthFunc;
        boolean depthMask;
        boolean blendEnabled;
        int blendSrcRGB, blendDstRGB, blendSrcAlpha, blendDstAlpha;
        boolean cullEnabled;
        int cullFaceMode;
        boolean polygonOffsetEnabled;
        float polygonOffsetFactor, polygonOffsetUnits;
        boolean colorMaskR, colorMaskG, colorMaskB, colorMaskA;
    }

    // Generation counter — increments on every state mutation.
    // CoreShaderProgram compares this to skip per-draw dirty checks.
    private int generation = 0;

    public int getGeneration() { return generation; }

    /**
     * Flush any pending immediate-mode vertices BEFORE a state mutation.
     * Deferred batching accumulates vertices that were drawn with the current
     * UBO state — the flush must happen before fields change so the shader
     * reads the old values.
     */
    private void flushPending() {
        ImmediateModeEmulator.INSTANCE.flush();
    }

    /** Bump generation counter AFTER a state mutation. */
    private void dirty() {
        generation++;
        com.github.gl46core.api.debug.RenderProfiler.INSTANCE.recordStateGenBump();
    }

    // Track which threads have had their default state initialized
    private final java.util.concurrent.ConcurrentHashMap<Long, Boolean> initializedThreads = new java.util.concurrent.ConcurrentHashMap<>();

    private CoreStateTracker() {
        attribStack = new AttribSnapshot[ATTRIB_STACK_DEPTH];
        for (int i = 0; i < ATTRIB_STACK_DEPTH; i++) attribStack[i] = new AttribSnapshot();
    }

    /**
     * Called before each draw to ensure the current thread's GL context
     * matches what our ThreadLocal dirty-flags think it is.
     *
     * When a thread first acquires a GL context (e.g. Modern Splash's
     * Thread-1 taking the main context via Display.getDrawable().makeCurrent()),
     * the real GL state may differ from our ThreadCoreState defaults because
     * the previous owner (Client thread) modified it. Without force-syncing,
     * our dirty-flag optimization would skip GL calls that the new thread
     * issues (e.g. glDisable(GL_DEPTH_TEST) is skipped because our default
     * already says "disabled", but the real context has it enabled).
     */
    public void ensureThreadDefaults() {
        long tid = Thread.currentThread().getId();
        if (initializedThreads.putIfAbsent(tid, Boolean.TRUE) == null) {
            // First draw on this thread — force-sync real GL state to match
            // our ThreadCoreState defaults so dirty-flagging works correctly.
            ThreadCoreState cs = coreState();

            // Depth
            if (cs.depthTestEnabled) GL11.glEnable(GL11.GL_DEPTH_TEST);
            else GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthFunc(cs.depthFunc);
            GL11.glDepthMask(cs.depthMask);

            // Blend
            if (cs.blendEnabled) GL11.glEnable(GL11.GL_BLEND);
            else GL11.glDisable(GL11.GL_BLEND);
            GL14.glBlendFuncSeparate(cs.blendSrcRGB, cs.blendDstRGB,
                    cs.blendSrcAlpha, cs.blendDstAlpha);

            // Cull face
            if (cs.cullEnabled) GL11.glEnable(GL11.GL_CULL_FACE);
            else GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glCullFace(cs.cullFaceMode);

            // Polygon offset
            if (cs.polygonOffsetEnabled) GL11.glEnable(0x8037); // GL_POLYGON_OFFSET_FILL
            else GL11.glDisable(0x8037);
            GL11.glPolygonOffset(cs.polygonOffsetFactor, cs.polygonOffsetUnits);

            // Color mask
            GL11.glColorMask(cs.colorMaskR, cs.colorMaskG, cs.colorMaskB, cs.colorMaskA);

            dirty();
        }
    }

    public void pushAttrib() {
        if (attribStackPointer >= ATTRIB_STACK_DEPTH) return;
        AttribSnapshot s = attribStack[attribStackPointer];
        // Removed state
        s.alphaTestEnabled = alphaTestEnabled;
        s.alphaFunc = alphaFunc;
        s.alphaRef = alphaRef;
        s.lightingEnabled = lightingEnabled;
        System.arraycopy(lightEnabled, 0, s.lightEnabled, 0, 8);
        s.fogEnabled = fogEnabled;
        s.fogMode = fogMode;
        s.fogDensity = fogDensity;
        s.fogStart = fogStart;
        s.fogEnd = fogEnd;
        s.fogR = fogR; s.fogG = fogG; s.fogB = fogB; s.fogA = fogA;
        // Thread-local removed state (color + texture2D)
        ThreadCoreState cs = coreState();
        s.colorR = cs.colorR; s.colorG = cs.colorG; s.colorB = cs.colorB; s.colorA = cs.colorA;
        System.arraycopy(cs.texture2DEnabled, 0, s.texture2DEnabled, 0, 8);
        s.normalizeEnabled = normalizeEnabled;
        s.rescaleNormalEnabled = rescaleNormalEnabled;
        s.colorMaterialEnabled = colorMaterialEnabled;
        s.shadeModel = shadeModel;
        s.texEnvMode = texEnvMode;
        System.arraycopy(texGenEnabled, 0, s.texGenEnabled, 0, 4);
        System.arraycopy(texGenMode, 0, s.texGenMode, 0, 4);
        System.arraycopy(clipPlaneEnabled, 0, s.clipPlaneEnabled, 0, 6);
        for (int i = 0; i < 6; i++) System.arraycopy(clipPlaneEquation[i], 0, s.clipPlaneEquation[i], 0, 4);
        // Core state (from this thread's GL context)
        s.depthTestEnabled = cs.depthTestEnabled;
        s.depthFunc = cs.depthFunc;
        s.depthMask = cs.depthMask;
        s.blendEnabled = cs.blendEnabled;
        s.blendSrcRGB = cs.blendSrcRGB; s.blendDstRGB = cs.blendDstRGB;
        s.blendSrcAlpha = cs.blendSrcAlpha; s.blendDstAlpha = cs.blendDstAlpha;
        s.cullEnabled = cs.cullEnabled;
        s.cullFaceMode = cs.cullFaceMode;
        s.polygonOffsetEnabled = cs.polygonOffsetEnabled;
        s.polygonOffsetFactor = cs.polygonOffsetFactor; s.polygonOffsetUnits = cs.polygonOffsetUnits;
        s.colorMaskR = cs.colorMaskR; s.colorMaskG = cs.colorMaskG;
        s.colorMaskB = cs.colorMaskB; s.colorMaskA = cs.colorMaskA;
        attribStack[attribStackPointer++] = s;
    }

    public void popAttrib() {
        if (attribStackPointer <= 0) return;
        flushPending();
        AttribSnapshot s = attribStack[--attribStackPointer];
        // Removed state
        alphaTestEnabled = s.alphaTestEnabled;
        alphaFunc = s.alphaFunc;
        alphaRef = s.alphaRef;
        lightingEnabled = s.lightingEnabled;
        System.arraycopy(s.lightEnabled, 0, lightEnabled, 0, 8);
        fogEnabled = s.fogEnabled;
        fogMode = s.fogMode;
        fogDensity = s.fogDensity;
        fogStart = s.fogStart;
        fogEnd = s.fogEnd;
        fogR = s.fogR; fogG = s.fogG; fogB = s.fogB; fogA = s.fogA;
        ThreadCoreState cs0 = coreState();
        cs0.colorR = s.colorR; cs0.colorG = s.colorG; cs0.colorB = s.colorB; cs0.colorA = s.colorA;
        System.arraycopy(s.texture2DEnabled, 0, cs0.texture2DEnabled, 0, 8);
        normalizeEnabled = s.normalizeEnabled;
        rescaleNormalEnabled = s.rescaleNormalEnabled;
        colorMaterialEnabled = s.colorMaterialEnabled;
        shadeModel = s.shadeModel;
        texEnvMode = s.texEnvMode;
        System.arraycopy(s.texGenEnabled, 0, texGenEnabled, 0, 4);
        System.arraycopy(s.texGenMode, 0, texGenMode, 0, 4);
        System.arraycopy(s.clipPlaneEnabled, 0, clipPlaneEnabled, 0, 6);
        for (int i = 0; i < 6; i++) System.arraycopy(s.clipPlaneEquation[i], 0, clipPlaneEquation[i], 0, 4);
        // Core state — restore with real GL calls (on this thread's context)
        // Force-set by resetting cached state first, ensuring GL calls are issued
        ThreadCoreState cs = coreState();
        cs.depthTestEnabled = !s.depthTestEnabled; // force mismatch
        enableDepthTest(s.depthTestEnabled);
        cs.depthFunc = ~s.depthFunc;
        depthFunc(s.depthFunc);
        cs.depthMask = !s.depthMask;
        depthMask(s.depthMask);
        cs.blendEnabled = !s.blendEnabled;
        enableBlend(s.blendEnabled);
        cs.blendSrcRGB = ~s.blendSrcRGB;
        blendFuncSeparate(s.blendSrcRGB, s.blendDstRGB, s.blendSrcAlpha, s.blendDstAlpha);
        cs.cullEnabled = !s.cullEnabled;
        enableCull(s.cullEnabled);
        cs.cullFaceMode = ~s.cullFaceMode;
        cullFace(s.cullFaceMode);
        cs.polygonOffsetEnabled = !s.polygonOffsetEnabled;
        enablePolygonOffset(s.polygonOffsetEnabled);
        cs.polygonOffsetFactor = s.polygonOffsetFactor + 1; // force mismatch
        polygonOffset(s.polygonOffsetFactor, s.polygonOffsetUnits);
        cs.colorMaskR = !s.colorMaskR;
        colorMask(s.colorMaskR, s.colorMaskG, s.colorMaskB, s.colorMaskA);
        dirty();
    }

    // ── Alpha test ──────────────────────────────────────────────────────
    private boolean alphaTestEnabled = false;
    private int alphaFunc = 0x0207; // GL_ALWAYS
    private float alphaRef = 0.0f;

    // ── Lighting ────────────────────────────────────────────────────────
    private boolean lightingEnabled = false;
    private final boolean[] lightEnabled = new boolean[8];

    // Per-light parameters (only 2 lights used by vanilla MC)
    // Each light stores: position(x,y,z,w), diffuse(r,g,b,a), ambient(r,g,b,a)
    private final float[][] lightPosition = new float[2][4];
    private final float[][] lightDiffuse = new float[2][4];
    private final float[][] lightAmbient = new float[2][4];

    // Global ambient from glLightModel(GL_LIGHT_MODEL_AMBIENT)
    private float ambientR = 0.2f, ambientG = 0.2f, ambientB = 0.2f, ambientA = 1.0f;

    // ── Fog ─────────────────────────────────────────────────────────────
    private boolean fogEnabled = false;
    private int fogMode = 0x0800; // GL_EXP
    private float fogDensity = 1.0f;
    private float fogStart = 0.0f;
    private float fogEnd = 1.0f;
    private float fogR, fogG, fogB, fogA;

    // ── Color (glColor4f) — thread-local to avoid cross-thread corruption ──
    // ── Texture 2D enable/disable — thread-local (per texture unit) ─────────
    // Both moved to ThreadCoreState to prevent Client thread texture loading
    // from overwriting Modern Splash's render state on its separate thread.

    // ── Normalize / RescaleNormal ───────────────────────────────────────
    private boolean normalizeEnabled = false;
    private boolean rescaleNormalEnabled = false;

    // ── Color material ──────────────────────────────────────────────────
    private boolean colorMaterialEnabled = false;

    // ── Shade model ─────────────────────────────────────────────────────
    private int shadeModel = 0x1D01; // GL_SMOOTH

    public void enableAlphaTest() { flushPending(); alphaTestEnabled = true; dirty(); }
    public void disableAlphaTest() { flushPending(); alphaTestEnabled = false; dirty(); }
    public void alphaFunc(int func, float ref) { flushPending(); alphaFunc = func; alphaRef = ref; dirty(); }
    public boolean isAlphaTestEnabled() { return alphaTestEnabled; }
    public int getAlphaFunc() { return alphaFunc; }
    public float getAlphaRef() { return alphaRef; }

    // ── Lighting ────────────────────────────────────────────────────────

    public void enableLighting() { flushPending(); lightingEnabled = true; dirty(); }
    public void disableLighting() { flushPending(); lightingEnabled = false; dirty(); }
    public void enableLight(int light) { if (light >= 0 && light < 8) { flushPending(); lightEnabled[light] = true; dirty(); } }
    public void disableLight(int light) { if (light >= 0 && light < 8) { flushPending(); lightEnabled[light] = false; dirty(); } }
    public boolean isLightingEnabled() { return lightingEnabled; }
    public boolean isLightEnabled(int light) { return light >= 0 && light < 8 && lightEnabled[light]; }

    public void setLightPosition(int light, float x, float y, float z, float w) {
        if (light >= 0 && light < 2) {
            flushPending();
            lightPosition[light][0] = x; lightPosition[light][1] = y;
            lightPosition[light][2] = z; lightPosition[light][3] = w;
            dirty();
        }
    }
    public void setLightDiffuse(int light, float r, float g, float b, float a) {
        if (light >= 0 && light < 2) {
            flushPending();
            lightDiffuse[light][0] = r; lightDiffuse[light][1] = g;
            lightDiffuse[light][2] = b; lightDiffuse[light][3] = a;
            dirty();
        }
    }
    public void setLightAmbient(int light, float r, float g, float b, float a) {
        if (light >= 0 && light < 2) {
            flushPending();
            lightAmbient[light][0] = r; lightAmbient[light][1] = g;
            lightAmbient[light][2] = b; lightAmbient[light][3] = a;
            dirty();
        }
    }
    public float[] getLightPosition(int light) { return light >= 0 && light < 2 ? lightPosition[light] : new float[4]; }
    public float[] getLightDiffuse(int light) { return light >= 0 && light < 2 ? lightDiffuse[light] : new float[4]; }

    public void setLightModelAmbient(float r, float g, float b, float a) {
        flushPending(); ambientR = r; ambientG = g; ambientB = b; ambientA = a; dirty();
    }
    public float getLightModelAmbientR() { return ambientR; }
    public float getLightModelAmbientG() { return ambientG; }
    public float getLightModelAmbientB() { return ambientB; }

    // ── Fog ─────────────────────────────────────────────────────────────

    public void enableFog() { flushPending(); fogEnabled = true; dirty(); }
    public void disableFog() { flushPending(); fogEnabled = false; dirty(); }
    public void setFogMode(int mode) { flushPending(); fogMode = mode; dirty(); }
    public void setFogDensity(float density) { flushPending(); fogDensity = density; dirty(); }
    public void setFogStart(float start) { flushPending(); fogStart = start; dirty(); }
    public void setFogEnd(float end) { flushPending(); fogEnd = end; dirty(); }
    public void setFogColor(float r, float g, float b, float a) { flushPending(); fogR = r; fogG = g; fogB = b; fogA = a; dirty(); }
    public boolean isFogEnabled() { return fogEnabled; }
    public int getFogMode() { return fogMode; }
    public float getFogDensity() { return fogDensity; }
    public float getFogStart() { return fogStart; }
    public float getFogEnd() { return fogEnd; }

    // ── Color ───────────────────────────────────────────────────────────

    public void color(float r, float g, float b, float a) {
        flushPending(); ThreadCoreState cs = coreState();
        cs.colorR = r; cs.colorG = g; cs.colorB = b; cs.colorA = a; dirty();
    }
    public void resetColor() {
        flushPending(); ThreadCoreState cs = coreState();
        cs.colorR = 1.0f; cs.colorG = 1.0f; cs.colorB = 1.0f; cs.colorA = 1.0f; dirty();
    }
    public float getColorR() { return coreState().colorR; }
    public float getColorG() { return coreState().colorG; }
    public float getColorB() { return coreState().colorB; }
    public float getColorA() { return coreState().colorA; }

    // ── Texture 2D ──────────────────────────────────────────────────────

    public void enableTexture2D(int unit) { if (unit >= 0 && unit < 8) { flushPending(); coreState().texture2DEnabled[unit] = true; dirty(); } }
    public void disableTexture2D(int unit) { if (unit >= 0 && unit < 8) { flushPending(); coreState().texture2DEnabled[unit] = false; dirty(); } }
    public boolean isTexture2DEnabled(int unit) { return unit >= 0 && unit < 8 && coreState().texture2DEnabled[unit]; }

    /**
     * Track the currently bound texture for a given unit.
     * Called from GlStateManager.bindTexture() mixin.
     */
    public void bindTexture(int unit, int textureId) {
        if (unit >= 0 && unit < 8) {
            coreState().boundTextureId[unit] = textureId;
        }
    }
    public int getBoundTexture(int unit) {
        return (unit >= 0 && unit < 8) ? coreState().boundTextureId[unit] : 0;
    }
    /** Get the diffuse texture bound to unit 0. */
    public int getBoundTexture2D() { return coreState().boundTextureId[0]; }
    /** Get the lightmap texture bound to unit 1. */
    public int getLightmapTexture() { return coreState().boundTextureId[1]; }

    // ── Normalize ───────────────────────────────────────────────────────

    public void enableNormalize() { flushPending(); normalizeEnabled = true; dirty(); }
    public void disableNormalize() { flushPending(); normalizeEnabled = false; dirty(); }
    public boolean isNormalizeEnabled() { return normalizeEnabled; }
    public void enableRescaleNormal() { flushPending(); rescaleNormalEnabled = true; dirty(); }
    public void disableRescaleNormal() { flushPending(); rescaleNormalEnabled = false; dirty(); }
    public boolean isRescaleNormalEnabled() { return rescaleNormalEnabled; }

    // ── Color material ──────────────────────────────────────────────────

    public void enableColorMaterial() { flushPending(); colorMaterialEnabled = true; dirty(); }
    public void disableColorMaterial() { flushPending(); colorMaterialEnabled = false; dirty(); }
    public boolean isColorMaterialEnabled() { return colorMaterialEnabled; }

    // ── Shade model ─────────────────────────────────────────────────────

    public void shadeModel(int model) { flushPending(); shadeModel = model; dirty(); }
    public int getShadeModel() { return shadeModel; }

    // ── Lightmap coordinates (replaces glMultiTexCoord2f) ─────────────
    private float lightmapX = 240.0f;
    private float lightmapY = 240.0f;

    public void setLightmapCoords(float x, float y) { flushPending(); lightmapX = x; lightmapY = y; dirty(); }
    public float getLightmapX() { return lightmapX; }
    public float getLightmapY() { return lightmapY; }

    // ── Active texture unit (for direct GL11.glEnable(GL_TEXTURE_2D) redirection) ──
    private int activeTextureUnit = 0;

    public void setActiveTextureUnit(int unit) { activeTextureUnit = unit; }
    public int getActiveTextureUnit() { return activeTextureUnit; }

    // ── Fog color getters ─────────────────────────────────────────────
    public float getFogR() { return fogR; }
    public float getFogG() { return fogG; }
    public float getFogB() { return fogB; }
    public float getFogA() { return fogA; }

    // ── TexEnv mode ─────────────────────────────────────────────────
    // GL_MODULATE=0x2100, GL_REPLACE=0x1E01, GL_DECAL=0x2101,
    // GL_BLEND=0x0BE2, GL_ADD=0x0104, GL_COMBINE=0x8570
    private int texEnvMode = 0x2100; // GL_MODULATE (default)
    private float texEnvColorR = 0, texEnvColorG = 0, texEnvColorB = 0, texEnvColorA = 0;

    public void setTexEnvMode(int mode) { flushPending(); texEnvMode = mode; dirty(); }
    public int getTexEnvMode() { return texEnvMode; }
    public void setTexEnvColor(float r, float g, float b, float a) {
        flushPending(); texEnvColorR = r; texEnvColorG = g; texEnvColorB = b; texEnvColorA = a; dirty();
    }
    public float getTexEnvColorR() { return texEnvColorR; }
    public float getTexEnvColorG() { return texEnvColorG; }
    public float getTexEnvColorB() { return texEnvColorB; }
    public float getTexEnvColorA() { return texEnvColorA; }

    // ── TexGen ──────────────────────────────────────────────────────
    // Indices: 0=S, 1=T, 2=R, 3=Q
    // Modes: GL_OBJECT_LINEAR=0x2401, GL_EYE_LINEAR=0x2400, GL_SPHERE_MAP=0x2402
    private final boolean[] texGenEnabled = new boolean[4];
    private final int[] texGenMode = {0x2400, 0x2400, 0x2400, 0x2400}; // default EYE_LINEAR
    private final float[][] texGenObjectPlane = {{1,0,0,0},{0,1,0,0},{0,0,0,0},{0,0,0,0}};
    private final float[][] texGenEyePlane = {{1,0,0,0},{0,1,0,0},{0,0,0,0},{0,0,0,0}};

    public void enableTexGen(int coord) { if (coord >= 0 && coord < 4) { flushPending(); texGenEnabled[coord] = true; dirty(); } }
    public void disableTexGen(int coord) { if (coord >= 0 && coord < 4) { flushPending(); texGenEnabled[coord] = false; dirty(); } }
    public boolean isTexGenEnabled(int coord) { return coord >= 0 && coord < 4 && texGenEnabled[coord]; }
    public void setTexGenMode(int coord, int mode) { if (coord >= 0 && coord < 4) { flushPending(); texGenMode[coord] = mode; dirty(); } }
    public int getTexGenMode(int coord) { return coord >= 0 && coord < 4 ? texGenMode[coord] : 0x2400; }
    public void setTexGenObjectPlane(int coord, float a, float b, float c, float d) {
        if (coord >= 0 && coord < 4) { flushPending(); texGenObjectPlane[coord][0] = a; texGenObjectPlane[coord][1] = b; texGenObjectPlane[coord][2] = c; texGenObjectPlane[coord][3] = d; dirty(); }
    }
    public float[] getTexGenObjectPlane(int coord) { return coord >= 0 && coord < 4 ? texGenObjectPlane[coord] : new float[4]; }
    public void setTexGenEyePlane(int coord, float a, float b, float c, float d) {
        if (coord >= 0 && coord < 4) { flushPending(); texGenEyePlane[coord][0] = a; texGenEyePlane[coord][1] = b; texGenEyePlane[coord][2] = c; texGenEyePlane[coord][3] = d; dirty(); }
    }
    public float[] getTexGenEyePlane(int coord) { return coord >= 0 && coord < 4 ? texGenEyePlane[coord] : new float[4]; }

    // ── Clip planes (replaces glClipPlane / glEnable(GL_CLIP_PLANEn)) ──
    // In core profile, these are implemented via gl_ClipDistance[n] in the vertex shader.
    // The plane equation is in eye space (transformed by modelview at the time of glClipPlane call).
    private final boolean[] clipPlaneEnabled = new boolean[6];
    private final float[][] clipPlaneEquation = new float[6][4];

    public void enableClipPlane(int plane) { if (plane >= 0 && plane < 6) { flushPending(); clipPlaneEnabled[plane] = true; dirty(); } }
    public void disableClipPlane(int plane) { if (plane >= 0 && plane < 6) { flushPending(); clipPlaneEnabled[plane] = false; dirty(); } }
    public boolean isClipPlaneEnabled(int plane) { return plane >= 0 && plane < 6 && clipPlaneEnabled[plane]; }
    public void setClipPlaneEquation(int plane, float a, float b, float c, float d) {
        if (plane >= 0 && plane < 6) {
            flushPending();
            clipPlaneEquation[plane][0] = a; clipPlaneEquation[plane][1] = b;
            clipPlaneEquation[plane][2] = c; clipPlaneEquation[plane][3] = d;
            dirty();
        }
    }
    public float[] getClipPlaneEquation(int plane) { return plane >= 0 && plane < 6 ? clipPlaneEquation[plane] : new float[4]; }

    // ═══════════════════════════════════════════════════════════════════
    // CORE-PROFILE STATE — per-thread, tracked + real GL calls, skip redundant
    //
    // Each GL context (thread) has independent state. Modern Splash runs
    // on a separate thread with its own GL context; without ThreadLocal,
    // the dirty-flag optimization skips GL calls on the wrong context.
    // ═══════════════════════════════════════════════════════════════════

    private static class ThreadCoreState {
        // Core-profile state (real GL calls, dirty-flagged per-thread)
        boolean depthTestEnabled = false;
        int depthFunc = GL11.GL_LESS;
        boolean depthMask = true;
        boolean blendEnabled = false;
        int blendSrcRGB = GL11.GL_ONE, blendDstRGB = GL11.GL_ZERO;
        int blendSrcAlpha = GL11.GL_ONE, blendDstAlpha = GL11.GL_ZERO;
        boolean cullEnabled = false;
        int cullFaceMode = GL11.GL_BACK;
        boolean polygonOffsetEnabled = false;
        float polygonOffsetFactor = 0.0f, polygonOffsetUnits = 0.0f;
        boolean colorMaskR = true, colorMaskG = true, colorMaskB = true, colorMaskA = true;

        // Removed state that must be per-thread to avoid cross-thread corruption.
        // Modern Splash toggles texture2D and color on its thread while the Client
        // thread does texture loading — without ThreadLocal, the Client thread's
        // enableTexture2D overwrites the splash thread's disableTexture2D, causing
        // the shader to sample garbage textures and making bars invisible.
        boolean[] texture2DEnabled = {true, false, false, false, false, false, false, false};
        int[] boundTextureId = new int[8]; // GL texture name per unit
        float colorR = 1.0f, colorG = 1.0f, colorB = 1.0f, colorA = 1.0f;
    }

    private static final ThreadLocal<ThreadCoreState> threadCoreState =
            ThreadLocal.withInitial(ThreadCoreState::new);

    private ThreadCoreState coreState() { return threadCoreState.get(); }

    // ── Depth ──────────────────────────────────────────────────────────

    public void enableDepthTest(boolean enable) {
        ThreadCoreState cs = coreState();
        if (enable == cs.depthTestEnabled) return;
        cs.depthTestEnabled = enable;
        if (enable) GL11.glEnable(GL11.GL_DEPTH_TEST);
        else GL11.glDisable(GL11.GL_DEPTH_TEST);
    }
    public void depthFunc(int func) {
        ThreadCoreState cs = coreState();
        if (func == cs.depthFunc) return;
        cs.depthFunc = func;
        GL11.glDepthFunc(func);
    }
    public void depthMask(boolean flag) {
        ThreadCoreState cs = coreState();
        if (flag == cs.depthMask) return;
        cs.depthMask = flag;
        GL11.glDepthMask(flag);
    }
    public boolean isDepthTestEnabled() { return coreState().depthTestEnabled; }
    public int getDepthFunc() { return coreState().depthFunc; }
    public boolean getDepthMask() { return coreState().depthMask; }
    public boolean isDepthMaskEnabled() { return coreState().depthMask; }

    // ── Blend ──────────────────────────────────────────────────────────

    public void enableBlend(boolean enable) {
        ThreadCoreState cs = coreState();
        if (enable == cs.blendEnabled) return;
        cs.blendEnabled = enable;
        if (enable) GL11.glEnable(GL11.GL_BLEND);
        else GL11.glDisable(GL11.GL_BLEND);
    }
    public void blendFunc(int src, int dst) {
        blendFuncSeparate(src, dst, src, dst);
    }
    public void blendFuncSeparate(int srcRGB, int dstRGB, int srcAlpha, int dstAlpha) {
        ThreadCoreState cs = coreState();
        if (srcRGB == cs.blendSrcRGB && dstRGB == cs.blendDstRGB
                && srcAlpha == cs.blendSrcAlpha && dstAlpha == cs.blendDstAlpha) return;
        cs.blendSrcRGB = srcRGB; cs.blendDstRGB = dstRGB;
        cs.blendSrcAlpha = srcAlpha; cs.blendDstAlpha = dstAlpha;
        GL14.glBlendFuncSeparate(srcRGB, dstRGB, srcAlpha, dstAlpha);
    }
    public boolean isBlendEnabled() { return coreState().blendEnabled; }
    public int getBlendSrcRgb()   { return coreState().blendSrcRGB; }
    public int getBlendDstRgb()   { return coreState().blendDstRGB; }
    public int getBlendSrcAlpha() { return coreState().blendSrcAlpha; }
    public int getBlendDstAlpha() { return coreState().blendDstAlpha; }

    // ── Cull face ──────────────────────────────────────────────────────

    public void enableCull(boolean enable) {
        ThreadCoreState cs = coreState();
        if (enable == cs.cullEnabled) return;
        cs.cullEnabled = enable;
        if (enable) GL11.glEnable(GL11.GL_CULL_FACE);
        else GL11.glDisable(GL11.GL_CULL_FACE);
    }
    public void cullFace(int mode) {
        ThreadCoreState cs = coreState();
        if (mode == cs.cullFaceMode) return;
        cs.cullFaceMode = mode;
        GL11.glCullFace(mode);
    }
    public boolean isCullEnabled() { return coreState().cullEnabled; }
    public boolean isCullFaceEnabled() { return coreState().cullEnabled; }
    public int getCullFaceMode() { return coreState().cullFaceMode; }

    // ── Polygon offset ─────────────────────────────────────────────────

    public void enablePolygonOffset(boolean enable) {
        ThreadCoreState cs = coreState();
        if (enable == cs.polygonOffsetEnabled) return;
        cs.polygonOffsetEnabled = enable;
        if (enable) GL11.glEnable(0x8037); // GL_POLYGON_OFFSET_FILL
        else GL11.glDisable(0x8037);
    }
    public void polygonOffset(float factor, float units) {
        ThreadCoreState cs = coreState();
        if (factor == cs.polygonOffsetFactor && units == cs.polygonOffsetUnits) return;
        cs.polygonOffsetFactor = factor; cs.polygonOffsetUnits = units;
        GL11.glPolygonOffset(factor, units);
    }
    public boolean isPolygonOffsetEnabled() { return coreState().polygonOffsetEnabled; }

    // ── Color mask ─────────────────────────────────────────────────────

    public void colorMask(boolean r, boolean g, boolean b, boolean a) {
        ThreadCoreState cs = coreState();
        if (r == cs.colorMaskR && g == cs.colorMaskG && b == cs.colorMaskB && a == cs.colorMaskA) return;
        cs.colorMaskR = r; cs.colorMaskG = g; cs.colorMaskB = b; cs.colorMaskA = a;
        GL11.glColorMask(r, g, b, a);
    }
    public boolean getColorMaskR() { return coreState().colorMaskR; }
    public boolean getColorMaskG() { return coreState().colorMaskG; }
    public boolean getColorMaskB() { return coreState().colorMaskB; }
    public boolean getColorMaskA() { return coreState().colorMaskA; }
}
