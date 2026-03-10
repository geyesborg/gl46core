package com.github.gl46core.gl;

import com.github.gl46core.GL46Core;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL45;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Scanner;

/**
 * Compiles and manages the core-profile shader program (core.vert + core.frag).
 * Uses GL4.5 DSA UBOs for all uniform data — two buffer objects:
 *   - PerFrame (binding 0): matrices + lighting (208 bytes)
 *   - PerDraw  (binding 1): fog, alpha test, state flags (80 bytes)
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

    private int programId = 0;
    private boolean initialized = false;

    // ── UBO handles (DSA) ──
    private int perFrameUbo = 0;
    private int perDrawUbo = 0;

    // PerFrame layout (std140, binding 0) — 208 bytes
    //   mat4 uModelViewProjection  offset 0   (64 bytes)
    //   mat4 uModelView            offset 64  (64 bytes)
    //   vec4 uLight0Position       offset 128 (16 bytes)
    //   vec4 uLight0Diffuse        offset 144 (16 bytes)
    //   vec4 uLight1Position       offset 160 (16 bytes)
    //   vec4 uLight1Diffuse        offset 176 (16 bytes)
    //   vec4 uLightModelAmbient    offset 192 (16 bytes)  — vec3 padded to vec4
    private static final int PF_SIZE = 208;
    private final ByteBuffer pfBuf = ByteBuffer.allocateDirect(PF_SIZE).order(ByteOrder.nativeOrder());

    // PerDraw layout (std140, binding 1) — 80 bytes
    //   vec4  uFogColor              offset 0
    //   float uAlphaRef              offset 16
    //   float uFogDensity            offset 20
    //   float uFogStart              offset 24
    //   float uFogEnd                offset 28
    //   vec2  uGlobalLightMapCoord   offset 32
    //   int   uAlphaFunc             offset 40
    //   int   uFogMode               offset 44
    //   int   uLightMapEnabled       offset 48
    //   int   uTextureEnabled        offset 52
    //   int   uAlphaTestEnabled      offset 56
    //   int   uFogEnabled            offset 60
    //   int   uLightingEnabled       offset 64
    //   int   uUseLightMapTexture    offset 68
    //   int   uTexEnvMode            offset 72
    //   int   uTexGenEnabled          offset 76  (bitmask: bit0=S, bit1=T)
    //   vec4  uTexGenEyePlaneS        offset 80
    //   vec4  uTexGenEyePlaneT        offset 96
    //   vec4  uTexGenObjectPlaneS     offset 112
    //   vec4  uTexGenObjectPlaneT     offset 128
    //   int   uTexGenSMode            offset 144
    //   int   uTexGenTMode            offset 148
    //   int   _pad0                   offset 152
    //   int   _pad1                   offset 156
    private static final int PD_SIZE = 160;
    private final ByteBuffer pdBuf = ByteBuffer.allocateDirect(PD_SIZE).order(ByteOrder.nativeOrder());

    private final org.joml.Matrix4f cachedMVP = new org.joml.Matrix4f();

    private int lightingLogCount = 0;

    // Dirty tracking — skip redundant UBO uploads when state hasn't changed
    private int lastStateGeneration = -1;
    private boolean lastMvDirty = true;
    private boolean lastProjDirty = true;
    private int lastFormatFlags = -1;

    public static void endFrame() {
        CoreTextureTracker.flushPendingDeletes();
    }

    private CoreShaderProgram() {}

    public void ensureInitialized() {
        if (initialized) return;
        initialized = true;

        String vertSrc = loadShaderSource("/assets/gl46core/shaders/core.vert");
        String fragSrc = loadShaderSource("/assets/gl46core/shaders/core.frag");

        int vert = compileShader(GL20.GL_VERTEX_SHADER, vertSrc);
        int frag = compileShader(GL20.GL_FRAGMENT_SHADER, fragSrc);

        if (vert == 0 || frag == 0) {
            GL46Core.LOGGER.error("Shader compilation failed — core shader program will not be available");
            if (vert != 0) GL20.glDeleteShader(vert);
            if (frag != 0) GL20.glDeleteShader(frag);
            programId = 0;
            return;
        }

        programId = GL20.glCreateProgram();
        GL20.glAttachShader(programId, vert);
        GL20.glAttachShader(programId, frag);

        GL20.glLinkProgram(programId);
        if (GL20.glGetProgrami(programId, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetProgramInfoLog(programId, 4096);
            GL46Core.LOGGER.error("Shader link failed:\n{}", log);
            GL20.glDeleteProgram(programId);
            programId = 0;
            return;
        }

        GL20.glDeleteShader(vert);
        GL20.glDeleteShader(frag);

        // Create UBOs with DSA (GL4.5)
        int[] ubos = new int[2];
        GL45.glCreateBuffers(ubos);
        perFrameUbo = ubos[0];
        perDrawUbo = ubos[1];

        GL45.glNamedBufferStorage(perFrameUbo, PF_SIZE, GL45.GL_DYNAMIC_STORAGE_BIT);
        GL45.glNamedBufferStorage(perDrawUbo, PD_SIZE, GL45.GL_DYNAMIC_STORAGE_BIT);

        GL31.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, 0, perFrameUbo);
        GL31.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, 1, perDrawUbo);

        GL46Core.LOGGER.info("Core shader program compiled and linked (id={}) with DSA UBOs (pf={}, pd={})",
            programId, perFrameUbo, perDrawUbo);
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
        if (programId == 0) return;

        GL20.glUseProgram(programId);

        CoreMatrixStack ms = CoreMatrixStack.INSTANCE;
        CoreStateTracker state = CoreStateTracker.INSTANCE;

        // Set default vertex attribute values for disabled attributes.
        // glVertexAttrib* is context state (not per-VAO), and is only read
        // by the shader when the corresponding VAO attribute is disabled.
        if (!hasColor) {
            GL20.glVertexAttrib4f(ATTR_COLOR,
                    state.getColorR(), state.getColorG(),
                    state.getColorB(), state.getColorA());
        }
        if (!hasTexCoord) {
            GL20.glVertexAttrib2f(ATTR_TEXCOORD, 0.0f, 0.0f);
        }
        if (!hasNormal) {
            GL20.glVertexAttrib3f(ATTR_NORMAL, 0.0f, 0.0f, 0.0f);
        }
        if (!hasLightMap) {
            GL20.glVertexAttrib2f(ATTR_LIGHTMAP, 0.0f, 0.0f);
        }

        boolean mvDirty = ms.isModelViewDirty();
        boolean projDirty = ms.isProjectionDirty();
        boolean matricesChanged = mvDirty || projDirty || lastMvDirty || lastProjDirty;

        int gen = state.getGeneration();
        boolean stateChanged = gen != lastStateGeneration;

        // ── PerFrame UBO: matrices + lighting ──
        if (matricesChanged || stateChanged) {
            ms.getProjection().mul(ms.getModelView(), cachedMVP);
            cachedMVP.get(0, pfBuf);
            ms.getModelView().get(64, pfBuf);

            float[] l0p = state.getLightPosition(0);
            pfBuf.putFloat(128, l0p[0]); pfBuf.putFloat(132, l0p[1]); pfBuf.putFloat(136, l0p[2]); pfBuf.putFloat(140, l0p[3]);
            float[] l0d = state.getLightDiffuse(0);
            pfBuf.putFloat(144, l0d[0]); pfBuf.putFloat(148, l0d[1]); pfBuf.putFloat(152, l0d[2]); pfBuf.putFloat(156, l0d[3]);
            float[] l1p = state.getLightPosition(1);
            pfBuf.putFloat(160, l1p[0]); pfBuf.putFloat(164, l1p[1]); pfBuf.putFloat(168, l1p[2]); pfBuf.putFloat(172, l1p[3]);
            float[] l1d = state.getLightDiffuse(1);
            pfBuf.putFloat(176, l1d[0]); pfBuf.putFloat(180, l1d[1]); pfBuf.putFloat(184, l1d[2]); pfBuf.putFloat(188, l1d[3]);
            pfBuf.putFloat(192, state.getLightModelAmbientR());
            pfBuf.putFloat(196, state.getLightModelAmbientG());
            pfBuf.putFloat(200, state.getLightModelAmbientB());
            pfBuf.putFloat(204, 0.0f);

            pfBuf.position(0).limit(PF_SIZE);
            GL45.glNamedBufferSubData(perFrameUbo, 0, pfBuf);

            ms.clearModelViewDirty();
            ms.clearProjectionDirty();
            lastMvDirty = false;
            lastProjDirty = false;

            if (lightingLogCount < 5 && state.isLightingEnabled()) {
                lightingLogCount++;
                GL46Core.LOGGER.info("[LitDraw #{}] L0pos=[{},{},{},{}] L0diff=[{},{},{},{}] L1pos=[{},{},{},{}] L1diff=[{},{},{},{}] ambient=[{},{},{}] color=[{},{},{},{}] lmCoord=[{},{}] lmEnabled={} useLmTex={} hasNormal={} hasColor={}",
                    lightingLogCount,
                    l0p[0], l0p[1], l0p[2], l0p[3], l0d[0], l0d[1], l0d[2], l0d[3],
                    l1p[0], l1p[1], l1p[2], l1p[3], l1d[0], l1d[1], l1d[2], l1d[3],
                    state.getLightModelAmbientR(), state.getLightModelAmbientG(), state.getLightModelAmbientB(),
                    state.getColorR(), state.getColorG(), state.getColorB(), state.getColorA(),
                    state.getLightmapX(), state.getLightmapY(),
                    hasLightMap, (!hasLightMap && state.isTexture2DEnabled(1)) ? 1 : 0, hasNormal, hasColor);
            }

            ms.clearModelViewDirty();
            ms.clearProjectionDirty();
            lastMvDirty = mvDirty;
            lastProjDirty = projDirty;
        }

        // ── PerDraw UBO: fog, alpha test, state flags ──
        int formatFlags = (hasColor ? 1 : 0) | (hasTexCoord ? 2 : 0) | (hasNormal ? 4 : 0) | (hasLightMap ? 8 : 0);
        if (stateChanged || formatFlags != lastFormatFlags) {
            int hlm = hasLightMap ? 1 : 0;
            int ulmt = (!hasLightMap && state.isTexture2DEnabled(1)) ? 1 : 0;

            pdBuf.putFloat(0, state.getFogR()); pdBuf.putFloat(4, state.getFogG());
            pdBuf.putFloat(8, state.getFogB()); pdBuf.putFloat(12, state.getFogA());
            pdBuf.putFloat(16, state.getAlphaRef());
            pdBuf.putFloat(20, state.getFogDensity());
            pdBuf.putFloat(24, state.getFogStart());
            pdBuf.putFloat(28, state.getFogEnd());
            pdBuf.putFloat(32, state.getLightmapX()); pdBuf.putFloat(36, state.getLightmapY());
            pdBuf.putInt(40, state.getAlphaFunc());
            pdBuf.putInt(44, state.getFogMode());
            pdBuf.putInt(48, hlm);
            pdBuf.putInt(52, state.isTexture2DEnabled(0) ? 1 : 0);
            pdBuf.putInt(56, state.isAlphaTestEnabled() ? 1 : 0);
            pdBuf.putInt(60, state.isFogEnabled() ? 1 : 0);
            pdBuf.putInt(64, state.isLightingEnabled() ? 1 : 0);
            pdBuf.putInt(68, ulmt);
            pdBuf.putInt(72, state.getTexEnvMode());
            int texGenBits = (state.isTexGenEnabled(0) ? 1 : 0) | (state.isTexGenEnabled(1) ? 2 : 0)
                    | (state.isTexGenEnabled(2) ? 4 : 0) | (state.isTexGenEnabled(3) ? 8 : 0);
            pdBuf.putInt(76, texGenBits);
            // TexGen eye planes (S and T)
            float[] eyeS = state.getTexGenEyePlane(0);
            pdBuf.putFloat(80, eyeS[0]); pdBuf.putFloat(84, eyeS[1]); pdBuf.putFloat(88, eyeS[2]); pdBuf.putFloat(92, eyeS[3]);
            float[] eyeT = state.getTexGenEyePlane(1);
            pdBuf.putFloat(96, eyeT[0]); pdBuf.putFloat(100, eyeT[1]); pdBuf.putFloat(104, eyeT[2]); pdBuf.putFloat(108, eyeT[3]);
            // TexGen object planes (S and T)
            float[] objS = state.getTexGenObjectPlane(0);
            pdBuf.putFloat(112, objS[0]); pdBuf.putFloat(116, objS[1]); pdBuf.putFloat(120, objS[2]); pdBuf.putFloat(124, objS[3]);
            float[] objT = state.getTexGenObjectPlane(1);
            pdBuf.putFloat(128, objT[0]); pdBuf.putFloat(132, objT[1]); pdBuf.putFloat(136, objT[2]); pdBuf.putFloat(140, objT[3]);
            pdBuf.putInt(144, state.getTexGenMode(0));
            pdBuf.putInt(148, state.getTexGenMode(1));
            pdBuf.putInt(152, 0);
            pdBuf.putInt(156, 0);

            pdBuf.position(0).limit(PD_SIZE);
            GL45.glNamedBufferSubData(perDrawUbo, 0, pdBuf);

            lastStateGeneration = gen;
            lastFormatFlags = formatFlags;
        }
    }

    /** Unbind shader */
    public void unbind() {
        GL20.glUseProgram(0);
    }

    public int getProgramId() {
        return programId;
    }

    // ── Shader compilation ──

    private int compileShader(int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shader, 4096);
            String typeName = type == GL20.GL_VERTEX_SHADER ? "vertex" : "fragment";
            GL46Core.LOGGER.error("{} shader compilation failed:\n{}", typeName, log);
            GL20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    private String loadShaderSource(String path) {
        try (InputStream is = CoreShaderProgram.class.getResourceAsStream(path)) {
            if (is == null) {
                GL46Core.LOGGER.error("Shader resource not found: {}", path);
                return "";
            }
            Scanner scanner = new Scanner(is, "UTF-8").useDelimiter("\\A");
            return scanner.hasNext() ? scanner.next() : "";
        } catch (Exception e) {
            GL46Core.LOGGER.error("Failed to load shader: {}", path, e);
            return "";
        }
    }
}
