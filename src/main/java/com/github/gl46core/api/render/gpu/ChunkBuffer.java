package com.github.gl46core.api.render.gpu;

import com.github.gl46core.api.render.ChunkData;
import org.lwjgl.opengl.GL43;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * GPU-side chunk metadata table stored as an SSBO.
 *
 * Each entry is {@link ChunkData#GPU_SIZE} bytes (48 bytes).
 * Shaders and indirect draw commands reference chunk data by index.
 *
 * SSBO binding point: 3
 *
 * Updated incrementally — only dirty chunks are re-uploaded.
 * Typical: 1000-4000 visible sections (48KB-192KB).
 */
public final class ChunkBuffer {

    public static final int BINDING_POINT = 3;
    public static final int ENTRY_SIZE = ChunkData.GPU_SIZE; // 48 bytes

    private static final int DEFAULT_CAPACITY = 1024;

    private GpuBuffer gpuBuffer;
    private ByteBuffer stagingBuffer;
    private int capacity;
    private int count;
    private int highWaterMark;

    public ChunkBuffer() {}

    /**
     * Initialize with the given max chunk section count.
     */
    public void init(int maxChunks) {
        this.capacity = maxChunks;
        long totalSize = (long) maxChunks * ENTRY_SIZE;
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
     * Add a visible chunk section. Returns the index.
     */
    public int addChunk(ChunkData chunk) {
        if (count >= capacity) grow();
        int index = count++;
        int offset = index * ENTRY_SIZE;

        // ivec4 chunkOrigin (xyz=block origin, w=regionId)
        stagingBuffer.putInt(offset, chunk.getBlockOriginX());
        stagingBuffer.putInt(offset + 4, chunk.getBlockOriginY());
        stagingBuffer.putInt(offset + 8, chunk.getBlockOriginZ());
        stagingBuffer.putInt(offset + 12, chunk.getRegionId());
        // ivec4 bounds (xyz=16, w=biomeIndex)
        stagingBuffer.putInt(offset + 16, 16);
        stagingBuffer.putInt(offset + 20, 16);
        stagingBuffer.putInt(offset + 24, 16);
        stagingBuffer.putInt(offset + 28, chunk.getBiomeIndex());
        // int visibilityFlags, meshSectionOffset, drawCommandOffset, lightVolumeIndex
        stagingBuffer.putInt(offset + 32, chunk.getVisibilityFlags());
        stagingBuffer.putInt(offset + 36, chunk.getMeshSectionOffset());
        stagingBuffer.putInt(offset + 40, chunk.getDrawCommandOffset());
        stagingBuffer.putInt(offset + 44, chunk.getLightVolumeIndex());

        return index;
    }

    /**
     * Upload all chunk data to the GPU.
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
