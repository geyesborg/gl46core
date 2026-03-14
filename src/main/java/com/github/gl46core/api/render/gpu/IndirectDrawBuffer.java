package com.github.gl46core.api.render.gpu;

import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GL45;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * GPU buffer for multi-draw indirect commands.
 *
 * Stores DrawArraysIndirectCommand or DrawElementsIndirectCommand structs
 * for GPU-driven terrain rendering via glMultiDrawArraysIndirect or
 * glMultiDrawElementsIndirect.
 *
 * DrawArraysIndirectCommand (16 bytes):
 *   uint count          — vertex count
 *   uint instanceCount  — instance count (1 for non-instanced)
 *   uint first          — first vertex offset
 *   uint baseInstance   — base instance (used for gl_BaseInstance → object index)
 *
 * DrawElementsIndirectCommand (20 bytes):
 *   uint count          — index count
 *   uint instanceCount  — instance count
 *   uint firstIndex     — first index offset
 *   int  baseVertex     — base vertex offset
 *   uint baseInstance   — base instance
 *
 * Bound as GL_DRAW_INDIRECT_BUFFER.
 */
public final class IndirectDrawBuffer {

    public static final int ARRAYS_COMMAND_SIZE = 16;
    public static final int ELEMENTS_COMMAND_SIZE = 20;

    // Align element commands to 4 bytes (already aligned at 20, but pad to 32 for cache)
    public static final int ELEMENTS_COMMAND_STRIDE = 20;

    private GpuBuffer gpuBuffer;
    private ByteBuffer stagingBuffer;
    private int commandStride;
    private int capacity;       // max commands
    private int count;          // active commands
    private boolean indexed;    // elements vs arrays

    public IndirectDrawBuffer() {}

    /**
     * Initialize for DrawArraysIndirect commands.
     */
    public void initArrays(int maxCommands) {
        this.capacity = maxCommands;
        this.commandStride = ARRAYS_COMMAND_SIZE;
        this.indexed = false;
        long totalSize = (long) maxCommands * commandStride;
        gpuBuffer = GpuBufferPool.INSTANCE.createDynamicSSBO(totalSize);
        stagingBuffer = ByteBuffer.allocateDirect((int) totalSize).order(ByteOrder.nativeOrder());
    }

    /**
     * Initialize for DrawElementsIndirect commands.
     */
    public void initElements(int maxCommands) {
        this.capacity = maxCommands;
        this.commandStride = ELEMENTS_COMMAND_STRIDE;
        this.indexed = true;
        long totalSize = (long) maxCommands * commandStride;
        gpuBuffer = GpuBufferPool.INSTANCE.createDynamicSSBO(totalSize);
        stagingBuffer = ByteBuffer.allocateDirect((int) totalSize).order(ByteOrder.nativeOrder());
    }

    /**
     * Reset for a new frame.
     */
    public void clear() {
        count = 0;
        if (stagingBuffer != null) {
            stagingBuffer.clear(); // reset position=0, limit=capacity
        }
    }

    /**
     * Add a DrawArraysIndirect command.
     *
     * @param vertexCount  number of vertices to draw
     * @param firstVertex  first vertex offset in bound VBO
     * @param baseInstance used as object index via gl_BaseInstance
     * @return command index
     */
    public int addArraysCommand(int vertexCount, int firstVertex, int baseInstance) {
        if (count >= capacity || indexed) return -1;
        int index = count++;
        int offset = index * ARRAYS_COMMAND_SIZE;

        stagingBuffer.putInt(offset, vertexCount);
        stagingBuffer.putInt(offset + 4, 1);  // instanceCount = 1
        stagingBuffer.putInt(offset + 8, firstVertex);
        stagingBuffer.putInt(offset + 12, baseInstance);

        return index;
    }

    /**
     * Add a DrawElementsIndirect command.
     *
     * @param indexCount   number of indices to draw
     * @param firstIndex   first index offset
     * @param baseVertex   added to each index value
     * @param baseInstance used as object index via gl_BaseInstance
     * @return command index
     */
    public int addElementsCommand(int indexCount, int firstIndex, int baseVertex, int baseInstance) {
        if (count >= capacity || !indexed) return -1;
        int index = count++;
        int offset = index * ELEMENTS_COMMAND_STRIDE;

        stagingBuffer.putInt(offset, indexCount);
        stagingBuffer.putInt(offset + 4, 1);  // instanceCount = 1
        stagingBuffer.putInt(offset + 8, firstIndex);
        stagingBuffer.putInt(offset + 12, baseVertex);
        stagingBuffer.putInt(offset + 16, baseInstance);

        return index;
    }

    /**
     * Upload commands to GPU.
     */
    public void flush() {
        if (count == 0) return;
        long size = (long) count * commandStride;
        stagingBuffer.position(0).limit((int) size);
        gpuBuffer.upload(stagingBuffer, 0, size);
    }

    /**
     * Bind as GL_DRAW_INDIRECT_BUFFER for indirect draw calls.
     */
    public void bind() {
        GL45.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, gpuBuffer.getHandle());
    }

    /**
     * Unbind the indirect buffer.
     */
    public void unbind() {
        GL45.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, 0);
    }

    /**
     * Issue glMultiDrawArraysIndirect.
     *
     * @param mode GL draw mode (GL_TRIANGLES, etc.)
     */
    public void multiDrawArrays(int mode) {
        if (count == 0 || indexed) return;
        bind();
        GL43.glMultiDrawArraysIndirect(mode, 0, count, commandStride);
    }

    /**
     * Issue glMultiDrawElementsIndirect.
     *
     * @param mode      GL draw mode
     * @param indexType  GL index type (GL_UNSIGNED_INT, etc.)
     */
    public void multiDrawElements(int mode, int indexType) {
        if (count == 0 || !indexed) return;
        bind();
        GL43.glMultiDrawElementsIndirect(mode, indexType, 0, count, commandStride);
    }

    public void destroy() {
        if (gpuBuffer != null) {
            GpuBufferPool.INSTANCE.destroy(gpuBuffer);
            gpuBuffer = null;
        }
    }

    public int     getCount()    { return count; }
    public int     getCapacity() { return capacity; }
    public boolean isIndexed()   { return indexed; }
}
