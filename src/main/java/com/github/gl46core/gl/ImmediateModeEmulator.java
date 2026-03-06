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
    private final byte[] quadScratch = new byte[MAX_VERTICES * VERTEX_SIZE]; // reusable scratch buffer

    private final ByteBuffer buffer;
    private int drawMode = -1;
    private int vertexCount = 0;
    private boolean drawing = false;

    // Current vertex state (set by glTexCoord2f, glColor4f, glNormal3f before glVertex3f)
    private float texU = 0, texV = 0;
    private float colR = 1, colG = 1, colB = 1, colA = 1;
    private float normX = 0, normY = 0, normZ = 1;

    private static int vao = 0;
    private static int vbo = 0;

    private ImmediateModeEmulator() {
        buffer = ByteBuffer.allocateDirect(MAX_VERTICES * VERTEX_SIZE)
                .order(ByteOrder.nativeOrder());
    }

    public void begin(int mode) {
        drawMode = mode;
        vertexCount = 0;
        drawing = true;
        buffer.clear();
    }

    public void texCoord2f(float u, float v) {
        texU = u;
        texV = v;
    }

    public void color4f(float r, float g, float b, float a) {
        colR = r;
        colG = g;
        colB = b;
        colA = a;
    }

    public void normal3f(float x, float y, float z) {
        normX = x;
        normY = y;
        normZ = z;
    }

    public void vertex3f(float x, float y, float z) {
        if (!drawing || vertexCount >= MAX_VERTICES) return;

        // Position (12 bytes)
        buffer.putFloat(x);
        buffer.putFloat(y);
        buffer.putFloat(z);

        // Color as RGBA ubyte (4 bytes) — clamp to [0,255] to prevent wrap-around
        buffer.put((byte) Math.min(Math.max((int) (colR * 255.0f + 0.5f), 0), 255));
        buffer.put((byte) Math.min(Math.max((int) (colG * 255.0f + 0.5f), 0), 255));
        buffer.put((byte) Math.min(Math.max((int) (colB * 255.0f + 0.5f), 0), 255));
        buffer.put((byte) Math.min(Math.max((int) (colA * 255.0f + 0.5f), 0), 255));

        // Texcoord (8 bytes)
        buffer.putFloat(texU);
        buffer.putFloat(texV);

        // Normal (12 bytes)
        buffer.putFloat(normX);
        buffer.putFloat(normY);
        buffer.putFloat(normZ);

        vertexCount++;
    }

    public void end() {
        if (!drawing || vertexCount == 0) {
            drawing = false;
            return;
        }
        drawing = false;

        CoreShaderProgram.INSTANCE.ensureInitialized();

        if (vao == 0) {
            int[] vaos = new int[1];
            GL45.glCreateVertexArrays(vaos);
            vao = vaos[0];
        }
        if (vbo == 0) {
            int[] bufs = new int[1];
            GL45.glCreateBuffers(bufs);
            vbo = bufs[0];
        }

        GL30.glBindVertexArray(vao);
        CoreVboDrawHandler.setTerrainVaoUnbound();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);

        // GL_QUADS is removed in core profile — convert to GL_TRIANGLES
        int actualMode = drawMode;
        int actualCount = vertexCount;
        if (drawMode == GL11.GL_QUADS) {
            expandQuadsToTriangles();
            actualMode = GL11.GL_TRIANGLES;
            actualCount = (vertexCount / 4) * 6;
        }

        buffer.flip();
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buffer, GL15.GL_STREAM_DRAW);

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

        // Don't unbind shader or VAO/VBO — dirty tracking handles re-use
    }

    /**
     * Convert GL_QUADS vertex data to GL_TRIANGLES in-place.
     * For every 4 vertices (quad), emit 6 vertices (two triangles: 0,1,2 and 0,2,3).
     * The buffer is rewritten with the expanded data.
     */
    private void expandQuadsToTriangles() {
        int quadCount = vertexCount / 4;
        if (quadCount == 0) return;

        // Read the current quad data into reusable scratch buffer
        buffer.flip();
        int dataSize = vertexCount * VERTEX_SIZE;
        buffer.get(quadScratch, 0, dataSize);

        // Allocate expanded triangle data
        buffer.clear();
        for (int q = 0; q < quadCount; q++) {
            int base = q * 4 * VERTEX_SIZE;
            // Triangle 1: vertices 0, 1, 2
            buffer.put(quadScratch, base + 0 * VERTEX_SIZE, VERTEX_SIZE);
            buffer.put(quadScratch, base + 1 * VERTEX_SIZE, VERTEX_SIZE);
            buffer.put(quadScratch, base + 2 * VERTEX_SIZE, VERTEX_SIZE);
            // Triangle 2: vertices 0, 2, 3
            buffer.put(quadScratch, base + 0 * VERTEX_SIZE, VERTEX_SIZE);
            buffer.put(quadScratch, base + 2 * VERTEX_SIZE, VERTEX_SIZE);
            buffer.put(quadScratch, base + 3 * VERTEX_SIZE, VERTEX_SIZE);
        }
    }

    /**
     * Sync the emulator's current color with CoreStateTracker's glColor4f state.
     * Called at glBegin time so that vertices inherit the current GL color.
     */
    public void syncColorFromState() {
        CoreStateTracker state = CoreStateTracker.INSTANCE;
        colR = state.getColorR();
        colG = state.getColorG();
        colB = state.getColorB();
        colA = state.getColorA();
    }
}
