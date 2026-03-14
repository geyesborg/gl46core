package com.github.gl46core.api.render.gpu;

import com.github.gl46core.api.render.LightData;
import org.lwjgl.opengl.GL43;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * GPU-side dynamic light table stored as an SSBO.
 *
 * Each entry is {@link LightData#GPU_SIZE} bytes (48 bytes).
 * Shaders iterate this buffer to apply local lighting.
 *
 * SSBO binding point: 2
 *
 * The first 16 bytes of the buffer are a header:
 *   int lightCount     offset 0
 *   int maxLights      offset 4
 *   int _pad0          offset 8
 *   int _pad1          offset 12
 * Light entries start at offset 16, aligned to 16 bytes.
 *
 * Reset each frame. Typical: 0-128 lights (6KB max).
 */
public final class LightBuffer {

    public static final int BINDING_POINT = 2;
    public static final int HEADER_SIZE = 16;
    public static final int ENTRY_SIZE = LightData.GPU_SIZE; // 48 bytes

    private static final int DEFAULT_CAPACITY = 64;

    private GpuBuffer gpuBuffer;
    private ByteBuffer stagingBuffer;
    private int capacity;
    private int count;
    private int highWaterMark;

    public LightBuffer() {}

    /**
     * Initialize with the given max light count.
     */
    public void init(int maxLights) {
        this.capacity = maxLights;
        long totalSize = HEADER_SIZE + (long) maxLights * ENTRY_SIZE;
        gpuBuffer = GpuBufferPool.INSTANCE.createDynamicSSBO(totalSize);
        stagingBuffer = ByteBuffer.allocateDirect((int) totalSize).order(ByteOrder.nativeOrder());
    }

    /**
     * Reset for a new frame.
     */
    public void clear() {
        count = 0;
        if (stagingBuffer != null) {
            stagingBuffer.clear(); // reset limit to capacity for absolute puts
        }
    }

    /**
     * Add a light. Grows the buffer if full.
     */
    public int addLight(LightData light) {
        if (count >= capacity) grow();
        int index = count++;
        int offset = HEADER_SIZE + index * ENTRY_SIZE;

        // vec4 positionAndRadius (xyz=pos, w=radius)
        stagingBuffer.putFloat(offset, light.getPosition().x);
        stagingBuffer.putFloat(offset + 4, light.getPosition().y);
        stagingBuffer.putFloat(offset + 8, light.getPosition().z);
        stagingBuffer.putFloat(offset + 12, light.getRadius());
        // vec4 colorAndIntensity (rgb=color, a=intensity)
        stagingBuffer.putFloat(offset + 16, light.getR());
        stagingBuffer.putFloat(offset + 20, light.getG());
        stagingBuffer.putFloat(offset + 24, light.getB());
        stagingBuffer.putFloat(offset + 28, light.getIntensity());
        // int lightType, shadowFlags, float falloff, spotAngle
        stagingBuffer.putInt(offset + 32, light.getLightType().ordinal());
        stagingBuffer.putInt(offset + 36, light.getShadowFlags());
        stagingBuffer.putFloat(offset + 40, light.getFalloffExponent());
        stagingBuffer.putFloat(offset + 44, light.getSpotAngle());

        return index;
    }

    /**
     * Upload header + lights to GPU.
     */
    public void flush() {
        if (count > highWaterMark) highWaterMark = count;

        // Write header
        stagingBuffer.putInt(0, count);
        stagingBuffer.putInt(4, capacity);
        stagingBuffer.putInt(8, 0);
        stagingBuffer.putInt(12, 0);

        long size = HEADER_SIZE + (long) count * ENTRY_SIZE;
        stagingBuffer.position(0).limit((int) size);
        gpuBuffer.upload(stagingBuffer, 0, size);
    }

    /**
     * Grow capacity by 2x. Recreates the immutable GPU buffer.
     */
    private void grow() {
        int newCapacity = Math.max(capacity * 2, DEFAULT_CAPACITY);
        long newSize = HEADER_SIZE + (long) newCapacity * ENTRY_SIZE;

        GpuBuffer newBuf = GpuBufferPool.INSTANCE.createDynamicSSBO(newSize);
        if (gpuBuffer != null) {
            GpuBufferPool.INSTANCE.destroy(gpuBuffer);
        }
        gpuBuffer = newBuf;

        ByteBuffer newStaging = ByteBuffer.allocateDirect((int) newSize).order(ByteOrder.nativeOrder());
        if (stagingBuffer != null) {
            int oldDataSize = HEADER_SIZE + count * ENTRY_SIZE;
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
