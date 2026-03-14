package com.github.gl46core.api.render.deferred;

import com.github.gl46core.api.render.PassType;

import java.util.Arrays;

/**
 * Per-frame growable list of {@link DrawCommand}s with pool allocation.
 *
 * Commands are acquired via {@link #acquire()}, configured by the caller,
 * then replayed in sorted order by {@link DrawCommandExecutor}.
 * At frame start, all commands are recycled (zero GC).
 *
 * Sorting is done in two phases:
 *   1. Group by PassType (enum ordinal order = pass graph order)
 *   2. Within each group, sort by sort key (opaque: front-to-back,
 *      translucent: back-to-front)
 */
public final class DrawCommandBuffer {

    private static final int INITIAL_CAPACITY = 4096;

    private DrawCommand[] commands;
    private int count;
    private boolean sorted;

    public DrawCommandBuffer() {
        commands = new DrawCommand[INITIAL_CAPACITY];
        for (int i = 0; i < INITIAL_CAPACITY; i++) {
            commands[i] = new DrawCommand();
        }
    }

    /**
     * Reset for a new frame. Recycles all commands.
     */
    public void beginFrame() {
        for (int i = 0; i < count; i++) {
            commands[i].reset();
        }
        count = 0;
        sorted = false;
    }

    /**
     * Acquire a command slot from the pool.
     * Caller must configure it via {@link DrawCommand#configure}.
     */
    public DrawCommand acquire() {
        if (count >= commands.length) grow();
        DrawCommand cmd = commands[count++];
        return cmd;
    }

    /**
     * Sort all commands by pass type then sort key.
     * Call once before replay.
     */
    public void sort() {
        if (sorted || count == 0) return;
        Arrays.sort(commands, 0, count, (a, b) -> {
            // Primary: pass type ordinal (matches pass graph execution order)
            int cmp = Integer.compare(a.passType.ordinal(), b.passType.ordinal());
            if (cmp != 0) return cmp;
            // Secondary: sort key (unsigned comparison)
            return Long.compareUnsigned(a.sortKey, b.sortKey);
        });
        sorted = true;
    }

    /**
     * Get command at index. Only valid after sort or for sequential access.
     */
    public DrawCommand get(int index) {
        return commands[index];
    }

    /**
     * Get the total number of recorded commands this frame.
     */
    public int getCount() {
        return count;
    }

    /**
     * Find the start index for a given pass type in the sorted buffer.
     * Returns count if no commands exist for this pass.
     */
    public int findPassStart(PassType type) {
        int ord = type.ordinal();
        for (int i = 0; i < count; i++) {
            if (commands[i].passType.ordinal() >= ord) return i;
        }
        return count;
    }

    /**
     * Find the end index (exclusive) for a given pass type.
     */
    public int findPassEnd(PassType type) {
        int ord = type.ordinal();
        for (int i = count - 1; i >= 0; i--) {
            if (commands[i].passType.ordinal() <= ord) return i + 1;
        }
        return 0;
    }

    private void grow() {
        int newLen = commands.length * 2;
        DrawCommand[] newArr = new DrawCommand[newLen];
        System.arraycopy(commands, 0, newArr, 0, commands.length);
        for (int i = commands.length; i < newLen; i++) {
            newArr[i] = new DrawCommand();
        }
        commands = newArr;
    }
}
