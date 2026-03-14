package com.github.gl46core.gl;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL45;

/**
 * Core-profile handler for VBO-based rendering paths.
 * Uses GL4.5 DSA where possible for VAO creation and EBO management.
 *
 * Handles two scenarios:
 *   1. Terrain chunks via VboRenderList — fixed BLOCK format (28 bytes/vertex)
 *   2. General VBO draws (sky, etc.) via legacy glVertexPointer/glColorPointer/
 *      glTexCoordPointer state tracking
 *
 * Note: Terrain VAO attrib setup still uses bind-to-modify (glVertexAttribPointer)
 * because the VBO is externally bound by Minecraft's VboRenderList before calling
 * setupArrayPointers(). DSA attrib format requires knowing the VBO handle.
 */
public final class CoreVboDrawHandler {

    private static final int TERRAIN_STRIDE = 28;

    // Legacy vertex array state tracking
    private static int posSize = 3, posType = GL11.GL_FLOAT, posStride = 0;
    private static long posOffset = 0;
    private static boolean posEnabled = false;

    private static int colSize = 4, colType = GL11.GL_UNSIGNED_BYTE, colStride = 0;
    private static long colOffset = 0;
    private static boolean colEnabled = false;

    private static int texSize = 2, texType = GL11.GL_FLOAT, texStride = 0;
    private static long texOffset = 0;
    private static boolean texEnabled = false;

    // Software-tracked binding state (avoids glGetInteger sync points)
    private static boolean terrainVaoBound = false;
    private static boolean vboBound = false;

    private CoreVboDrawHandler() {}

    // ═══════════════════════════════════════════════════════════════════
    // Legacy vertex array state tracking (called from GlStateManager overrides)
    // ═══════════════════════════════════════════════════════════════════

    public static void glVertexPointer(int size, int type, int stride, int offset) {
        posSize = size;
        posType = type;
        posStride = stride;
        posOffset = offset;
    }

    public static void glColorPointer(int size, int type, int stride, int offset) {
        colSize = size;
        colType = type;
        colStride = stride;
        colOffset = offset;
    }

    public static void glTexCoordPointer(int size, int type, int stride, int offset) {
        texSize = size;
        texType = type;
        texStride = stride;
        texOffset = offset;
    }

    public static void glEnableClientState(int cap) {
        // GL_VERTEX_ARRAY = 0x8074, GL_COLOR_ARRAY = 0x8076, GL_TEXTURE_COORD_ARRAY = 0x8078
        if (cap == 0x8074) posEnabled = true;
        else if (cap == 0x8076) colEnabled = true;
        else if (cap == 0x8078) texEnabled = true;
    }

