package com.github.gl46core.gl;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL45;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Software emulator for glBegin/glEnd immediate mode rendering.
 *
 * Minecraft's FontRenderer and a few other systems use glBegin/glVertex3f/
 * glTexCoord2f/glEnd for drawing. These are removed in core profile.
 *
 * This collects vertices between glBegin and glEnd, then flushes them
 * through a VAO/VBO + the core shader program, identical to how
 * CoreDrawHandler handles BufferBuilder output.
 *
 * Vertex layout (per vertex, 36 bytes):
 *   float x, y, z       (12 bytes) — position
 *   ubyte r, g, b, a    (4 bytes)  — color
 *   float u, v           (8 bytes)  — texcoord
 *   float nx, ny, nz     (12 bytes) — normal
 */
public final class ImmediateModeEmulator {

    public static final ImmediateModeEmulator INSTANCE = new ImmediateModeEmulator();

    private static final int VERTEX_SIZE = 36; // bytes per vertex
    private static final int MAX_VERTICES = 8192;

    // Per-thread emulator state — prevents Modern Splash's thread from
    // corrupting the client thread's batched vertices (and vice versa).
    // Same ThreadLocal pattern as CoreMatrixStack and CoreStateTracker.
    private static class ThreadState {
        final ByteBuffer buffer = ByteBuffer.allocateDirect(MAX_VERTICES * VERTEX_SIZE)
                .order(ByteOrder.nativeOrder());
        final byte[] quadScratch = new byte[MAX_VERTICES * VERTEX_SIZE];
        int drawMode = -1;
        int vertexCount = 0;
        boolean drawing = false;

        // Current vertex state (set by glTexCoord2f, glColor4f, glNormal3f)
        float texU = 0, texV = 0;
        float colR = 1, colG = 1, colB = 1, colA = 1;
        float normX = 0, normY = 0, normZ = 1;
    }

    private final ThreadLocal<ThreadState> threadState =
            ThreadLocal.withInitial(ThreadState::new);

    private ImmediateModeEmulator() {}

    private ThreadState ts() { return threadState.get(); }

    public void begin(int mode) {
        ThreadState ts = ts();
        ts.drawMode = mode;
        ts.vertexCount = 0;
        ts.buffer.clear();
        ts.drawing = true;
    }

    public void texCoord2f(float u, float v) {
        ThreadState ts = ts();
        ts.texU = u;
        ts.texV = v;
    }

    public void color4f(float r, float g, float b, float a) {
        ThreadState ts = ts();
        ts.colR = r;
        ts.colG = g;
        ts.colB = b;
        ts.colA = a;
    }

    public void normal3f(float x, float y, float z) {
        ThreadState ts = ts();
        ts.normX = x;
        ts.normY = y;
        ts.normZ = z;
    }

    public void vertex3f(float x, float y, float z) {
        ThreadState ts = ts();
        if (!ts.drawing) return;
        if (ts.vertexCount >= MAX_VERTICES) {
            // Buffer full — flush and start fresh with same mode
            doFlush(ts);
            ts.vertexCount = 0;
            ts.buffer.clear();
        }

        // Position (12 bytes)
        ts.buffer.putFloat(x);
        ts.buffer.putFloat(y);
        ts.buffer.putFloat(z);

        // Color as RGBA ubyte (4 bytes) — clamp to [0,255] to prevent wrap-around
        ts.buffer.put((byte) Math.min(Math.max((int) (ts.colR * 255.0f + 0.5f), 0), 255));
        ts.buffer.put((byte) Math.min(Math.max((int) (ts.colG * 255.0f + 0.5f), 0), 255));
        ts.buffer.put((byte) Math.min(Math.max((int) (ts.colB * 255.0f + 0.5f), 0), 255));
        ts.buffer.put((byte) Math.min(Math.max((int) (ts.colA * 255.0f + 0.5f), 0), 255));

        // Texcoord (8 bytes)
        ts.buffer.putFloat(ts.texU);
        ts.buffer.putFloat(ts.texV);

        // Normal (12 bytes)
        ts.buffer.putFloat(ts.normX);
        ts.buffer.putFloat(ts.normY);
        ts.buffer.putFloat(ts.normZ);

        ts.vertexCount++;
    }

