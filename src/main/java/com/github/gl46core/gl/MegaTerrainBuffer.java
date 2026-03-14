package com.github.gl46core.gl;

import com.github.gl46core.GL46Core;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL45;

import java.util.ArrayList;
import java.util.List;

/**
 * Single large VBO holding ALL terrain chunk geometry.
 *
 * When a chunk uploads data via VertexBuffer.bufferData(), we also copy
 * it into this mega-buffer at an allocated region. During rendering,
 * TerrainDrawCollector binds this buffer ONCE and uses baseVertex in
 * indirect draw commands to reference each chunk's data.
 *
 * Allocation strategy: first-fit free list with bump fallback.
 * Freed regions are tracked for reuse. No compaction (v1).
 *
 * Benefits:
 *   - Eliminates per-chunk glVertexArrayVertexBuffer (VBO swap)
 *   - Enables multi-draw indirect (1 GL call per layer)
 *   - Better GPU memory locality
 */
public final class MegaTerrainBuffer {

    public static final MegaTerrainBuffer INSTANCE = new MegaTerrainBuffer();

    // Allocation granularity: must be a multiple of TERRAIN_STRIDE (28 bytes)
    // so every mega-buffer offset divides evenly into a baseVertex.
    // 28 * 128 = 3584 bytes (~3.5KB) — limits free-list fragmentation.
    private static final int ALLOC_GRANULARITY = CoreVboDrawHandler.TERRAIN_STRIDE * 128;

    // Buffer size limits
    private static final long MIN_BUFFER_SIZE = 64L * 1024 * 1024;   // 64MB floor
    private static final long MAX_BUFFER_SIZE = 2048L * 1024 * 1024; // 2GB ceiling

    private int handle;
    private long bufferSize;  // actual allocated size (computed from render distance)
    private boolean initialized;

    // Bump allocator high-water mark
    private long highWaterMark;

    // Free list — sorted by offset for coalescing
    private final List<FreeRegion> freeList = new ArrayList<>();

    // Stats
    private long totalAllocated;
    private long totalFreed;
    private int activeRegions;
    private long lastFullWarningMs;  // rate-limit FULL warnings

    private MegaTerrainBuffer() {}

    public void ensureInitialized() {
        if (initialized) return;
        initialized = true;

        bufferSize = computeBufferSize();
        handle = GL45.glCreateBuffers();
        // GL_DYNAMIC_STORAGE_BIT: allows glNamedBufferSubData + glCopyNamedBufferSubData
        if (!tryAllocate(bufferSize)) {
            // Driver refused — delete handle and retry at half size
            long halfSize = Math.max(MIN_BUFFER_SIZE, bufferSize / 2);
            GL46Core.LOGGER.warn("MegaTerrainBuffer: {}MB allocation failed, trying {}MB",
                    bufferSize / (1024 * 1024), halfSize / (1024 * 1024));
            GL45.glDeleteBuffers(handle);
            handle = GL45.glCreateBuffers();
            bufferSize = halfSize;
            tryAllocate(bufferSize); // if this also fails, mega-buffer stays empty
        }
        highWaterMark = 0;

        GL46Core.LOGGER.info("MegaTerrainBuffer initialized: {}MB (RD={}), handle={}",
                bufferSize / (1024 * 1024), getRenderDistance(), handle);
    }

    /**
     * Compute buffer size from current render distance.
     *
     * Formula:
     *   sections = (2*RD+1)^2 * 16
     *   estimatedBytes = sections * avgBytesPerSection * headroom
     *
     * avgBytesPerSection accounts for all layers (~20KB empirical average
     * across SOLID/CUTOUT/TRANSLUCENT, including empty sections).
     * headroom (1.5x) covers peaks during chunk rebuilds where old + new
     * regions coexist briefly.
     */
    private static long computeBufferSize() {
        int rd = getRenderDistance();
        int side = 2 * rd + 1;
        long sections = (long) side * side * 16;
        long avgBytesPerSection = 20L * 1024; // ~20KB across all layers
        long estimate = (long)(sections * avgBytesPerSection * 1.5);

        // Round up to nearest 64MB for clean GPU allocation
        long aligned = ((estimate + (64L * 1024 * 1024 - 1)) / (64L * 1024 * 1024)) * (64L * 1024 * 1024);
        long clamped = Math.max(MIN_BUFFER_SIZE, Math.min(MAX_BUFFER_SIZE, aligned));

        GL46Core.LOGGER.info("MegaTerrainBuffer sizing: RD={}, sections={}, estimate={}MB, allocated={}MB",
                rd, sections, estimate / (1024 * 1024), clamped / (1024 * 1024));
        return clamped;
    }

