package com.github.gl46core.core;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.*;

import java.util.HashMap;
import java.util.Map;

/**
 * ASM transformer that redirects direct GL11/GLU legacy calls that bypass GlStateManager.
 *
 * Targets ALL classes (Minecraft, Forge, and third-party mods) to catch:
 * - Matrix: glMultMatrix, glLoadIdentity, glMatrixMode, glPushMatrix, glPopMatrix,
 *           glRotatef, glScalef, glTranslatef, glTranslated, glOrtho
 * - Color:  glColor4f, glColor3f
 * - Immediate mode: glBegin, glEnd, glVertex3f, glVertex2f, glTexCoord2f, glNormal3f
 * - GLU:    gluPerspective, gluLookAt
 *
 * Excludes: gl46core itself, LWJGL, ASM, Mixin, Java stdlib, Cleanroom internals.
 */
public class LegacyGLTransformer implements IClassTransformer {

    private static final String REDIRECTS = "com/github/gl46core/gl/LegacyGLRedirects";

    /**
     * A redirect entry: maps a source (owner + method + descriptor) to a target method name.
     * The target descriptor is always the same as the source unless overridden.
     */
    private record Redirect(String targetMethod, String targetDesc) {
        Redirect(String targetMethod) {
            this(targetMethod, null); // null = use source descriptor
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

        // GL11 matrix ops
        table.put(new CallKey("org/lwjgl/opengl/GL11", "glMultMatrix", "(Ljava/nio/FloatBuffer;)V"),
                  new Redirect("glMultMatrix", "(Ljava/nio/FloatBuffer;)V"));
        table.put(new CallKey("org/lwjgl/opengl/GL11", "glLoadIdentity", "()V"),
                  new Redirect("glLoadIdentity", "()V"));
        table.put(new CallKey("org/lwjgl/opengl/GL11", "glMatrixMode", "(I)V"),
                  new Redirect("glMatrixMode", "(I)V"));
        table.put(new CallKey("org/lwjgl/opengl/GL11", "glPushMatrix", "()V"),
                  new Redirect("glPushMatrix", "()V"));
        table.put(new CallKey("org/lwjgl/opengl/GL11", "glPopMatrix", "()V"),
                  new Redirect("glPopMatrix", "()V"));
        table.put(new CallKey("org/lwjgl/opengl/GL11", "glRotatef", "(FFFF)V"),
                  new Redirect("glRotatef", "(FFFF)V"));
        table.put(new CallKey("org/lwjgl/opengl/GL11", "glScalef", "(FFF)V"),
                  new Redirect("glScalef", "(FFF)V"));
        table.put(new CallKey("org/lwjgl/opengl/GL11", "glTranslatef", "(FFF)V"),
                  new Redirect("glTranslatef", "(FFF)V"));
        table.put(new CallKey("org/lwjgl/opengl/GL11", "glTranslated", "(DDD)V"),
                  new Redirect("glTranslated", "(DDD)V"));
        table.put(new CallKey("org/lwjgl/opengl/GL11", "glOrtho", "(DDDDDD)V"),
                  new Redirect("glOrtho", "(DDDDDD)V"));

        // GL11 color
        table.put(new CallKey("org/lwjgl/opengl/GL11", "glColor4f", "(FFFF)V"),
                  new Redirect("glColor4f", "(FFFF)V"));
        table.put(new CallKey("org/lwjgl/opengl/GL11", "glColor3f", "(FFF)V"),
                  new Redirect("glColor3f", "(FFF)V"));

        // GL11 immediate mode
        table.put(new CallKey("org/lwjgl/opengl/GL11", "glBegin", "(I)V"),
                  new Redirect("glBegin", "(I)V"));
        table.put(new CallKey("org/lwjgl/opengl/GL11", "glEnd", "()V"),
                  new Redirect("glEnd", "()V"));
        table.put(new CallKey("org/lwjgl/opengl/GL11", "glVertex3f", "(FFF)V"),
                  new Redirect("glVertex3f", "(FFF)V"));
        table.put(new CallKey("org/lwjgl/opengl/GL11", "glVertex2f", "(FF)V"),
                  new Redirect("glVertex2f", "(FF)V"));
        table.put(new CallKey("org/lwjgl/opengl/GL11", "glTexCoord2f", "(FF)V"),
                  new Redirect("glTexCoord2f", "(FF)V"));
        table.put(new CallKey("org/lwjgl/opengl/GL11", "glNormal3f", "(FFF)V"),
                  new Redirect("glNormal3f", "(FFF)V"));

        // GLU / Project — match any descriptor (desc = null in key)
        table.put(new CallKey("org/lwjgl/util/glu/GLU", "gluPerspective", null),
                  new Redirect("gluPerspective"));
        table.put(new CallKey("org/lwjgl/util/glu/Project", "gluPerspective", null),
                  new Redirect("gluPerspective"));
        table.put(new CallKey("org/lwjgl/util/glu/GLU", "gluLookAt", null),
                  new Redirect("gluLookAt"));
        table.put(new CallKey("org/lwjgl/util/glu/Project", "gluLookAt", null),
                  new Redirect("gluLookAt"));

        return Map.copyOf(table);
    }

    // ── Excluded package prefixes ────────────────────────────────────
    private static final String[] EXCLUDED_PREFIXES = {
        "com.github.gl46core.",        // our own mod
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
    };

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null) return null;

        // Skip classes that must never be transformed
        for (String prefix : EXCLUDED_PREFIXES) {
            if (transformedName.startsWith(prefix)) return basicClass;
        }

        try {
            return doTransform(basicClass);
        } catch (Throwable e) {
            // Don't let transformer failures block class loading or mixin application
            return basicClass;
        }
    }

    private byte[] doTransform(byte[] basicClass) {
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
                            // Try exact match first (owner + method + descriptor)
                            Redirect redirect = REDIRECT_TABLE.get(new CallKey(owner, mname2, desc2));
                            // Fall back to wildcard descriptor match (for GLU methods)
                            if (redirect == null) {
                                redirect = REDIRECT_TABLE.get(new CallKey(owner, mname2, null));
                            }
                            if (redirect != null) {
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
