package com.github.gl46core.api.shader;

import com.github.gl46core.api.render.FrameContext;
import com.github.gl46core.api.render.PassData;
import com.github.gl46core.api.render.PassResourceDeclaration;
import com.github.gl46core.api.render.PassType;
import com.github.gl46core.api.render.RenderPass;

/**
 * A render pass defined by a shaderpack.
 *
 * Wraps a pack-provided shader program with the RenderPass interface
 * so it can be inserted into the pass graph alongside built-in passes.
 *
 * Shaderpack passes typically:
 *   - Override an existing pass type (e.g. custom terrain shader)
 *   - Add post-processing passes (bloom, DOF, motion blur, etc.)
 *   - Add shadow passes with custom cascade setups
 */
public final class ShaderpackPass implements RenderPass {

    private final String name;
    private final PassType type;
    private final int priority;
    private final int programHandle;    // compiled GL program

    // Pack-defined resource requirements
    private final String[] inputTextures;
    private final String outputTarget;
    private final boolean needsDepth;
    private final boolean needsSceneData;

    // Per-pass uniform callback
    private final ShaderpackUniformProvider uniformProvider;

    public ShaderpackPass(String name, PassType type, int priority,
                          int programHandle, String[] inputTextures,
                          String outputTarget, boolean needsDepth,
                          boolean needsSceneData,
                          ShaderpackUniformProvider uniformProvider) {
        this.name = name;
        this.type = type;
        this.priority = priority;
        this.programHandle = programHandle;
        this.inputTextures = inputTextures;
        this.outputTarget = outputTarget;
        this.needsDepth = needsDepth;
        this.needsSceneData = needsSceneData;
        this.uniformProvider = uniformProvider;
    }

    @Override
    public String getName() { return name; }

    @Override
    public PassType getType() { return type; }

    @Override
    public int getPriority() { return priority; }

    @Override
    public void declareResources(PassResourceDeclaration declaration) {
        if (needsSceneData) declaration.needsSceneData();
        if (needsDepth) declaration.readsDepth();
        if (outputTarget != null) {
            declaration.resource(outputTarget, PassResourceDeclaration.Access.WRITE);
        }
        if (inputTextures != null) {
            for (String tex : inputTextures) {
                declaration.resource(tex, PassResourceDeclaration.Access.READ);
            }
        }
    }

    @Override
    public void setup(FrameContext frame, PassData passData) {
        org.lwjgl.opengl.GL45.glUseProgram(programHandle);
        if (uniformProvider != null) {
            uniformProvider.uploadUniforms(frame, programHandle);
        }
    }

    @Override
    public void execute(FrameContext frame) {
        // Shaderpack passes typically render a fullscreen quad for post,
        // or delegate to the standard submission queues for geometry passes.
        // The actual execution depends on the pass type — geometry passes
        // process the queue, post passes draw a fullscreen triangle.
        if (type == PassType.POST_CHAIN) {
            // Fullscreen triangle (3 vertices, no VAO needed with gl_VertexID)
            org.lwjgl.opengl.GL45.glDrawArrays(org.lwjgl.opengl.GL11.GL_TRIANGLES, 0, 3);
        }
        // Geometry passes are executed by the FrameOrchestrator's queue processing
    }

    public int getProgramHandle() { return programHandle; }
    public String getOutputTarget() { return outputTarget; }
}
