package com.github.gl46core.api.render.deferred;

import com.github.gl46core.api.render.PassType;
import com.github.gl46core.gl.CoreShaderProgram;
import com.github.gl46core.gl.ShaderVariants;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL45;

/**
 * Replays recorded {@link DrawCommand}s from a {@link DrawCommandBuffer}
 * in sorted pass order, minimizing GL state changes.
 *
 * Execution strategy:
 *   1. Buffer is pre-sorted by pass type, then sort key
 *   2. For each pass group, bind the pass UBO once
 *   3. Within a group, track last-bound state and skip redundant calls:
 *      - Shader program (variant key)
 *      - VAO binding
 *      - Texture bindings
 *      - Blend/depth/cull state
 *   4. Issue the actual draw call
 *
 * This executor does NOT manage FBO binding or pass UBO uploads —
 * those are handled by {@code FrameOrchestrator.setActivePass()}.
 */
public final class DrawCommandExecutor {

    // Last-bound state for redundancy elimination
    private int lastProgram;
    private int lastVao;
    private int lastTexture;
    private int lastLightmap;
    private boolean lastBlend;
    private int lastBlendSrc;
    private int lastBlendDst;
    private boolean lastDepthTest;
    private boolean lastDepthMask;
    private boolean lastCullFace;

    // Stats
    private int drawCallsIssued;
    private int stateChanges;
    private long replayTimeNanos;

    public DrawCommandExecutor() {}

    /**
     * Reset state tracking for a new replay pass.
     */
    public void beginReplay() {
        lastProgram = -1;
        lastVao = -1;
        lastTexture = -1;
        lastLightmap = -1;
        lastBlend = false;
        lastBlendSrc = -1;
        lastBlendDst = -1;
        lastDepthTest = true;
        lastDepthMask = true;
        lastCullFace = true;
        drawCallsIssued = 0;
        stateChanges = 0;
        replayTimeNanos = 0;
    }

    /**
     * Replay all commands in the buffer for a specific pass type.
     *
     * @param buffer     the sorted command buffer
     * @param passType   which pass to replay
     * @param frameVbo   the deferred VBO handle (for VAO vertex buffer binding)
     */
    public void replayPass(DrawCommandBuffer buffer, PassType passType, int frameVbo) {
        long start = System.nanoTime();

        int startIdx = buffer.findPassStart(passType);
        int endIdx = buffer.findPassEnd(passType);

        for (int i = startIdx; i < endIdx; i++) {
            DrawCommand cmd = buffer.get(i);
            if (!cmd.isActive() || cmd.passType != passType) continue;
            executeCommand(cmd, frameVbo);
        }

        replayTimeNanos += System.nanoTime() - start;
    }

    /**
     * Replay ALL commands in sorted order (all passes).
     *
     * @param buffer   the sorted command buffer
     * @param frameVbo the deferred VBO handle
     */
    public void replayAll(DrawCommandBuffer buffer, int frameVbo) {
        long start = System.nanoTime();

        for (int i = 0; i < buffer.getCount(); i++) {
            DrawCommand cmd = buffer.get(i);
            if (!cmd.isActive()) continue;
            executeCommand(cmd, frameVbo);
        }

        replayTimeNanos = System.nanoTime() - start;
    }

    /**
     * Execute a single draw command, applying only changed state.
     */
    private void executeCommand(DrawCommand cmd, int frameVbo) {
        // Bind VAO
        if (cmd.vao != lastVao) {
            GL30.glBindVertexArray(cmd.vao);
            lastVao = cmd.vao;
            stateChanges++;

            // Re-bind the frame VBO to the VAO's binding point 0
            GL45.glVertexArrayVertexBuffer(cmd.vao, 0, frameVbo, cmd.vboOffset, cmd.stride);
        } else if (cmd.vboOffset != 0) {
            // Same VAO but different offset — re-bind with new offset
            GL45.glVertexArrayVertexBuffer(cmd.vao, 0, frameVbo, cmd.vboOffset, cmd.stride);
        }

        // Bind shader program
        int programId = ShaderVariants.getProgram(cmd.shaderVariantKey);
        if (programId != lastProgram) {
            org.lwjgl.opengl.GL20.glUseProgram(programId);
            lastProgram = programId;
            stateChanges++;
        }

        // Bind textures
        if (cmd.textureId != lastTexture) {
            GL45.glBindTextureUnit(0, cmd.textureId);
            lastTexture = cmd.textureId;
            stateChanges++;
        }
        if (cmd.lightmapTextureId != 0 && cmd.lightmapTextureId != lastLightmap) {
            GL45.glBindTextureUnit(1, cmd.lightmapTextureId);
            lastLightmap = cmd.lightmapTextureId;
            stateChanges++;
        }

        // Blend state
        if (cmd.blendEnabled != lastBlend) {
            if (cmd.blendEnabled) {
                GL11.glEnable(GL11.GL_BLEND);
            } else {
                GL11.glDisable(GL11.GL_BLEND);
            }
            lastBlend = cmd.blendEnabled;
            stateChanges++;
        }
        if (cmd.blendEnabled && (cmd.blendSrcRgb != lastBlendSrc || cmd.blendDstRgb != lastBlendDst)) {
            GL14.glBlendFuncSeparate(cmd.blendSrcRgb, cmd.blendDstRgb,
                    cmd.blendSrcAlpha, cmd.blendDstAlpha);
            lastBlendSrc = cmd.blendSrcRgb;
            lastBlendDst = cmd.blendDstRgb;
            stateChanges++;
        }

        // Depth state
        if (cmd.depthTest != lastDepthTest) {
            if (cmd.depthTest) GL11.glEnable(GL11.GL_DEPTH_TEST);
            else GL11.glDisable(GL11.GL_DEPTH_TEST);
            lastDepthTest = cmd.depthTest;
            stateChanges++;
        }
        if (cmd.depthMask != lastDepthMask) {
            GL11.glDepthMask(cmd.depthMask);
            lastDepthMask = cmd.depthMask;
            stateChanges++;
        }

        // Cull state
        if (cmd.cullFace != lastCullFace) {
            if (cmd.cullFace) GL11.glEnable(GL11.GL_CULL_FACE);
            else GL11.glDisable(GL11.GL_CULL_FACE);
            lastCullFace = cmd.cullFace;
            stateChanges++;
        }

        // Issue draw call
        if (cmd.indexCount > 0 && cmd.eboHandle != 0) {
            GL45.glVertexArrayElementBuffer(cmd.vao, cmd.eboHandle);
            GL11.glDrawElements(cmd.drawMode, cmd.indexCount, GL11.GL_UNSIGNED_INT, 0);
        } else {
            GL11.glDrawArrays(cmd.drawMode, 0, cmd.vertexCount);
        }
        drawCallsIssued++;
    }

    // ── Stats ──

    public int  getDrawCallsIssued() { return drawCallsIssued; }
    public int  getStateChanges()    { return stateChanges; }
    public long getReplayTimeNanos() { return replayTimeNanos; }
    public double getReplayTimeMs()  { return replayTimeNanos / 1_000_000.0; }
}
