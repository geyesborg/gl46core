package com.github.gl46core.mixin;

import com.github.gl46core.gl.CoreMatrixStack;
import com.github.gl46core.gl.CoreShaderProgram;
import com.github.gl46core.gl.CoreStateTracker;
import com.github.gl46core.gl.CoreVboDrawHandler;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

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

    // ═══════════════════════════════════════════════════════════════════
    // Matrix stack operations → CoreMatrixStack
    // ═══════════════════════════════════════════════════════════════════

    /**
     * @author GL46Core
     * @reason glMatrixMode removed in core profile — track in CoreMatrixStack
     */
    @Overwrite
    public static void matrixMode(int mode) {
        CoreMatrixStack.INSTANCE.matrixMode(mode);
    }

    /**
     * @author GL46Core
     * @reason glPushMatrix removed in core profile — track in CoreMatrixStack
     */
    @Overwrite
    public static void pushMatrix() {
        CoreMatrixStack.INSTANCE.pushMatrix();
    }

    /**
     * @author GL46Core
     * @reason glPopMatrix removed in core profile — track in CoreMatrixStack
     */
    @Overwrite
    public static void popMatrix() {
        CoreMatrixStack.INSTANCE.popMatrix();
    }

    /**
     * @author GL46Core
     * @reason glLoadIdentity removed in core profile — track in CoreMatrixStack
     */
    @Overwrite
    public static void loadIdentity() {
        CoreMatrixStack.INSTANCE.loadIdentity();
    }

    /**
     * @author GL46Core
     * @reason glOrtho removed in core profile — track in CoreMatrixStack
     */
    @Overwrite
    public static void ortho(double left, double right, double bottom, double top, double zNear, double zFar) {
        CoreMatrixStack.INSTANCE.ortho(left, right, bottom, top, zNear, zFar);
    }

    /**
     * @author GL46Core
     * @reason glRotatef removed in core profile — track in CoreMatrixStack
     */
    @Overwrite
    public static void rotate(float angle, float x, float y, float z) {
        CoreMatrixStack.INSTANCE.rotate(angle, x, y, z);
    }

    /**
     * @author GL46Core
     * @reason glScalef removed in core profile — track in CoreMatrixStack
     */
    @Overwrite
    public static void scale(float x, float y, float z) {
        CoreMatrixStack.INSTANCE.scale(x, y, z);
    }

    /**
     * @author GL46Core
     * @reason glScaled removed in core profile — track in CoreMatrixStack
     */
    @Overwrite
    public static void scale(double x, double y, double z) {
        CoreMatrixStack.INSTANCE.scale(x, y, z);
    }

    /**
     * @author GL46Core
     * @reason glTranslatef removed in core profile — track in CoreMatrixStack
     */
    @Overwrite
    public static void translate(float x, float y, float z) {
        CoreMatrixStack.INSTANCE.translate(x, y, z);
    }

    /**
     * @author GL46Core
     * @reason glTranslated removed in core profile — track in CoreMatrixStack
     */
    @Overwrite
    public static void translate(double x, double y, double z) {
        CoreMatrixStack.INSTANCE.translate(x, y, z);
    }

    /**
     * @author GL46Core
     * @reason glMultMatrix removed in core profile — track in CoreMatrixStack
     */
    @Overwrite
    public static void multMatrix(FloatBuffer matrix) {
        CoreMatrixStack.INSTANCE.multMatrix(matrix);
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
        CoreStateTracker.INSTANCE.enableFog();
    }

    /**
     * @author GL46Core
     * @reason GL_FOG removed in core profile — track in CoreStateTracker
     */
    @Overwrite
    public static void disableFog() {
        CoreStateTracker.INSTANCE.disableFog();
    }

    /**
     * @author GL46Core
     * @reason glFog removed in core profile — track in CoreStateTracker
     */
    @Overwrite
    public static void setFog(int mode) {
        CoreStateTracker.INSTANCE.setFogMode(mode);
    }

    /**
     * @author GL46Core
     * @reason glFog removed in core profile — track in CoreStateTracker
     */
    @Overwrite
    public static void setFogDensity(float density) {
        CoreStateTracker.INSTANCE.setFogDensity(density);
    }

    /**
     * @author GL46Core
     * @reason glFog removed in core profile — track in CoreStateTracker
     */
    @Overwrite
    public static void setFogStart(float start) {
        CoreStateTracker.INSTANCE.setFogStart(start);
    }

    /**
     * @author GL46Core
     * @reason glFog removed in core profile — track in CoreStateTracker
     */
    @Overwrite
    public static void setFogEnd(float end) {
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
    // ═══════════════════════════════════════════════════════════════════

    /**
     * @author GL46Core
     * @reason Track color in CoreStateTracker for shader uniform
     */
    @Overwrite
    public static void color(float r, float g, float b, float a) {
        CoreStateTracker.INSTANCE.color(r, g, b, a);
    }

    /**
     * @author GL46Core
     * @reason Track color in CoreStateTracker for shader uniform
     */
    @Overwrite
    public static void color(float r, float g, float b) {
        CoreStateTracker.INSTANCE.color(r, g, b, 1.0f);
    }

    /**
     * @author GL46Core
     * @reason Track color reset in CoreStateTracker
     */
    @Overwrite
    public static void resetColor() {
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
        GL13.glActiveTexture(texture); // still needed for glTexImage2D, glTexParameteri, etc.
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
     * @reason Pass through — bindTexture still exists in core profile
     */
    @Overwrite
    public static void bindTexture(int texture) {
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
        // No-op: texture environment removed in core profile
    }

    /**
     * @author GL46Core
     * @reason glTexEnvf removed in core profile — no-op
     */
    @Overwrite
    public static void glTexEnvf(int target, int pname, float param) {
        // No-op: texture environment removed in core profile
    }

    /**
     * @author GL46Core
     * @reason glTexEnv removed in core profile — no-op
     */
    @Overwrite
    public static void glTexEnv(int target, int pname, FloatBuffer params) {
        // No-op: texture environment removed in core profile
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
     * @reason glTexImage2D still exists — pass through
     */
    @Overwrite
    public static void glTexImage2D(int target, int level, int internalFormat, int width, int height, int border, int format, int type, java.nio.IntBuffer pixels) {
        GL11.glTexImage2D(target, level, internalFormat, width, height, border, format, type, pixels);
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
     * @reason glDeleteTextures — no-op (allocateTextureImpl rebinds same ID)
     */
    @Overwrite
    public static void deleteTexture(int texture) {
        // No-op: The primary caller (TextureUtil.allocateTextureImpl) immediately
        // rebinds the same ID. Other cleanup paths are infrequent.
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
    // Display lists → no-ops (immediate mode removed in core profile)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * @author GL46Core
     * @reason glCallList removed in core profile — no-op
     */
    @Overwrite
    public static void callList(int list) {
        // No-op: display lists removed in core profile
    }

    /**
     * @author GL46Core
     * @reason glGenLists removed — return dummy ID
     */
    @Overwrite
    public static int glGenLists(int range) {
        return 1; // Dummy ID
    }

    /**
     * @author GL46Core
     * @reason glNewList removed — no-op
     */
    @Overwrite
    public static void glNewList(int list, int mode) {
        // No-op
    }

    /**
     * @author GL46Core
     * @reason glEndList removed — no-op
     */
    @Overwrite
    public static void glEndList() {
        // No-op
    }

    /**
     * @author GL46Core
     * @reason glDeleteLists removed — no-op
     */
    @Overwrite
    public static void glDeleteLists(int list, int range) {
        // No-op
    }

    // ═══════════════════════════════════════════════════════════════════
    // Immediate mode → no-ops (removed in core profile)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * @author GL46Core
     * @reason glBegin removed in core profile — route to ImmediateModeEmulator
     */
    @Overwrite
    public static void glBegin(int mode) {
        com.github.gl46core.gl.ImmediateModeEmulator.INSTANCE.syncColorFromState();
        com.github.gl46core.gl.ImmediateModeEmulator.INSTANCE.begin(mode);
    }

    /**
     * @author GL46Core
     * @reason glEnd removed in core profile — route to ImmediateModeEmulator
     */
    @Overwrite
    public static void glEnd() {
        com.github.gl46core.gl.ImmediateModeEmulator.INSTANCE.end();
    }

    /**
     * @author GL46Core
     * @reason glVertex3f removed in core profile — route to ImmediateModeEmulator
     */
    @Overwrite
    public static void glVertex3f(float x, float y, float z) {
        com.github.gl46core.gl.ImmediateModeEmulator.INSTANCE.vertex3f(x, y, z);
    }

    /**
     * @author GL46Core
     * @reason glNormal3f removed in core profile — route to ImmediateModeEmulator
     */
    @Overwrite
    public static void glNormal3f(float x, float y, float z) {
        com.github.gl46core.gl.ImmediateModeEmulator.INSTANCE.normal3f(x, y, z);
    }

    /**
     * @author GL46Core
     * @reason glTexCoord2f removed in core profile — route to ImmediateModeEmulator
     */
    @Overwrite
    public static void glTexCoord2f(float u, float v) {
        com.github.gl46core.gl.ImmediateModeEmulator.INSTANCE.texCoord2f(u, v);
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
        // No-op
    }

    /**
     * @author GL46Core
     * @reason texGen removed in core profile — no-op
     */
    @Overwrite
    public static void texGen(GlStateManager.TexGen coord, int pname, FloatBuffer params) {
        // No-op
    }

    /**
     * @author GL46Core
     * @reason GL_TEXTURE_GEN_S/T/R/Q removed in core profile — no-op
     */
    @Overwrite
    public static void enableTexGenCoord(GlStateManager.TexGen texGen) {
        // No-op
    }

    /**
     * @author GL46Core
     * @reason GL_TEXTURE_GEN_S/T/R/Q removed in core profile — no-op
     */
    @Overwrite
    public static void disableTexGenCoord(GlStateManager.TexGen texGen) {
        // No-op
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
