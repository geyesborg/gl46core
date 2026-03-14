package com.github.gl46core.mixin;

import net.minecraft.client.renderer.vertex.VertexBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor for VertexBuffer's private GL buffer ID and vertex count.
 * Used by the terrain draw collector to capture geometry references
 * into DrawPackets without calling bind/draw immediately.
 */
@Mixin(VertexBuffer.class)
public interface AccessorVertexBuffer {

    @Accessor("glBufferId")
    int gl46core$getGlBufferId();

    @Accessor("count")
    int gl46core$getCount();
}
