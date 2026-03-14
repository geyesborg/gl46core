package com.github.gl46core.api.render.gpu;

import org.lwjgl.opengl.GL43;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * GPU-side spatial light index for per-chunk/section light lookup.
 *
 * Instead of every fragment looping over ALL dynamic lights, each chunk
 * section stores an offset+count into this index buffer. The index buffer
 * contains light IDs that reference the main Light SSBO.
 *
 * SSBO binding point: 4
 *
 * Layout (std430):
 *   uint lightIndices[];   // each entry is a light index into LightBuffer
 *
 * Usage:
 *   1. For each chunk section, determine which lights affect it (radius test)
 *   2. Write those light indices contiguously into this buffer
 *   3. Store the (offset, count) in ChunkData.localLightListOffset/Count
 *   4. Shader reads: for (i = offset; i < offset+count; i++) { lightIdx = lightIndices[i]; }
 *
 * Reset each frame. Typical: 0-8 lights per section, ~4000 sections = ~32K entries max.
 */
public final class LightIndexBuffer {

    public static final int BINDING_POINT = 4;
    public static final int ENTRY_SIZE = 4; // one uint per index

    private static final int DEFAULT_CAPACITY = 4096; // indices, not sections

    private GpuBuffer gpuBuffer;
    private ByteBuffer stagingBuffer;
    private int capacity;
    private int count;
    private int highWaterMark;

    public LightIndexBuffer() {}

    /**
     * Initialize with the given max index count.
     */
    public void init(int maxIndices) {
        this.capacity = maxIndices;
        long totalSize = (long) maxIndices * ENTRY_SIZE;
        gpuBuffer = GpuBufferPool.INSTANCE.createDynamicSSBO(totalSize);
        stagingBuffer = ByteBuffer.allocateDirect((int) totalSize).order(ByteOrder.nativeOrder());
    }

    /**
     * Reset for a new frame.
     */
    public void clear() {
        count = 0;
    }

    /**
     * Begin a light list for a chunk section.
     * Returns the starting offset (for ChunkData.setLocalLightList).
     */
    public int beginList() {
        return count;
    }

    /**
     * Add a light index to the current list. Grows if needed.
     *
     * @param lightIndex index into LightBuffer
     */
    public void addLightIndex(int lightIndex) {
        if (count >= capacity) grow();
        int offset = count * ENTRY_SIZE;
        stagingBuffer.putInt(offset, lightIndex);
        count++;
    }

    /**
     * End the current light list.
     * Returns the number of lights added since beginList().
     */
    public int endList(int startOffset) {
        return count - startOffset;
    }

    /**
     * Upload all indices to GPU.
     */
    public void flush() {
        if (count == 0) return;
        if (count > highWaterMark) highWaterMark = count;
        long size = (long) count * ENTRY_SIZE;
        stagingBuffer.position(0).limit((int) size);
        gpuBuffer.upload(stagingBuffer, 0, size);
    }

    /**
     * Grow capacity by 2x. Recreates the immutable GPU buffer.
     */
    private void grow() {
        int newCapacity = Math.max(capacity * 2, DEFAULT_CAPACITY);
        long newSize = (long) newCapacity * ENTRY_SIZE;

        GpuBuffer newBuf = GpuBufferPool.INSTANCE.createDynamicSSBO(newSize);
        if (gpuBuffer != null) {
            GpuBufferPool.INSTANCE.destroy(gpuBuffer);
        }
        gpuBuffer = newBuf;

        ByteBuffer newStaging = ByteBuffer.allocateDirect((int) newSize).order(ByteOrder.nativeOrder());
        if (stagingBuffer != null) {
            int oldDataSize = count * ENTRY_SIZE;
            stagingBuffer.position(0).limit(oldDataSize);
            newStaging.put(stagingBuffer);
            newStaging.clear();
        }
        stagingBuffer = newStaging;
        capacity = newCapacity;
    }

    /**
     * Bind to the SSBO binding point for shader access.
     */
    public void bind() {
        gpuBuffer.bindBase(GL43.GL_SHADER_STORAGE_BUFFER, BINDING_POINT);
    }

    public void destroy() {
        if (gpuBuffer != null) {
            GpuBufferPool.INSTANCE.destroy(gpuBuffer);
            gpuBuffer = null;
        }
    }

    public int getCount()         { return count; }
    public int getCapacity()      { return capacity; }
    public int getHighWaterMark() { return highWaterMark; }
}
