package com.github.gl46core.gl;

import com.github.gl46core.GL46Core;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL45;

import org.joml.Matrix4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Manages UBOs and dispatches to the correct shader variant.
 *
 * Instead of a single uber-shader with runtime branching, {@link ShaderVariants}
 * lazily compiles specialized programs for each unique GL state combination.
 * This class manages the shared UBO buffers and uploads uniform data.
 *
 * Uses GL4.5 DSA UBOs for all uniform data — three buffer objects:
 *   - PerScene    (binding 0, 112 bytes): lighting + fog params (uploaded ~once/frame)
 *   - PerObject   (binding 1, 128 bytes): matrices (uploaded per draw call)
 *   - PerMaterial  (binding 2, 224 bytes): state flags + texgen + clips (uploaded on state change)
 *
 * Format-dependent attribute presence (hasColor, hasTexCoord, hasNormal) is
 * handled via glVertexAttrib* default values instead of UBO flags, eliminating
 * shader branching for vertex format variations.
 */
public final class CoreShaderProgram {

    public static final CoreShaderProgram INSTANCE = new CoreShaderProgram();

    // Attribute locations (must match layout qualifiers in shader)
    public static final int ATTR_POSITION = 0;
    public static final int ATTR_COLOR = 1;
    public static final int ATTR_TEXCOORD = 2;
    public static final int ATTR_LIGHTMAP = 3;
    public static final int ATTR_NORMAL = 4;

    private boolean initialized = false;

    // PerScene layout (std140, binding 0) — 112 bytes
    //   vec4  uLight0Position       offset 0
    //   vec4  uLight0Diffuse        offset 16
    //   vec4  uLight1Position       offset 32
    //   vec4  uLight1Diffuse        offset 48
    //   vec4  uLightModelAmbient    offset 64
    //   vec4  uFogColor             offset 80
    //   float uFogDensity           offset 96
    //   float uFogStart             offset 100
    //   float uFogEnd               offset 104
    //   int   uFogMode              offset 108
    private static final int PS_SIZE = 112;
    private final ByteBuffer psBuf = ByteBuffer.allocateDirect(PS_SIZE).order(ByteOrder.nativeOrder());

    // PerObject layout (std140, binding 1) — 128 bytes
    //   mat4  uModelViewProjection  offset 0
    //   mat4  uModelView            offset 64
    private static final int PO_SIZE = 128;
    private final ByteBuffer poBuf = ByteBuffer.allocateDirect(PO_SIZE).order(ByteOrder.nativeOrder());

    // PerMaterial layout (std140, binding 2) — 224 bytes
    //   float uAlphaRef             offset 0
    //   int   uAlphaFunc            offset 4
    //   vec2  uGlobalLightMapCoord  offset 8
    //   int   uLightMapEnabled      offset 16
    //   int   uTextureEnabled       offset 20
    //   int   uAlphaTestEnabled     offset 24
    //   int   uFogEnabled           offset 28
    //   int   uLightingEnabled      offset 32
    //   int   uUseLightMapTexture   offset 36
    //   int   uTexEnvMode           offset 40
    //   int   uTexGenEnabled        offset 44
    //   vec4  uTexGenEyePlaneS      offset 48
    //   vec4  uTexGenEyePlaneT      offset 64
    //   vec4  uTexGenObjectPlaneS   offset 80
    //   vec4  uTexGenObjectPlaneT   offset 96
    //   int   uTexGenSMode          offset 112
    //   int   uTexGenTMode          offset 116
    //   int   uClipPlaneEnabled     offset 120
    //   int   _pad0                 offset 124
    //   vec4  uClipPlane[6]         offset 128
    private static final int PM_SIZE = 224;
    private final ByteBuffer pmBuf = ByteBuffer.allocateDirect(PM_SIZE).order(ByteOrder.nativeOrder());

    private final org.joml.Matrix4f cachedMVP = new org.joml.Matrix4f();

    // Dirty tracking — skip redundant UBO uploads when state hasn't changed
    private int lastStateGeneration = -1;
    private boolean lastMvDirty = true;
    private boolean lastProjDirty = true;
    private int lastFormatFlags = -1;

    // Track which thread last bound UBOs — shared GL contexts don't share
    // buffer base bindings, so we must re-bind when the context changes.
    private volatile long lastBoundThread = -1;

    // Track current program to skip redundant glUseProgram calls
    private int lastProgramId = 0;

    // Extra variant bits ORed into the key during bind() — used by terrain
    // to request the SSBO variant (BIT_OBJECT_SSBO) without changing state.
    private int extraVariantBits = 0;

    public void setExtraVariantBits(int bits)  { this.extraVariantBits = bits; }
    public void clearExtraVariantBits()         { this.extraVariantBits = 0; }

