package com.github.gl46core.core;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.*;

import java.util.HashMap;
import java.util.Map;

/**
 * ASM transformer that redirects ALL direct GL11/GL13/GLU legacy calls that bypass
 * GlStateManager to LegacyGLRedirects.
 *
 * Covers:
 * - Matrix:        glMatrixMode, glPushMatrix, glPopMatrix, glLoadIdentity, glMultMatrix(f/d),
 *                  glLoadMatrix(f/d), glTranslatef/d, glRotatef/d, glScalef/d, glOrtho, glFrustum
 * - Enable/Disable: glEnable, glDisable (runtime filter for legacy vs core caps)
 * - Color:         glColor3f/3d/3ub/4f/4d/4ub
 * - Immediate:     glBegin, glEnd, glVertex2f/2d/2i/3f/3d/3i/4f/4d, glTexCoord2f/2d,
 *                  glNormal3f/3d/3i/3b, glRectf/i/d
 * - State:         glAlphaFunc, glShadeModel, glColorMaterial
 * - Core state:    glDepthFunc, glDepthMask, glBlendFunc, glBlendFuncSeparate (GL14),
 *                  glCullFace, glPolygonOffset, glColorMask
 * - Fog:           glFogf, glFogi, glFogfv
 * - Lighting:      glLightfv, glLightf, glLightModelfv
 * - Material:      glMaterialfv, glMaterialf, glMateriali (no-ops)
 * - Texture:       glTexEnvi, glTexEnvf, glTexEnvfv, glActiveTexture, glMultiTexCoord2f,
 *                  glTexGeni, glTexGenfv, glTexGendv
 * - Tex upload:    glTexImage2D (ByteBuffer/IntBuffer), glTexSubImage2D (ByteBuffer/IntBuffer)
 * - Display lists: glCallList, glGenLists, glNewList, glEndList, glDeleteLists,
 *                  glListBase, glCallLists
 * - Attrib stack:  glPushAttrib, glPopAttrib
 * - Client state:  glEnableClientState, glDisableClientState
 * - Clip planes:   glClipPlane
 * - Shaders:       glShaderSource (auto-converts deprecated GLSL to 460 core),
 *                  glUseProgram (auto-uploads matrix uniforms), glDeleteProgram
 * - Queries:       glGetFloatv, glGetIntegerv, glGetDoublev, glGetInteger, glIsEnabled
 * - Pixel ops:     glRasterPos, glBitmap, glDrawPixels, glPixelTransfer, glPixelZoom (no-ops)
 * - GLU:           gluPerspective, gluLookAt, gluProject, gluUnProject
 *
 * All GL11/GL13/GL14/GL20 redirects are mirrored to their LWJGL3 "C" variants
 * (GL11C, GL13C, GL14C, GL20C) to catch code using the base LWJGL3 classes.
 *
 * Excludes: gl46core itself, LWJGL, ASM, Mixin, Java stdlib, Cleanroom internals.
 */
public class LegacyGLTransformer implements IClassTransformer {

    private static final String REDIRECTS = "com/github/gl46core/gl/LegacyGLRedirects";

    /**
     * A redirect entry: maps a source (owner + method + descriptor) to a target method name.
     * The target descriptor is always the same as the source unless overridden.
     * If deprecatedFeature is non-null, usage is tracked in DeprecatedUsageTracker.
     */
    private record Redirect(String targetMethod, String targetDesc, String deprecatedFeature) {
        Redirect(String targetMethod) {
            this(targetMethod, null, null);
        }
        Redirect(String targetMethod, String targetDesc) {
            this(targetMethod, targetDesc, null);
        }
        static Redirect deprecated(String targetMethod, String featureName) {
            return new Redirect(targetMethod, null, featureName);
        }
    }

    /**
     * Lookup key for a GL method call: owner class + method name + descriptor.
     * Descriptor may be null for "match any descriptor" (used for GLU methods).
     */
    private record CallKey(String owner, String method, String desc) {}

