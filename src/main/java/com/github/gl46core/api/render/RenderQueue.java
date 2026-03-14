package com.github.gl46core.api.render;

import java.util.Arrays;

/**
 * High-performance submission queue for a render pass.
 *
 * Collects {@link RenderSubmission} instances during the submit phase,
 * then sorts them by sort key before execution. Uses a flat array with
 * manual growth to avoid GC pressure from ArrayList boxing.
 *
 * Each pass type gets its own queue:
 *   - Opaque queues: sorted front-to-back (minimize overdraw)
 *   - Translucent queues: sorted back-to-front (correct blending)
 *   - UI queue: insertion order (no sorting)
 *
 * The queue owns a pool of RenderSubmission objects that are reused
 * across frames to eliminate per-frame allocation.
 */
public final class RenderQueue {

    /** Sort mode for this queue. */
    public enum SortMode {
        FRONT_TO_BACK,      // opaque geometry
        BACK_TO_FRONT,      // translucent geometry
        BY_MATERIAL,        // minimize state changes (shadow passes)
        NONE                // insertion order (UI, debug)
    }

    private static final int INITIAL_CAPACITY = 256;

    private RenderSubmission[] submissions;
    private int count;
    private final SortMode sortMode;
    private boolean sorted;

    // Stats
    private int peakCount;

    public RenderQueue(SortMode sortMode) {
        this.sortMode = sortMode;
        this.submissions = new RenderSubmission[INITIAL_CAPACITY];
        for (int i = 0; i < INITIAL_CAPACITY; i++) {
            submissions[i] = new RenderSubmission();
        }
    }

    /**
     * Reset for a new frame. Does not deallocate — reuses existing pool.
     */
    public void clear() {
        count = 0;
        sorted = false;
    }

    /**
     * Acquire the next submission slot. Returns a reusable object
     * that the caller should populate before the next acquire() call.
     */
    public RenderSubmission acquire() {
        if (count >= submissions.length) {
            grow();
        }
        sorted = false;
        return submissions[count++];
    }

    /**
     * Sort all submissions by their sort keys.
     * Called once before execution, after all submissions are collected.
     */
    public void sort() {
        if (sorted || count <= 1 || sortMode == SortMode.NONE) {
            sorted = true;
            return;
        }

        // Sort the active portion of the array by sort key
        Arrays.sort(submissions, 0, count,
            (a, b) -> Long.compare(a.getSortKey(), b.getSortKey()));

        sorted = true;
        peakCount = Math.max(peakCount, count);
    }

    /**
     * Get submission at index. Must call sort() first for correct order.
     */
    public RenderSubmission get(int index) {
        return submissions[index];
    }

    public int       getCount()     { return count; }
    public boolean   isEmpty()      { return count == 0; }
    public SortMode  getSortMode()  { return sortMode; }
    public int       getPeakCount() { return peakCount; }

    private void grow() {
        int newCap = submissions.length * 2;
        RenderSubmission[] newArr = new RenderSubmission[newCap];
        System.arraycopy(submissions, 0, newArr, 0, submissions.length);
        for (int i = submissions.length; i < newCap; i++) {
            newArr[i] = new RenderSubmission();
        }
        submissions = newArr;
    }
}
