package com.github.gl46core.gl;

import com.github.gl46core.api.render.FrameOrchestrator;
import com.github.gl46core.api.render.deferred.DeferredVboAllocator;
import com.github.gl46core.api.render.deferred.DrawCommand;
import com.github.gl46core.api.render.deferred.DrawCommandBuffer;
import com.github.gl46core.api.translate.LegacyDrawTranslator;
import com.github.gl46core.api.translate.LegacyStateInterpreter;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL45;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Core-profile replacement for WorldVertexBufferUploader.draw().
 * Uses GL4.5 DSA for all buffer and VAO operations.
 *
 * Attribute layout (matches shader layout qualifiers):
 *   0 = position  (vec3 float)
 *   1 = color     (vec4 ubyte, normalized)
 *   2 = texcoord  (vec2 float)
 *   3 = lightmap  (vec2 short)
 *   4 = normal    (vec3 byte, normalized)
 */
public final class CoreDrawHandler {

    /**
     * Per-thread draw state. VAOs are per-GL-context (not shared between
     * contexts), so each thread that renders (main thread, Modern Splash's
     * SharedDrawable thread, etc.) needs its own VAO + VBO + format cache.
     */
    private static class ThreadDrawState {
        int vao;
        int vbo;
        int vboCapacity;
        VertexFormat lastFormat;
    }

    private static final ThreadLocal<ThreadDrawState> threadState =
            ThreadLocal.withInitial(ThreadDrawState::new);

    // Track all thread states for cleanup on shutdown
    private static final ConcurrentHashMap<Long, ThreadDrawState> allStates = new ConcurrentHashMap<>();

    private CoreDrawHandler() {}