    // ── Redirect table: populated once at class load ─────────────────
    private static final Map<CallKey, Redirect> REDIRECT_TABLE = buildRedirectTable();

    private static Map<CallKey, Redirect> buildRedirectTable() {
        var table = new HashMap<CallKey, Redirect>();
        String GL11 = "org/lwjgl/opengl/GL11";
        String GL13 = "org/lwjgl/opengl/GL13";
        String FB = "Ljava/nio/FloatBuffer;";
        String DB = "Ljava/nio/DoubleBuffer;";
        String IB = "Ljava/nio/IntBuffer;";
        String BB = "Ljava/nio/ByteBuffer;";

        // ── Matrix operations ─────────────────────────────────────────
        table.put(new CallKey(GL11, "glMatrixMode", "(I)V"),       new Redirect("glMatrixMode"));
        table.put(new CallKey(GL11, "glPushMatrix", "()V"),        new Redirect("glPushMatrix"));
        table.put(new CallKey(GL11, "glPopMatrix", "()V"),         new Redirect("glPopMatrix"));
        table.put(new CallKey(GL11, "glLoadIdentity", "()V"),      new Redirect("glLoadIdentity"));
        table.put(new CallKey(GL11, "glMultMatrix", "(" + FB + ")V"), new Redirect("glMultMatrix"));
        table.put(new CallKey(GL11, "glMultMatrix", "(" + DB + ")V"), new Redirect("glMultMatrixd"));
        table.put(new CallKey(GL11, "glLoadMatrix", "(" + FB + ")V"), new Redirect("glLoadMatrix"));
        table.put(new CallKey(GL11, "glLoadMatrix", "(" + DB + ")V"), new Redirect("glLoadMatrixd"));
        table.put(new CallKey(GL11, "glTranslatef", "(FFF)V"),     new Redirect("glTranslatef"));
        table.put(new CallKey(GL11, "glTranslated", "(DDD)V"),     new Redirect("glTranslated"));
        table.put(new CallKey(GL11, "glRotatef", "(FFFF)V"),       new Redirect("glRotatef"));
        table.put(new CallKey(GL11, "glRotated", "(DDDD)V"),       new Redirect("glRotated"));
        table.put(new CallKey(GL11, "glScalef", "(FFF)V"),         new Redirect("glScalef"));
        table.put(new CallKey(GL11, "glScaled", "(DDD)V"),         new Redirect("glScaled"));
        table.put(new CallKey(GL11, "glOrtho", "(DDDDDD)V"),       new Redirect("glOrtho"));
        table.put(new CallKey(GL11, "glFrustum", "(DDDDDD)V"),     new Redirect("glFrustum"));

        // ── Enable / Disable (runtime filter) ─────────────────────────
        table.put(new CallKey(GL11, "glEnable", "(I)V"),           new Redirect("glEnable"));
        table.put(new CallKey(GL11, "glDisable", "(I)V"),          new Redirect("glDisable"));

        // ── Shader source (auto-convert deprecated GLSL to 460 core) ──
        String GL20 = "org/lwjgl/opengl/GL20";
        String CS = "Ljava/lang/CharSequence;";
        table.put(new CallKey(GL20, "glShaderSource", "(I" + CS + ")V"), new Redirect("glShaderSource"));

        // ── Shader program (auto-upload matrix uniforms for mod shaders) ──
        table.put(new CallKey(GL20, "glUseProgram", "(I)V"),             new Redirect("glUseProgram"));
        table.put(new CallKey(GL20, "glDeleteProgram", "(I)V"),          new Redirect("glDeleteProgram"));

        // ── Core-profile state (tracked to prevent desync) ──────────
        table.put(new CallKey(GL11, "glDepthFunc", "(I)V"),        new Redirect("glDepthFunc"));
        table.put(new CallKey(GL11, "glDepthMask", "(Z)V"),        new Redirect("glDepthMask"));
        table.put(new CallKey(GL11, "glBlendFunc", "(II)V"),       new Redirect("glBlendFunc"));
        table.put(new CallKey("org/lwjgl/opengl/GL14", "glBlendFuncSeparate", "(IIII)V"), new Redirect("glBlendFuncSeparate"));
        table.put(new CallKey(GL11, "glCullFace", "(I)V"),         new Redirect("glCullFace"));
        table.put(new CallKey(GL11, "glPolygonOffset", "(FF)V"),   new Redirect("glPolygonOffset"));
        table.put(new CallKey(GL11, "glColorMask", "(ZZZZ)V"),    new Redirect("glColorMask"));

        // ── Color variants ────────────────────────────────────────────
        table.put(new CallKey(GL11, "glColor4f", "(FFFF)V"),       new Redirect("glColor4f"));
        table.put(new CallKey(GL11, "glColor3f", "(FFF)V"),        new Redirect("glColor3f"));
        table.put(new CallKey(GL11, "glColor4d", "(DDDD)V"),       new Redirect("glColor4d"));
        table.put(new CallKey(GL11, "glColor3d", "(DDD)V"),        new Redirect("glColor3d"));
        table.put(new CallKey(GL11, "glColor4ub", "(BBBB)V"),      new Redirect("glColor4ub"));
        table.put(new CallKey(GL11, "glColor3ub", "(BBB)V"),       new Redirect("glColor3ub"));

        // ── Alpha test / shade model ──────────────────────────────────
        table.put(new CallKey(GL11, "glAlphaFunc", "(IF)V"),       new Redirect("glAlphaFunc"));
        table.put(new CallKey(GL11, "glShadeModel", "(I)V"),       new Redirect("glShadeModel"));

        // ── Immediate mode — all type variants ────────────────────────
        table.put(new CallKey(GL11, "glBegin", "(I)V"),            new Redirect("glBegin"));
        table.put(new CallKey(GL11, "glEnd", "()V"),               new Redirect("glEnd"));
        table.put(new CallKey(GL11, "glVertex3f", "(FFF)V"),       new Redirect("glVertex3f"));
        table.put(new CallKey(GL11, "glVertex2f", "(FF)V"),        new Redirect("glVertex2f"));
        table.put(new CallKey(GL11, "glVertex3d", "(DDD)V"),       new Redirect("glVertex3d"));
        table.put(new CallKey(GL11, "glVertex2d", "(DD)V"),        new Redirect("glVertex2d"));
        table.put(new CallKey(GL11, "glVertex2i", "(II)V"),        new Redirect("glVertex2i"));
        table.put(new CallKey(GL11, "glVertex3i", "(III)V"),       new Redirect("glVertex3i"));
        table.put(new CallKey(GL11, "glVertex4f", "(FFFF)V"),      new Redirect("glVertex4f"));
        table.put(new CallKey(GL11, "glVertex4d", "(DDDD)V"),      new Redirect("glVertex4d"));
        table.put(new CallKey(GL11, "glTexCoord2f", "(FF)V"),      new Redirect("glTexCoord2f"));
        table.put(new CallKey(GL11, "glTexCoord2d", "(DD)V"),      new Redirect("glTexCoord2d"));
        table.put(new CallKey(GL11, "glNormal3f", "(FFF)V"),       new Redirect("glNormal3f"));
        table.put(new CallKey(GL11, "glNormal3d", "(DDD)V"),       new Redirect("glNormal3d"));
        table.put(new CallKey(GL11, "glNormal3i", "(III)V"),       new Redirect("glNormal3i"));
        table.put(new CallKey(GL11, "glNormal3b", "(BBB)V"),       new Redirect("glNormal3b"));

        // ── Rectangle (removed — emulate via immediate mode) ──────────
        table.put(new CallKey(GL11, "glRectf", "(FFFF)V"),        new Redirect("glRectf"));
        table.put(new CallKey(GL11, "glRecti", "(IIII)V"),        new Redirect("glRecti"));
        table.put(new CallKey(GL11, "glRectd", "(DDDD)V"),        new Redirect("glRectd"));

        // ── Display lists (emulated via VAO/VBO in LegacyGLRedirects) ──
        table.put(new CallKey(GL11, "glCallList", "(I)V"),         new Redirect("glCallList"));
        table.put(new CallKey(GL11, "glGenLists", "(I)I"),         new Redirect("glGenLists"));
        table.put(new CallKey(GL11, "glNewList", "(II)V"),         new Redirect("glNewList"));
        table.put(new CallKey(GL11, "glEndList", "()V"),           new Redirect("glEndList"));
        table.put(new CallKey(GL11, "glDeleteLists", "(II)V"),     new Redirect("glDeleteLists"));
        table.put(new CallKey(GL11, "glListBase", "(I)V"),         new Redirect("glListBase"));
        table.put(new CallKey(GL11, "glCallLists", "(" + IB + ")V"), new Redirect("glCallLists"));

        // ── Attribute stack ───────────────────────────────────────────
        table.put(new CallKey(GL11, "glPushAttrib", "(I)V"),       new Redirect("glPushAttrib"));
        table.put(new CallKey(GL11, "glPopAttrib", "()V"),         new Redirect("glPopAttrib"));

        // ── Fog ───────────────────────────────────────────────────────
        table.put(new CallKey(GL11, "glFogf", "(IF)V"),            new Redirect("glFogf"));
        table.put(new CallKey(GL11, "glFogi", "(II)V"),            new Redirect("glFogi"));
        table.put(new CallKey(GL11, "glFogfv", "(I" + FB + ")V"), new Redirect("glFogfv"));

        // ── Lighting ──────────────────────────────────────────────────
        table.put(new CallKey(GL11, "glLightfv", "(II" + FB + ")V"), new Redirect("glLightfv"));
        table.put(new CallKey(GL11, "glLightf", "(IIF)V"),         new Redirect("glLightf"));
        table.put(new CallKey(GL11, "glLightModelfv", "(I" + FB + ")V"), new Redirect("glLightModelfv"));

        // ── Material (no-op — shader handles lighting differently) ───
        table.put(new CallKey(GL11, "glMaterialfv", "(II" + FB + ")V"), Redirect.deprecated("glMaterialfv", "Material"));
        table.put(new CallKey(GL11, "glMaterialf", "(IIF)V"),      Redirect.deprecated("glMaterialf", "Material"));
        table.put(new CallKey(GL11, "glMateriali", "(III)V"),       Redirect.deprecated("glMateriali", "Material"));

        // ── Texture environment (no-op — shader uses GL_MODULATE behavior) ──
        table.put(new CallKey(GL11, "glTexEnvi", "(III)V"),        Redirect.deprecated("glTexEnvi", "Texture Environment"));
        table.put(new CallKey(GL11, "glTexEnvf", "(IIF)V"),        Redirect.deprecated("glTexEnvf", "Texture Environment"));
        table.put(new CallKey(GL11, "glTexEnvfv", "(II" + FB + ")V"), Redirect.deprecated("glTexEnvfv", "Texture Environment"));

        // ── Color material (no-op — shader always treats vertex color as material) ──
        table.put(new CallKey(GL11, "glColorMaterial", "(II)V"),   Redirect.deprecated("glColorMaterial", "Color Material"));

        // ── Client state ──────────────────────────────────────────────
        table.put(new CallKey(GL11, "glEnableClientState", "(I)V"),  new Redirect("glEnableClientState"));
        table.put(new CallKey(GL11, "glDisableClientState", "(I)V"), new Redirect("glDisableClientState"));

        // ── TexGen (removed — emulated via CoreStateTracker) ──────────
        table.put(new CallKey(GL11, "glTexGeni", "(III)V"),        new Redirect("glTexGeni"));
        table.put(new CallKey(GL11, "glTexGenfv", "(II" + FB + ")V"), new Redirect("glTexGenfv"));
        table.put(new CallKey(GL11, "glTexGendv", "(II" + DB + ")V"), new Redirect("glTexGendv"));

        // ── Texture image upload (convert deprecated GL_ALPHA/GL_LUMINANCE formats) ──
        table.put(new CallKey(GL11, "glTexImage2D", "(IIIIIIII" + BB + ")V"), new Redirect("glTexImage2D"));
        table.put(new CallKey(GL11, "glTexImage2D", "(IIIIIIII" + IB + ")V"),  new Redirect("glTexImage2D_IntBuffer", "(IIIIIIII" + IB + ")V"));
        table.put(new CallKey(GL11, "glTexSubImage2D", "(IIIIIIII" + BB + ")V"), new Redirect("glTexSubImage2D"));
        table.put(new CallKey(GL11, "glTexSubImage2D", "(IIIIIIII" + IB + ")V"), new Redirect("glTexSubImage2D_IntBuffer", "(IIIIIIII" + IB + ")V"));

        // ── Texture bind / delete (deferred deletion for core-profile safety) ──
        table.put(new CallKey(GL11, "glBindTexture", "(II)V"),     new Redirect("glBindTexture"));
        table.put(new CallKey(GL11, "glDeleteTextures", "(I)V"),   new Redirect("glDeleteTextures"));

        // ── Active texture / multi-texcoord (GL13) ─────────────────────
        table.put(new CallKey(GL13, "glActiveTexture", "(I)V"),    new Redirect("glActiveTexture"));
        table.put(new CallKey(GL13, "glMultiTexCoord2f", "(IFF)V"), new Redirect("glMultiTexCoord2f"));

        // ── State queries (intercept removed-state queries) ──────────
        table.put(new CallKey(GL11, "glGetFloatv", "(I" + FB + ")V"), new Redirect("glGetFloatv"));
        table.put(new CallKey(GL11, "glGetIntegerv", "(I" + IB + ")V"), new Redirect("glGetIntegerv"));
        table.put(new CallKey(GL11, "glGetDoublev", "(I" + DB + ")V"), new Redirect("glGetDoublev"));
        table.put(new CallKey(GL11, "glGetInteger", "(I)I"),       new Redirect("glGetInteger"));
        table.put(new CallKey(GL11, "glIsEnabled", "(I)Z"),        new Redirect("glIsEnabled"));

        // ── Clip planes (removed — emulated via CoreStateTracker) ────
        table.put(new CallKey(GL11, "glClipPlane", "(I" + DB + ")V"), new Redirect("glClipPlane"));

        // ── Pixel operations (removed in core — no-ops) ─────────────
        table.put(new CallKey(GL11, "glRasterPos2f", "(FF)V"),     Redirect.deprecated("glRasterPos2f", "Raster Position"));
        table.put(new CallKey(GL11, "glRasterPos2d", "(DD)V"),     Redirect.deprecated("glRasterPos2d", "Raster Position"));
        table.put(new CallKey(GL11, "glRasterPos2i", "(II)V"),     Redirect.deprecated("glRasterPos2i", "Raster Position"));
        table.put(new CallKey(GL11, "glRasterPos3f", "(FFF)V"),    Redirect.deprecated("glRasterPos3f", "Raster Position"));
        table.put(new CallKey(GL11, "glRasterPos3d", "(DDD)V"),    Redirect.deprecated("glRasterPos3d", "Raster Position"));
        table.put(new CallKey(GL11, "glBitmap", "(IIFFFF" + BB + ")V"), Redirect.deprecated("glBitmap", "Bitmap"));
        table.put(new CallKey(GL11, "glDrawPixels", "(IIII" + BB + ")V"), Redirect.deprecated("glDrawPixels", "DrawPixels"));
        table.put(new CallKey(GL11, "glPixelTransferf", "(IF)V"),  Redirect.deprecated("glPixelTransferf", "Pixel Transfer"));
        table.put(new CallKey(GL11, "glPixelTransferi", "(II)V"),  Redirect.deprecated("glPixelTransferi", "Pixel Transfer"));
        table.put(new CallKey(GL11, "glPixelZoom", "(FF)V"),       Redirect.deprecated("glPixelZoom", "Pixel Zoom"));

        // ── GLU / Project ─────────────────────────────────────────────
        table.put(new CallKey("org/lwjgl/util/glu/GLU", "gluPerspective", null),
                  new Redirect("gluPerspective"));
        table.put(new CallKey("org/lwjgl/util/glu/Project", "gluPerspective", null),
                  new Redirect("gluPerspective"));
        table.put(new CallKey("org/lwjgl/util/glu/GLU", "gluLookAt", null),
                  new Redirect("gluLookAt"));
        table.put(new CallKey("org/lwjgl/util/glu/Project", "gluLookAt", null),
                  new Redirect("gluLookAt"));
        table.put(new CallKey("org/lwjgl/util/glu/GLU", "gluProject", null),
                  new Redirect("gluProject"));
        table.put(new CallKey("org/lwjgl/util/glu/Project", "gluProject", null),
                  new Redirect("gluProject"));
        table.put(new CallKey("org/lwjgl/util/glu/GLU", "gluUnProject", null),
                  new Redirect("gluUnProject"));
        table.put(new CallKey("org/lwjgl/util/glu/Project", "gluUnProject", null),
                  new Redirect("gluUnProject"));

        // ── LWJGL3 "C" variant classes ─────────────────────────────────
        // In LWJGL3, GL11 extends GL11C, GL13 extends GL13C, etc.
        // Code that calls GL11C.glEnable() directly bypasses our GL11
        // redirects above. Mirror all core-profile redirects to the C variants.
        String GL11C = "org/lwjgl/opengl/GL11C";
        String GL13C = "org/lwjgl/opengl/GL13C";
        String GL14C = "org/lwjgl/opengl/GL14C";
        String GL20C = "org/lwjgl/opengl/GL20C";

        // GL11C — enable/disable, state, queries, texture
        table.put(new CallKey(GL11C, "glEnable", "(I)V"),           new Redirect("glEnable"));
        table.put(new CallKey(GL11C, "glDisable", "(I)V"),          new Redirect("glDisable"));
        table.put(new CallKey(GL11C, "glDepthFunc", "(I)V"),        new Redirect("glDepthFunc"));
        table.put(new CallKey(GL11C, "glDepthMask", "(Z)V"),        new Redirect("glDepthMask"));
        table.put(new CallKey(GL11C, "glBlendFunc", "(II)V"),       new Redirect("glBlendFunc"));
        table.put(new CallKey(GL11C, "glCullFace", "(I)V"),         new Redirect("glCullFace"));
        table.put(new CallKey(GL11C, "glPolygonOffset", "(FF)V"),   new Redirect("glPolygonOffset"));
        table.put(new CallKey(GL11C, "glColorMask", "(ZZZZ)V"),    new Redirect("glColorMask"));
        table.put(new CallKey(GL11C, "glGetFloatv", "(I" + FB + ")V"), new Redirect("glGetFloatv"));
        table.put(new CallKey(GL11C, "glGetIntegerv", "(I" + IB + ")V"), new Redirect("glGetIntegerv"));
        table.put(new CallKey(GL11C, "glGetDoublev", "(I" + DB + ")V"), new Redirect("glGetDoublev"));
        table.put(new CallKey(GL11C, "glGetInteger", "(I)I"),       new Redirect("glGetInteger"));
        table.put(new CallKey(GL11C, "glIsEnabled", "(I)Z"),        new Redirect("glIsEnabled"));
        table.put(new CallKey(GL11C, "glTexImage2D", "(IIIIIIII" + BB + ")V"), new Redirect("glTexImage2D"));
        table.put(new CallKey(GL11C, "glTexImage2D", "(IIIIIIII" + IB + ")V"), new Redirect("glTexImage2D_IntBuffer", "(IIIIIIII" + IB + ")V"));
        table.put(new CallKey(GL11C, "glTexSubImage2D", "(IIIIIIII" + BB + ")V"), new Redirect("glTexSubImage2D"));
        table.put(new CallKey(GL11C, "glTexSubImage2D", "(IIIIIIII" + IB + ")V"), new Redirect("glTexSubImage2D_IntBuffer", "(IIIIIIII" + IB + ")V"));
        table.put(new CallKey(GL11C, "glBindTexture", "(II)V"),     new Redirect("glBindTexture"));
        table.put(new CallKey(GL11C, "glDeleteTextures", "(I)V"),   new Redirect("glDeleteTextures"));

        // GL13C — active texture
        table.put(new CallKey(GL13C, "glActiveTexture", "(I)V"),    new Redirect("glActiveTexture"));

        // GL14C — blend func separate
        table.put(new CallKey(GL14C, "glBlendFuncSeparate", "(IIII)V"), new Redirect("glBlendFuncSeparate"));

        // GL20C — shader source, program
        table.put(new CallKey(GL20C, "glShaderSource", "(I" + CS + ")V"), new Redirect("glShaderSource"));
        table.put(new CallKey(GL20C, "glUseProgram", "(I)V"),       new Redirect("glUseProgram"));
        table.put(new CallKey(GL20C, "glDeleteProgram", "(I)V"),    new Redirect("glDeleteProgram"));

        return Map.copyOf(table);
    }

