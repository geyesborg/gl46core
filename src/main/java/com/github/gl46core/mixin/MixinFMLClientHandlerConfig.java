package com.github.gl46core.mixin;

import com.github.gl46core.client.ConfigGuiInjector;
import net.minecraftforge.fml.client.FMLClientHandler;
import net.minecraftforge.fml.client.IModGuiFactory;
import net.minecraftforge.fml.common.ModContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Intercepts getGuiFactoryFor to inject config GUIs for mods without one.
 * Separate from MixinFMLClientHandler (splash) so it's never skipped.
 */
@Mixin(value = FMLClientHandler.class, remap = false)
public class MixinFMLClientHandlerConfig {

    @Inject(method = "getGuiFactoryFor", at = @At("RETURN"), cancellable = true)
    private void gl46core$injectConfigGui(ModContainer mc, CallbackInfoReturnable<IModGuiFactory> cir) {
        if (cir.getReturnValue() == null) {
            IModGuiFactory factory = ConfigGuiInjector.getFactory(mc);
            if (factory != null) {
                cir.setReturnValue(factory);
            }
        }
    }
}