    /**
     * Draw the contents of a BufferBuilder using core-profile GL + DSA.
     */
    public static void draw(BufferBuilder bufferBuilder) {
        int vertexCount = bufferBuilder.getVertexCount();
        if (vertexCount <= 0) {
            bufferBuilder.reset();
            return;
        }

        try {
        // Flush any pending immediate-mode vertices before this draw
        ImmediateModeEmulator.INSTANCE.flush();
        CoreShaderProgram.INSTANCE.ensureInitialized();

        VertexFormat format = bufferBuilder.getVertexFormat();
        int stride = format.getSize();
        ByteBuffer data = bufferBuilder.getByteBuffer();
        List<VertexFormatElement> elements = format.getElements();

        // Detect which attributes are present in this format
        boolean hasColor = false;
        boolean hasTexCoord = false;
        boolean hasLightMap = false;
        boolean hasNormal = false;
        int posOffset = 0;
        int colorOffset = 0;
        int texCoordOffset = 0;
        int lightMapOffset = 0;
        int normalOffset = 0;

        for (int i = 0; i < elements.size(); i++) {
            VertexFormatElement elem = elements.get(i);
            int offset = format.getOffset(i);
            switch (elem.getUsage()) {
                case POSITION -> posOffset = offset;
                case COLOR -> {
                    hasColor = true;
                    colorOffset = offset;
                }
                case UV -> {
                    if (elem.getIndex() == 0) {
                        hasTexCoord = true;
                        texCoordOffset = offset;
                    } else if (elem.getIndex() == 1) {
                        hasLightMap = true;
                        lightMapOffset = offset;
                    }
                }
                case NORMAL -> {
                    hasNormal = true;
                    normalOffset = offset;
                }
                default -> {}
            }
        }

        // Get or create per-thread VAO/VBO (VAOs are per-GL-context)
        ThreadDrawState ts = threadState.get();
        if (ts.vao == 0) {
            int[] vaos = new int[1];
            GL45.glCreateVertexArrays(vaos);
            ts.vao = vaos[0];
            int[] bufs = new int[1];
            GL45.glCreateBuffers(bufs);
            ts.vbo = bufs[0];
            allStates.put(Thread.currentThread().getId(), ts);
            com.github.gl46core.GL46Core.LOGGER.debug(
                    "[CoreDrawHandler] Created thread-local VAO={} VBO={} for thread {}",
                    ts.vao, ts.vbo, Thread.currentThread().getName());
        }
        int vao = ts.vao;
        int vbo = ts.vbo;

        GL30.glBindVertexArray(vao);
        CoreVboDrawHandler.setTerrainVaoUnbound();

        // Upload vertex data — grow VBO capacity only when needed
        int dataSize = vertexCount * stride;
        data.position(0);
        data.limit(dataSize);
        if (dataSize > ts.vboCapacity) {
            // Need to reallocate — DSA immutable storage requires recreating the buffer
            int newCapacity = Math.max(dataSize, ts.vboCapacity * 2);
            int[] bufs = new int[1];
            GL45.glCreateBuffers(bufs);
            int newVbo = bufs[0];
            GL45.glNamedBufferStorage(newVbo, newCapacity, GL45.GL_DYNAMIC_STORAGE_BIT);
            // Re-attach to VAO binding point 0
            GL45.glVertexArrayVertexBuffer(vao, 0, newVbo, 0, stride);
            // Delete old VBO
            if (ts.vbo != 0) GL45.glDeleteBuffers(ts.vbo);
            ts.vbo = newVbo;
            vbo = newVbo;
            ts.vboCapacity = newCapacity;
            ts.lastFormat = null; // force re-specify attribs for new buffer
        }
        GL45.glNamedBufferSubData(vbo, 0, data);

        // Only re-specify vertex attribs when format changes
        if (format != ts.lastFormat) {
            ts.lastFormat = format;

            // Bind VBO to VAO binding point 0 with current stride
            GL45.glVertexArrayVertexBuffer(vao, 0, vbo, 0, stride);

            // Position (always present) — vec3 float
            GL45.glEnableVertexArrayAttrib(vao, CoreShaderProgram.ATTR_POSITION);
            GL45.glVertexArrayAttribFormat(vao, CoreShaderProgram.ATTR_POSITION, 3, GL11.GL_FLOAT, false, posOffset);
            GL45.glVertexArrayAttribBinding(vao, CoreShaderProgram.ATTR_POSITION, 0);

            // Color — vec4 ubyte normalized
            if (hasColor) {
                GL45.glEnableVertexArrayAttrib(vao, CoreShaderProgram.ATTR_COLOR);
                GL45.glVertexArrayAttribFormat(vao, CoreShaderProgram.ATTR_COLOR, 4, GL11.GL_UNSIGNED_BYTE, true, colorOffset);
                GL45.glVertexArrayAttribBinding(vao, CoreShaderProgram.ATTR_COLOR, 0);
            } else {
                GL45.glDisableVertexArrayAttrib(vao, CoreShaderProgram.ATTR_COLOR);
            }

            // Texcoord — vec2 float
            if (hasTexCoord) {
                GL45.glEnableVertexArrayAttrib(vao, CoreShaderProgram.ATTR_TEXCOORD);
                GL45.glVertexArrayAttribFormat(vao, CoreShaderProgram.ATTR_TEXCOORD, 2, GL11.GL_FLOAT, false, texCoordOffset);
                GL45.glVertexArrayAttribBinding(vao, CoreShaderProgram.ATTR_TEXCOORD, 0);
            } else {
                GL45.glDisableVertexArrayAttrib(vao, CoreShaderProgram.ATTR_TEXCOORD);
            }

            // Lightmap — vec2 short
            if (hasLightMap) {
                GL45.glEnableVertexArrayAttrib(vao, CoreShaderProgram.ATTR_LIGHTMAP);
                GL45.glVertexArrayAttribFormat(vao, CoreShaderProgram.ATTR_LIGHTMAP, 2, GL11.GL_SHORT, false, lightMapOffset);
                GL45.glVertexArrayAttribBinding(vao, CoreShaderProgram.ATTR_LIGHTMAP, 0);
            } else {
                GL45.glDisableVertexArrayAttrib(vao, CoreShaderProgram.ATTR_LIGHTMAP);
            }

            // Normal — vec3 byte normalized
            if (hasNormal) {
                GL45.glEnableVertexArrayAttrib(vao, CoreShaderProgram.ATTR_NORMAL);
                GL45.glVertexArrayAttribFormat(vao, CoreShaderProgram.ATTR_NORMAL, 3, GL11.GL_BYTE, true, normalOffset);
                GL45.glVertexArrayAttribBinding(vao, CoreShaderProgram.ATTR_NORMAL, 0);
            } else {
                GL45.glDisableVertexArrayAttrib(vao, CoreShaderProgram.ATTR_NORMAL);
            }
        }

        // Bind shader and upload UBO data
        CoreShaderProgram.INSTANCE.bind(hasColor, hasTexCoord, hasNormal, hasLightMap);

        int drawMode = bufferBuilder.getDrawMode();

        // Branch: deferred mode records command, immediate mode draws now
        FrameOrchestrator orch = FrameOrchestrator.INSTANCE;
        if (orch.isDeferredMode()) {
            // Deferred path — append vertex data to frame allocator and record command
            recordDeferredDraw(orch, vao, data, dataSize, stride, drawMode, vertexCount,
                    hasColor, hasTexCoord, hasNormal, hasLightMap);
        } else {
            // Immediate path — draw now, shadow-submit for statistics
            if (drawMode == GL11.GL_QUADS) {
                int quadCount = vertexCount / 4;
                int indexCount = quadCount * 6;
                int ebo = QuadIndexBuffer.ensure(quadCount);
                GL45.glVertexArrayElementBuffer(vao, ebo);
                GL11.glDrawElements(GL11.GL_TRIANGLES, indexCount, GL11.GL_UNSIGNED_INT, 0);
            } else {
                GL11.glDrawArrays(drawMode, 0, vertexCount);
            }
            com.github.gl46core.api.debug.RenderProfiler.INSTANCE.recordDrawCall(vertexCount);

            LegacyDrawTranslator.INSTANCE.translateDraw(
                drawMode, vertexCount, 0,
                hasColor, hasTexCoord, hasNormal, hasLightMap);
        }

        } catch (Throwable t) {
            com.github.gl46core.GL46Core.LOGGER.error("[CoreDrawHandler] Exception during draw:", t);
        } finally {
            bufferBuilder.reset();
        }
    }

