package com.github.gl46core.mixin;

import com.github.gl46core.gl.CoreShaderProgram;
import com.github.gl46core.gl.CoreVboDrawHandler;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.VboRenderList;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.minecraft.util.BlockRenderLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * Replaces VboRenderList.renderChunkLayer() with a core-profile implementation.
 * The original uses legacy glVertexPointer/glColorPointer/glTexCoordPointer +
 * glEnableClientState (all removed in core profile) to set up the terrain VBOs.
 * 
 * We replace setupArrayPointers() with VAO-based vertex attribute setup and
 * convert GL_QUADS draws to GL_TRIANGLES using an element buffer.
 */
@Mixin(VboRenderList.class)
public abstract class MixinVboRenderList {

    /**
     * @author GL46Core
     * @reason Replace legacy vertex array setup with core-profile VAO for terrain VBOs.
     *         The original setupArrayPointers() calls glVertexPointer/glColorPointer/
     *         glTexCoordPointer which are no-ops in core profile.
     */
    @Overwrite
    private void setupArrayPointers() {
        CoreVboDrawHandler.setupArrayPointers();
    }
}