    // ── Excluded package prefixes ────────────────────────────────────
    private static final String[] EXCLUDED_PREFIXES = {
        "com.github.gl46core.",        // our own mod
        "net.minecraft.client.renderer.GlStateManager", // handled by MixinGlStateManager
        "org.lwjgl.",                  // LWJGL itself
        "org.objectweb.asm.",          // ASM library
        "org.spongepowered.asm.",      // Mixin
        "zone.rong.mixinbooter.",      // MixinBooter
        "java.",                       // Java stdlib
        "javax.",                      // Java extensions
        "sun.",                        // JDK internals
        "jdk.",                        // JDK internals
        "net.minecraft.launchwrapper.",// LaunchWrapper
        "org.apache.",                 // Log4j, Commons
        "com.google.",                 // Guava, Gson
        "io.netty.",                   // Netty
        "it.unimi.dsi.",               // FastUtil
        "org.joml.",                   // JOML
        "org.taumc.celeritas.",        // Celeritas
        "org.embeddedt.embeddium.",    // Embeddium (Celeritas core)
        "me.cortex.nvidium.",          // Nvidium
        "net.irisshaders.",            // Iris
        "de.johni0702.minecraft.",     // BetterPortals fork (core-profile native)
    };

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null) return null;

        // Skip classes that must never be transformed
        for (String prefix : EXCLUDED_PREFIXES) {
            if (transformedName.startsWith(prefix)) return basicClass;
        }

        try {
            return doTransform(basicClass, transformedName);
        } catch (Throwable e) {
            // Don't let transformer failures block class loading or mixin application
            return basicClass;
        }
    }

    private byte[] doTransform(byte[] basicClass, String className) {
        ClassReader cr = new ClassReader(basicClass);
        ClassWriter cw = new ClassWriter(cr, 0);
        final boolean[] modified = {false};

        ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String mname, String desc, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, mname, desc, signature, exceptions);
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String mname2, String desc2, boolean itf) {
                        if (opcode == Opcodes.INVOKESTATIC) {
                            Redirect redirect = REDIRECT_TABLE.get(new CallKey(owner, mname2, desc2));
                            if (redirect == null) {
                                redirect = REDIRECT_TABLE.get(new CallKey(owner, mname2, null));
                            }
                            if (redirect != null) {
                                if (redirect.deprecatedFeature() != null) {
                                    DeprecatedUsageTracker.record(redirect.deprecatedFeature(), className);
                                }
                                String targetDesc = redirect.targetDesc() != null ? redirect.targetDesc() : desc2;
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, REDIRECTS,
                                        redirect.targetMethod(), targetDesc, false);
                                modified[0] = true;
                                return;
                            }
                        }
                        super.visitMethodInsn(opcode, owner, mname2, desc2, itf);
                    }
                };
            }
        };
        cr.accept(cv, 0);
        return modified[0] ? cw.toByteArray() : basicClass;
    }
}