    /**
     * Record a deferred draw command instead of issuing an immediate GL draw.
     * Vertex data is appended to the frame VBO allocator, and a DrawCommand
     * captures all state needed for sorted replay.
     */
    private static void recordDeferredDraw(FrameOrchestrator orch, int vao,
                                            ByteBuffer data, int dataSize, int stride,
                                            int drawMode, int vertexCount,
                                            boolean hasColor, boolean hasTexCoord,
                                            boolean hasNormal, boolean hasLightMap) {
        DeferredVboAllocator allocator = orch.getDeferredVbo();
        DrawCommandBuffer cmdBuffer = orch.getDrawCommandBuffer();

        // Append vertex data to the frame allocator
        data.position(0).limit(dataSize);
        int vboOffset = allocator.append(data, dataSize);

        // Compute draw mode and index count (quad→triangle conversion)
        int finalDrawMode = drawMode;
        int indexCount = 0;
        int eboHandle = 0;
        if (drawMode == GL11.GL_QUADS) {
            int quadCount = vertexCount / 4;
            indexCount = quadCount * 6;
            eboHandle = QuadIndexBuffer.ensure(quadCount);
            finalDrawMode = GL11.GL_TRIANGLES;
        }

        // Get shader variant key and texture state from CoreStateTracker
        int variantKey = CoreShaderProgram.INSTANCE.computeVariantKey(
                hasColor, hasTexCoord, hasNormal, hasLightMap);
        int textureId = CoreStateTracker.INSTANCE.getBoundTexture2D();
        int lightmapId = CoreStateTracker.INSTANCE.getLightmapTexture();

        // Infer pass type and material from legacy state
        LegacyStateInterpreter interp = LegacyStateInterpreter.INSTANCE;
        com.github.gl46core.api.render.PassType passType = interp.inferPassType();
        com.github.gl46core.api.render.MaterialData material = interp.inferMaterial(
                hasColor, hasTexCoord, hasNormal, hasLightMap);
        int materialIndex = LegacyDrawTranslator.INSTANCE.registerMaterialPublic(
                material.getMaterialId(), material);

        // Acquire and configure command
        DrawCommand cmd = cmdBuffer.acquire();
        cmd.configure(vao, vboOffset, vertexCount, indexCount,
                finalDrawMode, eboHandle, variantKey,
                textureId, lightmapId, materialIndex,
                passType, 0L, // sort key computed below
                hasColor, hasTexCoord, hasNormal, hasLightMap, stride);

        // Capture GL state
        cmd.captureBlendState(
                CoreStateTracker.INSTANCE.isBlendEnabled(),
                CoreStateTracker.INSTANCE.getBlendSrcRgb(),
                CoreStateTracker.INSTANCE.getBlendDstRgb(),
                CoreStateTracker.INSTANCE.getBlendSrcAlpha(),
                CoreStateTracker.INSTANCE.getBlendDstAlpha());
        cmd.captureDepthCullState(
                CoreStateTracker.INSTANCE.isDepthTestEnabled(),
                CoreStateTracker.INSTANCE.isDepthMaskEnabled(),
                CoreStateTracker.INSTANCE.isCullFaceEnabled(),
                GL11.GL_BACK);

        // Build sort key (same logic as LegacyDrawTranslator)
        int matId = material.getMaterialId() & 0xFF;
        int subIdx = LegacyDrawTranslator.INSTANCE.getSubmissionCount();
        if (passType.isTranslucent()) {
            long key = ((long) 2 << 56) | ((long) matId << 48) | subIdx;
            cmd.sortKey = key;
        } else {
            long key = ((long) matId << 48) | subIdx;
            cmd.sortKey = key;
        }

        // Record profiler stats
        com.github.gl46core.api.debug.RenderProfiler.INSTANCE.recordDrawCall(vertexCount);
        com.github.gl46core.api.debug.RenderProfiler.INSTANCE.recordPassDrawCount(passType, 1);
    }

    /**
     * Destroy all thread-local GL objects. Called on shutdown.
     */
    public static void destroyAll() {
        for (var entry : allStates.entrySet()) {
            ThreadDrawState s = entry.getValue();
            if (s.vao != 0) GL30.glDeleteVertexArrays(s.vao);
            if (s.vbo != 0) GL45.glDeleteBuffers(s.vbo);
        }
        allStates.clear();
    }
}
