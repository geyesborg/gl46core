package com.github.gl46core.gl;

import com.github.gl46core.api.debug.RenderProfiler;
import com.github.gl46core.api.render.DrawPacket;
import com.github.gl46core.api.render.PassType;
import com.github.gl46core.api.translate.LegacyStateInterpreter;
import org.joml.Matrix4f;
import com.github.gl46core.api.render.gpu.IndirectDrawBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL42;

import java.util.Arrays;
import java.util.Comparator;

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

    private static final Comparator<DrawPacket> SORT_KEY_COMPARATOR =
            (a, b) -> Long.compare(a.getSortKey(), b.getSortKey());

    private DrawPacket[] packets;
    private int count;

    // Captured once per layer (state is uniform across all chunks in a layer)
    private int layerVariantKey;
    private int layerMaterialHash;

    // Per-frame stats (reset each beginFrame, accumulated across layers)
    private int frameChunksQueued;
    private int frameVerticesQueued;
    private int frameSortedLayers;
    private int frameMdiLayers;       // how many layers used MDI path
    private int frameSsboLayers;      // how many layers used SSBO per-draw path

    // Indirect draw command buffer (reused across frames)
    private IndirectDrawBuffer indirectBuf;

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
     * @param baseVertex  vertex offset in MegaTerrainBuffer, or -1 if not available
     */
    public void submit(PassType passType, int vboId, int vertexCount,
                       float translateX, float translateY, float translateZ,
                       int chunkX, int chunkY, int chunkZ,
                       float distanceSq, int baseVertex) {
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
        p.setBaseVertex(baseVertex);

        if (passType.isTranslucent()) {
            p.buildTranslucentSortKey(passType.getDefaultOrder(), layerMaterialHash & 0xFF);
        } else {
            p.buildOpaqueSortKey(passType.getDefaultOrder(), layerMaterialHash & 0xFF);
        }
    }

    // Reusable matrices — avoid per-frame allocation
    private final Matrix4f baseProj = new Matrix4f();
    private final Matrix4f baseMV = new Matrix4f();
    private final Matrix4f chunkMV = new Matrix4f();
    private final Matrix4f chunkMVP = new Matrix4f();

    /**
     * Sort collected packets and execute draws.
     *
     * Sorts front-to-back (opaque) to reduce overdraw, then iterates
     * packets issuing GL draws through the existing shader/VAO system.
     *
     * Optimization: captures base projection/modelview once, then computes
     * per-chunk MVP/MV directly without push/translate/pop. Shader variant,
     * scene UBO, and material UBO are bound once — only the PerObject UBO
     * (128 bytes of matrices) is uploaded per chunk.
     */
    public void sortAndExecute() {
        if (count == 0) return;

        // Accumulate stats
        frameSortedLayers++;
        frameChunksQueued += count;
        for (int i = 0; i < count; i++) {
            frameVerticesQueued += packets[i].getVertexCount();
        }

        // Sort by sort key (front-to-back for opaque, back-to-front for translucent)
        Arrays.sort(packets, 0, count, SORT_KEY_COMPARATOR);

        CoreShaderProgram shader = CoreShaderProgram.INSTANCE;
        shader.ensureInitialized();

        ObjectBuffer objBuf = ObjectBuffer.INSTANCE;
        objBuf.ensureInitialized();

        // Capture base matrices (set by MC before renderChunkLayer)
        CoreMatrixStack ms = CoreMatrixStack.INSTANCE;
        baseProj.set(ms.getProjection());
        baseMV.set(ms.getModelView());

        // ── Pass 1: Pack transforms + check mega-buffer availability ──
        objBuf.begin();
        int maxVerts = 0;
        boolean allHaveMega = true;
        for (int i = 0; i < count; i++) {
            DrawPacket p = packets[i];
            baseMV.translate(p.getTranslateX(), p.getTranslateY(), p.getTranslateZ(), chunkMV);
            baseProj.mul(chunkMV, chunkMVP);
            objBuf.submitTransform(chunkMVP, chunkMV);

            int v = p.getVertexCount();
            if (v > maxVerts) maxVerts = v;
            if (!p.hasMegaRegion()) allHaveMega = false;
        }
        objBuf.upload(); // ONE bulk upload to GPU

        boolean useSSBO = objBuf.isSsboMode();
        boolean useMDI = useSSBO && allHaveMega;

        // Request SSBO shader variant if using SSBO or MDI
        if (useSSBO) {
            shader.setExtraVariantBits(ShaderVariants.BIT_OBJECT_SSBO);
        }

        // Bind shader variant + upload scene/material UBOs once
        shader.bind(true, true, false, true);

        if (useSSBO) {
            shader.clearExtraVariantBits();
            objBuf.bindAsSSBO();
        }

        // Configure terrain VAO format once (DSA)
        CoreVboDrawHandler.ensureTerrainVaoDSA();

        // EBO: ensure + bind to VAO via DSA (all terrain is GL_QUADS)
        int ebo = QuadIndexBuffer.ensure(maxVerts / 4);
        CoreVboDrawHandler.bindTerrainEbo(ebo);

        // ── Pass 2: Issue draws ──
        if (useMDI) {
            // ★ MDI path: bind mega-buffer ONCE, issue ONE multi-draw indirect call
            executeMDI(objBuf);
        } else if (useSSBO) {
            // SSBO per-draw path: VBO swap + draw with gl_BaseInstance
            executeSsboPerDraw();
        } else {
            // UBO range fallback: VBO swap + bindBufferRange + draw
            executeUboFallback(objBuf);
        }

        if (useSSBO) objBuf.unbindSSBO();
        else objBuf.restorePerObjectBinding();

        shader.invalidateMatrices();

        // Mark terrain VAO as unbound for non-terrain paths
        CoreVboDrawHandler.setVboBound(false);
        CoreVboDrawHandler.setTerrainVaoUnbound();
    }

    /**
     * ★ Multi-Draw Indirect path: 1 GL call per layer.
     * All chunk geometry is in MegaTerrainBuffer. Builds DrawElementsIndirect
     * commands with baseVertex (mega-buffer offset) and baseInstance (SSBO index).
     */
    private void executeMDI(ObjectBuffer objBuf) {
        frameMdiLayers++;

        if (indirectBuf == null) {
            indirectBuf = new IndirectDrawBuffer();
            indirectBuf.initElements(4096);
        }
        indirectBuf.clear();

        // Bind mega-buffer to terrain VAO (ONE call for entire layer)
        MegaTerrainBuffer.INSTANCE.bindToTerrainVao();

        // Build indirect draw commands
        int totalVerts = 0;
        for (int i = 0; i < count; i++) {
            DrawPacket p = packets[i];
            int vertexCount = p.getVertexCount();
            int indexCount = (vertexCount / 4) * 6;
            indirectBuf.addElementsCommand(
                    indexCount,     // count (indices)
                    0,              // firstIndex (reuse same EBO pattern)
                    p.getBaseVertex(), // baseVertex (offset in mega-buffer)
                    i               // baseInstance (SSBO object index)
            );
            totalVerts += vertexCount;
        }

        // Upload commands + issue ONE multi-draw indirect call
        indirectBuf.flush();
        indirectBuf.multiDrawElements(GL11.GL_TRIANGLES, GL11.GL_UNSIGNED_INT);
        indirectBuf.unbind();

        RenderProfiler.INSTANCE.recordDrawCall(totalVerts);
    }

    /**
     * SSBO per-draw path: gl_BaseInstance indexes into ObjectSSBO.
     * Still swaps VBO per chunk but no per-draw UBO binding.
     */
    private void executeSsboPerDraw() {
        frameSsboLayers++;

        for (int i = 0; i < count; i++) {
            DrawPacket p = packets[i];
            CoreVboDrawHandler.bindTerrainChunkVbo(p.getGeometrySourceId());

            int vertexCount = p.getVertexCount();
            int indexCount = (vertexCount / 4) * 6;
            GL42.glDrawElementsInstancedBaseVertexBaseInstance(
                    GL11.GL_TRIANGLES, indexCount, GL11.GL_UNSIGNED_INT, 0L,
                    1, 0, i);

            RenderProfiler.INSTANCE.recordDrawCall(vertexCount);
        }
    }

    /**
     * UBO range fallback: per-draw glBindBufferRange + VBO swap.
     */
    private void executeUboFallback(ObjectBuffer objBuf) {
        for (int i = 0; i < count; i++) {
            DrawPacket p = packets[i];
            CoreVboDrawHandler.bindTerrainChunkVbo(p.getGeometrySourceId());
            objBuf.bindObject(i);

            int vertexCount = p.getVertexCount();
            int indexCount = (vertexCount / 4) * 6;
            GL11.glDrawElements(GL11.GL_TRIANGLES, indexCount, GL11.GL_UNSIGNED_INT, 0);

            RenderProfiler.INSTANCE.recordDrawCall(vertexCount);
        }
    }

    public int getCount() { return count; }

    // ── Stats for F3 overlay ──

    /** Call at frame start to reset per-frame stats. */
    public void resetFrameStats() {
        frameChunksQueued = 0;
        frameVerticesQueued = 0;
        frameSortedLayers = 0;
        frameMdiLayers = 0;
        frameSsboLayers = 0;
    }

    public int getFrameChunksQueued()   { return frameChunksQueued; }
    public int getFrameVerticesQueued() { return frameVerticesQueued; }
    public int getFrameSortedLayers()   { return frameSortedLayers; }
    public int getFrameMdiLayers()      { return frameMdiLayers; }
    public int getFrameSsboLayers()     { return frameSsboLayers; }

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
