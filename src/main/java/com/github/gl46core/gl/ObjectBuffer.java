package com.github.gl46core.gl;

import com.github.gl46core.api.render.gpu.GpuBuffer;
import com.github.gl46core.api.render.gpu.GpuBufferPool;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL31;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Manages a large UBO for per-draw object transforms (MVP + MV).
 *
 * Instead of uploading 128 bytes per draw via glNamedBufferSubData,
 * all transforms are packed into a staging buffer and uploaded in ONE
 * bulk call. Per-draw binding uses glBindBufferRange to select the
 * 128-byte slice for each object — a state change, not a data transfer.
 *
 * Each object occupies an aligned stride (rounded up to
 * GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT, typically 256 bytes on NVIDIA).
 *
 * Layout per object (std140):
 *   mat4 mvp    (64 bytes, offset 0)
 *   mat4 mv     (64 bytes, offset 64)
 *   [padding to alignment]
 */
public final class ObjectBuffer {

    public static final ObjectBuffer INSTANCE = new ObjectBuffer();

    public static final int OBJECT_DATA_SIZE = 128; // 2x mat4
    private static final int MAX_OBJECTS = 4096;

    private GpuBuffer buffer;
    private ByteBuffer staging;
    private int objectCount;
    private int uboAlignment;
    private int alignedStride;
    private boolean initialized;

    private ObjectBuffer() {}

    public void ensureInitialized() {
        if (initialized) return;
        initialized = true;

        uboAlignment = GL11.glGetInteger(GL31.GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT);
        alignedStride = ((OBJECT_DATA_SIZE + uboAlignment - 1) / uboAlignment) * uboAlignment;
        long totalSize = (long) alignedStride * MAX_OBJECTS;

        buffer = GpuBufferPool.INSTANCE.createDynamicUBO(totalSize);
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
     * @return the object index (used with {@link #bindObject(int)})
     */
    public int submitTransform(Matrix4f mvp, Matrix4f mv) {
        int idx = objectCount++;
        int offset = idx * alignedStride;
        mvp.get(offset, staging);
        mv.get(offset + 64, staging);
        return idx;
    }

    /**
     * Upload all packed transforms to the GPU in ONE call.
     * Call after all submitTransform() calls, before any bindObject() calls.
     */
    public void upload() {
        if (objectCount == 0) return;
        int size = objectCount * alignedStride;
        staging.position(0).limit(size);
        buffer.upload(staging, 0, size);
    }

    /**
     * Bind the given object's transform slice to UBO binding point 1 (PerObject).
     * This is a state change only — no data transfer.
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

    public int getObjectCount()   { return objectCount; }
    public int getAlignedStride() { return alignedStride; }
    public int getUboAlignment()  { return uboAlignment; }
    public GpuBuffer getBuffer()  { return buffer; }
}
