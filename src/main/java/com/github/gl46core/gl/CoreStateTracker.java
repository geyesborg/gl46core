package com.github.gl46core.gl;

/**
 * Tracks legacy fixed-function GL state that has been removed in core profile.
 * Instead of calling glEnable(GL_ALPHA_TEST) etc., we store the state here
 * and make it available as uniform data for shaders that need to emulate
 * the fixed-function behaviour.
 *
 * This class does NOT issue any GL calls — it is purely a software state tracker.
 */
public final class CoreStateTracker {

    public static final CoreStateTracker INSTANCE = new CoreStateTracker();

    // ── Attrib stack (pushAttrib/popAttrib emulation) ─────────────────
    private static final int ATTRIB_STACK_DEPTH = 8;
    private final AttribSnapshot[] attribStack;
    private int attribStackPointer = 0;

    private static class AttribSnapshot {
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
    }

    // Generation counter — increments on every state mutation.
    // CoreShaderProgram compares this to skip per-draw dirty checks.
    private int generation = 0;

    public int getGeneration() { return generation; }

    // Track which threads have had their default state initialized
    private final java.util.concurrent.ConcurrentHashMap<Long, Boolean> initializedThreads = new java.util.concurrent.ConcurrentHashMap<>();

    private CoreStateTracker() {
        attribStack = new AttribSnapshot[ATTRIB_STACK_DEPTH];
        for (int i = 0; i < ATTRIB_STACK_DEPTH; i++) attribStack[i] = new AttribSnapshot();
        // Default: texture unit 0 enabled
        texture2DEnabled[0] = true;
    }

    /**
     * Called before each draw to ensure the current thread starts with
     * correct default state. Needed because Modern Splash's thread can
     * leave texture2DEnabled[0]=false in this shared singleton before
     * the Client thread starts rendering.
     */
    public void ensureThreadDefaults() {
        long tid = Thread.currentThread().getId();
        if (initializedThreads.putIfAbsent(tid, Boolean.TRUE) == null) {
            // First draw on this thread — reset to OpenGL defaults
            texture2DEnabled[0] = true;
            generation++;
        }
    }

    public void pushAttrib() {
        if (attribStackPointer >= ATTRIB_STACK_DEPTH) return;
        AttribSnapshot s = attribStack[attribStackPointer];
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
        s.colorR = colorR; s.colorG = colorG; s.colorB = colorB; s.colorA = colorA;
        System.arraycopy(texture2DEnabled, 0, s.texture2DEnabled, 0, 8);
        s.normalizeEnabled = normalizeEnabled;
        s.rescaleNormalEnabled = rescaleNormalEnabled;
        s.colorMaterialEnabled = colorMaterialEnabled;
        s.shadeModel = shadeModel;
        s.texEnvMode = texEnvMode;
        System.arraycopy(texGenEnabled, 0, s.texGenEnabled, 0, 4);
        System.arraycopy(texGenMode, 0, s.texGenMode, 0, 4);
        System.arraycopy(clipPlaneEnabled, 0, s.clipPlaneEnabled, 0, 6);
        for (int i = 0; i < 6; i++) System.arraycopy(clipPlaneEquation[i], 0, s.clipPlaneEquation[i], 0, 4);
        attribStack[attribStackPointer++] = s;
    }

