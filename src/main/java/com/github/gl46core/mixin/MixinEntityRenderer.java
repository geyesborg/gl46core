package com.github.gl46core.mixin;

import com.github.gl46core.api.debug.RenderProfiler;
import com.github.gl46core.api.render.FrameOrchestrator;
import com.github.gl46core.api.translate.LegacyDrawTranslator;
import com.github.gl46core.api.translate.LegacyStateInterpreter;
import com.github.gl46core.gl.CoreMatrixStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks the frame rendering lifecycle into EntityRenderer.
 *
 * Injects beginFrame/endFrame around the main render pass to drive
 * the FrameOrchestrator, RenderProfiler, and scene data capture.
 */
@Mixin(EntityRenderer.class)
public class MixinEntityRenderer {

    /**
     * Begin frame — fires at the start of updateCameraAndRender,
     * before any world or GUI rendering.
     */
    @Inject(method = "updateCameraAndRender", at = @At("HEAD"))
    private void gl46core$beginFrame(float partialTicks, long nanoTime, CallbackInfo ci) {
        Minecraft mc = Minecraft.getMinecraft();
        double worldTime = mc.world != null
                ? mc.world.getTotalWorldTime() + partialTicks
                : 0.0;

        // Drive the orchestrator lifecycle
        FrameOrchestrator.INSTANCE.beginFrame(partialTicks, worldTime);
        FrameOrchestrator.INSTANCE.beginCollectScene();

        // Start profiler
        RenderProfiler.INSTANCE.beginFrame();

        // Reset per-frame translation counter
        LegacyDrawTranslator.INSTANCE.beginFrame();
    }

    /**
     * Capture scene data — fires at the start of renderWorldPass,
     * after camera setup but before any draw calls.
     */
    @Inject(method = "renderWorldPass", at = @At("HEAD"))
    private void gl46core$captureScene(int pass, float partialTicks, long finishTimeNano, CallbackInfo ci) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null || mc.player == null) return;

        FrameOrchestrator orch = FrameOrchestrator.INSTANCE;

        // Capture camera state from CoreMatrixStack
        CoreMatrixStack ms = CoreMatrixStack.INSTANCE;
        orch.getFrameContext().getCamera().capture(
            ms.getModelView(), ms.getProjection(),
            mc.player.posX, mc.player.posY + mc.player.getEyeHeight(), mc.player.posZ,
            partialTicks, mc.gameSettings.fovSetting,
            0.05f, mc.gameSettings.renderDistanceChunks * 16.0f,
            mc.displayWidth, mc.displayHeight
        );

        // Capture fog state from CoreStateTracker
        LegacyStateInterpreter.INSTANCE.captureFog(orch.getFrameContext().getFog());

        // Capture global lighting from CoreStateTracker
        LegacyStateInterpreter.INSTANCE.captureGlobalLight(orch.getFrameContext().getGlobalLight());

        // Capture weather
        float rain = mc.world.getRainStrength(partialTicks);
        float thunder = mc.world.getThunderStrength(partialTicks);
        float temp = mc.world.getBiome(mc.player.getPosition()).getDefaultTemperature();
        boolean isSnow = temp < 0.15f && rain > 0;
        orch.getFrameContext().getWeather().capture(rain, thunder, temp, isSnow);

        // Capture dimension
        int dimId = mc.world.provider.getDimensionType().getId();
        boolean hasSky = mc.world.provider.hasSkyLight();
        boolean hasCeiling = mc.world.provider.isNether();
        float celestialAngle = mc.world.getCelestialAngle(partialTicks);
        float sunBrightness = mc.world.getSunBrightness(partialTicks);
        float starBrightness = mc.world.getStarBrightness(partialTicks);
        net.minecraft.util.math.Vec3d skyColor = mc.world.getSkyColor(mc.getRenderViewEntity(), partialTicks);
        orch.getFrameContext().getDimension().capture(dimId, hasSky, hasCeiling,
            celestialAngle, sunBrightness, starBrightness,
            (float) skyColor.x, (float) skyColor.y, (float) skyColor.z);

        // Finalize scene collection
        orch.endCollectScene();

        RenderProfiler.INSTANCE.beginPass("world_pass_" + pass);
    }

    /**
     * End world pass timing.
     */
    @Inject(method = "renderWorldPass", at = @At("RETURN"))
    private void gl46core$endWorldPass(int pass, float partialTicks, long finishTimeNano, CallbackInfo ci) {
        RenderProfiler.INSTANCE.endPass("world_pass_" + pass);
    }

    /**
     * End frame — fires at the return of updateCameraAndRender,
     * after all world and GUI rendering is complete.
     */
    @Inject(method = "updateCameraAndRender", at = @At("RETURN"))
    private void gl46core$endFrame(float partialTicks, long nanoTime, CallbackInfo ci) {
        // Finalize orchestrator
        FrameOrchestrator.INSTANCE.endFrame();

        // Finalize profiler (F3 debug screen reads stats directly)
        RenderProfiler.INSTANCE.endFrame();
    }
}