    /**
     * Attempt to allocate the immutable buffer storage.
     * Returns false if the driver rejects the allocation (e.g. out of VRAM).
     */
    private boolean tryAllocate(long size) {
        try {
            GL45.glNamedBufferStorage(handle, size, GL45.GL_DYNAMIC_STORAGE_BIT);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static int getRenderDistance() {
        try {
            return Minecraft.getMinecraft().gameSettings.renderDistanceChunks;
        } catch (Exception e) {
            return 12; // safe default
        }
    }

    /**
     * Allocate a region in the mega-buffer.
     *
     * @param requestedSize bytes needed (will be rounded up to ALLOC_GRANULARITY)
     * @return byte offset in the mega-buffer, or -1 if full
     */
    public long allocate(int requestedSize) {
        int size = alignUp(requestedSize, ALLOC_GRANULARITY);

        // Try free list first (first-fit)
        for (int i = 0; i < freeList.size(); i++) {
            FreeRegion r = freeList.get(i);
            if (r.size >= size) {
                long offset = r.offset;
                if (r.size == size) {
                    freeList.remove(i);
                } else {
                    r.offset += size;
                    r.size -= size;
                }
                totalAllocated += size;
                activeRegions++;
                return offset;
            }
        }

        // Bump allocate
        if (highWaterMark + size > bufferSize) {
            long now = System.currentTimeMillis();
            if (now - lastFullWarningMs > 60_000) {
                GL46Core.LOGGER.warn("MegaTerrainBuffer FULL: hwm={}/{}, freeSegs={}",
                        highWaterMark, bufferSize, freeList.size());
                lastFullWarningMs = now;
            }
            return -1;
        }
        long offset = highWaterMark;
        highWaterMark += size;
        totalAllocated += size;
        activeRegions++;
        return offset;
    }

    /**
     * Free a previously allocated region.
     * Attempts to coalesce with adjacent free regions.
     */
    public void free(long offset, int originalSize) {
        int size = alignUp(originalSize, ALLOC_GRANULARITY);
        totalFreed += size;
        activeRegions--;

        // Insert sorted by offset and try to coalesce
        long end = offset + size;
        for (int i = 0; i < freeList.size(); i++) {
            FreeRegion r = freeList.get(i);
            if (end == r.offset) {
                // Merge with next region
                r.offset = offset;
                r.size += size;
                // Check if we can also merge with previous
                if (i > 0) {
                    FreeRegion prev = freeList.get(i - 1);
                    if (prev.offset + prev.size == r.offset) {
                        prev.size += r.size;
                        freeList.remove(i);
                    }
                }
                return;
            } else if (r.offset + r.size == offset) {
                // Merge with previous region
                r.size += size;
                // Check if we can also merge with next
                if (i + 1 < freeList.size()) {
                    FreeRegion next = freeList.get(i + 1);
                    if (r.offset + r.size == next.offset) {
                        r.size += next.size;
                        freeList.remove(i + 1);
                    }
                }
                return;
            } else if (r.offset > end) {
                // Insert before this region (maintains sorted order)
                freeList.add(i, new FreeRegion(offset, size));
                return;
            }
        }
        // Append at end of free list
        freeList.add(new FreeRegion(offset, size));
    }

    /**
     * Upload CPU-side data directly to a region of the mega-buffer.
     * Called from MixinVertexBuffer after MC uploads to the per-chunk VBO.
     */
    public void upload(long megaOffset, java.nio.ByteBuffer data, int size) {
        data.position(0).limit(size);
        GL45.glNamedBufferSubData(handle, megaOffset, data);
    }

    /**
     * Bind the mega-buffer to the terrain VAO's binding point 0.
     * After this call, baseVertex in draw commands selects chunk data.
     */
    public void bindToTerrainVao() {
        int vao = RenderContext.get().handle(RenderContext.GL.TERRAIN_VAO);
        GL45.glVertexArrayVertexBuffer(vao, 0, handle, 0, CoreVboDrawHandler.TERRAIN_STRIDE);
    }

    // ── Accessors ──

    public int getHandle()          { return handle; }
    public boolean isInitialized()  { return initialized; }
    public long getHighWaterMark()  { return highWaterMark; }
    public int getActiveRegions()   { return activeRegions; }
    public int getFreeRegionCount() { return freeList.size(); }

    public float getFillPercent() {
        if (!initialized) return 0;
        long usedByActive = totalAllocated - totalFreed;
        return (float) usedByActive / bufferSize * 100f;
    }

    public long getBufferSize() { return bufferSize; }

    // ── Internals ──

    private static int alignUp(int value, int alignment) {
        return ((value + alignment - 1) / alignment) * alignment;
    }

    private static final class FreeRegion {
        long offset;
        int size;

        FreeRegion(long offset, int size) {
            this.offset = offset;
            this.size = size;
        }
    }
}
