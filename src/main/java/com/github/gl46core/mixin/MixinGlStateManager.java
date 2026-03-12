package com.github.gl46core.mixin;

import com.github.gl46core.gl.CoreDrawHandler;
import com.github.gl46core.gl.CoreMatrixStack;
import com.github.gl46core.gl.CoreShaderProgram;
import com.github.gl46core.gl.CoreStateTracker;
import com.github.gl46core.gl.CoreTextureTracker;
import com.github.gl46core.gl.CoreVboDrawHandler;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.nio.FloatBuffer;

/**
 * Replaces all GlStateManager methods with core-profile equivalents.
 *
 * Phase 1: Matrix operations → CoreMatrixStack (JOML)
 * Phase 2: Removed fixed-function state → CoreStateTracker (software tracking)
 * Phase 3: Legacy vertex arrays, immediate mode, display lists → no-ops or redirects
 */
@Mixin(GlStateManager.class)
public abstract class MixinGlStateManager {

    @Shadow private static int activeTextureUnit;

    // GlStateManager.FogState is package-private, so we can't @Shadow it directly.
    // Cache reflection handles for the fog state fields so Celeritas (and other mods)
    // that read GlStateManager.fogState.* directly get correct values.
    @Unique
    private static Object gl46core$fogState;
    @Unique
    private static java.lang.reflect.Field gl46core$fogEnd;
    @Unique
    private static java.lang.reflect.Field gl46core$fogStart;
    @Unique
    private static java.lang.reflect.Field gl46core$fogDensity;
    @Unique
    private static java.lang.reflect.Field gl46core$fogMode;
    @Unique
    private static java.lang.reflect.Field gl46core$fogBoolState;
    @Unique
    private static java.lang.reflect.Field gl46core$boolCurrentState;

    @Unique
    private static java.lang.reflect.Field gl46core$findField(Class<?> clazz, String mcpName, String srgName) throws NoSuchFieldException {
        try {
            java.lang.reflect.Field f = clazz.getDeclaredField(mcpName);
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException e) {
            java.lang.reflect.Field f = clazz.getDeclaredField(srgName);
            f.setAccessible(true);
            return f;
        }
    }