    /**
     * Compute the shader variant key for the given format flags without binding.
     * Used by the deferred draw system to capture which variant a draw would use.
     */
    public int computeVariantKey(boolean hasColor, boolean hasTexCoord,
                                  boolean hasNormal, boolean hasLightMap) {
        return ShaderVariants.computeKey(CoreStateTracker.INSTANCE, hasLightMap) | extraVariantBits;
    }

    // Cached vertex attrib defaults — skip redundant glVertexAttrib* calls
    private float lastColorR = -1, lastColorG = -1, lastColorB = -1, lastColorA = -1;
    private int lastLightMapDummy = -1; // 0=not bound, 1=bound

    public static void endFrame() {
        CoreTextureTracker.flushPendingDeletes();
    }

    private CoreShaderProgram() {}

    public void ensureInitialized() {
        if (initialized) return;
        initialized = true;

        RenderContext ctx = RenderContext.get();

        // Create 3 UBOs with DSA (GL4.5) via RenderContext
        int sceneUbo    = ctx.createBuffer(RenderContext.GL.PER_FRAME_UBO);
        int objectUbo   = ctx.createBuffer(RenderContext.GL.PER_DRAW_UBO);
        int materialUbo = ctx.createBuffer(RenderContext.GL.PER_MATERIAL_UBO);

        GL45.glNamedBufferStorage(sceneUbo,    PS_SIZE, GL45.GL_DYNAMIC_STORAGE_BIT);
        GL45.glNamedBufferStorage(objectUbo,   PO_SIZE, GL45.GL_DYNAMIC_STORAGE_BIT);
        GL45.glNamedBufferStorage(materialUbo, PM_SIZE, GL45.GL_DYNAMIC_STORAGE_BIT);

        // NOTE: glBindBufferBase is per-context state and is NOT shared between
        // GL contexts. We bind in bind() instead, so splash threads with
        // SharedDrawable contexts also get the UBO binding points.

        // Create 1x1 white dummy texture — bound to unit 1 when no lightmap is active
        // to prevent NVIDIA debug warning about texture object 0 with no base level
        ctx.createDummyTexture(RenderContext.GL.DUMMY_TEXTURE);

        GL46Core.LOGGER.info("Shader variant system initialized with 3 DSA UBOs (scene={}, object={}, material={})",
            sceneUbo, objectUbo, materialUbo);
    }

