package com.github.gl46core.api.render;

import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Per-object rendering data — transforms, identity, and render flags.
 *
 * One ObjectData per renderable instance (entity, block entity, particle batch,
 * etc.). For the current legacy translation path, this is populated from
 * CoreMatrixStack's modelview at draw time.
 *
 * Future: stored in Object SSBO for GPU-driven rendering.
 *
 * GPU layout (std140, 192 bytes):
 *   mat4  modelMatrix           offset 0
 *   mat4  normalMatrix          offset 64   (inverse transpose of model, padded to mat4)
 *   mat4  prevModelMatrix       offset 128  (for motion vectors)
 *   int   objectId              offset 192
 *   int   materialId            offset 196
 *   int   chunkId               offset 200  (region/chunk reference)
 *   int   renderFlags           offset 204
 *   vec4  boundingSphere        offset 208  (xyz=center, w=radius)
 * Total: 224 bytes
 */
public final class ObjectData {

    public static final int GPU_SIZE = 224;

    // Render flag bits
    public static final int FLAG_VISIBLE        = 1;
    public static final int FLAG_SHADOW_CASTER  = 1 << 1;
    public static final int FLAG_SHADOW_RECEIVER= 1 << 2;
    public static final int FLAG_MOTION_VECTORS = 1 << 3;
    public static final int FLAG_EMISSIVE       = 1 << 4;
    public static final int FLAG_OUTLINE        = 1 << 5;
    public static final int FLAG_TRANSLUCENT    = 1 << 6;
    public static final int FLAG_ALPHA_TESTED   = 1 << 7;

    private final Matrix4f modelMatrix     = new Matrix4f();
    private final Matrix4f normalMatrix    = new Matrix4f();
    private final Matrix4f prevModelMatrix = new Matrix4f();

    private int objectId;
    private int materialId;
    private int chunkId;
    private int renderFlags = FLAG_VISIBLE | FLAG_SHADOW_CASTER | FLAG_SHADOW_RECEIVER;

    private final Vector4f boundingSphere = new Vector4f(0, 0, 0, 1.0f);

    public ObjectData() {}

    /**
     * Set the model matrix and derive the normal matrix.
     */
    public void setModelMatrix(Matrix4f model) {
        this.prevModelMatrix.set(this.modelMatrix);
        this.modelMatrix.set(model);
        this.modelMatrix.invert(this.normalMatrix).transpose();
    }

    public void setObjectId(int id)    { this.objectId = id; }
    public void setMaterialId(int id)  { this.materialId = id; }
    public void setChunkId(int id)     { this.chunkId = id; }
    public void setRenderFlags(int f)  { this.renderFlags = f; }
    public void addFlag(int flag)      { this.renderFlags |= flag; }
    public void removeFlag(int flag)   { this.renderFlags &= ~flag; }
    public void setBoundingSphere(float x, float y, float z, float radius) {
        this.boundingSphere.set(x, y, z, radius);
    }

    // ── Accessors ──

    public Matrix4f getModelMatrix()     { return modelMatrix; }
    public Matrix4f getNormalMatrix()    { return normalMatrix; }
    public Matrix4f getPrevModelMatrix() { return prevModelMatrix; }
    public int      getObjectId()        { return objectId; }
    public int      getMaterialId()      { return materialId; }
    public int      getChunkId()         { return chunkId; }
    public int      getRenderFlags()     { return renderFlags; }
    public boolean  hasFlag(int flag)    { return (renderFlags & flag) != 0; }
    public Vector4f getBoundingSphere()  { return boundingSphere; }
    public boolean  isVisible()          { return hasFlag(FLAG_VISIBLE); }
}