    public static void glDisableClientState(int cap) {
        if (cap == 0x8074) posEnabled = false;
        else if (cap == 0x8076) colEnabled = false;
        else if (cap == 0x8078) texEnabled = false;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Terrain-specific setup (VboRenderList)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Replaces VboRenderList.setupArrayPointers().
     * Assumes the chunk VBO is already bound to GL_ARRAY_BUFFER.
     * Sets up vertex attributes on a VAO using the fixed BLOCK vertex format.
     * Uses DSA for VAO creation but bind-to-modify for attribs (VBO is external).
     */
    public static void setupArrayPointers() {
        RenderContext ctx = RenderContext.get();
        int terrainVao = ctx.handle(RenderContext.GL.TERRAIN_VAO);
        if (terrainVao == 0) {
            terrainVao = ctx.createVAO(RenderContext.GL.TERRAIN_VAO);
        }
        GL30.glBindVertexArray(terrainVao);
        terrainVaoBound = true;

        // Position: 3 floats at offset 0
        GL20.glEnableVertexAttribArray(CoreShaderProgram.ATTR_POSITION);
        GL20.glVertexAttribPointer(CoreShaderProgram.ATTR_POSITION, 3, GL11.GL_FLOAT, false, TERRAIN_STRIDE, 0);

        // Color: 4 unsigned bytes at offset 12, normalized
        GL20.glEnableVertexAttribArray(CoreShaderProgram.ATTR_COLOR);
        GL20.glVertexAttribPointer(CoreShaderProgram.ATTR_COLOR, 4, GL11.GL_UNSIGNED_BYTE, true, TERRAIN_STRIDE, 12);

        // TexCoord: 2 floats at offset 16
        GL20.glEnableVertexAttribArray(CoreShaderProgram.ATTR_TEXCOORD);
        GL20.glVertexAttribPointer(CoreShaderProgram.ATTR_TEXCOORD, 2, GL11.GL_FLOAT, false, TERRAIN_STRIDE, 16);

        // Lightmap: 2 shorts at offset 24 (NOT normalized — lightmap coords are in integer range)
        GL20.glEnableVertexAttribArray(CoreShaderProgram.ATTR_LIGHTMAP);
        GL20.glVertexAttribPointer(CoreShaderProgram.ATTR_LIGHTMAP, 2, GL11.GL_SHORT, false, TERRAIN_STRIDE, 24);

        // Normal: not present in BLOCK format
        GL20.glDisableVertexAttribArray(CoreShaderProgram.ATTR_NORMAL);
    }

    // ═══════════════════════════════════════════════════════════════════
    // General VBO draw (sky, etc.) — uses tracked legacy vertex array state
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Set up a general-purpose VAO from tracked legacy vertex array state.
     * Called when glDrawArrays is invoked with a VBO bound but not from terrain.
     * Uses DSA for VAO creation but bind-to-modify for attribs (VBO is external).
     */
    private static void setupGeneralVao() {
        RenderContext ctx = RenderContext.get();
        int generalVao = ctx.handle(RenderContext.GL.GENERAL_VAO);
        if (generalVao == 0) {
            generalVao = ctx.createVAO(RenderContext.GL.GENERAL_VAO);
        }
        GL30.glBindVertexArray(generalVao);

        // Position
        if (posEnabled) {
            GL20.glEnableVertexAttribArray(CoreShaderProgram.ATTR_POSITION);
            GL20.glVertexAttribPointer(CoreShaderProgram.ATTR_POSITION,
                    posSize, posType, false, posStride, posOffset);
        } else {
            GL20.glDisableVertexAttribArray(CoreShaderProgram.ATTR_POSITION);
        }

        // Color
        if (colEnabled) {
            GL20.glEnableVertexAttribArray(CoreShaderProgram.ATTR_COLOR);
            boolean normalize = (colType == GL11.GL_UNSIGNED_BYTE || colType == GL11.GL_BYTE);
            GL20.glVertexAttribPointer(CoreShaderProgram.ATTR_COLOR,
                    colSize, colType, normalize, colStride, colOffset);
        } else {
            GL20.glDisableVertexAttribArray(CoreShaderProgram.ATTR_COLOR);
        }

        // TexCoord
        if (texEnabled) {
            GL20.glEnableVertexAttribArray(CoreShaderProgram.ATTR_TEXCOORD);
            GL20.glVertexAttribPointer(CoreShaderProgram.ATTR_TEXCOORD,
                    texSize, texType, false, texStride, texOffset);
        } else {
            GL20.glDisableVertexAttribArray(CoreShaderProgram.ATTR_TEXCOORD);
        }

        // Lightmap and Normal: not used in general VBO path
        GL20.glDisableVertexAttribArray(CoreShaderProgram.ATTR_LIGHTMAP);
        GL20.glDisableVertexAttribArray(CoreShaderProgram.ATTR_NORMAL);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Draw entry point — called from GlStateManager.glDrawArrays override
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Draw using the core-profile shader.
     * Handles both terrain VAO and general VBO draws.
     * Converts GL_QUADS → GL_TRIANGLES via DSA index buffer.
     *
     * @param isTerrain true if the terrain VAO is already set up (from VboRenderList)
     */
    public static void draw(int mode, int first, int count, boolean isTerrain) {
        if (count <= 0) return;

        // Flush any pending immediate-mode vertices before this draw
        ImmediateModeEmulator.INSTANCE.flush();
        CoreShaderProgram.INSTANCE.ensureInitialized();

        if (!isTerrain) {
            setupGeneralVao();
        }

        // Determine format flags
        boolean hasColor, hasTexCoord, hasLightMap;
        if (isTerrain) {
            hasColor = true;
            hasTexCoord = true;
            hasLightMap = true;
        } else {
            hasColor = colEnabled;
            hasTexCoord = texEnabled;
            hasLightMap = false;
        }

        // Bind shader and upload UBO data
        CoreShaderProgram.INSTANCE.bind(hasColor, hasTexCoord, false, hasLightMap);

        if (mode == GL11.GL_QUADS) {
            drawQuadsAsTriangles(first, count);
        } else {
            GL11.glDrawArrays(mode, first, count);
        }
    }

    private static void drawQuadsAsTriangles(int first, int count) {
        int quadCount = count / 4;
        int indexCount = quadCount * 6;

        int neededQuads = first / 4 + quadCount;
        int ebo = QuadIndexBuffer.ensure(neededQuads);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ebo);

        int indexOffset = (first / 4) * 6 * 4; // byte offset
        GL11.glDrawElements(GL11.GL_TRIANGLES, indexCount, GL11.GL_UNSIGNED_INT, indexOffset);
    }

    /**
     * Returns true if our terrain VAO is currently bound.
     */
    public static boolean isTerrainVaoBound() {
        return terrainVaoBound;
    }

    /**
     * Returns true if a VBO is currently bound to GL_ARRAY_BUFFER.
     */
    public static boolean isVboBound() {
        return vboBound;
    }

    /**
     * Called when a VBO is bound/unbound to GL_ARRAY_BUFFER.
     */
    public static void setVboBound(boolean bound) {
        vboBound = bound;
    }

    /**
     * Called when terrain VAO is unbound.
     */
    public static void setTerrainVaoUnbound() {
        terrainVaoBound = false;
    }

}
