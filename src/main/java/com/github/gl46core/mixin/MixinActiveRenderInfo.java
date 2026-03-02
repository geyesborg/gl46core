package com.github.gl46core.mixin;

import com.github.gl46core.gl.CoreMatrixStack;
import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.entity.player.EntityPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.FloatBuffer;

/**
 * Fixes ActiveRenderInfo.MODELVIEW and PROJECTION FloatBuffers in core profile.
 *
 * Vanilla updateRenderInfo() calls glGetFloat(GL_MODELVIEW_MATRIX) and
 * glGetFloat(GL_PROJECTION_MATRIX) which are removed in core profile — they
 * return stale/garbage data. This mixin overwrites those buffers with
 * gl46core's software-tracked matrices so that Celeritas, Nvidium, and any
 * other mod reading these buffers gets correct values.
 */
@Mixin(ActiveRenderInfo.class)
public abstract class MixinActiveRenderInfo {

    @Shadow
    private static FloatBuffer MODELVIEW;

    @Shadow
    private static FloatBuffer PROJECTION;

    @Inject(method = "updateRenderInfo", at = @At("TAIL"))
    private static void gl46core$fixMatrixBuffers(EntityPlayer entityplayerIn, boolean isThirdPerson, CallbackInfo ci) {
        CoreMatrixStack ms = CoreMatrixStack.INSTANCE;

        // Overwrite the MODELVIEW buffer with gl46core's software-tracked modelview matrix
        MODELVIEW.clear();
        ms.getModelView().get(MODELVIEW);
        MODELVIEW.rewind();

        // Overwrite the PROJECTION buffer with gl46core's software-tracked projection matrix
        PROJECTION.clear();
        ms.getProjection().get(PROJECTION);
        PROJECTION.rewind();
    }
}
