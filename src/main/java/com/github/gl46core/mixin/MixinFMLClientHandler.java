package com.github.gl46core.mixin;

import com.github.gl46core.gl.CoreSplashRenderer;
import net.minecraftforge.fml.client.FMLClientHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * Hooks processWindowMessages to render splash frames on every progress update.
 * Skipped when Modern Splash is present (see GL46CoreMixinPlugin).
 */
@Mixin(value = FMLClientHandler.class, remap = false)
public class MixinFMLClientHandler {

    /**
     * @author GL46Core
     * @reason Render splash frame on each progress update + poll GLFW events
     */
    @Overwrite
    public void processWindowMessages() {
        // Poll GLFW events to keep the window responsive (replaces Display.processMessages)
        org.lwjgl.glfw.GLFW.glfwPollEvents();
        // Render a splash frame if the splash is active
        CoreSplashRenderer.renderFrame();
    }
}