    /**
     * Bind the shader, set default vertex attributes for disabled format
     * elements, and upload UBO data from CoreMatrixStack + CoreStateTracker.
     *
     * Default vertex attributes replace the old uHasColor/uHasTexCoord/uHasNormal
     * UBO flags — when an attribute isn't in the buffer, glVertexAttrib* provides
     * the fallback value so the shader reads all attributes unconditionally.
     */
    public void bind(boolean hasColor, boolean hasTexCoord, boolean hasNormal, boolean hasLightMap) {
        RenderContext ctx = RenderContext.get();
        int sceneUbo    = ctx.handle(RenderContext.GL.PER_FRAME_UBO);
        int objectUbo   = ctx.handle(RenderContext.GL.PER_DRAW_UBO);
        int materialUbo = ctx.handle(RenderContext.GL.PER_MATERIAL_UBO);
        if (sceneUbo == 0 || objectUbo == 0 || materialUbo == 0) return;

        // Ensure this thread starts with correct default state (texture2D enabled).
        // Modern Splash's thread can leave texture2DEnabled[0]=false in the shared
        // CoreStateTracker before the Client thread starts rendering.
        CoreStateTracker.INSTANCE.ensureThreadDefaults();

        // Ensure UBO binding points are set for this context.
        // glBindBufferBase is per-context state — shared GL contexts
        // (e.g. Modern Splash's SharedDrawable) don't inherit these.
        // Must happen BEFORE variant selection so lastProgramId is correct.
        long currentThread = Thread.currentThread().getId();
        if (currentThread != lastBoundThread) {
            GL31.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, 0, sceneUbo);
            GL31.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, 1, objectUbo);
            GL31.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, 2, materialUbo);
            lastBoundThread = currentThread;
            // Force full re-upload and program re-bind on context switch
            lastStateGeneration = -1;
            lastMvDirty = true;
            lastProjDirty = true;
            lastFormatFlags = -1;
            lastProgramId = 0;
        }

        CoreStateTracker state = CoreStateTracker.INSTANCE;

        // Select shader variant based on current GL state + extra bits (e.g. SSBO)
        int variantKey = ShaderVariants.computeKey(state, hasLightMap) | extraVariantBits;
        int programId = ShaderVariants.getProgram(variantKey);
        if (programId == 0) return;

        // Skip glUseProgram if this variant is already bound on this context
        if (programId != lastProgramId) {
            GL20.glUseProgram(programId);
            lastProgramId = programId;
            com.github.gl46core.api.debug.RenderProfiler.INSTANCE.recordShaderSwitch();
        }

        CoreMatrixStack ms = CoreMatrixStack.INSTANCE;

        // Set default vertex attribute values for disabled attributes.
        // glVertexAttrib* is context state (not per-VAO), and is only read
        // by the shader when the corresponding VAO attribute is disabled.
        // Cache values to skip redundant calls.
        if (!hasColor) {
            float cr = state.getColorR(), cg = state.getColorG(),
                  cb = state.getColorB(), ca = state.getColorA();
            if (cr != lastColorR || cg != lastColorG || cb != lastColorB || ca != lastColorA) {
                GL20.glVertexAttrib4f(ATTR_COLOR, cr, cg, cb, ca);
                lastColorR = cr; lastColorG = cg; lastColorB = cb; lastColorA = ca;
            }
        }
        // texcoord and normal defaults are always 0 — only need to set once
        if (!hasLightMap) {
            // Bind dummy white texture to unit 1 when no lightmap is active,
            // preventing NVIDIA warning about texture object 0 with no base level.
            if (lastLightMapDummy != 1 && !state.isTexture2DEnabled(1)) {
                int dummyTex = ctx.handle(RenderContext.GL.DUMMY_TEXTURE);
                if (dummyTex != 0) {
                    GL45.glBindTextureUnit(1, dummyTex);
                    lastLightMapDummy = 1;
                }
            }
        } else {
            lastLightMapDummy = 0;
        }

        boolean mvDirty = ms.isModelViewDirty();
        boolean projDirty = ms.isProjectionDirty();
        boolean matricesChanged = mvDirty || projDirty || lastMvDirty || lastProjDirty;

        int gen = state.getGeneration();
        boolean stateChanged = gen != lastStateGeneration;

        // ── PerScene UBO (binding 0): lighting + fog ──
        // Only uploaded when state generation changes (lighting/fog rarely change)
        if (stateChanged) {
            float[] l0p = state.getLightPosition(0);
            psBuf.putFloat(0,  l0p[0]); psBuf.putFloat(4,  l0p[1]); psBuf.putFloat(8,  l0p[2]); psBuf.putFloat(12, l0p[3]);
            float[] l0d = state.getLightDiffuse(0);
            psBuf.putFloat(16, l0d[0]); psBuf.putFloat(20, l0d[1]); psBuf.putFloat(24, l0d[2]); psBuf.putFloat(28, l0d[3]);
            float[] l1p = state.getLightPosition(1);
            psBuf.putFloat(32, l1p[0]); psBuf.putFloat(36, l1p[1]); psBuf.putFloat(40, l1p[2]); psBuf.putFloat(44, l1p[3]);
            float[] l1d = state.getLightDiffuse(1);
            psBuf.putFloat(48, l1d[0]); psBuf.putFloat(52, l1d[1]); psBuf.putFloat(56, l1d[2]); psBuf.putFloat(60, l1d[3]);
            psBuf.putFloat(64, state.getLightModelAmbientR());
            psBuf.putFloat(68, state.getLightModelAmbientG());
            psBuf.putFloat(72, state.getLightModelAmbientB());
            psBuf.putFloat(76, 0.0f);
            psBuf.putFloat(80, state.getFogR()); psBuf.putFloat(84, state.getFogG());
            psBuf.putFloat(88, state.getFogB()); psBuf.putFloat(92, state.getFogA());
            psBuf.putFloat(96, state.getFogDensity());
            psBuf.putFloat(100, state.getFogStart());
            psBuf.putFloat(104, state.getFogEnd());
            psBuf.putInt(108, state.getFogMode());

            psBuf.position(0).limit(PS_SIZE);
            GL45.glNamedBufferSubData(sceneUbo, 0, psBuf);
        }

        // ── PerObject UBO (binding 1): matrices ──
        // Uploaded every draw when modelview or projection changes
        if (matricesChanged) {
            ms.getProjection().mul(ms.getModelView(), cachedMVP);
            cachedMVP.get(0, poBuf);
            ms.getModelView().get(64, poBuf);

            poBuf.position(0).limit(PO_SIZE);
            GL45.glNamedBufferSubData(objectUbo, 0, poBuf);

            ms.clearModelViewDirty();
            ms.clearProjectionDirty();
            lastMvDirty = mvDirty;
            lastProjDirty = projDirty;
        }

        // ── PerMaterial UBO (binding 2): state flags + texgen + clips ──
        int formatFlags = (hasColor ? 1 : 0) | (hasTexCoord ? 2 : 0) | (hasNormal ? 4 : 0) | (hasLightMap ? 8 : 0);
        if (stateChanged || formatFlags != lastFormatFlags) {
            int hlm = hasLightMap ? 1 : 0;
            int ulmt = (!hasLightMap && state.isTexture2DEnabled(1)) ? 1 : 0;

            pmBuf.putFloat(0, state.getAlphaRef());
            pmBuf.putInt(4, state.getAlphaFunc());
            pmBuf.putFloat(8, state.getLightmapX()); pmBuf.putFloat(12, state.getLightmapY());
            pmBuf.putInt(16, hlm);
            pmBuf.putInt(20, state.isTexture2DEnabled(0) ? 1 : 0);
            pmBuf.putInt(24, state.isAlphaTestEnabled() ? 1 : 0);
            pmBuf.putInt(28, state.isFogEnabled() ? 1 : 0);
            pmBuf.putInt(32, state.isLightingEnabled() ? 1 : 0);
            pmBuf.putInt(36, ulmt);
            pmBuf.putInt(40, state.getTexEnvMode());
            int texGenBits = (state.isTexGenEnabled(0) ? 1 : 0) | (state.isTexGenEnabled(1) ? 2 : 0)
                    | (state.isTexGenEnabled(2) ? 4 : 0) | (state.isTexGenEnabled(3) ? 8 : 0);
            pmBuf.putInt(44, texGenBits);
            // TexGen eye planes (S and T)
            float[] eyeS = state.getTexGenEyePlane(0);
            pmBuf.putFloat(48, eyeS[0]); pmBuf.putFloat(52, eyeS[1]); pmBuf.putFloat(56, eyeS[2]); pmBuf.putFloat(60, eyeS[3]);
            float[] eyeT = state.getTexGenEyePlane(1);
            pmBuf.putFloat(64, eyeT[0]); pmBuf.putFloat(68, eyeT[1]); pmBuf.putFloat(72, eyeT[2]); pmBuf.putFloat(76, eyeT[3]);
            // TexGen object planes (S and T)
            float[] objS = state.getTexGenObjectPlane(0);
            pmBuf.putFloat(80, objS[0]); pmBuf.putFloat(84, objS[1]); pmBuf.putFloat(88, objS[2]); pmBuf.putFloat(92, objS[3]);
            float[] objT = state.getTexGenObjectPlane(1);
            pmBuf.putFloat(96, objT[0]); pmBuf.putFloat(100, objT[1]); pmBuf.putFloat(104, objT[2]); pmBuf.putFloat(108, objT[3]);
            pmBuf.putInt(112, state.getTexGenMode(0));
            pmBuf.putInt(116, state.getTexGenMode(1));
            int clipBits = 0;
            for (int i = 0; i < 6; i++) {
                if (state.isClipPlaneEnabled(i)) clipBits |= (1 << i);
                float[] eq = state.getClipPlaneEquation(i);
                int off = 128 + i * 16;
                pmBuf.putFloat(off, eq[0]); pmBuf.putFloat(off + 4, eq[1]);
                pmBuf.putFloat(off + 8, eq[2]); pmBuf.putFloat(off + 12, eq[3]);
            }
            pmBuf.putInt(120, clipBits);
            pmBuf.putInt(124, 0);

            pmBuf.position(0).limit(PM_SIZE);
            GL45.glNamedBufferSubData(materialUbo, 0, pmBuf);

            lastStateGeneration = gen;
            lastFormatFlags = formatFlags;
        }
    }

    /**
     * Upload pre-computed matrices directly to the PerObject UBO.
     * Bypasses CoreMatrixStack dirty flag tracking.
     * Use when the caller has already computed the correct MVP/MV
     * (e.g. terrain queue with pre-sorted, pre-computed transforms).
     */
    public void uploadMatricesDirect(Matrix4f mvp, Matrix4f mv) {
        int objectUbo = RenderContext.get().handle(RenderContext.GL.PER_DRAW_UBO);
        if (objectUbo == 0) return;
        mvp.get(0, poBuf);
        mv.get(64, poBuf);
        poBuf.position(0).limit(PO_SIZE);
        GL45.glNamedBufferSubData(objectUbo, 0, poBuf);
    }

    /**
     * Invalidate cached matrix state so the next bind() re-uploads PerObject UBO.
     * Call after external code has written to the PerObject UBO directly.
     */
    public void invalidateMatrices() {
        lastMvDirty = true;
        lastProjDirty = true;
    }

    /** Unbind shader */
    public void unbind() {
        GL20.glUseProgram(0);
        lastProgramId = 0;
    }

    /**
     * Invalidate the cached program binding. Must be called whenever an
     * external glUseProgram happens (mod shaders, splash renderer, etc.)
     * so the next bind() re-issues glUseProgram for our variant.
     */
    public void invalidateProgram() {
        lastProgramId = 0;
    }

    public int getProgramId() {
        return lastProgramId;
    }

    public boolean isOurProgram(int program) {
        return ShaderVariants.isOurProgram(program);
    }

    public int getPerDrawUbo() {
        return RenderContext.get().handle(RenderContext.GL.PER_MATERIAL_UBO);
    }

}
