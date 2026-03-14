package com.github.gl46core.api.render.gpu;

import org.lwjgl.opengl.GL45;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Low-level GPU buffer wrapper using GL4.5 DSA.
 *
 * Supports two allocation strategies:
 *   - {@link #allocateImmutable} — glNamedBufferStorage with GL_DYNAMIC_STORAGE_BIT
 *     for buffers updated via glNamedBufferSubData (current UBO approach)
 *   - {@link #allocatePersistent} — glNamedBufferStorage with persistent mapping
 *     for triple-buffered streaming (zero-copy upload path)
 *
 * Lifecycle: create → allocate → upload/map → destroy
 */
public final class GpuBuffer {

    /** Buffer usage hints. */
    public enum Usage {
        /** Updated via glNamedBufferSubData. Simple, correct. */
        DYNAMIC,
        /** Persistent-mapped for streaming. Zero driver overhead. */
        PERSISTENT,
        /** Written once, never updated. Optimal for static geometry. */
        STATIC
    }

    private int handle;
    private long capacity;
    private Usage usage;
    private boolean alive;

    // Persistent mapping state
    private ByteBuffer mappedPointer;
    private int sectionCount = 1;       // triple-buffer sections
    private int currentSection;
    private long sectionSize;

    // Stats
    private long totalBytesUploaded;

    public GpuBuffer() {}

    /**
     * Create the GL buffer object via DSA.
     */
    public void create() {
        handle = GL45.glCreateBuffers();
        alive = true;
    }

    /**
     * Allocate immutable storage with dynamic update support.
     * Updated via {@link #upload(ByteBuffer, long, long)}.
     */
    public void allocateImmutable(long size) {
        this.capacity = size;
        this.usage = Usage.DYNAMIC;
        GL45.glNamedBufferStorage(handle, size, GL45.GL_DYNAMIC_STORAGE_BIT);
    }

    /**
     * Allocate immutable static storage. Written once via upload, never updated.
     */
    public void allocateStatic(long size) {
        this.capacity = size;
        this.usage = Usage.STATIC;
        GL45.glNamedBufferStorage(handle, size, 0);
    }

    /**
     * Allocate persistent-mapped storage for streaming uploads.
     *
     * Creates a buffer with N sections for round-robin writes.
     * Each section is sectionSize bytes. Total buffer = sectionSize * sections.
     *
     * @param sectionSize bytes per section
     * @param sections    number of sections (typically 3 for triple buffering)
     */
    public void allocatePersistent(long sectionSize, int sections) {
        this.sectionSize = sectionSize;
        this.sectionCount = sections;
        this.capacity = sectionSize * sections;
        this.usage = Usage.PERSISTENT;

        int flags = GL45.GL_MAP_WRITE_BIT
                  | GL45.GL_MAP_PERSISTENT_BIT
                  | GL45.GL_MAP_COHERENT_BIT;

        GL45.glNamedBufferStorage(handle, capacity, flags);
        mappedPointer = GL45.glMapNamedBufferRange(handle, 0, capacity, flags);
    }

    /**
     * Upload data to the buffer via glNamedBufferSubData.
     * Only valid for DYNAMIC usage.
     */
    public void upload(ByteBuffer data, long offset, long size) {
        data.position(0).limit((int) size);
        GL45.glNamedBufferSubData(handle, offset, data);
        totalBytesUploaded += size;
    }

    /**
     * Upload entire buffer contents.
     */
    public void upload(ByteBuffer data) {
        upload(data, 0, data.remaining());
    }

    /**
     * Get a writable view into the current section of the persistent map.
     * Advances to the next section for the next frame.
     *
     * @return ByteBuffer positioned at the current section's start
     */
    public ByteBuffer mapCurrentSection() {
        if (usage != Usage.PERSISTENT || mappedPointer == null) {
            throw new IllegalStateException("Buffer not persistent-mapped");
        }
        long offset = currentSection * sectionSize;
        mappedPointer.position((int) offset).limit((int)(offset + sectionSize));
        return mappedPointer.slice().order(ByteOrder.nativeOrder());
    }

    /**
     * Advance to the next section (call after submitting draws for this frame).
     */
    public void advanceSection() {
        currentSection = (currentSection + 1) % sectionCount;
    }

    /**
     * Get the byte offset of the current section (for glBindBufferRange).
     */
    public long getCurrentSectionOffset() {
        return currentSection * sectionSize;
    }

    /**
     * Bind this buffer to an indexed binding point (UBO or SSBO).
     */
    public void bindBase(int target, int bindingPoint) {
        GL45.glBindBufferBase(target, bindingPoint, handle);
    }

    /**
     * Bind a range of this buffer to an indexed binding point.
     */
    public void bindRange(int target, int bindingPoint, long offset, long size) {
        GL45.glBindBufferRange(target, bindingPoint, handle, offset, size);
    }

    /**
     * Destroy the buffer and release GPU memory.
     */
    public void destroy() {
        if (alive) {
            if (mappedPointer != null) {
                GL45.glUnmapNamedBuffer(handle);
                mappedPointer = null;
            }
            GL45.glDeleteBuffers(handle);
            handle = 0;
            alive = false;
        }
    }

    // ── Accessors ──

    public int    getHandle()             { return handle; }
    public long   getCapacity()           { return capacity; }
    public Usage  getUsage()              { return usage; }
    public boolean isAlive()              { return alive; }
    public int    getSectionCount()       { return sectionCount; }
    public int    getCurrentSection()     { return currentSection; }
    public long   getSectionSize()        { return sectionSize; }
    public long   getTotalBytesUploaded() { return totalBytesUploaded; }
    public void   resetStats()            { totalBytesUploaded = 0; }
}
