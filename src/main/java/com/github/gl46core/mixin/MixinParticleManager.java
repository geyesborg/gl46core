package com.github.gl46core.mixin;

import com.github.gl46core.api.render.FrameOrchestrator;
import com.github.gl46core.api.render.PassType;
import net.minecraft.client.particle.ParticleManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tracks particle rendering stage for PerPass UBO.
 */
@Mixin(ParticleManager.class)
public class MixinParticleManager {

    @Inject(method = "renderParticles", at = @At("HEAD"))
    private void gl46core$onRenderParticles(net.minecraft.entity.Entity entity, float partialTicks, CallbackInfo ci) {
        FrameOrchestrator.INSTANCE.setActivePass(PassType.PARTICLES);
    }
}
