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
    }

    // Generation counter — increments on every state mutation.
    // CoreShaderProgram compares this to skip per-draw dirty checks.
    private int generation = 0;

    public int getGeneration() { return generation; }

    private CoreStateTracker() {
        attribStack = new AttribSnapshot[ATTRIB_STACK_DEPTH];
        for (int i = 0; i < ATTRIB_STACK_DEPTH; i++) attribStack[i] = new AttribSnapshot();
        // Default: texture unit 0 enabled
        texture2DEnabled[0] = true;
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
    public void resetColor() { colorR = -1.0f; colorG = -1.0f; colorB = -1.0f; colorA = -1.0f; generation++; }
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
    public void enableRescaleNormal() { rescaleNormalEnabled = true; generation++; }
    public void disableRescaleNormal() { rescaleNormalEnabled = false; generation++; }

    // ── Color material ──────────────────────────────────────────────────

    public void enableColorMaterial() { colorMaterialEnabled = true; generation++; }
    public void disableColorMaterial() { colorMaterialEnabled = false; generation++; }

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
}
