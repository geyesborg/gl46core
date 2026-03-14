package com.github.gl46core.api.render.gpu;

import com.github.gl46core.api.render.ObjectData;
import org.lwjgl.opengl.GL43;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * GPU-side object/instance table stored as an SSBO.
 *
 * Each entry is {@link ObjectData#GPU_SIZE} bytes (224 bytes).
 * Shaders index by object ID to fetch transforms and flags.
 *
 * SSBO binding point: 1
 *
 * Reset each frame — objects are transient submissions, not persistent.
 * Typical peak: 500-2000 objects per frame (112KB-448KB).
 */
public final class ObjectBuffer {

    public static final int BINDING_POINT = 1;
    public static final int ENTRY_SIZE = ObjectData.GPU_SIZE; // 224 bytes

    private static final int DEFAULT_CAPACITY = 512;

    private GpuBuffer gpuBuffer;
    private ByteBuffer stagingBuffer;
    private int capacity;
    private int count;
    private int highWaterMark;

    public ObjectBuffer() {}

    /**
     * Initialize with the given max object count.
     */
    public void init(int maxObjects) {
        this.capacity = maxObjects;
        long totalSize = (long) maxObjects * ENTRY_SIZE;
        gpuBuffer = GpuBufferPool.INSTANCE.createDynamicSSBO(totalSize);
        stagingBuffer = ByteBuffer.allocateDirect((int) totalSize).order(ByteOrder.nativeOrder());
    }

    /**
     * Reset for a new frame. Objects are transient.
     */
    public void clear() {
        count = 0;
    }

    /**
     * Allocate the next object slot and write transforms.
     * Returns the index (for use in RenderSubmission.setObjectIndex).
     */
    public int addObject(ObjectData obj) {
        if (count >= capacity) grow();
        int index = count++;
        int offset = index * ENTRY_SIZE;

        // mat4 modelMatrix (64 bytes)
        obj.getModelMatrix().get(offset, stagingBuffer);
        // mat4 normalMatrix (64 bytes)
        obj.getNormalMatrix().get(offset + 64, stagingBuffer);
        // mat4 prevModelMatrix (64 bytes)
        obj.getPrevModelMatrix().get(offset + 128, stagingBuffer);
        // int objectId, materialId, chunkId, renderFlags
        stagingBuffer.putInt(offset + 192, obj.getObjectId());
        stagingBuffer.putInt(offset + 196, obj.getMaterialId());
        stagingBuffer.putInt(offset + 200, obj.getChunkId());
        stagingBuffer.putInt(offset + 204, obj.getRenderFlags());
        // vec4 boundingSphere
        stagingBuffer.putFloat(offset + 208, obj.getBoundingSphere().x);
        stagingBuffer.putFloat(offset + 212, obj.getBoundingSphere().y);
        stagingBuffer.putFloat(offset + 216, obj.getBoundingSphere().z);
        stagingBuffer.putFloat(offset + 220, obj.getBoundingSphere().w);

        return index;
    }

    /**
     * Upload all objects to the GPU. Call once per frame after all submissions.
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
