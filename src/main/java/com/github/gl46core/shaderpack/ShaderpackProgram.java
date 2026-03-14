package com.github.gl46core.shaderpack;

import com.github.gl46core.GL46Core;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

/**
 * A compiled shaderpack program (vertex + fragment shader pair).
 *
 * Wraps a GL program ID with its associated {@link UniformBridge} for
 * uploading OptiFine/Iris uniform values. Each program corresponds to
 * one shaderpack stage (e.g. gbuffers_terrain, composite, final).
 *
 * Compilation inserts a version directive and common defines before
 * the shaderpack source code.
 */
public final class ShaderpackProgram {

    private static final String VERSION_HEADER = "#version 460 core\n";

    private final String name;
    private int programId;
    private final UniformBridge bridge;
    private int activeUniforms;

    /**
     * Create a program for the given stage name.
     *
     * @param name program name (e.g. "gbuffers_terrain")
     */
    public ShaderpackProgram(String name) {
        this.name = name;
        this.bridge = new UniformBridge();
    }

    /**
     * Compile and link vertex + fragment source.
     *
     * @param vertSource vertex shader GLSL (without #version)
     * @param fragSource fragment shader GLSL (without #version)
     * @return true if compilation and linking succeeded
     */
    public boolean compile(String vertSource, String fragSource) {
        if (programId != 0) destroy();

        int vert = compileShader(GL20.GL_VERTEX_SHADER, vertSource);
        if (vert == 0) return false;

        int frag = compileShader(GL20.GL_FRAGMENT_SHADER, fragSource);
        if (frag == 0) {
            GL20.glDeleteShader(vert);
            return false;
        }

        programId = GL20.glCreateProgram();
        GL20.glAttachShader(programId, vert);
        GL20.glAttachShader(programId, frag);

        // Bind standard attribute locations for compatibility
        GL20.glBindAttribLocation(programId, 0, "mc_Entity");
        GL20.glBindAttribLocation(programId, 10, "mc_midTexCoord");
        GL20.glBindAttribLocation(programId, 11, "at_tangent");

        GL20.glLinkProgram(programId);

        GL20.glDeleteShader(vert);
        GL20.glDeleteShader(frag);

        if (GL20.glGetProgrami(programId, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetProgramInfoLog(programId, 4096);
            GL46Core.LOGGER.error("Failed to link shaderpack program '{}': {}", name, log);
            GL20.glDeleteProgram(programId);
            programId = 0;
            return false;
        }

        // Resolve uniform locations
        activeUniforms = bridge.resolve(programId);
        GL46Core.LOGGER.info("Compiled shaderpack program '{}': {} active uniforms", name, activeUniforms);
        return true;
    }

    /**
     * Bind this program and upload all uniforms for the current frame.
     */
    public void bind() {
        if (programId == 0) return;
        GL20.glUseProgram(programId);
        bridge.upload();
    }

    /**
     * Unbind — restore no program.
     */
    public void unbind() {
        GL20.glUseProgram(0);
    }

    /**
     * Destroy the GL program.
     */
    public void destroy() {
        if (programId != 0) {
            GL20.glDeleteProgram(programId);
            programId = 0;
        }
    }

    // ── Accessors ──

    public String getName()            { return name; }
    public int    getProgramId()       { return programId; }
    public boolean isCompiled()        { return programId != 0; }
    public int    getActiveUniforms()  { return activeUniforms; }
    public UniformBridge getBridge()   { return bridge; }

    // ═══════════════════════════════════════════════════════════════════
    // Internal
    // ═══════════════════════════════════════════════════════════════════

    private int compileShader(int type, String source) {
        if (source == null || source.isEmpty()) {
            GL46Core.LOGGER.error("Missing {} source for shaderpack program '{}'",
                    type == GL20.GL_VERTEX_SHADER ? "vertex" : "fragment", name);
            return 0;
        }

        // Prepend version if not already present
        String fullSource;
        if (source.startsWith("#version")) {
            fullSource = source;
        } else {
            fullSource = VERSION_HEADER + source;
        }

        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, fullSource);
        GL20.glCompileShader(shader);

        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shader, 4096);
            GL46Core.LOGGER.error("Failed to compile {} for shaderpack program '{}': {}",
                    type == GL20.GL_VERTEX_SHADER ? "vertex" : "fragment", name, log);
            GL20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    @Override
    public String toString() {
        return String.format("ShaderpackProgram[%s id=%d uniforms=%d]",
                name, programId, activeUniforms);
    }
}