    public void popAttrib() {
        if (attribStackPointer <= 0) return;
        AttribSnapshot s = attribStack[--attribStackPointer];
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
        colorR = s.colorR; colorG = s.colorG; colorB = s.colorB; colorA = s.colorA;
        System.arraycopy(s.texture2DEnabled, 0, texture2DEnabled, 0, 8);
        normalizeEnabled = s.normalizeEnabled;
        rescaleNormalEnabled = s.rescaleNormalEnabled;
        colorMaterialEnabled = s.colorMaterialEnabled;
        shadeModel = s.shadeModel;
        texEnvMode = s.texEnvMode;
        System.arraycopy(s.texGenEnabled, 0, texGenEnabled, 0, 4);
        System.arraycopy(s.texGenMode, 0, texGenMode, 0, 4);
        System.arraycopy(s.clipPlaneEnabled, 0, clipPlaneEnabled, 0, 6);
        for (int i = 0; i < 6; i++) System.arraycopy(s.clipPlaneEquation[i], 0, clipPlaneEquation[i], 0, 4);
        generation++;
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

    // ── Color (glColor4f) ───────────────────────────────────────────────
    private float colorR = 1.0f, colorG = 1.0f, colorB = 1.0f, colorA = 1.0f;

    // ── Texture 2D enable/disable (per texture unit) ────────────────────
    private final boolean[] texture2DEnabled = new boolean[8];

    // ── Normalize / RescaleNormal ───────────────────────────────────────
    private boolean normalizeEnabled = false;
    private boolean rescaleNormalEnabled = false;

    // ── Color material ──────────────────────────────────────────────────
    private boolean colorMaterialEnabled = false;

    // ── Shade model ─────────────────────────────────────────────────────
    private int shadeModel = 0x1D01; // GL_SMOOTH

    public void enableAlphaTest() { alphaTestEnabled = true; generation++; }
    public void disableAlphaTest() { alphaTestEnabled = false; generation++; }
    public void alphaFunc(int func, float ref) { alphaFunc = func; alphaRef = ref; generation++; }
    public boolean isAlphaTestEnabled() { return alphaTestEnabled; }
    public int getAlphaFunc() { return alphaFunc; }
    public float getAlphaRef() { return alphaRef; }

    // ── Lighting ────────────────────────────────────────────────────────

    public void enableLighting() { lightingEnabled = true; generation++; }
    public void disableLighting() { lightingEnabled = false; generation++; }
    public void enableLight(int light) { if (light >= 0 && light < 8) { lightEnabled[light] = true; generation++; } }
    public void disableLight(int light) { if (light >= 0 && light < 8) { lightEnabled[light] = false; generation++; } }
    public boolean isLightingEnabled() { return lightingEnabled; }
    public boolean isLightEnabled(int light) { return light >= 0 && light < 8 && lightEnabled[light]; }

    public void setLightPosition(int light, float x, float y, float z, float w) {
        if (light >= 0 && light < 2) {
            lightPosition[light][0] = x; lightPosition[light][1] = y;
            lightPosition[light][2] = z; lightPosition[light][3] = w;
            generation++;
        }
    }
    public void setLightDiffuse(int light, float r, float g, float b, float a) {
        if (light >= 0 && light < 2) {
            lightDiffuse[light][0] = r; lightDiffuse[light][1] = g;
            lightDiffuse[light][2] = b; lightDiffuse[light][3] = a;
            generation++;
        }
    }
    public void setLightAmbient(int light, float r, float g, float b, float a) {
        if (light >= 0 && light < 2) {
            lightAmbient[light][0] = r; lightAmbient[light][1] = g;
            lightAmbient[light][2] = b; lightAmbient[light][3] = a;
            generation++;
        }
    }
    public float[] getLightPosition(int light) { return light >= 0 && light < 2 ? lightPosition[light] : new float[4]; }
    public float[] getLightDiffuse(int light) { return light >= 0 && light < 2 ? lightDiffuse[light] : new float[4]; }

    public void setLightModelAmbient(float r, float g, float b, float a) {
        ambientR = r; ambientG = g; ambientB = b; ambientA = a; generation++;
    }
    public float getLightModelAmbientR() { return ambientR; }
    public float getLightModelAmbientG() { return ambientG; }
    public float getLightModelAmbientB() { return ambientB; }

    // ── Fog ─────────────────────────────────────────────────────────────

    public void enableFog() { fogEnabled = true; generation++; }
    public void disableFog() { fogEnabled = false; generation++; }
    public void setFogMode(int mode) { fogMode = mode; generation++; }
    public void setFogDensity(float density) { fogDensity = density; generation++; }
    public void setFogStart(float start) { fogStart = start; generation++; }
    public void setFogEnd(float end) { fogEnd = end; generation++; }
    public void setFogColor(float r, float g, float b, float a) { fogR = r; fogG = g; fogB = b; fogA = a; generation++; }
    public boolean isFogEnabled() { return fogEnabled; }
    public int getFogMode() { return fogMode; }
    public float getFogDensity() { return fogDensity; }
    public float getFogStart() { return fogStart; }
    public float getFogEnd() { return fogEnd; }

    // ── Color ───────────────────────────────────────────────────────────

    public void color(float r, float g, float b, float a) { colorR = r; colorG = g; colorB = b; colorA = a; generation++; }
    public void resetColor() { colorR = 1.0f; colorG = 1.0f; colorB = 1.0f; colorA = 1.0f; generation++; }
    public float getColorR() { return colorR; }
    public float getColorG() { return colorG; }
    public float getColorB() { return colorB; }
    public float getColorA() { return colorA; }

    // ── Texture 2D ──────────────────────────────────────────────────────

    public void enableTexture2D(int unit) { if (unit >= 0 && unit < 8) { texture2DEnabled[unit] = true; generation++; } }
    public void disableTexture2D(int unit) { if (unit >= 0 && unit < 8) { texture2DEnabled[unit] = false; generation++; } }
    public boolean isTexture2DEnabled(int unit) { return unit >= 0 && unit < 8 && texture2DEnabled[unit]; }

    // ── Normalize ───────────────────────────────────────────────────────

    public void enableNormalize() { normalizeEnabled = true; generation++; }
    public void disableNormalize() { normalizeEnabled = false; generation++; }
    public boolean isNormalizeEnabled() { return normalizeEnabled; }
    public void enableRescaleNormal() { rescaleNormalEnabled = true; generation++; }
    public void disableRescaleNormal() { rescaleNormalEnabled = false; generation++; }
    public boolean isRescaleNormalEnabled() { return rescaleNormalEnabled; }

    // ── Color material ──────────────────────────────────────────────────

    public void enableColorMaterial() { colorMaterialEnabled = true; generation++; }
    public void disableColorMaterial() { colorMaterialEnabled = false; generation++; }
    public boolean isColorMaterialEnabled() { return colorMaterialEnabled; }

    // ── Shade model ─────────────────────────────────────────────────────

    public void shadeModel(int model) { shadeModel = model; generation++; }
    public int getShadeModel() { return shadeModel; }

    // ── Lightmap coordinates (replaces glMultiTexCoord2f) ─────────────
    private float lightmapX = 240.0f;
    private float lightmapY = 240.0f;

    public void setLightmapCoords(float x, float y) { lightmapX = x; lightmapY = y; generation++; }
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

    public void setTexEnvMode(int mode) { texEnvMode = mode; generation++; }
    public int getTexEnvMode() { return texEnvMode; }
    public void setTexEnvColor(float r, float g, float b, float a) {
        texEnvColorR = r; texEnvColorG = g; texEnvColorB = b; texEnvColorA = a; generation++;
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

    public void enableTexGen(int coord) { if (coord >= 0 && coord < 4) { texGenEnabled[coord] = true; generation++; } }
    public void disableTexGen(int coord) { if (coord >= 0 && coord < 4) { texGenEnabled[coord] = false; generation++; } }
    public boolean isTexGenEnabled(int coord) { return coord >= 0 && coord < 4 && texGenEnabled[coord]; }
    public void setTexGenMode(int coord, int mode) { if (coord >= 0 && coord < 4) { texGenMode[coord] = mode; generation++; } }
    public int getTexGenMode(int coord) { return coord >= 0 && coord < 4 ? texGenMode[coord] : 0x2400; }
    public void setTexGenObjectPlane(int coord, float a, float b, float c, float d) {
        if (coord >= 0 && coord < 4) { texGenObjectPlane[coord][0] = a; texGenObjectPlane[coord][1] = b; texGenObjectPlane[coord][2] = c; texGenObjectPlane[coord][3] = d; generation++; }
    }
    public float[] getTexGenObjectPlane(int coord) { return coord >= 0 && coord < 4 ? texGenObjectPlane[coord] : new float[4]; }
    public void setTexGenEyePlane(int coord, float a, float b, float c, float d) {
        if (coord >= 0 && coord < 4) { texGenEyePlane[coord][0] = a; texGenEyePlane[coord][1] = b; texGenEyePlane[coord][2] = c; texGenEyePlane[coord][3] = d; generation++; }
    }
    public float[] getTexGenEyePlane(int coord) { return coord >= 0 && coord < 4 ? texGenEyePlane[coord] : new float[4]; }

    // ── Clip planes (replaces glClipPlane / glEnable(GL_CLIP_PLANEn)) ──
    // In core profile, these are implemented via gl_ClipDistance[n] in the vertex shader.
    // The plane equation is in eye space (transformed by modelview at the time of glClipPlane call).
    private final boolean[] clipPlaneEnabled = new boolean[6];
    private final float[][] clipPlaneEquation = new float[6][4];

    public void enableClipPlane(int plane) { if (plane >= 0 && plane < 6) { clipPlaneEnabled[plane] = true; generation++; } }
    public void disableClipPlane(int plane) { if (plane >= 0 && plane < 6) { clipPlaneEnabled[plane] = false; generation++; } }
    public boolean isClipPlaneEnabled(int plane) { return plane >= 0 && plane < 6 && clipPlaneEnabled[plane]; }
    public void setClipPlaneEquation(int plane, float a, float b, float c, float d) {
        if (plane >= 0 && plane < 6) {
            clipPlaneEquation[plane][0] = a; clipPlaneEquation[plane][1] = b;
            clipPlaneEquation[plane][2] = c; clipPlaneEquation[plane][3] = d;
            generation++;
        }
    }
    public float[] getClipPlaneEquation(int plane) { return plane >= 0 && plane < 6 ? clipPlaneEquation[plane] : new float[4]; }
}
