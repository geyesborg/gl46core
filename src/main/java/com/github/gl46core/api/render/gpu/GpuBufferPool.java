package com.github.gl46core.api.render.gpu;

import java.util.ArrayList;
import java.util.List;

/**
 * Pool of GPU buffers for managing lifecycle and bulk operations.
 *
 * Tracks all allocated buffers, provides factory methods for common
 * buffer types, and handles bulk destroy on shutdown.
 */
public final class GpuBufferPool {

    public static final GpuBufferPool INSTANCE = new GpuBufferPool();

    private final List<GpuBuffer> buffers = new ArrayList<>();

    private GpuBufferPool() {}

    /**
     * Create a dynamic UBO (updated via SubData).
     */
    public GpuBuffer createDynamicUBO(long size) {
        GpuBuffer buf = new GpuBuffer();
        buf.create();
        buf.allocateImmutable(size);
        buffers.add(buf);
        return buf;
    }

    /**
     * Create a dynamic SSBO (updated via SubData).
     */
    public GpuBuffer createDynamicSSBO(long size) {
        GpuBuffer buf = new GpuBuffer();
        buf.create();
        buf.allocateImmutable(size);
        buffers.add(buf);
        return buf;
    }

    /**
     * Create a persistent-mapped SSBO for streaming.
     *
     * @param sectionSize bytes per section
     * @param sections    number of round-robin sections (typically 3)
     */
    public GpuBuffer createPersistentSSBO(long sectionSize, int sections) {
        GpuBuffer buf = new GpuBuffer();
        buf.create();
        buf.allocatePersistent(sectionSize, sections);
        buffers.add(buf);
        return buf;
    }

    /**
     * Create a static buffer (written once).
     */
    public GpuBuffer createStaticBuffer(long size) {
        GpuBuffer buf = new GpuBuffer();
        buf.create();
        buf.allocateStatic(size);
        buffers.add(buf);
        return buf;
    }

    /**
     * Destroy a specific buffer and remove from pool.
     */
    public void destroy(GpuBuffer buffer) {
        buffer.destroy();
        buffers.remove(buffer);
    }

    /**
     * Destroy all buffers. Call on shutdown.
     */
    public void destroyAll() {
        for (GpuBuffer buf : buffers) {
            buf.destroy();
        }
        buffers.clear();
    }

    /**
     * Reset upload stats for all buffers.
     */
    public void resetAllStats() {
        for (GpuBuffer buf : buffers) {
            buf.resetStats();
        }
    }

    /**
     * Get total bytes uploaded across all buffers since last reset.
     */
    public long getTotalBytesUploaded() {
        long total = 0;
        for (GpuBuffer buf : buffers) {
            total += buf.getTotalBytesUploaded();
        }
        return total;
    }

    public int getBufferCount() { return buffers.size(); }
}
