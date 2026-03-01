package com.github.gl46core.mixin;

import com.github.gl46core.gl.CoreSplashRenderer;
import net.minecraftforge.fml.client.SplashProgress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Replaces Forge's legacy-GL splash screen with a core-profile version.
 *
 * The original SplashProgress$2 rendering thread uses direct GL11 calls
 * (glBegin, glVertex2f, glMatrixMode, etc.) that are not available in a
 * core profile context. This mixin delegates to CoreSplashRenderer which
 * uses GlStateManager calls — intercepted by MixinGlStateManager and
 * rendered through our core-profile shader pipeline.
 */
@Mixin(value = SplashProgress.class, remap = false)
public abstract class MixinSplashProgress {

    @Shadow
    private static boolean enabled;

    /**
     * @author GL46Core
     * @reason Replace legacy-GL splash with core-profile rendering
     */
    @Overwrite
    public static void start() {
        enabled = true;
        CoreSplashRenderer.start();
    }

    /**
     * @author GL46Core
     * @reason Delegate to core-profile splash renderer
     */
    @Overwrite
    public static void pause() {
        CoreSplashRenderer.pause();
    }

    /**
     * @author GL46Core
     * @reason Delegate to core-profile splash renderer
     */
    @Overwrite
    public static void resume() {
        CoreSplashRenderer.resume();
    }

    /**
     * @author GL46Core
     * @reason Delegate to core-profile splash renderer
     */
    @Overwrite
    public static void finish() {
        CoreSplashRenderer.finish();
    }

    /**
     * @author GL46Core
     * @reason Render a splash frame instead of vanilla drawSplashScreen.
     *         The original method signature throws LWJGLException which is an LWJGL2 class.
     *         We declare throws Exception so the compiler doesn't need to resolve it.
     */
    @Overwrite
    public static void drawVanillaScreen(net.minecraft.client.renderer.texture.TextureManager renderEngine) throws Exception {
        if (enabled) {
            CoreSplashRenderer.renderFrame();
        }
    }

    /**
     * @author GL46Core
     * @reason No-op when splash is enabled — we manage our own textures
     */
    @Overwrite
    public static void clearVanillaResources(net.minecraft.client.renderer.texture.TextureManager renderEngine, net.minecraft.util.ResourceLocation mojangLogo) {
        if (!enabled) {
            renderEngine.deleteTexture(mojangLogo);
        }
    }
}
