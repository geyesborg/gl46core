package com.github.gl46core.mixin;

import net.minecraftforge.client.CloudRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * Disable Forge's CloudRenderer VBO path so vanilla's renderClouds() runs instead.
 * Vanilla bakes scroll offsets into vertex data each frame via Tessellator/BufferBuilder,
 * which flows through CoreDrawHandler — no texture matrix needed.
 */
@Mixin(value = CloudRenderer.class, remap = false)
public class MixinCloudRenderer {

    /**
     * @author gl46core
     * @reason Forge CloudRenderer uses legacy texture matrix for scrolling which
     *         is not supported in core profile. Return false so vanilla path runs.
     */
    @Overwrite
    public boolean render(int cloudTicks, float partialTicks) {
        return false;
    }
}
