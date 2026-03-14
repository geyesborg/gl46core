package com.github.gl46core.api.render.deferred;

import com.github.gl46core.GL46Core;
import org.lwjgl.opengl.GL45;

import java.nio.ByteBuffer;

/**
 * Per-frame append-only VBO allocator for deferred draw execution.
 *
 * When deferred mode is active, vertex data from each draw call is
 * appended sequentially into this buffer rather than overwriting a
 * per-draw VBO. Each {@link DrawCommand} records its byte offset into
 * this buffer. At frame end (after replay), the write pointer resets
 * to zero.
 *
 * Uses GL_DYNAMIC_STORAGE_BIT for sub-data uploads. Grows automatically
 * when the frame's vertex data exceeds capacity.
 *
 * Typical frame budget: 16-64 MB (terrain + entities + particles).
 */
public final class DeferredVboAllocator {

    private static final int INITIAL_CAPACITY = 16 * 1024 * 1024; // 16 MB

    private int vboHandle;
    private int capacity;
    private int writeOffset; // current append position (bytes)

    public DeferredVboAllocator() {}

    /**
     * Initialize the allocator with default capacity.
     */
    public void init() {
        init(INITIAL_CAPACITY);
    }

    /**
     * Initialize with a specific capacity in bytes.
     */
    public void init(int capacityBytes) {
        if (vboHandle != 0) destroy();
        this.capacity = capacityBytes;
        this.writeOffset = 0;

        int[] bufs = new int[1];
        GL45.glCreateBuffers(bufs);
        vboHandle = bufs[0];
        GL45.glNamedBufferStorage(vboHandle, capacity, GL45.GL_DYNAMIC_STORAGE_BIT);

        GL46Core.LOGGER.info("DeferredVboAllocator initialized: {}MB",
                capacity / (1024 * 1024));
    }

    /**
     * Reset the write pointer for a new frame.
     * Previous data is logically invalidated.
     */
    public void beginFrame() {
        writeOffset = 0;
    }

    /**
     * Append vertex data and return the byte offset where it was written.
     *
     * @param data   vertex data buffer (position/limit set to the data range)
     * @param size   number of bytes to write
     * @return byte offset into the VBO where data was written
     */
    public int append(ByteBuffer data, int size) {
        if (writeOffset + size > capacity) {
            grow(writeOffset + size);
        }

        int offset = writeOffset;
        data.limit(data.position() + size);
        GL45.glNamedBufferSubData(vboHandle, offset, data);
        writeOffset += size;

        // Align to 16 bytes for GPU efficiency
        writeOffset = (writeOffset + 15) & ~15;

        return offset;
    }

    /**
     * Get the VBO handle for VAO binding.
     */
    public int getVboHandle() {
        return vboHandle;
    }

    /**
     * Get the current write offset (total bytes used this frame).
     */
    public int getUsedBytes() {
        return writeOffset;
    }

    /**
     * Get the total capacity in bytes.
     */
    public int getCapacity() {
        return capacity;
    }

    /**
     * Destroy the VBO.
     */
    public void destroy() {
        if (vboHandle != 0) {
            GL45.glDeleteBuffers(vboHandle);
            vboHandle = 0;
        }
        capacity = 0;
        writeOffset = 0;
    }

    /**
     * Grow the buffer to at least the requested size.
     * Creates a new buffer (immutable storage requires recreation).
     */
    private void grow(int requiredSize) {
        int newCapacity = Math.max(capacity * 2, requiredSize);

        int[] bufs = new int[1];
        GL45.glCreateBuffers(bufs);
        int newVbo = bufs[0];
        GL45.glNamedBufferStorage(newVbo, newCapacity, GL45.GL_DYNAMIC_STORAGE_BIT);

        // Copy existing data if any
        if (writeOffset > 0 && vboHandle != 0) {
            GL45.glCopyNamedBufferSubData(vboHandle, newVbo, 0, 0, writeOffset);
        }

        if (vboHandle != 0) {
            GL45.glDeleteBuffers(vboHandle);
        }
        vboHandle = newVbo;
        capacity = newCapacity;

        GL46Core.LOGGER.info("DeferredVboAllocator grew to {}MB", newCapacity / (1024 * 1024));
    }
}
