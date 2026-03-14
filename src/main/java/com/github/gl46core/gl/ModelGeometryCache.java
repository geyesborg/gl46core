package com.github.gl46core.gl;

import com.github.gl46core.GL46Core;
import net.minecraft.client.model.ModelBox;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.model.PositionTextureVertex;
import net.minecraft.client.model.TexturedQuad;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL45;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * Caches compiled model geometry in VBOs for ModelRenderer parts.
 *
 * ModelBox geometry is completely static — the same cube faces with the
 * same UV mapping every frame. Only bone transforms change (handled by
 * the matrix stack). This cache compiles all quads of a ModelRenderer
 * into a single VBO on first use, then draws from cache on subsequent frames.
 *
 * Benefits:
 *   - Eliminates per-frame vertex generation (no more Tessellator per face)
 *   - Reduces draw calls from 6/box (per-face) to 1/ModelRenderer
 *   - Eliminates per-frame VBO upload bandwidth
 */
public final class ModelGeometryCache {

    public static final ModelGeometryCache INSTANCE = new ModelGeometryCache();

    // POSITION_TEX_NORMAL stride: 3f pos(12) + 2f tex(8) + 3b normal(3) + 1b pad(1) = 24
    private static final int VERTEX_STRIDE = 24;

    // Shared VAO for cached model rendering, configured once with POSITION_TEX_NORMAL layout
    private int vao;
    private boolean initialized;

    // Cache: ModelRenderer identity → compiled VBO
    private final IdentityHashMap<ModelRenderer, CachedModel> cache = new IdentityHashMap<>();

    // Frame counter for eviction
    private long frameCounter;

    // Per-frame stats
    private int frameHits;
    private int frameMisses;
    private int frameDraws;
    private int lastHits;
    private int lastMisses;
    private int lastDraws;

    static final class CachedModel {
        final int vbo;
        final int vertexCount;
        final int quadCount;
        final float scale;
        final int cubeListSize;
        long lastUsedFrame;

        CachedModel(int vbo, int vertexCount, int quadCount, float scale, int cubeListSize) {
            this.vbo = vbo;
            this.vertexCount = vertexCount;
            this.quadCount = quadCount;
            this.scale = scale;
            this.cubeListSize = cubeListSize;
        }
    }

    private ModelGeometryCache() {}

    private void ensureInitialized() {
        if (initialized) return;
        initialized = true;

        vao = GL45.glCreateVertexArrays();

        // Position: 3 floats at offset 0
        GL45.glEnableVertexArrayAttrib(vao, CoreShaderProgram.ATTR_POSITION);
        GL45.glVertexArrayAttribFormat(vao, CoreShaderProgram.ATTR_POSITION, 3, GL11.GL_FLOAT, false, 0);
        GL45.glVertexArrayAttribBinding(vao, CoreShaderProgram.ATTR_POSITION, 0);

        // Color: disabled (POSITION_TEX_NORMAL has no vertex color)
        GL45.glDisableVertexArrayAttrib(vao, CoreShaderProgram.ATTR_COLOR);

        // TexCoord: 2 floats at offset 12
        GL45.glEnableVertexArrayAttrib(vao, CoreShaderProgram.ATTR_TEXCOORD);
        GL45.glVertexArrayAttribFormat(vao, CoreShaderProgram.ATTR_TEXCOORD, 2, GL11.GL_FLOAT, false, 12);
        GL45.glVertexArrayAttribBinding(vao, CoreShaderProgram.ATTR_TEXCOORD, 0);

        // Lightmap: disabled for models
        GL45.glDisableVertexArrayAttrib(vao, CoreShaderProgram.ATTR_LIGHTMAP);

        // Normal: 3 bytes at offset 20, normalized
        GL45.glEnableVertexArrayAttrib(vao, CoreShaderProgram.ATTR_NORMAL);
        GL45.glVertexArrayAttribFormat(vao, CoreShaderProgram.ATTR_NORMAL, 3, GL11.GL_BYTE, true, 20);
        GL45.glVertexArrayAttribBinding(vao, CoreShaderProgram.ATTR_NORMAL, 0);

        GL46Core.LOGGER.info("ModelGeometryCache initialized: VAO={}", vao);
    }

