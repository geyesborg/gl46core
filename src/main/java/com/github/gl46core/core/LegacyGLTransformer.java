package com.github.gl46core.core;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.*;

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

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null) return null;

        // Skip classes that must never be transformed
        if (transformedName.startsWith("com.github.gl46core.")       // our own mod
            || transformedName.startsWith("org.lwjgl.")              // LWJGL itself
            || transformedName.startsWith("org.objectweb.asm.")      // ASM library
            || transformedName.startsWith("org.spongepowered.asm.")  // Mixin
            || transformedName.startsWith("zone.rong.mixinbooter.")  // MixinBooter
            || transformedName.startsWith("java.")                   // Java stdlib
            || transformedName.startsWith("javax.")                  // Java extensions
            || transformedName.startsWith("sun.")                    // JDK internals
            || transformedName.startsWith("jdk.")                    // JDK internals
            || transformedName.startsWith("net.minecraft.launchwrapper.") // LaunchWrapper
            || transformedName.startsWith("org.apache.")             // Log4j, Commons
            || transformedName.startsWith("com.google.")             // Guava, Gson
            || transformedName.startsWith("io.netty.")               // Netty
            || transformedName.startsWith("it.unimi.dsi.")           // FastUtil
            || transformedName.startsWith("org.joml.")               // JOML
        ) {
            return basicClass;
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
                        // Redirect GL11.glMultMatrix(FloatBuffer)
                        if (opcode == Opcodes.INVOKESTATIC
                                && "org/lwjgl/opengl/GL11".equals(owner)
                                && "glMultMatrix".equals(mname2)
                                && "(Ljava/nio/FloatBuffer;)V".equals(desc2)) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, REDIRECTS,
                                    "glMultMatrix", "(Ljava/nio/FloatBuffer;)V", false);
                            modified[0] = true;
                        }
                        // Redirect GLU.gluPerspective
                        else if (opcode == Opcodes.INVOKESTATIC
                                && "org/lwjgl/util/glu/GLU".equals(owner)
                                && "gluPerspective".equals(mname2)) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, REDIRECTS,
                                    "gluPerspective", desc2, false);
                            modified[0] = true;
                        }
                        // Redirect Project.gluPerspective (called by GLU or directly)
                        else if (opcode == Opcodes.INVOKESTATIC
                                && "org/lwjgl/util/glu/Project".equals(owner)
                                && "gluPerspective".equals(mname2)) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, REDIRECTS,
                                    "gluPerspective", desc2, false);
                            modified[0] = true;
                        }
                        // Redirect GLU.gluLookAt / Project.gluLookAt
                        else if (opcode == Opcodes.INVOKESTATIC
                                && ("org/lwjgl/util/glu/GLU".equals(owner) || "org/lwjgl/util/glu/Project".equals(owner))
                                && "gluLookAt".equals(mname2)) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, REDIRECTS,
                                    "gluLookAt", desc2, false);
                            modified[0] = true;
                        }
                        // Redirect GL11.glLoadIdentity
                        else if (opcode == Opcodes.INVOKESTATIC
                                && "org/lwjgl/opengl/GL11".equals(owner)
                                && "glLoadIdentity".equals(mname2)
                                && "()V".equals(desc2)) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, REDIRECTS,
                                    "glLoadIdentity", "()V", false);
                            modified[0] = true;
                        }
                        // Redirect GL11.glMatrixMode
                        else if (opcode == Opcodes.INVOKESTATIC
                                && "org/lwjgl/opengl/GL11".equals(owner)
                                && "glMatrixMode".equals(mname2)
                                && "(I)V".equals(desc2)) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, REDIRECTS,
                                    "glMatrixMode", "(I)V", false);
                            modified[0] = true;
                        }
                        // Redirect GL11.glOrtho
                        else if (opcode == Opcodes.INVOKESTATIC
                                && "org/lwjgl/opengl/GL11".equals(owner)
                                && "glOrtho".equals(mname2)
                                && "(DDDDDD)V".equals(desc2)) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, REDIRECTS,
                                    "glOrtho", "(DDDDDD)V", false);
                            modified[0] = true;
                        }
                        // Redirect GL11.glRotatef
                        else if (opcode == Opcodes.INVOKESTATIC
                                && "org/lwjgl/opengl/GL11".equals(owner)
                                && "glRotatef".equals(mname2)
                                && "(FFFF)V".equals(desc2)) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, REDIRECTS,
                                    "glRotatef", "(FFFF)V", false);
                            modified[0] = true;
                        }
                        // Redirect GL11.glScalef
                        else if (opcode == Opcodes.INVOKESTATIC
                                && "org/lwjgl/opengl/GL11".equals(owner)
                                && "glScalef".equals(mname2)
                                && "(FFF)V".equals(desc2)) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, REDIRECTS,
                                    "glScalef", "(FFF)V", false);
                            modified[0] = true;
                        }
                        // Redirect GL11.glTranslatef
                        else if (opcode == Opcodes.INVOKESTATIC
                                && "org/lwjgl/opengl/GL11".equals(owner)
                                && "glTranslatef".equals(mname2)
                                && "(FFF)V".equals(desc2)) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, REDIRECTS,
                                    "glTranslatef", "(FFF)V", false);
                            modified[0] = true;
                        }
                        // Redirect GL11.glTranslated
                        else if (opcode == Opcodes.INVOKESTATIC
                                && "org/lwjgl/opengl/GL11".equals(owner)
                                && "glTranslated".equals(mname2)
                                && "(DDD)V".equals(desc2)) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, REDIRECTS,
                                    "glTranslated", "(DDD)V", false);
                            modified[0] = true;
                        }
                        // Redirect GL11.glColor4f
                        else if (opcode == Opcodes.INVOKESTATIC
                                && "org/lwjgl/opengl/GL11".equals(owner)
                                && "glColor4f".equals(mname2)
                                && "(FFFF)V".equals(desc2)) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, REDIRECTS,
                                    "glColor4f", "(FFFF)V", false);
                            modified[0] = true;
                        }
                        // Redirect GL11.glPushMatrix
                        else if (opcode == Opcodes.INVOKESTATIC
                                && "org/lwjgl/opengl/GL11".equals(owner)
                                && "glPushMatrix".equals(mname2)
                                && "()V".equals(desc2)) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, REDIRECTS,
                                    "glPushMatrix", "()V", false);
                            modified[0] = true;
                        }
                        // Redirect GL11.glPopMatrix
                        else if (opcode == Opcodes.INVOKESTATIC
                                && "org/lwjgl/opengl/GL11".equals(owner)
                                && "glPopMatrix".equals(mname2)
                                && "()V".equals(desc2)) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, REDIRECTS,
                                    "glPopMatrix", "()V", false);
                            modified[0] = true;
                        }
                        // Redirect GL11.glColor3f
                        else if (opcode == Opcodes.INVOKESTATIC
                                && "org/lwjgl/opengl/GL11".equals(owner)
                                && "glColor3f".equals(mname2)
                                && "(FFF)V".equals(desc2)) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, REDIRECTS,
                                    "glColor3f", "(FFF)V", false);
                            modified[0] = true;
                        }
                        // ── Immediate mode redirects ──
                        // Redirect GL11.glBegin
                        else if (opcode == Opcodes.INVOKESTATIC
                                && "org/lwjgl/opengl/GL11".equals(owner)
                                && "glBegin".equals(mname2)
                                && "(I)V".equals(desc2)) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, REDIRECTS,
                                    "glBegin", "(I)V", false);
                            modified[0] = true;
                        }
                        // Redirect GL11.glEnd
                        else if (opcode == Opcodes.INVOKESTATIC
                                && "org/lwjgl/opengl/GL11".equals(owner)
                                && "glEnd".equals(mname2)
                                && "()V".equals(desc2)) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, REDIRECTS,
                                    "glEnd", "()V", false);
                            modified[0] = true;
                        }
                        // Redirect GL11.glVertex3f
                        else if (opcode == Opcodes.INVOKESTATIC
                                && "org/lwjgl/opengl/GL11".equals(owner)
                                && "glVertex3f".equals(mname2)
                                && "(FFF)V".equals(desc2)) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, REDIRECTS,
                                    "glVertex3f", "(FFF)V", false);
                            modified[0] = true;
                        }
                        // Redirect GL11.glVertex2f
                        else if (opcode == Opcodes.INVOKESTATIC
                                && "org/lwjgl/opengl/GL11".equals(owner)
                                && "glVertex2f".equals(mname2)
                                && "(FF)V".equals(desc2)) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, REDIRECTS,
                                    "glVertex2f", "(FF)V", false);
                            modified[0] = true;
                        }
                        // Redirect GL11.glTexCoord2f
                        else if (opcode == Opcodes.INVOKESTATIC
                                && "org/lwjgl/opengl/GL11".equals(owner)
                                && "glTexCoord2f".equals(mname2)
                                && "(FF)V".equals(desc2)) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, REDIRECTS,
                                    "glTexCoord2f", "(FF)V", false);
                            modified[0] = true;
                        }
                        // Redirect GL11.glNormal3f
                        else if (opcode == Opcodes.INVOKESTATIC
                                && "org/lwjgl/opengl/GL11".equals(owner)
                                && "glNormal3f".equals(mname2)
                                && "(FFF)V".equals(desc2)) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, REDIRECTS,
                                    "glNormal3f", "(FFF)V", false);
                            modified[0] = true;
                        }
                        else {
                            super.visitMethodInsn(opcode, owner, mname2, desc2, itf);
                        }
                    }
                };
            }
        };
        cr.accept(cv, 0);
        return modified[0] ? cw.toByteArray() : basicClass;
    }
}
