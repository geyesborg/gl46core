package com.github.gl46core.gl;

import java.nio.FloatBuffer;

/**
 * Static redirect targets for legacy GL11/GL13/GLU calls that bypass GlStateManager.
 * The LegacyGLTransformer ASM-redirects direct GL calls in Minecraft/Forge/mod code
 * to these methods, which delegate to CoreMatrixStack, CoreStateTracker, and
 * ImmediateModeEmulator as appropriate.
 *
 * Since this class is in the com.github.gl46core package, it is excluded from
 * ASM transformation — so calls to real GL from within these methods are safe
 * and won't recurse.
 */
public final class LegacyGLRedirects {

    private LegacyGLRedirects() {}

    // ═══════════════════════════════════════════════════════════════════
    // Matrix operations
    // ═══════════════════════════════════════════════════════════════════

    public static void glMatrixMode(int mode) {
        CoreMatrixStack.INSTANCE.matrixMode(mode);
    }

    public static void glPushMatrix() {
        CoreMatrixStack.INSTANCE.pushMatrix();
    }

    public static void glPopMatrix() {
        CoreMatrixStack.INSTANCE.popMatrix();
    }

    public static void glLoadIdentity() {
        CoreMatrixStack.INSTANCE.loadIdentity();
    }

    public static void glMultMatrix(FloatBuffer matrix) {
        CoreMatrixStack.INSTANCE.multMatrix(matrix);
    }

    public static void glLoadMatrix(FloatBuffer matrix) {
        CoreMatrixStack.INSTANCE.loadMatrix(matrix);
    }

    public static void glTranslatef(float x, float y, float z) {
        CoreMatrixStack.INSTANCE.translate(x, y, z);
    }

    public static void glTranslated(double x, double y, double z) {
        CoreMatrixStack.INSTANCE.translate(x, y, z);
    }

    public static void glRotatef(float angle, float x, float y, float z) {
        CoreMatrixStack.INSTANCE.rotate(angle, x, y, z);
    }

    public static void glRotated(double angle, double x, double y, double z) {
        CoreMatrixStack.INSTANCE.rotate((float) angle, (float) x, (float) y, (float) z);
    }

    public static void glScalef(float x, float y, float z) {
        CoreMatrixStack.INSTANCE.scale(x, y, z);
    }

    public static void glScaled(double x, double y, double z) {
        CoreMatrixStack.INSTANCE.scale(x, y, z);
    }

    public static void glOrtho(double left, double right, double bottom, double top, double zNear, double zFar) {
        CoreMatrixStack.INSTANCE.ortho(left, right, bottom, top, zNear, zFar);
    }

    private static final org.joml.Matrix4f tempMatrix = new org.joml.Matrix4f();

    public static void glFrustum(double left, double right, double bottom, double top, double zNear, double zFar) {
        float l = (float) left, r = (float) right, b = (float) bottom, t = (float) top;
        float n = (float) zNear, f = (float) zFar;
        org.joml.Matrix4f m = tempMatrix;
        m.m00(2.0f * n / (r - l)); m.m01(0); m.m02(0); m.m03(0);
        m.m10(0); m.m11(2.0f * n / (t - b)); m.m12(0); m.m13(0);
        m.m20((r + l) / (r - l)); m.m21((t + b) / (t - b)); m.m22(-(f + n) / (f - n)); m.m23(-1.0f);
        m.m30(0); m.m31(0); m.m32(-2.0f * f * n / (f - n)); m.m33(0);
        CoreMatrixStack.INSTANCE.multMatrix(m);
    }

    public static void gluPerspective(float fovy, float aspect, float zNear, float zFar) {
        float radians = (float) Math.toRadians(fovy / 2.0f);
        float deltaZ = zFar - zNear;
        float sine = (float) Math.sin(radians);
        if (deltaZ == 0 || sine == 0 || aspect == 0) return;

        float cotangent = (float) Math.cos(radians) / sine;
        org.joml.Matrix4f persp = tempMatrix;
        persp.m00(cotangent / aspect); persp.m01(0); persp.m02(0); persp.m03(0);
        persp.m10(0); persp.m11(cotangent); persp.m12(0); persp.m13(0);
        persp.m20(0); persp.m21(0); persp.m22(-(zFar + zNear) / deltaZ); persp.m23(-1.0f);
        persp.m30(0); persp.m31(0); persp.m32(-2.0f * zNear * zFar / deltaZ); persp.m33(0.0f);
        CoreMatrixStack.INSTANCE.multMatrix(persp);
    }