    /**
     * Draw the cached geometry for a ModelRenderer's cubeList.
     * Compiles and caches on first call, then draws from VBO cache.
     */
    public void drawCached(ModelRenderer renderer, List<ModelBox> cubeList, float scale) {
        if (cubeList.isEmpty()) return;

        ensureInitialized();

        // Flush any pending immediate-mode vertices
        ImmediateModeEmulator.INSTANCE.flush();
        CoreShaderProgram.INSTANCE.ensureInitialized();

        CachedModel cached = cache.get(renderer);

        // Check if cache is valid (same scale, same cubeList size)
        if (cached != null && Float.compare(cached.scale, scale) == 0
                && cached.cubeListSize == cubeList.size()) {
            cached.lastUsedFrame = frameCounter;
            frameHits++;
        } else {
            frameMisses++;
            if (cached != null && cached.vbo != 0) {
                GL45.glDeleteBuffers(cached.vbo);
            }
            cached = compile(cubeList, scale);
            cached.lastUsedFrame = frameCounter;
            cache.put(renderer, cached);
        }

        if (cached.vertexCount == 0) return;

        // Bind model VAO and attach this model's VBO
        GL30.glBindVertexArray(vao);
        CoreVboDrawHandler.setTerrainVaoUnbound();
        GL45.glVertexArrayVertexBuffer(vao, 0, cached.vbo, 0, VERTEX_STRIDE);

        // Bind shader (texcoord=true, normal=true, color=false, lightmap=false)
        CoreShaderProgram.INSTANCE.bind(false, true, true, false);

        // Draw using shared quad index buffer
        int ebo = QuadIndexBuffer.ensure(cached.quadCount);
        GL45.glVertexArrayElementBuffer(vao, ebo);
        GL11.glDrawElements(GL11.GL_TRIANGLES, cached.quadCount * 6, GL11.GL_UNSIGNED_INT, 0);

        frameDraws++;
        com.github.gl46core.api.debug.RenderProfiler.INSTANCE.recordDrawCall(cached.vertexCount);
    }

    /**
     * Compile all quads from a cubeList into a single immutable VBO.
     */
    private CachedModel compile(List<ModelBox> cubeList, float scale) {
        int totalQuads = 0;
        for (int i = 0; i < cubeList.size(); i++) {
            totalQuads += ModelGeometryBridge.getQuads(cubeList.get(i)).length;
        }

        if (totalQuads == 0) {
            return new CachedModel(0, 0, 0, scale, cubeList.size());
        }

        int totalVertices = totalQuads * 4;
        ByteBuffer buf = ByteBuffer.allocateDirect(totalVertices * VERTEX_STRIDE)
                .order(ByteOrder.nativeOrder());

        for (int i = 0; i < cubeList.size(); i++) {
            ModelBox box = cubeList.get(i);
            for (TexturedQuad quad : ModelGeometryBridge.getQuads(box)) {
                // Compute face normal (replicates vanilla TexturedQuad.draw logic)
                Vec3d p0 = quad.vertexPositions[0].vector3D;
                Vec3d p1 = quad.vertexPositions[1].vector3D;
                Vec3d p2 = quad.vertexPositions[2].vector3D;
                Vec3d edge1 = p1.subtract(p0);
                Vec3d edge2 = p1.subtract(p2);
                Vec3d normal = edge2.crossProduct(edge1).normalize();
                byte nx = (byte) (normal.x * 127);
                byte ny = (byte) (normal.y * 127);
                byte nz = (byte) (normal.z * 127);

                for (int j = 0; j < 4; j++) {
                    PositionTextureVertex vtx = quad.vertexPositions[j];
                    buf.putFloat((float) (vtx.vector3D.x * scale));
                    buf.putFloat((float) (vtx.vector3D.y * scale));
                    buf.putFloat((float) (vtx.vector3D.z * scale));
                    buf.putFloat(vtx.texturePositionX);
                    buf.putFloat(vtx.texturePositionY);
                    buf.put(nx);
                    buf.put(ny);
                    buf.put(nz);
                    buf.put((byte) 0); // padding
                }
            }
        }

        buf.flip();

        int vbo = GL45.glCreateBuffers();
        GL45.glNamedBufferStorage(vbo, buf, 0); // immutable — geometry never changes

        return new CachedModel(vbo, totalVertices, totalQuads, scale, cubeList.size());
    }

    /**
     * Per-frame tick: evict stale entries, rotate stats.
     * Call from MixinEntityRenderer at beginFrame.
     */
    public void tick() {
        frameCounter++;

        // Evict entries not used for 600 frames (~10 seconds at 60fps)
        cache.entrySet().removeIf(e -> {
            CachedModel m = e.getValue();
            if (frameCounter - m.lastUsedFrame > 600) {
                if (m.vbo != 0) GL45.glDeleteBuffers(m.vbo);
                return true;
            }
            return false;
        });

        lastHits = frameHits;
        lastMisses = frameMisses;
        lastDraws = frameDraws;
        frameHits = 0;
        frameMisses = 0;
        frameDraws = 0;
    }

    // Stats
    public int getCacheSize()  { return cache.size(); }
    public int getLastHits()   { return lastHits; }
    public int getLastMisses() { return lastMisses; }
    public int getLastDraws()  { return lastDraws; }
}