    public void end() {
        ThreadState ts = ts();
        if (!ts.drawing || ts.vertexCount == 0) {
            ts.drawing = false;
            return;
        }
        ts.drawing = false;

        // Expand strip/fan to independent triangles (required for core profile)
        if (ts.drawMode == GL11.GL_TRIANGLE_STRIP && ts.vertexCount >= 3) {
            expandStripToTriangles(ts, 0, ts.vertexCount);
            ts.drawMode = GL11.GL_TRIANGLES;
        } else if (ts.drawMode == GL11.GL_TRIANGLE_FAN && ts.vertexCount >= 3) {
            expandFanToTriangles(ts, 0, ts.vertexCount);
            ts.drawMode = GL11.GL_TRIANGLES;
        }

        // Flush immediately — no deferred batching.
        // Each glBegin/glEnd pair draws right away, preserving exact legacy semantics.
        doFlush(ts);
    }

    /**
     * Flush pending vertices for the CURRENT thread.
     * Now a no-op since end() flushes immediately, but kept as a safe
     * call-site for all the existing flush trigger points.
     */
    public void flush() {
        // No-op: end() flushes immediately, nothing is ever pending.
    }

    private void doFlush(ThreadState ts) {
        if (ts.vertexCount == 0) return;

        try {
            CoreShaderProgram.INSTANCE.ensureInitialized();

            int[] ids = RenderContext.get().threadLocalVaoVbo();

            GL30.glBindVertexArray(ids[0]);
            CoreVboDrawHandler.setTerrainVaoUnbound();
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, ids[1]);

            // GL_QUADS is removed in core profile — convert to GL_TRIANGLES
            int actualMode = ts.drawMode;
            int actualCount = ts.vertexCount;
            if (ts.drawMode == GL11.GL_QUADS) {
                expandQuadsToTriangles(ts);
                actualMode = GL11.GL_TRIANGLES;
                actualCount = (ts.vertexCount / 4) * 6;
            }

            ts.buffer.flip();
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, ts.buffer, GL15.GL_STREAM_DRAW);

            // Position — vec3 float at offset 0
            GL20.glEnableVertexAttribArray(CoreShaderProgram.ATTR_POSITION);
            GL20.glVertexAttribPointer(CoreShaderProgram.ATTR_POSITION, 3, GL11.GL_FLOAT, false, VERTEX_SIZE, 0);

            // Color — vec4 ubyte normalized at offset 12
            GL20.glEnableVertexAttribArray(CoreShaderProgram.ATTR_COLOR);
            GL20.glVertexAttribPointer(CoreShaderProgram.ATTR_COLOR, 4, GL11.GL_UNSIGNED_BYTE, true, VERTEX_SIZE, 12);

            // Texcoord — vec2 float at offset 16
            GL20.glEnableVertexAttribArray(CoreShaderProgram.ATTR_TEXCOORD);
            GL20.glVertexAttribPointer(CoreShaderProgram.ATTR_TEXCOORD, 2, GL11.GL_FLOAT, false, VERTEX_SIZE, 16);

            // Normal — vec3 float at offset 24
            GL20.glEnableVertexAttribArray(CoreShaderProgram.ATTR_NORMAL);
            GL20.glVertexAttribPointer(CoreShaderProgram.ATTR_NORMAL, 3, GL11.GL_FLOAT, false, VERTEX_SIZE, 24);

            // No lightmap in immediate mode
            GL20.glDisableVertexAttribArray(CoreShaderProgram.ATTR_LIGHTMAP);

            // Bind shader — always has color and texcoord from immediate mode state
            CoreShaderProgram.INSTANCE.bind(true, true, true, false);

            GL11.glDrawArrays(actualMode, 0, actualCount);
            com.github.gl46core.api.debug.RenderProfiler.INSTANCE.recordDrawCall(actualCount);

            // Shadow submission for pass tracking (no lightmap in immediate mode)
            com.github.gl46core.api.translate.LegacyDrawTranslator.INSTANCE.translateDraw(
                actualMode, actualCount, 0, true, true, true, false);

