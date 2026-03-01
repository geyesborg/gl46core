package com.github.gl46core.mixin;

import com.github.gl46core.gl.CoreDrawHandler;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.WorldVertexBufferUploader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * Replaces WorldVertexBufferUploader.draw() with a core-profile implementation.
 * The original uses legacy glVertexPointer/glColorPointer/glTexCoordPointer +
 * glEnableClientState, none of which exist in core profile.
 */
@Mixin(WorldVertexBufferUploader.class)
public abstract class MixinWorldVertexBufferUploader {

    /**
     * @author GL46Core
     * @reason Replace legacy vertex array draw with VAO/VBO + shader
     */
    @Overwrite
    public void draw(BufferBuilder bufferBuilderIn) {
        CoreDrawHandler.draw(bufferBuilderIn);
    }
}
