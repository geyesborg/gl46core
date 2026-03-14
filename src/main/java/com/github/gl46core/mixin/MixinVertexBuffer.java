package com.github.gl46core.mixin;

import com.github.gl46core.gl.IMegaBufferAccess;
import com.github.gl46core.gl.MegaTerrainBuffer;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.minecraft.client.renderer.vertex.VertexFormat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;

/**
 * Intercepts VertexBuffer.bufferData() to mirror chunk geometry into
 * the MegaTerrainBuffer. After MC uploads to the per-chunk VBO,
 * we also upload the same data to a sub-allocated region of the
 * mega-buffer. This enables multi-draw indirect with baseVertex.
 *
 * Also implements IMegaBufferAccess so TerrainDrawCollector can read
 * the mega-buffer offset for each chunk's VertexBuffer.
 */
@Mixin(VertexBuffer.class)
public abstract class MixinVertexBuffer implements IMegaBufferAccess {

    @Shadow private int count;
    @Shadow private VertexFormat vertexFormat;

    @Unique private long gl46core$megaOffset = -1;
    @Unique private int gl46core$megaSize = 0;
    @Unique private int gl46core$megaVertexCount = 0;

    /**
     * After MC uploads data to the per-chunk VBO, also upload to mega-buffer.
     * The ByteBuffer still contains valid data at this point (LWJGL doesn't
     * consume it). this.count has been computed by MC.
     */
    @Inject(method = "bufferData", at = @At("TAIL"))
    private void gl46core$afterBufferData(ByteBuffer data, CallbackInfo ci) {
        MegaTerrainBuffer mega = MegaTerrainBuffer.INSTANCE;
        if (!mega.isInitialized()) return;

        // Free old allocation if we had one
        if (gl46core$megaOffset >= 0) {
            mega.free(gl46core$megaOffset, gl46core$megaSize);
            gl46core$megaOffset = -1;
            gl46core$megaSize = 0;
            gl46core$megaVertexCount = 0;
        }

        int dataSize = this.count * this.vertexFormat.getSize();
        if (dataSize <= 0) return;

        // Allocate region in mega-buffer
        long offset = mega.allocate(dataSize);
        if (offset < 0) return; // mega-buffer full — fall back to per-chunk VBO

        // Upload CPU data directly to mega-buffer region
        mega.upload(offset, data, dataSize);

        gl46core$megaOffset = offset;
        gl46core$megaSize = dataSize;
        gl46core$megaVertexCount = this.count;
    }

    /**
     * Free mega-buffer region when the VBO is deleted.
     */
    @Inject(method = "deleteGlBuffers", at = @At("HEAD"))
    private void gl46core$beforeDelete(CallbackInfo ci) {
        if (gl46core$megaOffset >= 0) {
            MegaTerrainBuffer mega = MegaTerrainBuffer.INSTANCE;
            if (mega.isInitialized()) {
                mega.free(gl46core$megaOffset, gl46core$megaSize);
            }
            gl46core$megaOffset = -1;
            gl46core$megaSize = 0;
            gl46core$megaVertexCount = 0;
        }
    }

    // ── IMegaBufferAccess implementation ──

    @Override
    public long gl46core$getMegaOffset() {
        return gl46core$megaOffset;
    }

    @Override
    public int gl46core$getMegaSize() {
        return gl46core$megaSize;
    }

    @Override
    public int gl46core$getMegaVertexCount() {
        return gl46core$megaVertexCount;
    }

    @Override
    public boolean gl46core$hasMegaRegion() {
        return gl46core$megaOffset >= 0;
    }
}