            // Don't unbind shader or VAO/VBO — dirty tracking handles re-use
        } catch (Throwable t) {
            com.github.gl46core.GL46Core.LOGGER.error("[ImmediateModeEmulator] Exception in flush():", t);
        }
    }

    /**
     * Expand a GL_TRIANGLE_STRIP segment to independent GL_TRIANGLES.
     * Reads strip vertices from [startVertex..startVertex+count), rewrites them
     * as independent triangles, and updates vertexCount.
     */
    private void expandStripToTriangles(ThreadState ts, int startVertex, int count) {
        int triCount = count - 2;
        if (triCount <= 0) return;

        int startByte = startVertex * VERTEX_SIZE;
        int dataSize = count * VERTEX_SIZE;

        // Save buffer position, read strip vertices into scratch
        int savedPos = ts.buffer.position();
        ts.buffer.position(startByte);
        ts.buffer.get(ts.quadScratch, 0, dataSize);

        // Rewind to strip start and write expanded triangles
        ts.buffer.position(startByte);
        for (int i = 0; i < triCount; i++) {
            if (i % 2 == 0) {
                // Even triangle: vertices i, i+1, i+2
                ts.buffer.put(ts.quadScratch, (i) * VERTEX_SIZE, VERTEX_SIZE);
                ts.buffer.put(ts.quadScratch, (i + 1) * VERTEX_SIZE, VERTEX_SIZE);
                ts.buffer.put(ts.quadScratch, (i + 2) * VERTEX_SIZE, VERTEX_SIZE);
            } else {
                // Odd triangle: vertices i+1, i, i+2 (reversed winding)
                ts.buffer.put(ts.quadScratch, (i + 1) * VERTEX_SIZE, VERTEX_SIZE);
                ts.buffer.put(ts.quadScratch, (i) * VERTEX_SIZE, VERTEX_SIZE);
                ts.buffer.put(ts.quadScratch, (i + 2) * VERTEX_SIZE, VERTEX_SIZE);
            }
        }
        ts.vertexCount = startVertex + triCount * 3;
    }

    /**
     * Expand a GL_TRIANGLE_FAN segment to independent GL_TRIANGLES.
     * Fan vertex 0 is the hub; triangles are (0, i+1, i+2) for each i.
     */
    private void expandFanToTriangles(ThreadState ts, int startVertex, int count) {
        int triCount = count - 2;
        if (triCount <= 0) return;

        int startByte = startVertex * VERTEX_SIZE;
        int dataSize = count * VERTEX_SIZE;

        int savedPos = ts.buffer.position();
        ts.buffer.position(startByte);
        ts.buffer.get(ts.quadScratch, 0, dataSize);

        ts.buffer.position(startByte);
        for (int i = 0; i < triCount; i++) {
            ts.buffer.put(ts.quadScratch, 0, VERTEX_SIZE);                    // hub vertex
            ts.buffer.put(ts.quadScratch, (i + 1) * VERTEX_SIZE, VERTEX_SIZE);
            ts.buffer.put(ts.quadScratch, (i + 2) * VERTEX_SIZE, VERTEX_SIZE);
        }
        ts.vertexCount = startVertex + triCount * 3;
    }

    /**
     * Convert GL_QUADS vertex data to GL_TRIANGLES in-place.
     * For every 4 vertices (quad), emit 6 vertices (two triangles: 0,1,2 and 0,2,3).
     * The buffer is rewritten with the expanded data.
     */
    private void expandQuadsToTriangles(ThreadState ts) {
        int quadCount = ts.vertexCount / 4;
        if (quadCount == 0) return;

        // Read the current quad data into reusable scratch buffer
        ts.buffer.flip();
        int dataSize = ts.vertexCount * VERTEX_SIZE;
        ts.buffer.get(ts.quadScratch, 0, dataSize);

        // Allocate expanded triangle data
        ts.buffer.clear();
        for (int q = 0; q < quadCount; q++) {
            int base = q * 4 * VERTEX_SIZE;
            // Triangle 1: vertices 0, 1, 2
            ts.buffer.put(ts.quadScratch, base + 0 * VERTEX_SIZE, VERTEX_SIZE);
            ts.buffer.put(ts.quadScratch, base + 1 * VERTEX_SIZE, VERTEX_SIZE);
            ts.buffer.put(ts.quadScratch, base + 2 * VERTEX_SIZE, VERTEX_SIZE);
            // Triangle 2: vertices 0, 2, 3
            ts.buffer.put(ts.quadScratch, base + 0 * VERTEX_SIZE, VERTEX_SIZE);
            ts.buffer.put(ts.quadScratch, base + 2 * VERTEX_SIZE, VERTEX_SIZE);
            ts.buffer.put(ts.quadScratch, base + 3 * VERTEX_SIZE, VERTEX_SIZE);
        }
    }

    /**
     * Sync the emulator's current color with CoreStateTracker's glColor4f state.
     * Called at glBegin time so that vertices inherit the current GL color.
     */
    public void syncColorFromState() {
        ThreadState ts = ts();
        CoreStateTracker state = CoreStateTracker.INSTANCE;
        ts.colR = state.getColorR();
        ts.colG = state.getColorG();
        ts.colB = state.getColorB();
        ts.colA = state.getColorA();
    }
}