    public static void gluLookAt(float eyeX, float eyeY, float eyeZ,
                                  float centerX, float centerY, float centerZ,
                                  float upX, float upY, float upZ) {
        org.joml.Matrix4f lookAt = tempMatrix.identity().lookAt(
                eyeX, eyeY, eyeZ, centerX, centerY, centerZ, upX, upY, upZ);
        CoreMatrixStack.INSTANCE.multMatrix(lookAt);
    }

    // ═══════════════════════════════════════════════════════════════════
    // glEnable / glDisable — runtime filter for legacy vs core caps
    // ═══════════════════════════════════════════════════════════════════

    public static void glEnable(int cap) {
        switch (cap) {
            case 0x0BC0 -> CoreStateTracker.INSTANCE.enableAlphaTest();          // GL_ALPHA_TEST
            case 0x0B50 -> CoreStateTracker.INSTANCE.enableLighting();            // GL_LIGHTING
            case 0x0B60 -> CoreStateTracker.INSTANCE.enableFog();                 // GL_FOG
            case 0x0DE1 -> CoreStateTracker.INSTANCE.enableTexture2D(             // GL_TEXTURE_2D
                    CoreStateTracker.INSTANCE.getActiveTextureUnit());
            case 0x0BA1 -> CoreStateTracker.INSTANCE.enableNormalize();            // GL_NORMALIZE
            case 0x803A -> CoreStateTracker.INSTANCE.enableRescaleNormal();         // GL_RESCALE_NORMAL
            case 0x0B57 -> CoreStateTracker.INSTANCE.enableColorMaterial();         // GL_COLOR_MATERIAL
            case 0x4000, 0x4001, 0x4002, 0x4003,                                  // GL_LIGHT0-7
                 0x4004, 0x4005, 0x4006, 0x4007 ->
                    CoreStateTracker.INSTANCE.enableLight(cap - 0x4000);
            case 0x0C60, 0x0C61, 0x0C62, 0x0C63 -> {}                             // GL_TEXTURE_GEN_S/T/R/Q — no-op
            default -> org.lwjgl.opengl.GL11.glEnable(cap);                        // core caps pass through
        }
    }

