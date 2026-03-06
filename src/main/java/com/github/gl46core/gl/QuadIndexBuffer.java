package com.github.gl46core.gl;

import org.lwjgl.opengl.GL45;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

/**
 * Shared GL_QUADS → GL_TRIANGLES element buffer using DSA immutable storage.
 * Both CoreDrawHandler and CoreVboDrawHandler use this to avoid duplicating
 * the index generation and EBO management logic.
 *
 * Thread-safety: all access is from the render thread only.
 */
public final class QuadIndexBuffer {

    private static int ebo = 0;
    private static int cachedMaxQuads = 0;

    private QuadIndexBuffer() {}

    /**
     * Ensure the shared EBO can handle at least {@code neededQuads} quads.
     * Returns the EBO handle (bind with GL_ELEMENT_ARRAY_BUFFER).
     */
    public static int ensure(int neededQuads) {
        if (neededQuads <= cachedMaxQuads) return ebo;

        cachedMaxQuads = Math.max(neededQuads, 256);
        int maxIndexCount = cachedMaxQuads * 6;
        IntBuffer indices = ByteBuffer.allocateDirect(maxIndexCount * 4)
                .order(ByteOrder.nativeOrder()).asIntBuffer();
        for (int q = 0; q < cachedMaxQuads; q++) {
            int base = q * 4;
            indices.put(base);     indices.put(base + 1); indices.put(base + 2);
            indices.put(base);     indices.put(base + 2); indices.put(base + 3);
        }
        indices.flip();

        int[] bufs = new int[1];
        GL45.glCreateBuffers(bufs);
        int newEbo = bufs[0];
        GL45.glNamedBufferStorage(newEbo, indices, 0);
        if (ebo != 0) GL45.glDeleteBuffers(ebo);
        ebo = newEbo;

        return ebo;
    }

    public static int getEbo() {
        return ebo;
    }
}
