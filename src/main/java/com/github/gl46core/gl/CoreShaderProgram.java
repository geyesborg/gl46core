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
 *   - PerDraw  (binding 1): color, fog, format flags, alpha test (112 bytes)
 * All buffer operations use DSA (glCreateBuffers, glNamedBufferStorage, glNamedBufferSubData).
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

    // PerDraw layout (std140, binding 1) — 112 bytes
    //   vec4  uColor               offset 0
    //   vec4  uFogColor            offset 16
    //   float uAlphaRef            offset 32
    //   float uFogDensity          offset 36
    //   float uFogStart            offset 40
    //   float uFogEnd              offset 44
    //   vec2  uGlobalLightMapCoord offset 48
    //   int   uAlphaFunc           offset 56
    //   int   uFogMode             offset 60
    //   int   uHasColor            offset 64
    //   int   uHasTexCoord         offset 68
    //   int   uHasNormal           offset 72
    //   int   uLightMapEnabled     offset 76
    //   int   uTextureEnabled      offset 80
    //   int   uAlphaTestEnabled    offset 84
    //   int   uFogEnabled          offset 88
    //   int   uLightingEnabled     offset 92
    //   int   uUseLightMapTexture  offset 96
    //   int   _pad0                offset 100
    //   int   _pad1                offset 104
    //   int   _pad2                offset 108
    private static final int PD_SIZE = 112;
    private final ByteBuffer pdBuf = ByteBuffer.allocateDirect(PD_SIZE).order(ByteOrder.nativeOrder());

    // Cached Matrix4f to avoid allocation per draw call
    private final org.joml.Matrix4f cachedMVP = new org.joml.Matrix4f();

    private boolean lightingLogged = false;

    // ── Frame profiling ──
    private static int frameFullBindCount = 0;
    private static int frameFastPathCount = 0;
    private static int framePerFrameUploads = 0;
    private static int framePerDrawUploads = 0;
    private static int frameCounter = 0;

    public static void endFrame() {
        frameCounter++;
        if (frameCounter % 200 == 0) {
            GL46Core.LOGGER.info("[Perf] fullBinds={} fastPath={} pfUploads={} pdUploads={}",
                frameFullBindCount, frameFastPathCount, framePerFrameUploads, framePerDrawUploads);
        }
        frameFullBindCount = 0;
        frameFastPathCount = 0;
        framePerFrameUploads = 0;
        framePerDrawUploads = 0;
    }

    // Track whether our shader is currently bound to skip redundant glUseProgram
    private static boolean shaderCurrentlyBound = false;

    private CoreShaderProgram() {}

    public void ensureInitialized() {
        if (initialized) return;
        initialized = true;

        String vertSrc = loadShaderSource("/assets/gl46core/shaders/core.vert");
        String fragSrc = loadShaderSource("/assets/gl46core/shaders/core.frag");

        int vert = compileShader(GL20.GL_VERTEX_SHADER, vertSrc);
        int frag = compileShader(GL20.GL_FRAGMENT_SHADER, fragSrc);

        programId = GL20.glCreateProgram();
        GL20.glAttachShader(programId, vert);
        GL20.glAttachShader(programId, frag);

        GL20.glLinkProgram(programId);
        if (GL20.glGetProgrami(programId, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetProgramInfoLog(programId, 4096);
            GL46Core.LOGGER.error("Shader link failed:\n{}", log);
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

        // GL_DYNAMIC_STORAGE_BIT allows glNamedBufferSubData
        GL45.glNamedBufferStorage(perFrameUbo, PF_SIZE, GL45.GL_DYNAMIC_STORAGE_BIT);
        GL45.glNamedBufferStorage(perDrawUbo, PD_SIZE, GL45.GL_DYNAMIC_STORAGE_BIT);

        // Bind UBOs to shader binding points (matches layout(binding=N) in GLSL)
        GL31.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, 0, perFrameUbo);
        GL31.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, 1, perDrawUbo);

        GL46Core.LOGGER.info("Core shader program compiled and linked (id={}) with DSA UBOs (pf={}, pd={})",
            programId, perFrameUbo, perDrawUbo);
    }

    // ── PerDraw dirty tracking (field-level, avoids buffer fill+compare) ──
    private int lastHasColor = -1, lastHasTexCoord = -1, lastHasNormal = -1, lastLightMapEnabled = -1;
    private int lastTextureEnabled = -1, lastAlphaTestEnabled = -1, lastAlphaFunc = -1;
    private float lastAlphaRef = -1;
    private int lastFogEnabled = -1, lastFogMode = -1;
    private float lastFogDensity = -999, lastFogStart = -999, lastFogEnd = -999;
    private float lastFogR = -1, lastFogG = -1, lastFogB = -1, lastFogA = -1;
    private int lastLightingEnabled = -1;
    private int lastUseLightMapTexture = -1;
    private float lastLightmapX = -1, lastLightmapY = -1;
    private float lastColorR = -999, lastColorG = -999, lastColorB = -999, lastColorA = -999;
    private boolean perFrameForceDirty = true;

    /**
     * Bind the shader and upload UBO data from CoreMatrixStack + CoreStateTracker.
     * Uses dirty flags to skip matrix computation and buffer operations entirely.
     * Skips glUseProgram if our shader is already the active program.
     */
    public void bind(boolean hasColor, boolean hasTexCoord, boolean hasNormal, boolean hasLightMap) {
        if (programId == 0) return;

        if (!shaderCurrentlyBound) {
            GL20.glUseProgram(programId);
            shaderCurrentlyBound = true;
            frameFullBindCount++;
        } else {
            frameFastPathCount++;
        }

        CoreMatrixStack ms = CoreMatrixStack.INSTANCE;
        CoreStateTracker state = CoreStateTracker.INSTANCE;

        // ── PerFrame UBO: only rebuild when matrices or lighting changed ──
        boolean pfDirty = perFrameForceDirty || ms.isModelViewDirty() || ms.isProjectionDirty();
        if (pfDirty) {
            // MVP matrix (offset 0)
            ms.getProjection().mul(ms.getModelView(), cachedMVP);
            cachedMVP.get(0, pfBuf);
            // MV matrix (offset 64)
            ms.getModelView().get(64, pfBuf);
            // Light params (offset 128-207) — always include since lighting may have changed
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
            framePerFrameUploads++;
            perFrameForceDirty = false;

            if (!lightingLogged && state.isLightingEnabled()) {
                lightingLogged = true;
                GL46Core.LOGGER.info("Lighting UBO: L0diff=[{},{},{},{}] L1diff=[{},{},{},{}] ambient=[{},{},{}]",
                    l0d[0], l0d[1], l0d[2], l0d[3], l1d[0], l1d[1], l1d[2], l1d[3],
                    state.getLightModelAmbientR(), state.getLightModelAmbientG(), state.getLightModelAmbientB());
            }

            ms.clearModelViewDirty();
            ms.clearProjectionDirty();
        }

        // ── PerDraw UBO: field-level dirty check (no buffer fill unless something changed) ──
        float cr = state.getColorR(), cg = state.getColorG(), cb = state.getColorB(), ca = state.getColorA();
        int te = state.isTexture2DEnabled(0) ? 1 : 0;
        int ate = state.isAlphaTestEnabled() ? 1 : 0;
        int af = state.getAlphaFunc();
        float ar = state.getAlphaRef();
        int fe = state.isFogEnabled() ? 1 : 0;
        int fm = state.getFogMode();
        float fd = state.getFogDensity(), fs = state.getFogStart(), fend = state.getFogEnd();
        float fr = state.getFogR(), fg = state.getFogG(), fb = state.getFogB(), fa = state.getFogA();
        int le = state.isLightingEnabled() ? 1 : 0;
        int hc = hasColor ? 1 : 0, htc = hasTexCoord ? 1 : 0, hn = hasNormal ? 1 : 0, hlm = hasLightMap ? 1 : 0;
        int ulmt = (!hasLightMap && state.isTexture2DEnabled(1)) ? 1 : 0;
        float lx = state.getLightmapX(), ly = state.getLightmapY();

        boolean pdDirty = (cr != lastColorR || cg != lastColorG || cb != lastColorB || ca != lastColorA
            || hc != lastHasColor || htc != lastHasTexCoord || hn != lastHasNormal || hlm != lastLightMapEnabled
            || te != lastTextureEnabled || ate != lastAlphaTestEnabled || af != lastAlphaFunc || ar != lastAlphaRef
            || fe != lastFogEnabled || fm != lastFogMode || fd != lastFogDensity || fs != lastFogStart || fend != lastFogEnd
            || fr != lastFogR || fg != lastFogG || fb != lastFogB || fa != lastFogA
            || le != lastLightingEnabled || ulmt != lastUseLightMapTexture
            || lx != lastLightmapX || ly != lastLightmapY);

        if (pdDirty) {
            pdBuf.putFloat(0, cr);  pdBuf.putFloat(4, cg);  pdBuf.putFloat(8, cb);  pdBuf.putFloat(12, ca);
            pdBuf.putFloat(16, fr); pdBuf.putFloat(20, fg); pdBuf.putFloat(24, fb); pdBuf.putFloat(28, fa);
            pdBuf.putFloat(32, ar);
            pdBuf.putFloat(36, fd);
            pdBuf.putFloat(40, fs);
            pdBuf.putFloat(44, fend);
            pdBuf.putFloat(48, lx); pdBuf.putFloat(52, ly);
            pdBuf.putInt(56, af);
            pdBuf.putInt(60, fm);
            pdBuf.putInt(64, hc);
            pdBuf.putInt(68, htc);
            pdBuf.putInt(72, hn);
            pdBuf.putInt(76, hlm);
            pdBuf.putInt(80, te);
            pdBuf.putInt(84, ate);
            pdBuf.putInt(88, fe);
            pdBuf.putInt(92, le);
            pdBuf.putInt(96, ulmt);
            pdBuf.putInt(100, 0);
            pdBuf.putInt(104, 0);
            pdBuf.putInt(108, 0);

            pdBuf.position(0).limit(PD_SIZE);
            GL45.glNamedBufferSubData(perDrawUbo, 0, pdBuf);
            framePerDrawUploads++;

            lastColorR = cr; lastColorG = cg; lastColorB = cb; lastColorA = ca;
            lastHasColor = hc; lastHasTexCoord = htc; lastHasNormal = hn; lastLightMapEnabled = hlm;
            lastTextureEnabled = te; lastAlphaTestEnabled = ate; lastAlphaFunc = af; lastAlphaRef = ar;
            lastFogEnabled = fe; lastFogMode = fm; lastFogDensity = fd; lastFogStart = fs; lastFogEnd = fend;
            lastFogR = fr; lastFogG = fg; lastFogB = fb; lastFogA = fa;
            lastLightingEnabled = le; lastUseLightMapTexture = ulmt;
            lastLightmapX = lx; lastLightmapY = ly;
        }
    }

    /** Unbind shader and invalidate dirty cache */
    public void unbind() {
        GL20.glUseProgram(0);
        shaderCurrentlyBound = false;
        invalidateAll();
    }

    /** Force all uniforms to re-upload on next bind */
    private void invalidateAll() {
        lastHasColor = lastHasTexCoord = lastHasNormal = lastLightMapEnabled = -1;
        lastTextureEnabled = lastAlphaTestEnabled = lastAlphaFunc = -1;
        lastAlphaRef = -1;
        lastFogEnabled = lastFogMode = -1;
        lastFogDensity = lastFogStart = lastFogEnd = -999;
        lastFogR = lastFogG = lastFogB = lastFogA = -1;
        lastLightingEnabled = -1;
        lastUseLightMapTexture = -1;
        lastLightmapX = lastLightmapY = -1;
        lastColorR = lastColorG = lastColorB = lastColorA = -999;
        perFrameForceDirty = true;
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