    public static void glDisable(int cap) {
        switch (cap) {
            case 0x0BC0 -> CoreStateTracker.INSTANCE.disableAlphaTest();
            case 0x0B50 -> CoreStateTracker.INSTANCE.disableLighting();
            case 0x0B60 -> CoreStateTracker.INSTANCE.disableFog();
            case 0x0DE1 -> CoreStateTracker.INSTANCE.disableTexture2D(
                    CoreStateTracker.INSTANCE.getActiveTextureUnit());
            case 0x0BA1 -> CoreStateTracker.INSTANCE.disableNormalize();
            case 0x803A -> CoreStateTracker.INSTANCE.disableRescaleNormal();
            case 0x0B57 -> CoreStateTracker.INSTANCE.disableColorMaterial();
            case 0x4000, 0x4001, 0x4002, 0x4003,
                 0x4004, 0x4005, 0x4006, 0x4007 ->
                    CoreStateTracker.INSTANCE.disableLight(cap - 0x4000);
            case 0x0C60, 0x0C61, 0x0C62, 0x0C63 -> {}
            default -> org.lwjgl.opengl.GL11.glDisable(cap);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Color — all variants
    // ═══════════════════════════════════════════════════════════════════

    public static void glColor4f(float r, float g, float b, float a) {
        CoreStateTracker.INSTANCE.color(r, g, b, a);
    }

    public static void glColor3f(float r, float g, float b) {
        CoreStateTracker.INSTANCE.color(r, g, b, 1.0f);
    }

    public static void glColor4d(double r, double g, double b, double a) {
        CoreStateTracker.INSTANCE.color((float) r, (float) g, (float) b, (float) a);
    }

    public static void glColor3d(double r, double g, double b) {
        CoreStateTracker.INSTANCE.color((float) r, (float) g, (float) b, 1.0f);
    }

    public static void glColor4ub(byte r, byte g, byte b, byte a) {
        CoreStateTracker.INSTANCE.color(
                (r & 0xFF) / 255.0f, (g & 0xFF) / 255.0f,
                (b & 0xFF) / 255.0f, (a & 0xFF) / 255.0f);
    }

    public static void glColor3ub(byte r, byte g, byte b) {
        CoreStateTracker.INSTANCE.color(
                (r & 0xFF) / 255.0f, (g & 0xFF) / 255.0f,
                (b & 0xFF) / 255.0f, 1.0f);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Alpha test / shade model — direct GL calls
    // ═══════════════════════════════════════════════════════════════════

    public static void glAlphaFunc(int func, float ref) {
        CoreStateTracker.INSTANCE.alphaFunc(func, ref);
    }

    public static void glShadeModel(int mode) {
        CoreStateTracker.INSTANCE.shadeModel(mode);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Immediate mode — all variants
    // ═══════════════════════════════════════════════════════════════════

    public static void glBegin(int mode) {
        ImmediateModeEmulator.INSTANCE.syncColorFromState();
        ImmediateModeEmulator.INSTANCE.begin(mode);
    }

    public static void glEnd() {
        ImmediateModeEmulator.INSTANCE.end();
    }

    public static void glVertex3f(float x, float y, float z) {
        ImmediateModeEmulator.INSTANCE.vertex3f(x, y, z);
    }

    public static void glVertex2f(float x, float y) {
        ImmediateModeEmulator.INSTANCE.vertex3f(x, y, 0.0f);
    }

    public static void glVertex3d(double x, double y, double z) {
        ImmediateModeEmulator.INSTANCE.vertex3f((float) x, (float) y, (float) z);
    }

    public static void glVertex2d(double x, double y) {
        ImmediateModeEmulator.INSTANCE.vertex3f((float) x, (float) y, 0.0f);
    }

    public static void glVertex2i(int x, int y) {
        ImmediateModeEmulator.INSTANCE.vertex3f(x, y, 0.0f);
    }

    public static void glTexCoord2f(float u, float v) {
        ImmediateModeEmulator.INSTANCE.texCoord2f(u, v);
    }

    public static void glTexCoord2d(double u, double v) {
        ImmediateModeEmulator.INSTANCE.texCoord2f((float) u, (float) v);
    }

    public static void glNormal3f(float x, float y, float z) {
        ImmediateModeEmulator.INSTANCE.normal3f(x, y, z);
    }

    public static void glNormal3d(double x, double y, double z) {
        ImmediateModeEmulator.INSTANCE.normal3f((float) x, (float) y, (float) z);
    }

    public static void glNormal3i(int x, int y, int z) {
        ImmediateModeEmulator.INSTANCE.normal3f(x, y, z);
    }

    public static void glNormal3b(byte x, byte y, byte z) {
        ImmediateModeEmulator.INSTANCE.normal3f(x / 127.0f, y / 127.0f, z / 127.0f);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Display lists — all no-ops in core profile
    // ═══════════════════════════════════════════════════════════════════

    public static void glCallList(int list) {}

    public static int glGenLists(int range) { return 1; }

    public static void glNewList(int list, int mode) {}

    public static void glEndList() {}

    public static void glDeleteLists(int list, int range) {}

    // ═══════════════════════════════════════════════════════════════════
    // Attribute stack
    // ═══════════════════════════════════════════════════════════════════

    public static void glPushAttrib(int mask) {
        CoreStateTracker.INSTANCE.pushAttrib();
    }

    public static void glPopAttrib() {
        CoreStateTracker.INSTANCE.popAttrib();
    }

    // ═══════════════════════════════════════════════════════════════════
    // Fog — direct GL calls
    // ═══════════════════════════════════════════════════════════════════

    public static void glFogf(int pname, float param) {
        CoreStateTracker state = CoreStateTracker.INSTANCE;
        switch (pname) {
            case 0x0B63 -> state.setFogDensity(param);   // GL_FOG_DENSITY
            case 0x0B64 -> state.setFogStart(param);      // GL_FOG_START
            case 0x0B65 -> {}                              // GL_FOG_MODE — use glFogi
            case 0x0B62 -> state.setFogEnd(param);         // GL_FOG_END
            default -> {}
        }
    }

    public static void glFogi(int pname, int param) {
        if (pname == 0x0B65) { // GL_FOG_MODE
            CoreStateTracker.INSTANCE.setFogMode(param);
        }
    }

    public static void glFogfv(int pname, FloatBuffer params) {
        if (pname == 0x0B66 && params.remaining() >= 4) { // GL_FOG_COLOR
            CoreStateTracker.INSTANCE.setFogColor(
                    params.get(params.position()), params.get(params.position() + 1),
                    params.get(params.position() + 2), params.get(params.position() + 3));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Lighting — direct GL calls
    // ═══════════════════════════════════════════════════════════════════

    public static void glLightfv(int light, int pname, FloatBuffer params) {
        int idx = light - 0x4000; // GL_LIGHT0
        if (idx < 0 || idx > 1 || params.remaining() < 4) return;
        CoreStateTracker state = CoreStateTracker.INSTANCE;
        float p0 = params.get(params.position()), p1 = params.get(params.position() + 1);
        float p2 = params.get(params.position() + 2), p3 = params.get(params.position() + 3);
        switch (pname) {
            case 0x1203 -> { // GL_POSITION — transform by current modelview
                org.joml.Matrix4f mv = CoreMatrixStack.INSTANCE.getModelView();
                float ex = mv.m00() * p0 + mv.m10() * p1 + mv.m20() * p2 + mv.m30() * p3;
                float ey = mv.m01() * p0 + mv.m11() * p1 + mv.m21() * p2 + mv.m31() * p3;
                float ez = mv.m02() * p0 + mv.m12() * p1 + mv.m22() * p2 + mv.m32() * p3;
                float ew = mv.m03() * p0 + mv.m13() * p1 + mv.m23() * p2 + mv.m33() * p3;
                state.setLightPosition(idx, ex, ey, ez, ew);
            }
            case 0x1201 -> state.setLightDiffuse(idx, p0, p1, p2, p3);  // GL_DIFFUSE
            case 0x1200 -> state.setLightAmbient(idx, p0, p1, p2, p3);  // GL_AMBIENT
        }
    }

    public static void glLightModelfv(int pname, FloatBuffer params) {
        if (pname == 0x0B53 && params.remaining() >= 4) { // GL_LIGHT_MODEL_AMBIENT
            CoreStateTracker.INSTANCE.setLightModelAmbient(
                    params.get(params.position()), params.get(params.position() + 1),
                    params.get(params.position() + 2), params.get(params.position() + 3));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Texture environment — no-op in core profile
    // ═══════════════════════════════════════════════════════════════════

    public static void glTexEnvi(int target, int pname, int param) {}

    public static void glTexEnvf(int target, int pname, float param) {}

    public static void glTexEnvfv(int target, int pname, FloatBuffer params) {}

    // ═══════════════════════════════════════════════════════════════════
    // Color material — no-op in core profile
    // ═══════════════════════════════════════════════════════════════════

    public static void glColorMaterial(int face, int mode) {}

    // ═══════════════════════════════════════════════════════════════════
    // Client state + vertex array pointers — route to CoreVboDrawHandler
    // ═══════════════════════════════════════════════════════════════════

    public static void glEnableClientState(int cap) {
        CoreVboDrawHandler.glEnableClientState(cap);
    }

    public static void glDisableClientState(int cap) {
        CoreVboDrawHandler.glDisableClientState(cap);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Texture bind / delete — deferred deletion for core-profile safety
    // ═══════════════════════════════════════════════════════════════════

    public static void glBindTexture(int target, int texture) {
        CoreTextureTracker.cancelDeletion(texture);
        org.lwjgl.opengl.GL11.glBindTexture(target, texture);
    }

    public static void glDeleteTextures(int texture) {
        if (texture > 0) {
            CoreTextureTracker.markForDeletion(texture);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Active texture unit — direct GL13 calls
    // ═══════════════════════════════════════════════════════════════════

    public static void glActiveTexture(int texture) {
        CoreStateTracker.INSTANCE.setActiveTextureUnit(texture - 0x84C0); // GL_TEXTURE0
        org.lwjgl.opengl.GL13.glActiveTexture(texture);
    }

    // ═══════════════════════════════════════════════════════════════════
    // glGetFloatv — intercept matrix queries, pass through the rest
    // ═══════════════════════════════════════════════════════════════════

    public static void glGetFloatv(int pname, FloatBuffer params) {
        if (CoreMatrixStack.INSTANCE.isMatrixQuery(pname)) {
            CoreMatrixStack.INSTANCE.getFloat(pname, params);
        } else {
            org.lwjgl.opengl.GL11.glGetFloatv(pname, params);
        }
    }
}
