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
    // Matrix operations (record when display list recording, else execute)
    // ═══════════════════════════════════════════════════════════════════

    public static void glMatrixMode(int mode) {
        if (DisplayListCache.INSTANCE.isRecording()) {
            DisplayListCache.INSTANCE.recordMatrixMode(mode);
        } else {
            CoreMatrixStack.INSTANCE.matrixMode(mode);
        }
    }

    public static void glPushMatrix() {
        if (DisplayListCache.INSTANCE.isRecording()) {
            DisplayListCache.INSTANCE.recordPushMatrix();
        } else {
            CoreMatrixStack.INSTANCE.pushMatrix();
        }
    }

    public static void glPopMatrix() {
        if (DisplayListCache.INSTANCE.isRecording()) {
            DisplayListCache.INSTANCE.recordPopMatrix();
        } else {
            CoreMatrixStack.INSTANCE.popMatrix();
        }
    }

    public static void glLoadIdentity() {
        if (DisplayListCache.INSTANCE.isRecording()) {
            DisplayListCache.INSTANCE.recordLoadIdentity();
        } else {
            CoreMatrixStack.INSTANCE.loadIdentity();
        }
    }

    public static void glMultMatrix(FloatBuffer matrix) {
        if (DisplayListCache.INSTANCE.isRecording()) {
            DisplayListCache.INSTANCE.recordMultMatrix(matrix);
        } else {
            CoreMatrixStack.INSTANCE.multMatrix(matrix);
        }
    }

    public static void glLoadMatrix(FloatBuffer matrix) {
        if (DisplayListCache.INSTANCE.isRecording()) {
            DisplayListCache.INSTANCE.recordLoadMatrix(matrix);
        } else {
            CoreMatrixStack.INSTANCE.loadMatrix(matrix);
        }
    }

    public static void glMultMatrixd(java.nio.DoubleBuffer matrix) {
        FloatBuffer fb = org.lwjgl.BufferUtils.createFloatBuffer(16);
        for (int i = 0; i < 16; i++) fb.put(i, (float) matrix.get(matrix.position() + i));
        glMultMatrix(fb);
    }

    public static void glLoadMatrixd(java.nio.DoubleBuffer matrix) {
        FloatBuffer fb = org.lwjgl.BufferUtils.createFloatBuffer(16);
        for (int i = 0; i < 16; i++) fb.put(i, (float) matrix.get(matrix.position() + i));
        glLoadMatrix(fb);
    }

    public static void glTranslatef(float x, float y, float z) {
        if (DisplayListCache.INSTANCE.isRecording()) {
            DisplayListCache.INSTANCE.recordTranslate(x, y, z);
        } else {
            CoreMatrixStack.INSTANCE.translate(x, y, z);
        }
    }

    public static void glTranslated(double x, double y, double z) {
        if (DisplayListCache.INSTANCE.isRecording()) {
            DisplayListCache.INSTANCE.recordTranslate(x, y, z);
        } else {
            CoreMatrixStack.INSTANCE.translate(x, y, z);
        }
    }

    public static void glRotatef(float angle, float x, float y, float z) {
        if (DisplayListCache.INSTANCE.isRecording()) {
            DisplayListCache.INSTANCE.recordRotate(angle, x, y, z);
        } else {
            CoreMatrixStack.INSTANCE.rotate(angle, x, y, z);
        }
    }

    public static void glRotated(double angle, double x, double y, double z) {
        if (DisplayListCache.INSTANCE.isRecording()) {
            DisplayListCache.INSTANCE.recordRotate((float) angle, (float) x, (float) y, (float) z);
        } else {
            CoreMatrixStack.INSTANCE.rotate((float) angle, (float) x, (float) y, (float) z);
        }
    }

    public static void glScalef(float x, float y, float z) {
        if (DisplayListCache.INSTANCE.isRecording()) {
            DisplayListCache.INSTANCE.recordScale(x, y, z);
        } else {
            CoreMatrixStack.INSTANCE.scale(x, y, z);
        }
    }

    public static void glScaled(double x, double y, double z) {
        if (DisplayListCache.INSTANCE.isRecording()) {
            DisplayListCache.INSTANCE.recordScale(x, y, z);
        } else {
            CoreMatrixStack.INSTANCE.scale(x, y, z);
        }
    }

    public static void glOrtho(double left, double right, double bottom, double top, double zNear, double zFar) {
        if (DisplayListCache.INSTANCE.isRecording()) {
            DisplayListCache.INSTANCE.recordOrtho(left, right, bottom, top, zNear, zFar);
        } else {
            CoreMatrixStack.INSTANCE.ortho(left, right, bottom, top, zNear, zFar);
        }
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
            case 0x0C60 -> CoreStateTracker.INSTANCE.enableTexGen(0);               // GL_TEXTURE_GEN_S
            case 0x0C61 -> CoreStateTracker.INSTANCE.enableTexGen(1);               // GL_TEXTURE_GEN_T
            case 0x0C62 -> CoreStateTracker.INSTANCE.enableTexGen(2);               // GL_TEXTURE_GEN_R
            case 0x0C63 -> CoreStateTracker.INSTANCE.enableTexGen(3);               // GL_TEXTURE_GEN_Q
            case 0x3000, 0x3001, 0x3002, 0x3003,                                  // GL_CLIP_PLANE0-5
                 0x3004, 0x3005 ->
                    CoreStateTracker.INSTANCE.enableClipPlane(cap - 0x3000);
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
            case 0x0C60 -> CoreStateTracker.INSTANCE.disableTexGen(0);
            case 0x0C61 -> CoreStateTracker.INSTANCE.disableTexGen(1);
            case 0x0C62 -> CoreStateTracker.INSTANCE.disableTexGen(2);
            case 0x0C63 -> CoreStateTracker.INSTANCE.disableTexGen(3);
            case 0x3000, 0x3001, 0x3002, 0x3003,
                 0x3004, 0x3005 ->
                    CoreStateTracker.INSTANCE.disableClipPlane(cap - 0x3000);
            default -> org.lwjgl.opengl.GL11.glDisable(cap);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Color — all variants
    // ═══════════════════════════════════════════════════════════════════

    public static void glColor4f(float r, float g, float b, float a) {
        CoreStateTracker.INSTANCE.color(r, g, b, a);
        if (DisplayListCache.INSTANCE.isRecording()) {
            DisplayListCache.INSTANCE.syncColorFromState();
        }
    }

    public static void glColor3f(float r, float g, float b) {
        CoreStateTracker.INSTANCE.color(r, g, b, 1.0f);
        if (DisplayListCache.INSTANCE.isRecording()) {
            DisplayListCache.INSTANCE.syncColorFromState();
        }
    }

    public static void glColor4d(double r, double g, double b, double a) {
        CoreStateTracker.INSTANCE.color((float) r, (float) g, (float) b, (float) a);
        if (DisplayListCache.INSTANCE.isRecording()) {
            DisplayListCache.INSTANCE.syncColorFromState();
        }
    }

    public static void glColor3d(double r, double g, double b) {
        CoreStateTracker.INSTANCE.color((float) r, (float) g, (float) b, 1.0f);
        if (DisplayListCache.INSTANCE.isRecording()) {
            DisplayListCache.INSTANCE.syncColorFromState();
        }
    }

    public static void glColor4ub(byte r, byte g, byte b, byte a) {
        float fr = (r & 0xFF) / 255.0f, fg = (g & 0xFF) / 255.0f,
              fb = (b & 0xFF) / 255.0f, fa = (a & 0xFF) / 255.0f;
        CoreStateTracker.INSTANCE.color(fr, fg, fb, fa);
        if (DisplayListCache.INSTANCE.isRecording()) {
            DisplayListCache.INSTANCE.syncColorFromState();
        }
    }

    public static void glColor3ub(byte r, byte g, byte b) {
        float fr = (r & 0xFF) / 255.0f, fg = (g & 0xFF) / 255.0f,
              fb = (b & 0xFF) / 255.0f;
        CoreStateTracker.INSTANCE.color(fr, fg, fb, 1.0f);
        if (DisplayListCache.INSTANCE.isRecording()) {
            DisplayListCache.INSTANCE.syncColorFromState();
        }
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
    // Immediate mode — all variants (route to DisplayListCache when recording)
    // ═══════════════════════════════════════════════════════════════════

    public static void glBegin(int mode) {
        if (DisplayListCache.INSTANCE.isRecording()) {
            DisplayListCache.INSTANCE.syncColorFromState();
            DisplayListCache.INSTANCE.recordBegin(mode);
        } else {
            ImmediateModeEmulator.INSTANCE.syncColorFromState();
            ImmediateModeEmulator.INSTANCE.begin(mode);
        }
    }

    public static void glEnd() {
        if (DisplayListCache.INSTANCE.isRecording()) {
            DisplayListCache.INSTANCE.recordEnd();
        } else {
            ImmediateModeEmulator.INSTANCE.end();
        }
    }

    public static void glVertex3f(float x, float y, float z) {
        if (DisplayListCache.INSTANCE.isRecording()) {
            DisplayListCache.INSTANCE.recordVertex(x, y, z);
        } else {
            ImmediateModeEmulator.INSTANCE.vertex3f(x, y, z);
        }
    }

    public static void glVertex2f(float x, float y) {
        if (DisplayListCache.INSTANCE.isRecording()) {
            DisplayListCache.INSTANCE.recordVertex(x, y, 0.0f);
        } else {
            ImmediateModeEmulator.INSTANCE.vertex3f(x, y, 0.0f);
        }
    }

    public static void glVertex3d(double x, double y, double z) {
        if (DisplayListCache.INSTANCE.isRecording()) {
            DisplayListCache.INSTANCE.recordVertex((float) x, (float) y, (float) z);
        } else {
            ImmediateModeEmulator.INSTANCE.vertex3f((float) x, (float) y, (float) z);
        }
    }

    public static void glVertex2d(double x, double y) {
        if (DisplayListCache.INSTANCE.isRecording()) {
            DisplayListCache.INSTANCE.recordVertex((float) x, (float) y, 0.0f);
        } else {
            ImmediateModeEmulator.INSTANCE.vertex3f((float) x, (float) y, 0.0f);
        }
    }

    public static void glVertex2i(int x, int y) {
        if (DisplayListCache.INSTANCE.isRecording()) {
            DisplayListCache.INSTANCE.recordVertex(x, y, 0.0f);
        } else {
            ImmediateModeEmulator.INSTANCE.vertex3f(x, y, 0.0f);
        }
    }

    public static void glVertex3i(int x, int y, int z) {
        if (DisplayListCache.INSTANCE.isRecording()) {
            DisplayListCache.INSTANCE.recordVertex(x, y, z);
        } else {
            ImmediateModeEmulator.INSTANCE.vertex3f(x, y, z);
        }
    }

    public static void glVertex4f(float x, float y, float z, float w) {
        // Drop w — immediate mode emulator only supports 3D vertices
        if (DisplayListCache.INSTANCE.isRecording()) {
            DisplayListCache.INSTANCE.recordVertex(x, y, z);
        } else {
            ImmediateModeEmulator.INSTANCE.vertex3f(x, y, z);
        }
    }

    public static void glVertex4d(double x, double y, double z, double w) {
        if (DisplayListCache.INSTANCE.isRecording()) {
            DisplayListCache.INSTANCE.recordVertex((float) x, (float) y, (float) z);
        } else {
            ImmediateModeEmulator.INSTANCE.vertex3f((float) x, (float) y, (float) z);
        }
    }

    public static void glTexCoord2f(float u, float v) {
        if (DisplayListCache.INSTANCE.isRecording()) {
            DisplayListCache.INSTANCE.recordTexCoord(u, v);
        } else {
            ImmediateModeEmulator.INSTANCE.texCoord2f(u, v);
        }
    }

    public static void glTexCoord2d(double u, double v) {
        if (DisplayListCache.INSTANCE.isRecording()) {
            DisplayListCache.INSTANCE.recordTexCoord((float) u, (float) v);
        } else {
            ImmediateModeEmulator.INSTANCE.texCoord2f((float) u, (float) v);
        }
    }

    public static void glNormal3f(float x, float y, float z) {
        if (DisplayListCache.INSTANCE.isRecording()) {
            DisplayListCache.INSTANCE.recordNormal(x, y, z);
        } else {
            ImmediateModeEmulator.INSTANCE.normal3f(x, y, z);
        }
    }

    public static void glNormal3d(double x, double y, double z) {
        if (DisplayListCache.INSTANCE.isRecording()) {
            DisplayListCache.INSTANCE.recordNormal((float) x, (float) y, (float) z);
        } else {
            ImmediateModeEmulator.INSTANCE.normal3f((float) x, (float) y, (float) z);
        }
    }

    public static void glNormal3i(int x, int y, int z) {
        if (DisplayListCache.INSTANCE.isRecording()) {
            DisplayListCache.INSTANCE.recordNormal(x, y, z);
        } else {
            ImmediateModeEmulator.INSTANCE.normal3f(x, y, z);
        }
    }

    public static void glNormal3b(byte x, byte y, byte z) {
        if (DisplayListCache.INSTANCE.isRecording()) {
            DisplayListCache.INSTANCE.recordNormal(x / 127.0f, y / 127.0f, z / 127.0f);
        } else {
            ImmediateModeEmulator.INSTANCE.normal3f(x / 127.0f, y / 127.0f, z / 127.0f);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Rectangle — emulate via immediate mode (removed in core)
    // ═══════════════════════════════════════════════════════════════════

    public static void glRectf(float x1, float y1, float x2, float y2) {
        glBegin(0x0007); // GL_QUADS
        glVertex2f(x1, y1);
        glVertex2f(x2, y1);
        glVertex2f(x2, y2);
        glVertex2f(x1, y2);
        glEnd();
    }

    public static void glRecti(int x1, int y1, int x2, int y2) {
        glRectf(x1, y1, x2, y2);
    }

    public static void glRectd(double x1, double y1, double x2, double y2) {
        glRectf((float) x1, (float) y1, (float) x2, (float) y2);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Display lists — emulated via VAO/VBO (for direct GL11 calls)
    // ═══════════════════════════════════════════════════════════════════

    public static void glCallList(int list) {
        DisplayListCache.INSTANCE.callList(list);
    }

    public static int glGenLists(int range) {
        return DisplayListCache.INSTANCE.genLists(range);
    }

    public static void glNewList(int list, int mode) {
        DisplayListCache.INSTANCE.startRecording(list, mode);
    }

    public static void glEndList() {
        DisplayListCache.INSTANCE.endRecording();
    }

    public static void glDeleteLists(int list, int range) {
        DisplayListCache.INSTANCE.deleteLists(list, range);
    }

    public static void glListBase(int base) {
        DisplayListCache.INSTANCE.setListBase(base);
    }

    public static void glCallLists(java.nio.IntBuffer lists) {
        int base = DisplayListCache.INSTANCE.getListBase();
        int count = lists.remaining();
        for (int i = 0; i < count; i++) {
            DisplayListCache.INSTANCE.callList(base + lists.get(lists.position() + i));
        }
    }

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

    public static void glLightf(int light, int pname, float param) {
        // Single-float light params (spot cutoff, attenuation, etc.) — no-op for our 2-light model
    }

    public static void glLightModelfv(int pname, FloatBuffer params) {
        if (pname == 0x0B53 && params.remaining() >= 4) { // GL_LIGHT_MODEL_AMBIENT
            CoreStateTracker.INSTANCE.setLightModelAmbient(
                    params.get(params.position()), params.get(params.position() + 1),
                    params.get(params.position() + 2), params.get(params.position() + 3));
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Material — no-op in core profile (shader handles lighting)
    // ═══════════════════════════════════════════════════════════════════

    public static void glMaterialfv(int face, int pname, FloatBuffer params) {}
    public static void glMaterialf(int face, int pname, float param) {}
    public static void glMateriali(int face, int pname, int param) {}

    // ═══════════════════════════════════════════════════════════════════
    // Texture environment — no-op in core profile
    // ═══════════════════════════════════════════════════════════════════

    public static void glTexEnvi(int target, int pname, int param) {
        if (pname == 0x2200) { // GL_TEXTURE_ENV_MODE
            CoreStateTracker.INSTANCE.setTexEnvMode(param);
        }
    }

    public static void glTexEnvf(int target, int pname, float param) {
        // TexEnv float params (e.g. GL_RGB_SCALE) — not commonly needed
    }

    public static void glTexEnvfv(int target, int pname, FloatBuffer params) {
        if (pname == 0x2201 && params.remaining() >= 4) { // GL_TEXTURE_ENV_COLOR
            CoreStateTracker.INSTANCE.setTexEnvColor(
                    params.get(params.position()), params.get(params.position() + 1),
                    params.get(params.position() + 2), params.get(params.position() + 3));
        }
    }

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
    // Texture image upload — convert deprecated formats for core profile
    // ═══════════════════════════════════════════════════════════════════

    private static final int GL_ALPHA8 = 0x803C;
    private static final int GL_LUMINANCE = 0x1909;
    private static final int GL_LUMINANCE_ALPHA = 0x190A;
    private static final int GL_LUMINANCE8 = 0x8040;
    private static final int GL_LUMINANCE8_ALPHA8 = 0x8045;
    private static final int GL_INTENSITY = 0x8049;
    private static final int GL_INTENSITY8 = 0x804B;

    public static void glTexImage2D(int target, int level, int internalformat,
                                     int width, int height, int border,
                                     int format, int type, java.nio.ByteBuffer pixels) {
        int fixedInternal = internalformat;
        int fixedFormat = format;
        boolean needSwizzle = false;
        int swizzleMode = 0; // 0=none, 1=alpha(R→A,RGB=1), 2=luminance(R→RGB,A=1), 3=luminance_alpha(R→RGB,G→A), 4=intensity(R→RGBA)

        // Convert deprecated internal formats
        switch (internalformat) {
            case 0x1906: // GL_ALPHA
            case GL_ALPHA8:
                fixedInternal = org.lwjgl.opengl.GL30.GL_R8;
                fixedFormat = org.lwjgl.opengl.GL11.GL_RED;
                needSwizzle = true;
                swizzleMode = 1;
                break;
            case GL_LUMINANCE:
            case GL_LUMINANCE8:
                fixedInternal = org.lwjgl.opengl.GL30.GL_R8;
                fixedFormat = org.lwjgl.opengl.GL11.GL_RED;
                needSwizzle = true;
                swizzleMode = 2;
                break;
            case GL_LUMINANCE_ALPHA:
            case GL_LUMINANCE8_ALPHA8:
                fixedInternal = org.lwjgl.opengl.GL30.GL_RG8;
                fixedFormat = org.lwjgl.opengl.GL30.GL_RG;
                needSwizzle = true;
                swizzleMode = 3;
                break;
            case GL_INTENSITY:
            case GL_INTENSITY8:
                fixedInternal = org.lwjgl.opengl.GL30.GL_R8;
                fixedFormat = org.lwjgl.opengl.GL11.GL_RED;
                needSwizzle = true;
                swizzleMode = 4;
                break;
        }
        // Also fix the format parameter if it uses a deprecated token
        if (format == 0x1906) fixedFormat = org.lwjgl.opengl.GL11.GL_RED; // GL_ALPHA as format
        else if (format == GL_LUMINANCE) fixedFormat = org.lwjgl.opengl.GL11.GL_RED;
        else if (format == GL_LUMINANCE_ALPHA) fixedFormat = org.lwjgl.opengl.GL30.GL_RG;

        org.lwjgl.opengl.GL11.glTexImage2D(target, level, fixedInternal, width, height, border, fixedFormat, type, pixels);

        if (needSwizzle && target == org.lwjgl.opengl.GL11.GL_TEXTURE_2D) {
            applyFormatSwizzle(swizzleMode);
        }
    }

    public static void glTexImage2D_IntBuffer(int target, int level, int internalformat,
                                               int width, int height, int border,
                                               int format, int type, java.nio.IntBuffer pixels) {
        // IntBuffer variant — same format fixup
        int fixedInternal = internalformat;
        int fixedFormat = format;
        boolean needSwizzle = false;
        int swizzleMode = 0;

        switch (internalformat) {
            case 0x1906: case GL_ALPHA8:
                fixedInternal = org.lwjgl.opengl.GL30.GL_R8;
                fixedFormat = org.lwjgl.opengl.GL11.GL_RED;
                needSwizzle = true; swizzleMode = 1; break;
            case GL_LUMINANCE: case GL_LUMINANCE8:
                fixedInternal = org.lwjgl.opengl.GL30.GL_R8;
                fixedFormat = org.lwjgl.opengl.GL11.GL_RED;
                needSwizzle = true; swizzleMode = 2; break;
            case GL_LUMINANCE_ALPHA: case GL_LUMINANCE8_ALPHA8:
                fixedInternal = org.lwjgl.opengl.GL30.GL_RG8;
                fixedFormat = org.lwjgl.opengl.GL30.GL_RG;
                needSwizzle = true; swizzleMode = 3; break;
            case GL_INTENSITY: case GL_INTENSITY8:
                fixedInternal = org.lwjgl.opengl.GL30.GL_R8;
                fixedFormat = org.lwjgl.opengl.GL11.GL_RED;
                needSwizzle = true; swizzleMode = 4; break;
        }
        if (format == 0x1906) fixedFormat = org.lwjgl.opengl.GL11.GL_RED;
        else if (format == GL_LUMINANCE) fixedFormat = org.lwjgl.opengl.GL11.GL_RED;
        else if (format == GL_LUMINANCE_ALPHA) fixedFormat = org.lwjgl.opengl.GL30.GL_RG;

        org.lwjgl.opengl.GL11.glTexImage2D(target, level, fixedInternal, width, height, border, fixedFormat, type, pixels);

        if (needSwizzle && target == org.lwjgl.opengl.GL11.GL_TEXTURE_2D) {
            applyFormatSwizzle(swizzleMode);
        }
    }

    private static void applyFormatSwizzle(int mode) {
        int GL_ONE = org.lwjgl.opengl.GL11.GL_ONE;
        int GL_ZERO = org.lwjgl.opengl.GL11.GL_ZERO;
        int GL_RED = org.lwjgl.opengl.GL11.GL_RED;
        int GL_GREEN = org.lwjgl.opengl.GL11.GL_GREEN;
        int TEX = org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
        int SWIZZLE_R = org.lwjgl.opengl.GL33.GL_TEXTURE_SWIZZLE_R;
        int SWIZZLE_G = org.lwjgl.opengl.GL33.GL_TEXTURE_SWIZZLE_G;
        int SWIZZLE_B = org.lwjgl.opengl.GL33.GL_TEXTURE_SWIZZLE_B;
        int SWIZZLE_A = org.lwjgl.opengl.GL33.GL_TEXTURE_SWIZZLE_A;
        switch (mode) {
            case 1: // GL_ALPHA: R→A, RGB=1
                org.lwjgl.opengl.GL11.glTexParameteri(TEX, SWIZZLE_R, GL_ONE);
                org.lwjgl.opengl.GL11.glTexParameteri(TEX, SWIZZLE_G, GL_ONE);
                org.lwjgl.opengl.GL11.glTexParameteri(TEX, SWIZZLE_B, GL_ONE);
                org.lwjgl.opengl.GL11.glTexParameteri(TEX, SWIZZLE_A, GL_RED);
                break;
            case 2: // GL_LUMINANCE: R→RGB, A=1
                org.lwjgl.opengl.GL11.glTexParameteri(TEX, SWIZZLE_R, GL_RED);
                org.lwjgl.opengl.GL11.glTexParameteri(TEX, SWIZZLE_G, GL_RED);
                org.lwjgl.opengl.GL11.glTexParameteri(TEX, SWIZZLE_B, GL_RED);
                org.lwjgl.opengl.GL11.glTexParameteri(TEX, SWIZZLE_A, GL_ONE);
                break;
            case 3: // GL_LUMINANCE_ALPHA: R→RGB, G→A
                org.lwjgl.opengl.GL11.glTexParameteri(TEX, SWIZZLE_R, GL_RED);
                org.lwjgl.opengl.GL11.glTexParameteri(TEX, SWIZZLE_G, GL_RED);
                org.lwjgl.opengl.GL11.glTexParameteri(TEX, SWIZZLE_B, GL_RED);
                org.lwjgl.opengl.GL11.glTexParameteri(TEX, SWIZZLE_A, GL_GREEN);
                break;
            case 4: // GL_INTENSITY: R→RGBA
                org.lwjgl.opengl.GL11.glTexParameteri(TEX, SWIZZLE_R, GL_RED);
                org.lwjgl.opengl.GL11.glTexParameteri(TEX, SWIZZLE_G, GL_RED);
                org.lwjgl.opengl.GL11.glTexParameteri(TEX, SWIZZLE_B, GL_RED);
                org.lwjgl.opengl.GL11.glTexParameteri(TEX, SWIZZLE_A, GL_RED);
                break;
        }
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
    // glMultiTexCoord2f — removed in core profile
    // ═══════════════════════════════════════════════════════════════════

    public static void glMultiTexCoord2f(int target, float s, float t) {
        // Track lightmap coords if targeting the lightmap unit (GL_TEXTURE1)
        if (target == 0x84C1) { // GL_TEXTURE1
            CoreStateTracker.INSTANCE.setLightmapCoords(s, t);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // TexGen — removed in core profile, emulated via CoreStateTracker
    // ═══════════════════════════════════════════════════════════════════

    public static void glTexGeni(int coord, int pname, int param) {
        int idx = texGenCoordIndex(coord);
        if (idx >= 0 && pname == 0x2500) { // GL_TEXTURE_GEN_MODE
            CoreStateTracker.INSTANCE.setTexGenMode(idx, param);
        }
    }

    public static void glTexGenfv(int coord, int pname, FloatBuffer params) {
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

    public static void glTexGendv(int coord, int pname, java.nio.DoubleBuffer params) {
        int idx = texGenCoordIndex(coord);
        if (idx < 0 || params.remaining() < 4) return;
        float a = (float) params.get(params.position()), b = (float) params.get(params.position() + 1);
        float c = (float) params.get(params.position() + 2), d = (float) params.get(params.position() + 3);
        if (pname == 0x2501) { // GL_OBJECT_PLANE
            CoreStateTracker.INSTANCE.setTexGenObjectPlane(idx, a, b, c, d);
        } else if (pname == 0x2502) { // GL_EYE_PLANE
            CoreStateTracker.INSTANCE.setTexGenEyePlane(idx, a, b, c, d);
        } else if (pname == 0x2500) { // GL_TEXTURE_GEN_MODE
            CoreStateTracker.INSTANCE.setTexGenMode(idx, (int) a);
        }
    }

    private static int texGenCoordIndex(int coord) {
        return switch (coord) {
            case 0x0C60 -> 0; // GL_TEXTURE_GEN_S / GL_S
            case 0x0C61 -> 1; // GL_TEXTURE_GEN_T / GL_T
            case 0x0C62 -> 2; // GL_TEXTURE_GEN_R / GL_R
            case 0x0C63 -> 3; // GL_TEXTURE_GEN_Q / GL_Q
            case 0x2000 -> 0; // GL_S
            case 0x2001 -> 1; // GL_T
            case 0x2002 -> 2; // GL_R
            case 0x2003 -> 3; // GL_Q
            default -> -1;
        };
    }

    // ═══════════════════════════════════════════════════════════════════
    // Texture sub-image upload — convert deprecated formats for core
    // ═══════════════════════════════════════════════════════════════════

    public static void glTexSubImage2D(int target, int level, int xoffset, int yoffset,
                                        int width, int height, int format, int type,
                                        java.nio.ByteBuffer pixels) {
        int fixedFormat = fixDeprecatedFormat(format);
        org.lwjgl.opengl.GL11.glTexSubImage2D(target, level, xoffset, yoffset, width, height, fixedFormat, type, pixels);
    }

    public static void glTexSubImage2D_IntBuffer(int target, int level, int xoffset, int yoffset,
                                                   int width, int height, int format, int type,
                                                   java.nio.IntBuffer pixels) {
        int fixedFormat = fixDeprecatedFormat(format);
        org.lwjgl.opengl.GL11.glTexSubImage2D(target, level, xoffset, yoffset, width, height, fixedFormat, type, pixels);
    }

    private static int fixDeprecatedFormat(int format) {
        return switch (format) {
            case 0x1906 -> org.lwjgl.opengl.GL11.GL_RED;    // GL_ALPHA as format
            case GL_LUMINANCE -> org.lwjgl.opengl.GL11.GL_RED;
            case GL_LUMINANCE_ALPHA -> org.lwjgl.opengl.GL30.GL_RG;
            default -> format;
        };
    }

    // ═══════════════════════════════════════════════════════════════════
    // Clip planes — legacy glClipPlane transforms equation by current MV
    // ═══════════════════════════════════════════════════════════════════

    public static void glClipPlane(int plane, java.nio.DoubleBuffer equation) {
        int idx = plane - 0x3000; // GL_CLIP_PLANE0
        if (idx < 0 || idx >= 6 || equation.remaining() < 4) return;
        float a = (float) equation.get(equation.position());
        float b = (float) equation.get(equation.position() + 1);
        float c = (float) equation.get(equation.position() + 2);
        float d = (float) equation.get(equation.position() + 3);
        org.joml.Matrix4f mv = CoreMatrixStack.INSTANCE.getModelView();
        org.joml.Matrix4f mvInv = new org.joml.Matrix4f(mv).invert();
        float ea = mvInv.m00() * a + mvInv.m10() * b + mvInv.m20() * c + mvInv.m30() * d;
        float eb = mvInv.m01() * a + mvInv.m11() * b + mvInv.m21() * c + mvInv.m31() * d;
        float ec = mvInv.m02() * a + mvInv.m12() * b + mvInv.m22() * c + mvInv.m32() * d;
        float ed = mvInv.m03() * a + mvInv.m13() * b + mvInv.m23() * c + mvInv.m33() * d;
        CoreStateTracker.INSTANCE.setClipPlaneEquation(idx, ea, eb, ec, ed);
    }

    // ═══════════════════════════════════════════════════════════════════
    // State queries — intercept removed-state queries, pass through rest
    // ═══════════════════════════════════════════════════════════════════

    public static void glGetFloatv(int pname, FloatBuffer params) {
        if (CoreMatrixStack.INSTANCE.isMatrixQuery(pname)) {
            CoreMatrixStack.INSTANCE.getFloat(pname, params);
        } else {
            org.lwjgl.opengl.GL11.glGetFloatv(pname, params);
        }
    }

    public static void glGetIntegerv(int pname, java.nio.IntBuffer params) {
        Integer val = queryRemovedIntegerState(pname);
        if (val != null) {
            params.put(params.position(), val);
        } else {
            org.lwjgl.opengl.GL11.glGetIntegerv(pname, params);
        }
    }

    public static int glGetInteger(int pname) {
        Integer val = queryRemovedIntegerState(pname);
        if (val != null) return val;
        return org.lwjgl.opengl.GL11.glGetInteger(pname);
    }

    public static void glGetDoublev(int pname, java.nio.DoubleBuffer params) {
        if (CoreMatrixStack.INSTANCE.isMatrixQuery(pname)) {
            // Get as float then convert
            FloatBuffer fb = org.lwjgl.BufferUtils.createFloatBuffer(16);
            CoreMatrixStack.INSTANCE.getFloat(pname, fb);
            for (int i = 0; i < 16 && i < params.remaining(); i++) {
                params.put(params.position() + i, fb.get(i));
            }
        } else {
            Integer intVal = queryRemovedIntegerState(pname);
            if (intVal != null) {
                params.put(params.position(), intVal);
            } else {
                org.lwjgl.opengl.GL11.glGetDoublev(pname, params);
            }
        }
    }

    private static Integer queryRemovedIntegerState(int pname) {
        return switch (pname) {
            case 0x0BA0 -> CoreMatrixStack.INSTANCE.getMatrixMode();  // GL_MATRIX_MODE
            case 0x0B52 -> CoreStateTracker.INSTANCE.getShadeModel(); // GL_SHADE_MODEL
            case 0x0BC0 -> CoreStateTracker.INSTANCE.isAlphaTestEnabled() ? 1 : 0; // GL_ALPHA_TEST
            case 0x0BC1 -> CoreStateTracker.INSTANCE.getAlphaFunc();  // GL_ALPHA_TEST_FUNC
            default -> null;
        };
    }

    public static boolean glIsEnabled(int cap) {
        return switch (cap) {
            case 0x0BC0 -> CoreStateTracker.INSTANCE.isAlphaTestEnabled();  // GL_ALPHA_TEST
            case 0x0B50 -> CoreStateTracker.INSTANCE.isLightingEnabled();   // GL_LIGHTING
            case 0x0B60 -> CoreStateTracker.INSTANCE.isFogEnabled();        // GL_FOG
            case 0x0DE1 -> CoreStateTracker.INSTANCE.isTexture2DEnabled(    // GL_TEXTURE_2D
                    CoreStateTracker.INSTANCE.getActiveTextureUnit());
            case 0x0BA1 -> CoreStateTracker.INSTANCE.isNormalizeEnabled();  // GL_NORMALIZE
            case 0x803A -> CoreStateTracker.INSTANCE.isRescaleNormalEnabled(); // GL_RESCALE_NORMAL
            case 0x0B57 -> CoreStateTracker.INSTANCE.isColorMaterialEnabled(); // GL_COLOR_MATERIAL
            case 0x4000, 0x4001, 0x4002, 0x4003,                            // GL_LIGHT0-7
                 0x4004, 0x4005, 0x4006, 0x4007 ->
                    CoreStateTracker.INSTANCE.isLightEnabled(cap - 0x4000);
            case 0x0C60 -> CoreStateTracker.INSTANCE.isTexGenEnabled(0);    // GL_TEXTURE_GEN_S
            case 0x0C61 -> CoreStateTracker.INSTANCE.isTexGenEnabled(1);    // GL_TEXTURE_GEN_T
            case 0x0C62 -> CoreStateTracker.INSTANCE.isTexGenEnabled(2);    // GL_TEXTURE_GEN_R
            case 0x0C63 -> CoreStateTracker.INSTANCE.isTexGenEnabled(3);    // GL_TEXTURE_GEN_Q
            case 0x3000, 0x3001, 0x3002, 0x3003,                            // GL_CLIP_PLANE0-5
                 0x3004, 0x3005 ->
                    CoreStateTracker.INSTANCE.isClipPlaneEnabled(cap - 0x3000);
            default -> org.lwjgl.opengl.GL11.glIsEnabled(cap);              // core caps pass through
        };
    }

    // ═══════════════════════════════════════════════════════════════════
    // GLU Project/UnProject — use CoreMatrixStack instead of GL queries
    // ═══════════════════════════════════════════════════════════════════

    public static boolean gluProject(float objX, float objY, float objZ,
                                      FloatBuffer modelview, FloatBuffer projection,
                                      java.nio.IntBuffer viewport, FloatBuffer winPos) {
        // Use the provided matrices (already filled by caller from CoreMatrixStack)
        // and perform the projection manually
        org.joml.Matrix4f mv = new org.joml.Matrix4f().set(modelview);
        org.joml.Matrix4f proj = new org.joml.Matrix4f().set(projection);

        // Transform: clip = proj * mv * obj
        org.joml.Vector4f obj = new org.joml.Vector4f(objX, objY, objZ, 1.0f);
        org.joml.Vector4f eye = mv.transform(obj, new org.joml.Vector4f());
        org.joml.Vector4f clip = proj.transform(eye, new org.joml.Vector4f());

        if (clip.w == 0.0f) return false;
        clip.x /= clip.w;
        clip.y /= clip.w;
        clip.z /= clip.w;

        // Map to window coords
        int vpX = viewport.get(viewport.position());
        int vpY = viewport.get(viewport.position() + 1);
        int vpW = viewport.get(viewport.position() + 2);
        int vpH = viewport.get(viewport.position() + 3);
        winPos.put(winPos.position(),     vpX + (clip.x * 0.5f + 0.5f) * vpW);
        winPos.put(winPos.position() + 1, vpY + (clip.y * 0.5f + 0.5f) * vpH);
        winPos.put(winPos.position() + 2, clip.z * 0.5f + 0.5f);
        return true;
    }

    public static boolean gluUnProject(float winX, float winY, float winZ,
                                        FloatBuffer modelview, FloatBuffer projection,
                                        java.nio.IntBuffer viewport, FloatBuffer objPos) {
        org.joml.Matrix4f mv = new org.joml.Matrix4f().set(modelview);
        org.joml.Matrix4f proj = new org.joml.Matrix4f().set(projection);

        org.joml.Matrix4f combined = new org.joml.Matrix4f(proj).mul(mv);
        org.joml.Matrix4f inv = new org.joml.Matrix4f(combined).invert();

        int vpX = viewport.get(viewport.position());
        int vpY = viewport.get(viewport.position() + 1);
        int vpW = viewport.get(viewport.position() + 2);
        int vpH = viewport.get(viewport.position() + 3);

        // Map window coords to NDC
        float ndcX = (winX - vpX) / vpW * 2.0f - 1.0f;
        float ndcY = (winY - vpY) / vpH * 2.0f - 1.0f;
        float ndcZ = winZ * 2.0f - 1.0f;

        org.joml.Vector4f ndc = new org.joml.Vector4f(ndcX, ndcY, ndcZ, 1.0f);
        org.joml.Vector4f result = inv.transform(ndc, new org.joml.Vector4f());

        if (result.w == 0.0f) return false;
        objPos.put(objPos.position(),     result.x / result.w);
        objPos.put(objPos.position() + 1, result.y / result.w);
        objPos.put(objPos.position() + 2, result.z / result.w);
        return true;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Pixel operations — removed in core profile, no-ops
    // ═══════════════════════════════════════════════════════════════════

    public static void glRasterPos2f(float x, float y) {}
    public static void glRasterPos2d(double x, double y) {}
    public static void glRasterPos2i(int x, int y) {}
    public static void glRasterPos3f(float x, float y, float z) {}
    public static void glRasterPos3d(double x, double y, double z) {}
    public static void glBitmap(int width, int height, float xorig, float yorig,
                                 float xmove, float ymove, java.nio.ByteBuffer bitmap) {}
    public static void glDrawPixels(int width, int height, int format, int type,
                                     java.nio.ByteBuffer pixels) {}
    public static void glPixelTransferf(int pname, float param) {}
    public static void glPixelTransferi(int pname, int param) {}
    public static void glPixelZoom(float xfactor, float yfactor) {}
}
