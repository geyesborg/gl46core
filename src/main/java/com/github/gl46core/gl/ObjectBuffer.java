package com.github.gl46core.gl;

import com.github.gl46core.api.render.gpu.GpuBuffer;
import com.github.gl46core.api.render.gpu.GpuBufferPool;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL43;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Manages a large buffer for per-draw object transforms (MVP + MV).
 *
 * Supports two binding modes:
 *   - <b>SSBO mode</b> (preferred): buffer bound to SSBO binding point 3.
 *     Shader reads transforms via {@code gl46_objects[gl_BaseInstance]}.
 *     Tight 128-byte packing, no alignment waste.
 *   - <b>UBO range mode</b> (fallback): per-draw glBindBufferRange on
 *     UBO binding point 1. Requires UBO offset alignment padding.
 *
 * All transforms are packed into a staging buffer and uploaded in ONE
 * bulk call via glNamedBufferSubData.
 *
 * Layout per object (std430 for SSBO, std140 compatible):
 *   mat4 mvp    (64 bytes, offset 0)
 *   mat4 mv     (64 bytes, offset 64)
 */
public final class ObjectBuffer {

    public static final ObjectBuffer INSTANCE = new ObjectBuffer();

    public static final int OBJECT_DATA_SIZE = 128; // 2x mat4
    public static final int SSBO_BINDING = 3;       // must match shader layout
    private static final int INITIAL_CAPACITY = 1024;

    private GpuBuffer buffer;
    private ByteBuffer staging;
    private int objectCount;
    private int capacity;
    private int highWaterMark;
    private int uboAlignment;
    private int alignedStride;  // UBO mode: padded to alignment. SSBO mode: 128.
    private boolean initialized;
    private boolean ssboMode;   // true = SSBO path, false = UBO range fallback

    private ObjectBuffer() {}

    public void ensureInitialized() {
        if (initialized) return;
        initialized = true;

        uboAlignment = GL11.glGetInteger(GL31.GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT);

        // SSBO mode: tight packing. UBO mode: aligned stride.
        ssboMode = true;
        alignedStride = ssboMode ? OBJECT_DATA_SIZE
                : ((OBJECT_DATA_SIZE + uboAlignment - 1) / uboAlignment) * uboAlignment;

        capacity = INITIAL_CAPACITY;
        long totalSize = (long) alignedStride * capacity;

        // Use SSBO-capable buffer (GL_DYNAMIC_STORAGE_BIT works for both UBO and SSBO)
        buffer = GpuBufferPool.INSTANCE.createDynamicSSBO(totalSize);
        staging = ByteBuffer.allocateDirect((int) totalSize).order(ByteOrder.nativeOrder());
    }

    /**
     * Reset for a new batch of submissions.
     */
    public void begin() {
        objectCount = 0;
    }

    /**
     * Pack a transform into the staging buffer.
     *
     * @return the object index (used as gl_BaseInstance or with {@link #bindObject(int)})
     */
    public int submitTransform(Matrix4f mvp, Matrix4f mv) {
        if (objectCount >= capacity) grow();
        int idx = objectCount++;
        int offset = idx * alignedStride;
        mvp.get(offset, staging);
        mv.get(offset + 64, staging);
        return idx;
    }

    /**
     * Upload all packed transforms to the GPU in ONE call.
     * Call after all submitTransform() calls, before drawing.
     */
    public void upload() {
        if (objectCount == 0) return;
        if (objectCount > highWaterMark) highWaterMark = objectCount;
        int size = objectCount * alignedStride;
        staging.position(0).limit(size);
        buffer.upload(staging, 0, size);
    }

    /**
     * Grow capacity by 2x. Recreates the immutable GPU buffer.
     */
    private void grow() {
        int newCapacity = capacity * 2;
        long newSize = (long) alignedStride * newCapacity;

        GpuBuffer newBuf = GpuBufferPool.INSTANCE.createDynamicSSBO(newSize);
        if (buffer != null) {
            GpuBufferPool.INSTANCE.destroy(buffer);
        }
        buffer = newBuf;

        ByteBuffer newStaging = ByteBuffer.allocateDirect((int) newSize).order(ByteOrder.nativeOrder());
        if (staging != null) {
            int oldDataSize = objectCount * alignedStride;
            staging.position(0).limit(oldDataSize);
            newStaging.put(staging);
            newStaging.clear();
        }
        staging = newStaging;
        capacity = newCapacity;
    }

    // ── SSBO mode (preferred) ──

    /**
     * Bind the entire buffer as SSBO at binding point 3.
     * Shader indexes via gl_BaseInstance — no per-draw binding needed.
     */
    public void bindAsSSBO() {
        buffer.bindBase(GL43.GL_SHADER_STORAGE_BUFFER, SSBO_BINDING);
    }

    /**
     * Unbind the SSBO after terrain rendering.
     */
    public void unbindSSBO() {
        GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, SSBO_BINDING, 0);
    }

    // ── UBO range mode (fallback) ──

    /**
     * Bind the given object's transform slice to UBO binding point 1 (PerObject).
     * Only used in UBO fallback mode. This is a state change only.
     */
    public void bindObject(int index) {
        buffer.bindRange(GL31.GL_UNIFORM_BUFFER, 1,
                (long) index * alignedStride, OBJECT_DATA_SIZE);
    }

    /**
     * Restore the original PerObject UBO binding after using ObjectBuffer.
     * Necessary so CoreShaderProgram.bind() finds the correct buffer at point 1.
     */
    public void restorePerObjectBinding() {
        int perDrawUbo = RenderContext.get().handle(RenderContext.GL.PER_DRAW_UBO);
        GL31.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, 1, perDrawUbo);
    }

    // ── Accessors ──

    public boolean isSsboMode()    { return ssboMode; }
    public int getObjectCount()    { return objectCount; }
    public int getCapacity()       { return capacity; }
    public int getHighWaterMark()  { return highWaterMark; }
    public int getAlignedStride()  { return alignedStride; }
    public int getUboAlignment()   { return uboAlignment; }
    public GpuBuffer getBuffer()   { return buffer; }
}
