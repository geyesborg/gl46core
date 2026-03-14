package com.github.gl46core.gl;

import com.github.gl46core.GL46Core;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL20;

import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Bridges mod shader programs with CoreMatrixStack by auto-uploading
 * matrix uniforms when a mod's shader uses the deprecated built-in names
 * ({@code gl_ModelViewProjectionMatrix}, {@code gl_ModelViewMatrix}, etc.)
 * that were converted to regular uniforms by {@link ShaderConverter}.
 *
 * <p>Uniform locations are cached per program ID to avoid repeated
 * {@code glGetUniformLocation} calls. The cache is invalidated when a
 * program is deleted.</p>
 */
public final class ModShaderBridge {

    private ModShaderBridge() {}

    // Uniform location cache: program ID → locations (-1 = not present)
    private static final Map<Integer, ProgramUniforms> cache = new HashMap<>();

    // Reusable buffers for matrix uploads (avoids allocation per frame)
    private static final FloatBuffer mat4Buf = BufferUtils.createFloatBuffer(16);
    private static final FloatBuffer mat3Buf = BufferUtils.createFloatBuffer(9);

    // The currently active mod program (0 = none or our own CoreShaderProgram)
    private static int activeModProgram = 0;

    private static class ProgramUniforms {
        final int mvpLoc;
        final int mvLoc;
        final int projLoc;
        final int normalMatLoc;
        final boolean hasAny;

        ProgramUniforms(int program) {
            mvpLoc = GL20.glGetUniformLocation(program, "gl_ModelViewProjectionMatrix");
            mvLoc = GL20.glGetUniformLocation(program, "gl_ModelViewMatrix");
            projLoc = GL20.glGetUniformLocation(program, "gl_ProjectionMatrix");
            normalMatLoc = GL20.glGetUniformLocation(program, "gl_NormalMatrix");
            hasAny = (mvpLoc >= 0 || mvLoc >= 0 || projLoc >= 0 || normalMatLoc >= 0);
        }
    }

    /**
     * Called when a mod's {@code glUseProgram} is intercepted.
     * Uploads current matrices from CoreMatrixStack to any discovered
     * legacy matrix uniforms in the program.
     */
    public static void onUseProgram(int program) {
        activeModProgram = program;

        if (program == 0) {
            CoreShaderProgram.INSTANCE.invalidateProgram();
            return;
        }

        // Skip our own shader program
        if (CoreShaderProgram.INSTANCE.isOurProgram(program)) {
            activeModProgram = 0;
            return;
        }

        // External program bound — invalidate our cached binding so the
        // next CoreShaderProgram.bind() re-issues glUseProgram.
        CoreShaderProgram.INSTANCE.invalidateProgram();

        ProgramUniforms u = cache.get(program);
        if (u == null) {
            u = new ProgramUniforms(program);
            cache.put(program, u);
            if (u.hasAny) {
                GL46Core.LOGGER.debug("[ModShaderBridge] Program {} has legacy matrix uniforms: MVP={} MV={} P={} N={}",
                        program, u.mvpLoc, u.mvLoc, u.projLoc, u.normalMatLoc);
            }
        }

        if (u.hasAny) {
            uploadMatrices(u);
        }
    }

    /**
     * Called when matrices change (from CoreMatrixStack dirty flags).
     * If a mod program is active, re-uploads the matrices.
     */
    public static void onMatrixChanged() {
        if (activeModProgram == 0) return;
        ProgramUniforms u = cache.get(activeModProgram);
        if (u != null && u.hasAny) {
            uploadMatrices(u);
        }
    }

    /**
     * Remove a program from the cache (called when program is deleted).
     */
    public static void onDeleteProgram(int program) {
        cache.remove(program);
        if (activeModProgram == program) activeModProgram = 0;
    }

    private static void uploadMatrices(ProgramUniforms u) {
        CoreMatrixStack ms = CoreMatrixStack.INSTANCE;

        if (u.mvLoc >= 0) {
            mat4Buf.clear();
            ms.getModelView().get(mat4Buf);
            mat4Buf.rewind();
            GL20.glUniformMatrix4fv(u.mvLoc, false, mat4Buf);
        }

        if (u.projLoc >= 0) {
            mat4Buf.clear();
            ms.getProjection().get(mat4Buf);
            mat4Buf.rewind();
            GL20.glUniformMatrix4fv(u.projLoc, false, mat4Buf);
        }

        if (u.mvpLoc >= 0) {
            Matrix4f mvp = new Matrix4f();
            ms.getProjection().mul(ms.getModelView(), mvp);
            mat4Buf.clear();
            mvp.get(mat4Buf);
            mat4Buf.rewind();
            GL20.glUniformMatrix4fv(u.mvpLoc, false, mat4Buf);
        }

        if (u.normalMatLoc >= 0) {
            // gl_NormalMatrix = transpose(inverse(mat3(modelview)))
            Matrix3f normalMat = new Matrix3f();
            ms.getModelView().get3x3(normalMat);
            normalMat.invert().transpose();
            mat3Buf.clear();
            normalMat.get(mat3Buf);
            mat3Buf.rewind();
            GL20.glUniformMatrix3fv(u.normalMatLoc, false, mat3Buf);
        }
    }
}
