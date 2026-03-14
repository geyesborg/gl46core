package com.github.gl46core.api.render.gpu;

import com.github.gl46core.api.render.MaterialData;
import org.lwjgl.opengl.GL43;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * GPU-side material table stored as an SSBO.
 *
 * Each entry is {@link MaterialData#GPU_SIZE} bytes (64 bytes).
 * Shaders index into this buffer by material ID to fetch per-material
 * parameters without per-draw uniform uploads.
 *
 * SSBO binding point: 0
 *
 * Typical capacity: 256-1024 materials (16KB-64KB).
 */
public final class MaterialBuffer {

    public static final int BINDING_POINT = 0;
    public static final int ENTRY_SIZE = MaterialData.GPU_SIZE; // 64 bytes

    private static final int DEFAULT_CAPACITY = 256;

    private GpuBuffer gpuBuffer;
    private ByteBuffer stagingBuffer;
    private int capacity;       // max materials
    private int count;          // active materials
    private boolean dirty;

    public MaterialBuffer() {}

    /**
     * Initialize with the given max material count.
     */
    public void init(int maxMaterials) {
        this.capacity = maxMaterials;
        long totalSize = (long) maxMaterials * ENTRY_SIZE;
        gpuBuffer = GpuBufferPool.INSTANCE.createDynamicSSBO(totalSize);
        stagingBuffer = ByteBuffer.allocateDirect((int) totalSize).order(ByteOrder.nativeOrder());
    }

    /**
     * Write a material entry at the given index.
     */
    public void setMaterial(int index, MaterialData mat) {
        if (index < 0) return;
        while (index >= capacity) grow();
        int offset = index * ENTRY_SIZE;

        stagingBuffer.putInt(offset, mat.getMaterialId());
        stagingBuffer.putInt(offset + 4, mat.getTextureIndex());
        stagingBuffer.putInt(offset + 8, mat.getLightmapIndex());
        stagingBuffer.putInt(offset + 12, mat.getShaderFeatureFlags());
        stagingBuffer.putFloat(offset + 16, mat.getColorR());
        stagingBuffer.putFloat(offset + 20, mat.getColorG());
        stagingBuffer.putFloat(offset + 24, mat.getColorB());
        stagingBuffer.putFloat(offset + 28, mat.getColorA());
        stagingBuffer.putFloat(offset + 32, mat.getEmissiveStrength());
        stagingBuffer.putFloat(offset + 36, mat.getRoughness());
        stagingBuffer.putFloat(offset + 40, mat.getMetallic());
        stagingBuffer.putFloat(offset + 44, mat.getAlphaCutoff());
        stagingBuffer.putInt(offset + 48, mat.getAlphaMode().ordinal());
        stagingBuffer.putInt(offset + 52, mat.getTexEnvMode());
        stagingBuffer.putInt(offset + 56, mat.getLightResponseFlags());
        stagingBuffer.putInt(offset + 60, 0); // padding

        if (index >= count) count = index + 1;
        dirty = true;
    }

    /**
     * Upload dirty data to the GPU. Call once per frame if materials changed.
     */
    public void flush() {
        if (!dirty || count == 0) return;
        long size = (long) count * ENTRY_SIZE;
        stagingBuffer.position(0).limit((int) size);
        gpuBuffer.upload(stagingBuffer, 0, size);
        stagingBuffer.clear(); // Reset limit to capacity for next frame's absolute puts
        dirty = false;
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
        dirty = true; // must re-upload after buffer recreation
    }

    public int getCount()    { return count; }
    public int getCapacity() { return capacity; }
    public boolean isDirty() { return dirty; }
}
