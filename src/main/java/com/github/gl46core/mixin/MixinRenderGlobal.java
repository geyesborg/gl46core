package com.github.gl46core.mixin;

import com.github.gl46core.api.render.FrameOrchestrator;
import com.github.gl46core.api.render.PassType;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.util.BlockRenderLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tracks render stage transitions in RenderGlobal.
 *
 * Notifies the FrameOrchestrator when MC transitions between rendering
 * stages (sky, terrain layers, entities) so the PerPass UBO is uploaded
 * with the correct PassData for each stage.
 */
@Mixin(RenderGlobal.class)
public class MixinRenderGlobal {

    @Inject(method = "renderSky(FI)V", at = @At("HEAD"))
    private void gl46core$onRenderSky(float partialTicks, int pass, CallbackInfo ci) {
        FrameOrchestrator.INSTANCE.setActivePass(PassType.SKY);
    }

    @Inject(method = "renderBlockLayer", at = @At("HEAD"))
    private void gl46core$onRenderBlockLayer(BlockRenderLayer layer, double partialTicks,
                                              int pass, net.minecraft.entity.Entity entity,
                                              CallbackInfoReturnable<Integer> cir) {
        PassType passType;
        if (layer == BlockRenderLayer.SOLID) {
            passType = PassType.TERRAIN_OPAQUE;
        } else if (layer == BlockRenderLayer.CUTOUT_MIPPED || layer == BlockRenderLayer.CUTOUT) {
            passType = PassType.TERRAIN_CUTOUT;
        } else {
            passType = PassType.TERRAIN_TRANSLUCENT;
        }
        FrameOrchestrator.INSTANCE.setActivePass(passType);
    }

    @Inject(method = "renderEntities", at = @At("HEAD"))
    private void gl46core$onRenderEntities(net.minecraft.entity.Entity renderViewEntity,
                                            net.minecraft.client.renderer.culling.ICamera camera,
                                            float partialTicks, CallbackInfo ci) {
        FrameOrchestrator.INSTANCE.setActivePass(PassType.ENTITY_OPAQUE);
    }
}
