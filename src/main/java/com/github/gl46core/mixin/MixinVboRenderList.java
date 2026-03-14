package com.github.gl46core.mixin;

import com.github.gl46core.api.render.PassType;
import com.github.gl46core.gl.CoreVboDrawHandler;
import com.github.gl46core.gl.TerrainDrawCollector;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ChunkRenderContainer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.VboRenderList;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.minecraft.entity.Entity;
import com.github.gl46core.gl.IMegaBufferAccess;
import com.github.gl46core.gl.MegaTerrainBuffer;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * Replaces VboRenderList rendering with core-profile implementation.
 *
 * Stage 1: Opaque terrain (SOLID layer) is collected into DrawPackets,
 * sorted front-to-back to reduce overdraw, then executed.
 * All other layers use the immediate draw path (same as vanilla flow).
 *
 * setupArrayPointers() is replaced with VAO-based vertex attribute setup.
 */
@Mixin(VboRenderList.class)
public abstract class MixinVboRenderList extends ChunkRenderContainer {

    /**
     * @author GL46Core
     * @reason Route opaque terrain through DrawPacket queue for front-to-back sorting.
     *         Other layers use immediate draw path. Core-profile VAO/shader for all.
     */
    @Overwrite
    public void renderChunkLayer(BlockRenderLayer layer) {
        if (this.renderChunks.isEmpty()) return;

        switch (layer) {
            case SOLID:
                renderQueued(layer, PassType.TERRAIN_OPAQUE);
                break;
            case CUTOUT_MIPPED:
            case CUTOUT:
                renderQueued(layer, PassType.TERRAIN_CUTOUT);
                break;
            case TRANSLUCENT:
                renderQueued(layer, PassType.TERRAIN_TRANSLUCENT);
                break;
            default:
                renderImmediate(layer);
                break;
        }
    }

    /**
     * Queued path — collect DrawPackets, sort front-to-back, execute.
     * Used for opaque terrain (Stage 1). Later stages add cutout/translucent.
     */
    private void renderQueued(BlockRenderLayer layer, PassType passType) {
        // Compute camera position from render view entity
        Minecraft mc = Minecraft.getMinecraft();
        Entity viewEntity = mc.getRenderViewEntity();
        double camX, camY, camZ;
        if (viewEntity != null) {
            float pt = mc.getRenderPartialTicks();
            camX = viewEntity.lastTickPosX + (viewEntity.posX - viewEntity.lastTickPosX) * pt;
            camY = viewEntity.lastTickPosY + (viewEntity.posY - viewEntity.lastTickPosY) * pt;
            camZ = viewEntity.lastTickPosZ + (viewEntity.posZ - viewEntity.lastTickPosZ) * pt;
        } else {
            // Fallback — shouldn't happen during world rendering
            renderImmediate(layer);
            return;
        }

        MegaTerrainBuffer.INSTANCE.ensureInitialized();

        TerrainDrawCollector collector = TerrainDrawCollector.INSTANCE;
        collector.begin();

        for (RenderChunk chunk : this.renderChunks) {
            VertexBuffer vbo = chunk.getVertexBufferByLayer(layer.ordinal());
            int vboId = ((AccessorVertexBuffer) vbo).gl46core$getGlBufferId();
            int vertexCount = ((AccessorVertexBuffer) vbo).gl46core$getCount();

            if (vertexCount <= 0) continue;

            BlockPos pos = chunk.getPosition();
            float tx = (float)(pos.getX() - camX);
            float ty = (float)(pos.getY() - camY);
            float tz = (float)(pos.getZ() - camZ);

            // Distance from camera to chunk center (chunk is 16x16x16)
            float cx = tx + 8, cy = ty + 8, cz = tz + 8;
            float distSq = cx * cx + cy * cy + cz * cz;

            // Check mega-buffer region for MDI baseVertex
            IMegaBufferAccess megaAccess = (IMegaBufferAccess) vbo;
            int baseVertex = megaAccess.gl46core$hasMegaRegion()
                    ? (int)(megaAccess.gl46core$getMegaOffset() / CoreVboDrawHandler.TERRAIN_STRIDE)
                    : -1;

            collector.submit(passType, vboId, vertexCount,
                    tx, ty, tz,
                    pos.getX(), pos.getY(), pos.getZ(),
                    distSq, baseVertex);
        }

        collector.sortAndExecute();

        // Cleanup (matches vanilla)
        OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, 0);
        GlStateManager.resetColor();
        this.renderChunks.clear();
    }

    /**
     * Immediate path — vanilla draw order, no sorting.
     * Used for cutout, translucent, and any unmigrated layers.
     */
    private void renderImmediate(BlockRenderLayer layer) {
        for (RenderChunk chunk : this.renderChunks) {
            VertexBuffer vbo = chunk.getVertexBufferByLayer(layer.ordinal());
            GlStateManager.pushMatrix();
            this.preRenderChunk(chunk);
            vbo.bindBuffer();
            this.setupArrayPointers();
            vbo.drawArrays(GL11.GL_QUADS);
            GlStateManager.popMatrix();
        }

        OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, 0);
        GlStateManager.resetColor();
        this.renderChunks.clear();
    }

    /**
     * @author GL46Core
     * @reason Replace legacy vertex array setup with core-profile VAO for terrain VBOs.
     */
    @Overwrite
    private void setupArrayPointers() {
        CoreVboDrawHandler.setupArrayPointers();
    }
}