    static {
        try {
            java.lang.reflect.Field fsField = gl46core$findField(GlStateManager.class, "fogState", "field_179155_g");
            gl46core$fogState = fsField.get(null);
            Class<?> fogStateClass = gl46core$fogState.getClass();
            gl46core$fogEnd = gl46core$findField(fogStateClass, "end", "field_179046_e");
            gl46core$fogStart = gl46core$findField(fogStateClass, "start", "field_179045_d");
            gl46core$fogDensity = gl46core$findField(fogStateClass, "density", "field_179048_c");
            gl46core$fogMode = gl46core$findField(fogStateClass, "mode", "field_179047_b");
            gl46core$fogBoolState = gl46core$findField(fogStateClass, "fog", "field_179049_a");
            Object boolState = gl46core$fogBoolState.get(gl46core$fogState);
            gl46core$boolCurrentState = gl46core$findField(boolState.getClass(), "currentState", "field_179201_b");
        } catch (Exception e) {
            throw new RuntimeException("gl46core: Failed to init fogState reflection", e);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Matrix stack operations → CoreMatrixStack
    // ═══════════════════════════════════════════════════════════════════

    /**
     * @author GL46Core
     * @reason glMatrixMode removed in core profile — track in CoreMatrixStack or record for display list
     */
    @Overwrite
    public static void matrixMode(int mode) {
        if (com.github.gl46core.gl.DisplayListCache.INSTANCE.isRecording()) {
            com.github.gl46core.gl.DisplayListCache.INSTANCE.recordMatrixMode(mode);
        } else {
            CoreMatrixStack.INSTANCE.matrixMode(mode);
        }
    }

    /**
     * @author GL46Core
     * @reason glPushMatrix removed in core profile — track in CoreMatrixStack or record for display list
     */
    @Overwrite
    public static void pushMatrix() {
        if (com.github.gl46core.gl.DisplayListCache.INSTANCE.isRecording()) {
            com.github.gl46core.gl.DisplayListCache.INSTANCE.recordPushMatrix();
        } else {
            CoreMatrixStack.INSTANCE.pushMatrix();
        }
    }

    /**
     * @author GL46Core
     * @reason glPopMatrix removed in core profile — track in CoreMatrixStack or record for display list
     */
    @Overwrite
    public static void popMatrix() {
        if (com.github.gl46core.gl.DisplayListCache.INSTANCE.isRecording()) {
            com.github.gl46core.gl.DisplayListCache.INSTANCE.recordPopMatrix();
        } else {
            CoreMatrixStack.INSTANCE.popMatrix();
        }
    }

    /**
     * @author GL46Core
     * @reason glLoadIdentity removed in core profile — track in CoreMatrixStack or record for display list
     */
    @Overwrite
    public static void loadIdentity() {
        if (com.github.gl46core.gl.DisplayListCache.INSTANCE.isRecording()) {
            com.github.gl46core.gl.DisplayListCache.INSTANCE.recordLoadIdentity();
        } else {
            CoreMatrixStack.INSTANCE.loadIdentity();
        }
    }

    /**
     * @author GL46Core
     * @reason glOrtho removed in core profile — track in CoreMatrixStack or record for display list
     */
    @Overwrite
    public static void ortho(double left, double right, double bottom, double top, double zNear, double zFar) {
        if (com.github.gl46core.gl.DisplayListCache.INSTANCE.isRecording()) {
            com.github.gl46core.gl.DisplayListCache.INSTANCE.recordOrtho(left, right, bottom, top, zNear, zFar);
        } else {
            CoreMatrixStack.INSTANCE.ortho(left, right, bottom, top, zNear, zFar);
        }
    }

    /**
     * @author GL46Core
     * @reason glRotatef removed in core profile — track in CoreMatrixStack or record for display list
     */
    @Overwrite
    public static void rotate(float angle, float x, float y, float z) {
        if (com.github.gl46core.gl.DisplayListCache.INSTANCE.isRecording()) {
            com.github.gl46core.gl.DisplayListCache.INSTANCE.recordRotate(angle, x, y, z);
        } else {
            CoreMatrixStack.INSTANCE.rotate(angle, x, y, z);
        }
    }

    /**
     * @author GL46Core
     * @reason glScalef removed in core profile — track in CoreMatrixStack or record for display list
     */
    @Overwrite
    public static void scale(float x, float y, float z) {
        if (com.github.gl46core.gl.DisplayListCache.INSTANCE.isRecording()) {
            com.github.gl46core.gl.DisplayListCache.INSTANCE.recordScale(x, y, z);
        } else {
            CoreMatrixStack.INSTANCE.scale(x, y, z);
        }
    }

    /**
     * @author GL46Core
     * @reason glScaled removed in core profile — track in CoreMatrixStack or record for display list
     */
    @Overwrite
    public static void scale(double x, double y, double z) {
        if (com.github.gl46core.gl.DisplayListCache.INSTANCE.isRecording()) {
            com.github.gl46core.gl.DisplayListCache.INSTANCE.recordScale(x, y, z);
        } else {
            CoreMatrixStack.INSTANCE.scale(x, y, z);
        }
    }

    /**
     * @author GL46Core
     * @reason glTranslatef removed in core profile — track in CoreMatrixStack or record for display list
     */
    @Overwrite
    public static void translate(float x, float y, float z) {
        if (com.github.gl46core.gl.DisplayListCache.INSTANCE.isRecording()) {
            com.github.gl46core.gl.DisplayListCache.INSTANCE.recordTranslate(x, y, z);
        } else {
            CoreMatrixStack.INSTANCE.translate(x, y, z);
        }
    }

    /**
     * @author GL46Core
     * @reason glTranslated removed in core profile — track in CoreMatrixStack or record for display list
     */
    @Overwrite
    public static void translate(double x, double y, double z) {
        if (com.github.gl46core.gl.DisplayListCache.INSTANCE.isRecording()) {
            com.github.gl46core.gl.DisplayListCache.INSTANCE.recordTranslate(x, y, z);
        } else {
            CoreMatrixStack.INSTANCE.translate(x, y, z);
        }
    }

    /**
     * @author GL46Core
     * @reason glMultMatrix removed in core profile — track in CoreMatrixStack or record for display list
     */
    @Overwrite
    public static void multMatrix(FloatBuffer matrix) {
        if (com.github.gl46core.gl.DisplayListCache.INSTANCE.isRecording()) {
            com.github.gl46core.gl.DisplayListCache.INSTANCE.recordMultMatrix(matrix);
        } else {
            CoreMatrixStack.INSTANCE.multMatrix(matrix);
        }
    }

    /**
     * @author GL46Core
     * @reason glGetFloat for matrix queries — read from CoreMatrixStack
     */
    @Overwrite
    public static void getFloat(int pname, FloatBuffer params) {
        if (CoreMatrixStack.INSTANCE.isMatrixQuery(pname)) {
            CoreMatrixStack.INSTANCE.getFloat(pname, params);
        } else {
            GL11.glGetFloatv(pname, params);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Alpha test → CoreStateTracker
    // ═══════════════════════════════════════════════════════════════════

    /**
     * @author GL46Core
     * @reason GL_ALPHA_TEST removed in core profile — track in CoreStateTracker
     */
    @Overwrite
    public static void enableAlpha() {
        CoreStateTracker.INSTANCE.enableAlphaTest();
    }

    /**
     * @author GL46Core
     * @reason GL_ALPHA_TEST removed in core profile — track in CoreStateTracker
     */
    @Overwrite
    public static void disableAlpha() {
        CoreStateTracker.INSTANCE.disableAlphaTest();
    }

    /**
     * @author GL46Core
     * @reason glAlphaFunc removed in core profile — track in CoreStateTracker
     */
    @Overwrite
    public static void alphaFunc(int func, float ref) {
        CoreStateTracker.INSTANCE.alphaFunc(func, ref);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Lighting → CoreStateTracker
    // ═══════════════════════════════════════════════════════════════════

    /**
     * @author GL46Core
     * @reason GL_LIGHTING removed in core profile — track in CoreStateTracker
     */
    @Overwrite
    public static void enableLighting() {
        CoreStateTracker.INSTANCE.enableLighting();
    }

    /**
     * @author GL46Core
     * @reason GL_LIGHTING removed in core profile — track in CoreStateTracker
     */
    @Overwrite
    public static void disableLighting() {
        CoreStateTracker.INSTANCE.disableLighting();
    }

    /**
     * @author GL46Core
     * @reason GL_LIGHTx removed in core profile — track in CoreStateTracker
     */
    @Overwrite
    public static void enableLight(int light) {
        CoreStateTracker.INSTANCE.enableLight(light);
    }

    /**
     * @author GL46Core
     * @reason GL_LIGHTx removed in core profile — track in CoreStateTracker
     */
    @Overwrite
    public static void disableLight(int light) {
        CoreStateTracker.INSTANCE.disableLight(light);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Fog → CoreStateTracker
    // ═══════════════════════════════════════════════════════════════════

    /**
     * @author GL46Core
     * @reason GL_FOG removed in core profile — track in CoreStateTracker
     */
    @Overwrite
    public static void enableFog() {
        try { gl46core$boolCurrentState.setBoolean(gl46core$fogBoolState.get(gl46core$fogState), true); } catch (Exception ignored) {}
        CoreStateTracker.INSTANCE.enableFog();
    }

    /**
     * @author GL46Core
     * @reason GL_FOG removed in core profile — track in CoreStateTracker
     */
    @Overwrite
    public static void disableFog() {
        try { gl46core$boolCurrentState.setBoolean(gl46core$fogBoolState.get(gl46core$fogState), false); } catch (Exception ignored) {}
        CoreStateTracker.INSTANCE.disableFog();
    }

    /**
     * @author GL46Core
     * @reason glFog removed in core profile — track in CoreStateTracker
     */
    @Overwrite
    public static void setFog(int mode) {
        try { gl46core$fogMode.setInt(gl46core$fogState, mode); } catch (Exception ignored) {}
        CoreStateTracker.INSTANCE.setFogMode(mode);
    }

    /**
     * @author GL46Core
     * @reason glFog removed in core profile — track in CoreStateTracker
     */
    @Overwrite
    public static void setFogDensity(float density) {
        try { gl46core$fogDensity.setFloat(gl46core$fogState, density); } catch (Exception ignored) {}
        CoreStateTracker.INSTANCE.setFogDensity(density);
    }

    /**
     * @author GL46Core
     * @reason glFog removed in core profile — track in CoreStateTracker
     */
    @Overwrite
    public static void setFogStart(float start) {
        try { gl46core$fogStart.setFloat(gl46core$fogState, start); } catch (Exception ignored) {}
        CoreStateTracker.INSTANCE.setFogStart(start);
    }

    /**
     * @author GL46Core
     * @reason glFog removed in core profile — track in CoreStateTracker
     */
    @Overwrite
    public static void setFogEnd(float end) {
        try { gl46core$fogEnd.setFloat(gl46core$fogState, end); } catch (Exception ignored) {}
        CoreStateTracker.INSTANCE.setFogEnd(end);
    }

    /**
     * @author GL46Core
     * @reason glFogfv(GL_FOG_COLOR) removed in core — track in CoreStateTracker
     */
    @Overwrite
    public static void glFog(int pname, FloatBuffer params) {
        if (pname == 0x0B66 && params.remaining() >= 4) { // GL_FOG_COLOR
            CoreStateTracker.INSTANCE.setFogColor(params.get(0), params.get(1), params.get(2), params.get(3));
        }
    }

    /**
     * @author GL46Core
     * @reason glFogi removed in core profile — track fog mode in CoreStateTracker
     */
    @Overwrite
    public static void glFogi(int pname, int param) {
        if (pname == 0x0B65) { // GL_FOG_MODE
            CoreStateTracker.INSTANCE.setFogMode(param);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Color → CoreStateTracker + real GL
    //
    // Vanilla GlStateManager caches the color and skips the actual GL call
    // when the value hasn't changed. Mods like ModernSplash rely on this:
    // they set the real color via direct GL11.glColor3ub (which bypasses
    // the GlStateManager cache) then FontRenderer's GlStateManager.color()
    // gets skipped because the cache still holds the same value from a
    // previous call.  We replicate that dirty-check here.
    // ═══════════════════════════════════════════════════════════════════

    @Unique private static float gl46core$cachedR = 1.0f;
    @Unique private static float gl46core$cachedG = 1.0f;
    @Unique private static float gl46core$cachedB = 1.0f;
    @Unique private static float gl46core$cachedA = 1.0f;

    /**
     * @author GL46Core
     * @reason Track color in CoreStateTracker for shader uniform (with dirty check)
     */
    @Overwrite
    public static void color(float r, float g, float b, float a) {
        if (r == gl46core$cachedR && g == gl46core$cachedG
                && b == gl46core$cachedB && a == gl46core$cachedA) return;
        gl46core$cachedR = r; gl46core$cachedG = g; gl46core$cachedB = b; gl46core$cachedA = a;
        CoreStateTracker.INSTANCE.color(r, g, b, a);
        if (com.github.gl46core.gl.DisplayListCache.INSTANCE.isRecording()) {
            com.github.gl46core.gl.DisplayListCache.INSTANCE.syncColorFromState();
        }
    }

    /**
     * @author GL46Core
     * @reason Track color in CoreStateTracker for shader uniform (with dirty check)
     */
    @Overwrite
    public static void color(float r, float g, float b) {
        color(r, g, b, 1.0f);
    }

    /**
     * @author GL46Core
     * @reason Track color reset in CoreStateTracker
     */
    @Overwrite
    public static void resetColor() {
        gl46core$cachedR = gl46core$cachedG = gl46core$cachedB = gl46core$cachedA = -1.0f;
        CoreStateTracker.INSTANCE.resetColor();
    }

    // ═══════════════════════════════════════════════════════════════════
    // Texture 2D → CoreStateTracker
    // ═══════════════════════════════════════════════════════════════════

    /**
     * @author GL46Core
     * @reason GL_TEXTURE_2D enable tracked in CoreStateTracker for shader
     */
    @Overwrite
    public static void enableTexture2D() {
        CoreStateTracker.INSTANCE.enableTexture2D(activeTextureUnit - GL13.GL_TEXTURE0);
    }

    /**
     * @author GL46Core
     * @reason GL_TEXTURE_2D disable tracked in CoreStateTracker for shader
     */
    @Overwrite
    public static void disableTexture2D() {
        CoreStateTracker.INSTANCE.disableTexture2D(activeTextureUnit - GL13.GL_TEXTURE0);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Texture unit activation — real GL (still exists in core)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * @author GL46Core
     * @reason Pass through to real GL and track active unit
     */
    @Overwrite
    public static void setActiveTexture(int texture) {
        activeTextureUnit = texture;
        CoreStateTracker.INSTANCE.setActiveTextureUnit(texture - GL13.GL_TEXTURE0);
        GL13.glActiveTexture(texture);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Normalize / RescaleNormal → CoreStateTracker
    // ═══════════════════════════════════════════════════════════════════

    /**
     * @author GL46Core
     * @reason GL_NORMALIZE removed in core profile — track in CoreStateTracker
     */
    @Overwrite
    public static void enableNormalize() {
        CoreStateTracker.INSTANCE.enableNormalize();
    }

    /**
     * @author GL46Core
     * @reason GL_NORMALIZE removed in core profile — track in CoreStateTracker
     */
    @Overwrite
    public static void disableNormalize() {
        CoreStateTracker.INSTANCE.disableNormalize();
    }

    /**
     * @author GL46Core
     * @reason GL_RESCALE_NORMAL removed in core profile — track in CoreStateTracker
     */
    @Overwrite
    public static void enableRescaleNormal() {
        CoreStateTracker.INSTANCE.enableRescaleNormal();
    }

    /**
     * @author GL46Core
     * @reason GL_RESCALE_NORMAL removed in core profile — track in CoreStateTracker
     */
    @Overwrite
    public static void disableRescaleNormal() {
        CoreStateTracker.INSTANCE.disableRescaleNormal();
    }

    // ═══════════════════════════════════════════════════════════════════
    // Color material → CoreStateTracker
    // ═══════════════════════════════════════════════════════════════════

    /**
     * @author GL46Core
     * @reason GL_COLOR_MATERIAL removed in core profile — track in CoreStateTracker
     */
    @Overwrite
    public static void enableColorMaterial() {
        CoreStateTracker.INSTANCE.enableColorMaterial();
    }

    /**
     * @author GL46Core
     * @reason GL_COLOR_MATERIAL removed in core profile — track in CoreStateTracker
     */
    @Overwrite
    public static void disableColorMaterial() {
        CoreStateTracker.INSTANCE.disableColorMaterial();
    }

    /**
     * @author GL46Core
     * @reason glColorMaterial removed in core profile — no-op
     */
    @Overwrite
    public static void colorMaterial(int face, int mode) {
        // No-op: color material is tracked as enabled/disabled only
    }

    // ═══════════════════════════════════════════════════════════════════
    // Shade model → CoreStateTracker
    // ═══════════════════════════════════════════════════════════════════

    /**
     * @author GL46Core
     * @reason glShadeModel removed in core profile — track in CoreStateTracker
     */
    @Overwrite
    public static void shadeModel(int mode) {
        CoreStateTracker.INSTANCE.shadeModel(mode);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Core-profile pass-through methods (still exist in core)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * @author GL46Core
     * @reason Pass through — depth test still exists in core profile
     */
    @Overwrite
    public static void enableDepth() {
        GL11.glEnable(GL11.GL_DEPTH_TEST);
    }

    /**
     * @author GL46Core
     * @reason Pass through — depth test still exists in core profile
     */
    @Overwrite
    public static void disableDepth() {
        GL11.glDisable(GL11.GL_DEPTH_TEST);
    }

    /**
     * @author GL46Core
     * @reason Pass through — depth func still exists in core profile
     */
    @Overwrite
    public static void depthFunc(int func) {
        GL11.glDepthFunc(func);
    }

    /**
     * @author GL46Core
     * @reason Pass through — depth mask still exists in core profile
     */
    @Overwrite
    public static void depthMask(boolean flag) {
        GL11.glDepthMask(flag);
    }

    /**
     * @author GL46Core
     * @reason Pass through — blend still exists in core profile
     */
    @Overwrite
    public static void enableBlend() {
        GL11.glEnable(GL11.GL_BLEND);
    }

    /**
     * @author GL46Core
     * @reason Pass through — blend still exists in core profile
     */
    @Overwrite
    public static void disableBlend() {
        GL11.glDisable(GL11.GL_BLEND);
    }

    /**
     * @author GL46Core
     * @reason Pass through — blendFunc still exists in core profile
     */
    @Overwrite
    public static void blendFunc(int sfactor, int dfactor) {
        GL11.glBlendFunc(sfactor, dfactor);
    }

    /**
     * @author GL46Core
     * @reason Pass through — blendFuncSeparate still exists in core profile
     */
    @Overwrite
    public static void tryBlendFuncSeparate(int srcFactor, int dstFactor, int srcFactorAlpha, int dstFactorAlpha) {
        GL14.glBlendFuncSeparate(srcFactor, dstFactor, srcFactorAlpha, dstFactorAlpha);
    }

    /**
     * @author GL46Core
     * @reason Pass through — cull face still exists in core profile
     */
    @Overwrite
    public static void enableCull() {
        GL11.glEnable(GL11.GL_CULL_FACE);
    }

    /**
     * @author GL46Core
     * @reason Pass through — cull face still exists in core profile
     */
    @Overwrite
    public static void disableCull() {
        GL11.glDisable(GL11.GL_CULL_FACE);
    }

    /**
     * @author GL46Core
     * @reason Pass through — cull face mode still exists in core profile
     */
    @Overwrite
    public static void cullFace(int mode) {
        GL11.glCullFace(mode);
    }

    /**
     * @author GL46Core
     * @reason Pass through — polygon offset still exists in core profile
     */
    @Overwrite
    public static void enablePolygonOffset() {
        GL11.glEnable(0x8037); // GL_POLYGON_OFFSET_FILL
    }

    /**
     * @author GL46Core
     * @reason Pass through — polygon offset still exists in core profile
     */
    @Overwrite
    public static void disablePolygonOffset() {
        GL11.glDisable(0x8037); // GL_POLYGON_OFFSET_FILL
    }

    /**
     * @author GL46Core
     * @reason Pass through — polygon offset still exists in core profile
     */
    @Overwrite
    public static void doPolygonOffset(float factor, float units) {
        GL11.glPolygonOffset(factor, units);
    }

    /**
     * @author GL46Core
     * @reason Pass through — color mask still exists in core profile
     */
    @Overwrite
    public static void colorMask(boolean r, boolean g, boolean b, boolean a) {
        GL11.glColorMask(r, g, b, a);
    }

    /**
     * @author GL46Core
     * @reason Pass through — clear color still exists in core profile
     */
    @Overwrite
    public static void clearColor(float r, float g, float b, float a) {
        GL11.glClearColor(r, g, b, a);
    }

    /**
     * @author GL46Core
     * @reason Pass through — clear still exists in core profile
     */
    @Overwrite
    public static void clear(int mask) {
        CoreShaderProgram.endFrame();
        GL11.glClear(mask);
    }

    /**
     * @author GL46Core
     * @reason Pass through — viewport still exists in core profile
     */
    @Overwrite
    public static void viewport(int x, int y, int width, int height) {
        GL11.glViewport(x, y, width, height);
    }

    /**
     * @author GL46Core
     * @reason glPolygonMode still exists but force GL_FRONT_AND_BACK in core
     */
    @Overwrite
    public static void glPolygonMode(int face, int mode) {
        // Core profile only supports GL_FRONT_AND_BACK
        GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, mode);
    }

    /**
     * @author GL46Core
     * @reason glLineWidth still exists in core profile
     */
    @Overwrite
    public static void glLineWidth(float width) {
        GL11.glLineWidth(width);
    }

    /**
     * @author GL46Core
     * @reason Cancel any pending deletion (compat-profile "delete + re-bind" pattern),
     *         then pass through to the real glBindTexture.
     */
    @Overwrite
    public static void bindTexture(int texture) {
        CoreTextureTracker.cancelDeletion(texture);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Legacy no-ops (removed in core profile)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * @author GL46Core
     * @reason glTexEnvi removed in core profile — no-op
     */
    @Overwrite
    public static void glTexEnvi(int target, int pname, int param) {
        if (pname == 0x2200) { // GL_TEXTURE_ENV_MODE
            CoreStateTracker.INSTANCE.setTexEnvMode(param);
        }
    }

    /**
     * @author GL46Core
     * @reason glTexEnvf removed in core profile — no-op
     */
    @Overwrite
    public static void glTexEnvf(int target, int pname, float param) {
        // TexEnv float params (e.g. GL_RGB_SCALE) — not commonly needed
    }

    /**
     * @author GL46Core
     * @reason glTexEnv removed in core profile — no-op
     */
    @Overwrite
    public static void glTexEnv(int target, int pname, FloatBuffer params) {
        if (pname == 0x2201 && params.remaining() >= 4) { // GL_TEXTURE_ENV_COLOR
            CoreStateTracker.INSTANCE.setTexEnvColor(
                    params.get(params.position()), params.get(params.position() + 1),
                    params.get(params.position() + 2), params.get(params.position() + 3));
        }
    }

    /**
     * @author GL46Core
     * @reason glTexParameteri still exists — pass through
     */
    @Overwrite
    public static void glTexParameteri(int target, int pname, int param) {
        GL11.glTexParameteri(target, pname, param);
    }

    /**
     * @author GL46Core
     * @reason glTexParameterf still exists — pass through
     */
    @Overwrite
    public static void glTexParameterf(int target, int pname, float param) {
        GL11.glTexParameterf(target, pname, param);
    }

    /**
     * @author GL46Core
     * @reason Convert deprecated GL_ALPHA/GL_LUMINANCE formats for core profile
     */
    @Overwrite
    public static void glTexImage2D(int target, int level, int internalFormat, int width, int height, int border, int format, int type, java.nio.IntBuffer pixels) {
        com.github.gl46core.gl.LegacyGLRedirects.glTexImage2D_IntBuffer(target, level, internalFormat, width, height, border, format, type, pixels);
    }

    /**
     * @author GL46Core
     * @reason glTexSubImage2D still exists — pass through
     */
    @Overwrite
    public static void glTexSubImage2D(int target, int level, int xOffset, int yOffset, int width, int height, int format, int type, java.nio.IntBuffer pixels) {
        GL11.glTexSubImage2D(target, level, xOffset, yOffset, width, height, format, type, pixels);
    }

    /**
     * @author GL46Core
     * @reason glGetTexImage still exists — pass through
     */
    @Overwrite
    public static void glGetTexImage(int target, int level, int format, int type, java.nio.IntBuffer pixels) {
        GL11.glGetTexImage(target, level, format, type, pixels);
    }

    /**
     * @author GL46Core
     * @reason Defer deletion so that the compat-profile "delete + re-bind" pattern
     *         (used by TextureUtil.allocateTextureImpl) works in core profile.
     */
    @Overwrite
    public static void deleteTexture(int texture) {
        if (texture > 0) {
            CoreTextureTracker.markForDeletion(texture);
        }
    }

    /**
     * @author GL46Core
     * @reason glGenTextures still exists — pass through
     */
    @Overwrite
    public static int generateTexture() {
        return GL11.glGenTextures();
    }

    // ═══════════════════════════════════════════════════════════════════
    // Legacy vertex arrays → no-ops or redirects to CoreVboDrawHandler
    // ═══════════════════════════════════════════════════════════════════

    /**
     * @author GL46Core
     * @reason glEnableClientState removed in core — track in CoreVboDrawHandler
     */
    @Overwrite
    public static void glEnableClientState(int cap) {
        CoreVboDrawHandler.glEnableClientState(cap);
    }

    /**
     * @author GL46Core
     * @reason glDisableClientState removed in core — track in CoreVboDrawHandler
     */
    @Overwrite
    public static void glDisableClientState(int cap) {
        CoreVboDrawHandler.glDisableClientState(cap);
    }

    /**
     * @author GL46Core
     * @reason glVertexPointer removed in core — track in CoreVboDrawHandler
     */
    @Overwrite
    public static void glVertexPointer(int size, int type, int stride, int offset) {
        CoreVboDrawHandler.glVertexPointer(size, type, stride, offset);
    }

    /**
     * @author GL46Core
     * @reason glColorPointer removed in core — track in CoreVboDrawHandler
     */
    @Overwrite
    public static void glColorPointer(int size, int type, int stride, int offset) {
        CoreVboDrawHandler.glColorPointer(size, type, stride, offset);
    }

    /**
     * @author GL46Core
     * @reason glTexCoordPointer removed in core — track in CoreVboDrawHandler
     */
    @Overwrite
    public static void glTexCoordPointer(int size, int type, int stride, int offset) {
        CoreVboDrawHandler.glTexCoordPointer(size, type, stride, offset);
    }

    /**
     * @author GL46Core
     * @reason glNormalPointer removed in core — no-op
     */
    @Overwrite
    public static void glNormalPointer(int type, int stride, java.nio.ByteBuffer buffer) {
        // No-op: legacy vertex arrays removed in core profile
    }

    /**
     * @author GL46Core
     * @reason glVertexPointer (ByteBuffer) removed in core — no-op
     */
    @Overwrite
    public static void glVertexPointer(int size, int type, int stride, java.nio.ByteBuffer buffer) {
        // No-op: legacy vertex arrays removed in core profile
    }

    /**
     * @author GL46Core
     * @reason glColorPointer (ByteBuffer) removed in core — no-op
     */
    @Overwrite
    public static void glColorPointer(int size, int type, int stride, java.nio.ByteBuffer buffer) {
        // No-op: legacy vertex arrays removed in core profile
    }

    /**
     * @author GL46Core
     * @reason glTexCoordPointer (ByteBuffer) removed in core — no-op
     */
    @Overwrite
    public static void glTexCoordPointer(int size, int type, int stride, java.nio.ByteBuffer buffer) {
        // No-op: legacy vertex arrays removed in core profile
    }

    // ═══════════════════════════════════════════════════════════════════
    // glDrawArrays — route terrain VBO draws through core-profile handler
    // ═══════════════════════════════════════════════════════════════════

    /**
     * @author GL46Core
     * @reason Route VBO draws through CoreVboDrawHandler for shader + VAO setup.
     *         Otherwise pass through to real GL.
     */
    @Overwrite
    public static void glDrawArrays(int mode, int first, int count) {
        if (com.github.gl46core.gl.CoreVboDrawHandler.isTerrainVaoBound()) {
            com.github.gl46core.gl.CoreVboDrawHandler.draw(mode, first, count, true);
        } else if (com.github.gl46core.gl.CoreVboDrawHandler.isVboBound()) {
            // General VBO path (sky, etc.) — use tracked legacy vertex array state
            com.github.gl46core.gl.CoreVboDrawHandler.draw(mode, first, count, false);
        } else {
            org.lwjgl.opengl.GL11.glDrawArrays(mode, first, count);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // glLight / glLightModel — track for shader lighting emulation
    // ═══════════════════════════════════════════════════════════════════

    /**
     * @author GL46Core
     * @reason glLight removed in core profile — track params in CoreStateTracker.
     *         GL_POSITION is transformed by the current modelview matrix.
     */
    @Overwrite
    public static void glLight(int light, int pname, FloatBuffer params) {
        int idx = light - 0x4000; // GL_LIGHT0 = 0x4000
        if (idx < 0 || idx > 1) return;
        CoreStateTracker state = CoreStateTracker.INSTANCE;
        if (pname == 0x1203 && params.remaining() >= 4) { // GL_POSITION
            // Transform by current modelview matrix (as fixed-function GL does)
            float x = params.get(params.position());
            float y = params.get(params.position() + 1);
            float z = params.get(params.position() + 2);
            float w = params.get(params.position() + 3);
            org.joml.Matrix4f mv = CoreMatrixStack.INSTANCE.getModelView();
            float ex = mv.m00() * x + mv.m10() * y + mv.m20() * z + mv.m30() * w;
            float ey = mv.m01() * x + mv.m11() * y + mv.m21() * z + mv.m31() * w;
            float ez = mv.m02() * x + mv.m12() * y + mv.m22() * z + mv.m32() * w;
            float ew = mv.m03() * x + mv.m13() * y + mv.m23() * z + mv.m33() * w;
            state.setLightPosition(idx, ex, ey, ez, ew);
        } else if (pname == 0x1201 && params.remaining() >= 4) { // GL_DIFFUSE
            state.setLightDiffuse(idx, params.get(params.position()), params.get(params.position() + 1),
                    params.get(params.position() + 2), params.get(params.position() + 3));
        } else if (pname == 0x1200 && params.remaining() >= 4) { // GL_AMBIENT
            state.setLightAmbient(idx, params.get(params.position()), params.get(params.position() + 1),
                    params.get(params.position() + 2), params.get(params.position() + 3));
        }
    }

    /**
     * @author GL46Core
     * @reason glLightModel removed in core profile — track ambient in CoreStateTracker
     */
    @Overwrite
    public static void glLightModel(int pname, FloatBuffer params) {
        if (pname == 0x0B53 && params.remaining() >= 4) { // GL_LIGHT_MODEL_AMBIENT
            CoreStateTracker.INSTANCE.setLightModelAmbient(
                    params.get(params.position()), params.get(params.position() + 1),
                    params.get(params.position() + 2), params.get(params.position() + 3));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Display lists → emulated via VAO/VBO (record at glNewList, replay at glCallList)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * @author GL46Core
     * @reason glCallList removed in core profile — replay recorded geometry as VAO/VBO
     */
    @Overwrite
    public static void callList(int list) {
        com.github.gl46core.gl.DisplayListCache.INSTANCE.callList(list);
    }

    /**
     * @author GL46Core
     * @reason glGenLists removed — return first ID of contiguous block for our cache
     */
    @Overwrite
    public static int glGenLists(int range) {
        return com.github.gl46core.gl.DisplayListCache.INSTANCE.genLists(range);
    }

    /**
     * @author GL46Core
     * @reason glNewList removed — start recording geometry for VAO/VBO replay
     */
    @Overwrite
    public static void glNewList(int list, int mode) {
        com.github.gl46core.gl.DisplayListCache.INSTANCE.startRecording(list, mode);
    }

    /**
     * @author GL46Core
     * @reason glEndList removed — finish recording
     */
    @Overwrite
    public static void glEndList() {
        com.github.gl46core.gl.DisplayListCache.INSTANCE.endRecording();
    }

    /**
     * @author GL46Core
     * @reason glDeleteLists removed — remove from cache
     */
    @Overwrite
    public static void glDeleteLists(int list, int range) {
        com.github.gl46core.gl.DisplayListCache.INSTANCE.deleteLists(list, range);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Immediate mode → no-ops (removed in core profile)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * @author GL46Core
     * @reason glBegin removed in core profile — route to ImmediateModeEmulator or DisplayListCache
     */
    @Overwrite
    public static void glBegin(int mode) {
        if (com.github.gl46core.gl.DisplayListCache.INSTANCE.isRecording()) {
            com.github.gl46core.gl.DisplayListCache.INSTANCE.syncColorFromState();
            com.github.gl46core.gl.DisplayListCache.INSTANCE.recordBegin(mode);
        } else {
            com.github.gl46core.gl.ImmediateModeEmulator.INSTANCE.syncColorFromState();
            com.github.gl46core.gl.ImmediateModeEmulator.INSTANCE.begin(mode);
        }
    }

    /**
     * @author GL46Core
     * @reason glEnd removed in core profile — route to ImmediateModeEmulator or DisplayListCache
     */
    @Overwrite
    public static void glEnd() {
        if (com.github.gl46core.gl.DisplayListCache.INSTANCE.isRecording()) {
            com.github.gl46core.gl.DisplayListCache.INSTANCE.recordEnd();
        } else {
            com.github.gl46core.gl.ImmediateModeEmulator.INSTANCE.end();
        }
    }

    /**
     * @author GL46Core
     * @reason glVertex3f removed in core profile — route to ImmediateModeEmulator or DisplayListCache
     */
    @Overwrite
    public static void glVertex3f(float x, float y, float z) {
        if (com.github.gl46core.gl.DisplayListCache.INSTANCE.isRecording()) {
            com.github.gl46core.gl.DisplayListCache.INSTANCE.recordVertex(x, y, z);
        } else {
            com.github.gl46core.gl.ImmediateModeEmulator.INSTANCE.vertex3f(x, y, z);
        }
    }

    /**
     * @author GL46Core
     * @reason glNormal3f removed in core profile — route to ImmediateModeEmulator or DisplayListCache
     */
    @Overwrite
    public static void glNormal3f(float x, float y, float z) {
        if (com.github.gl46core.gl.DisplayListCache.INSTANCE.isRecording()) {
            com.github.gl46core.gl.DisplayListCache.INSTANCE.recordNormal(x, y, z);
        } else {
            com.github.gl46core.gl.ImmediateModeEmulator.INSTANCE.normal3f(x, y, z);
        }
    }

    /**
     * @author GL46Core
     * @reason glTexCoord2f removed in core profile — route to ImmediateModeEmulator or DisplayListCache
     */
    @Overwrite
    public static void glTexCoord2f(float u, float v) {
        if (com.github.gl46core.gl.DisplayListCache.INSTANCE.isRecording()) {
            com.github.gl46core.gl.DisplayListCache.INSTANCE.recordTexCoord(u, v);
        } else {
            com.github.gl46core.gl.ImmediateModeEmulator.INSTANCE.texCoord2f(u, v);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // TexGen → no-op (removed in core profile)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * @author GL46Core
     * @reason texGen removed in core profile — no-op
     */
    @Overwrite
    public static void texGen(GlStateManager.TexGen coord, int pname) {
        int idx = texGenCoordIndex(coord);
        if (idx >= 0 && pname >= 0x2400 && pname <= 0x2402) { // GL_EYE_LINEAR..GL_SPHERE_MAP
            CoreStateTracker.INSTANCE.setTexGenMode(idx, pname);
        }
    }

    /**
     * @author GL46Core
     * @reason texGen removed in core profile — emulated via CoreStateTracker
     */
    @Overwrite
    public static void texGen(GlStateManager.TexGen coord, int pname, FloatBuffer params) {
        int idx = texGenCoordIndex(coord);
        if (idx < 0 || params.remaining() < 4) return;
        float a = params.get(params.position()), b = params.get(params.position() + 1);
        float c = params.get(params.position() + 2), d = params.get(params.position() + 3);
        if (pname == 0x2501) { // GL_OBJECT_PLANE
            CoreStateTracker.INSTANCE.setTexGenObjectPlane(idx, a, b, c, d);
        } else if (pname == 0x2502) { // GL_EYE_PLANE
            CoreStateTracker.INSTANCE.setTexGenEyePlane(idx, a, b, c, d);
        } else if (pname == 0x2500) { // GL_TEXTURE_GEN_MODE
            CoreStateTracker.INSTANCE.setTexGenMode(idx, (int) a);
        }
    }

    /**
     * @author GL46Core
     * @reason GL_TEXTURE_GEN_S/T/R/Q removed in core profile — emulated via CoreStateTracker
     */
    @Overwrite
    public static void enableTexGenCoord(GlStateManager.TexGen texGen) {
        int idx = texGenCoordIndex(texGen);
        if (idx >= 0) CoreStateTracker.INSTANCE.enableTexGen(idx);
    }

    /**
     * @author GL46Core
     * @reason GL_TEXTURE_GEN_S/T/R/Q removed in core profile — emulated via CoreStateTracker
     */
    @Overwrite
    public static void disableTexGenCoord(GlStateManager.TexGen texGen) {
        int idx = texGenCoordIndex(texGen);
        if (idx >= 0) CoreStateTracker.INSTANCE.disableTexGen(idx);
    }

    private static int texGenCoordIndex(GlStateManager.TexGen coord) {
        return switch (coord) {
            case S -> 0;
            case T -> 1;
            case R -> 2;
            case Q -> 3;
        };
    }

    // ═══════════════════════════════════════════════════════════════════
    // pushAttrib / popAttrib → CoreStateTracker
    // ═══════════════════════════════════════════════════════════════════

    /**
     * @author GL46Core
     * @reason glPushAttrib removed in core profile — save tracked state
     */
    @Overwrite
    public static void pushAttrib() {
        CoreStateTracker.INSTANCE.pushAttrib();
    }

    /**
     * @author GL46Core
     * @reason glPopAttrib removed in core profile — restore tracked state
     */
    @Overwrite
    public static void popAttrib() {
        CoreStateTracker.INSTANCE.popAttrib();
    }
}
