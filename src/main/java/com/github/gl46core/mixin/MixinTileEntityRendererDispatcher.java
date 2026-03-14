package com.github.gl46core.mixin;

import com.github.gl46core.api.render.FrameOrchestrator;
import com.github.gl46core.api.render.PassType;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.tileentity.TileEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tracks block entity (tile entity) rendering stage for PerPass UBO.
 */
@Mixin(TileEntityRendererDispatcher.class)
public class MixinTileEntityRendererDispatcher {

    @Inject(method = "render(Lnet/minecraft/tileentity/TileEntity;FI)V", at = @At("HEAD"))
    private void gl46core$onRenderTileEntity(TileEntity te, float partialTicks, int destroyStage, CallbackInfo ci) {
        FrameOrchestrator.INSTANCE.setActivePass(PassType.BLOCK_ENTITY);
    }
}
