package com.github.gl46core.gl;

import com.github.gl46core.api.debug.RenderProfiler;
import com.github.gl46core.api.render.DrawPacket;
import com.github.gl46core.api.render.PassType;
import com.github.gl46core.api.translate.LegacyStateInterpreter;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;

import java.util.Arrays;

/**
 * Collects terrain chunk draws into DrawPackets, sorts them,
 * and executes in optimal order.
 *
 * Stage 1: Opaque terrain only — sorted front-to-back to reduce overdraw.
 * Other layers fall through to the immediate draw path in MixinVboRenderList.
 *
 * Execution loop:
 *   1. begin() — capture shader variant key (uniform across layer)
 *   2. submit() per chunk — create DrawPacket with VBO ref + translation + distance
 *   3. sortAndExecute() — sort front-to-back, bind terrain VAO, draw in order
 */
public final class TerrainDrawCollector {

    public static final TerrainDrawCollector INSTANCE = new TerrainDrawCollector();

    private static final int INITIAL_CAPACITY = 512;

    private DrawPacket[] packets;
    private int count;

    // Captured once per layer (state is uniform across all chunks in a layer)
    private int layerVariantKey;
    private int layerMaterialHash;

    private TerrainDrawCollector() {
        packets = new DrawPacket[INITIAL_CAPACITY];
        for (int i = 0; i < INITIAL_CAPACITY; i++) {
            packets[i] = new DrawPacket();
        }
    }

    /**
     * Begin collecting for a terrain layer. Captures the shader variant key
     * once — GL state is uniform across all chunks within a layer.
     */
    public void begin() {
        count = 0;
        layerVariantKey = LegacyStateInterpreter.INSTANCE.computeVariantKey();
        layerMaterialHash = layerVariantKey;
    }

    /**
     * Submit a terrain chunk draw into the queue.
     *
     * @param passType    TERRAIN_OPAQUE, TERRAIN_CUTOUT, or TERRAIN_TRANSLUCENT
     * @param vboId       GL buffer handle for this chunk's VBO
     * @param vertexCount number of vertices in this chunk for this layer
     * @param translateX  chunk offset from camera X
     * @param translateY  chunk offset from camera Y
     * @param translateZ  chunk offset from camera Z
     * @param chunkX      chunk block position X
     * @param chunkY      chunk block position Y
     * @param chunkZ      chunk block position Z
     * @param distanceSq  squared distance from camera to chunk center
     */
    public void submit(PassType passType, int vboId, int vertexCount,
                       float translateX, float translateY, float translateZ,
                       int chunkX, int chunkY, int chunkZ,
                       float distanceSq) {
        if (vertexCount <= 0) return;
        if (count >= packets.length) grow();

        DrawPacket p = packets[count++];
        p.reset();
        p.setPassType(passType);
        p.setShaderVariantKey(layerVariantKey);
        p.setMaterialHash(layerMaterialHash);
        p.setGeometrySource(vboId, 0, vertexCount, GL11.GL_QUADS);
        p.setTranslation(translateX, translateY, translateZ);
        p.setChunkPos(chunkX, chunkY, chunkZ);
        p.setDistanceSq(distanceSq);
        p.setSourceSystem(DrawPacket.SOURCE_TERRAIN);

        if (passType.isTranslucent()) {
            p.buildTranslucentSortKey(passType.getDefaultOrder(), layerMaterialHash & 0xFF);
        } else {
            p.buildOpaqueSortKey(passType.getDefaultOrder(), layerMaterialHash & 0xFF);
        }
    }

    /**
     * Sort collected packets and execute draws.
     *
     * Sorts front-to-back (opaque) to reduce overdraw, then iterates
     * packets issuing GL draws through the existing shader/VAO system.
     */
    public void sortAndExecute() {
        if (count == 0) return;

        // Sort by sort key (front-to-back for opaque, back-to-front for translucent)
        Arrays.sort(packets, 0, count,
            (a, b) -> Long.compare(a.getSortKey(), b.getSortKey()));

        CoreShaderProgram.INSTANCE.ensureInitialized();

        for (int i = 0; i < count; i++) {
            DrawPacket p = packets[i];

            // Bind this chunk's VBO
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, p.getGeometrySourceId());
            CoreVboDrawHandler.setVboBound(true);

            // Set up terrain vertex attribs on the terrain VAO
            CoreVboDrawHandler.setupArrayPointers();

            // Push matrix and translate to chunk world position
            CoreMatrixStack ms = CoreMatrixStack.INSTANCE;
            ms.pushMatrix();
            ms.translate(p.getTranslateX(), p.getTranslateY(), p.getTranslateZ());

            // Bind shader variant and upload UBOs (terrain format: color + tex + lightmap)
            CoreShaderProgram.INSTANCE.bind(true, true, false, true);

            // Draw — GL_QUADS converted to GL_TRIANGLES via shared index buffer
            int vertexCount = p.getVertexCount();
            if (p.getDrawMode() == GL11.GL_QUADS) {
                int quadCount = vertexCount / 4;
                int indexCount = quadCount * 6;
                int ebo = QuadIndexBuffer.ensure(quadCount);
                GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ebo);
                GL11.glDrawElements(GL11.GL_TRIANGLES, indexCount, GL11.GL_UNSIGNED_INT, 0);
            } else {
                GL11.glDrawArrays(p.getDrawMode(), p.getVertexOffset(), vertexCount);
            }

            RenderProfiler.INSTANCE.recordDrawCall(vertexCount);

            ms.popMatrix();
        }

        // Unbind VBO (matches vanilla cleanup)
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        CoreVboDrawHandler.setVboBound(false);
        CoreVboDrawHandler.setTerrainVaoUnbound();
    }

    public int getCount() { return count; }

    private void grow() {
        int newCap = packets.length * 2;
        DrawPacket[] newArr = new DrawPacket[newCap];
        System.arraycopy(packets, 0, newArr, 0, packets.length);
        for (int i = packets.length; i < newCap; i++) {
            newArr[i] = new DrawPacket();
        }
        packets = newArr;
    }
}
