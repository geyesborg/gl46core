package com.github.gl46core.mixin;

import com.github.gl46core.api.debug.RenderProfiler;
import com.github.gl46core.api.render.DynamicLightCollector;
import com.github.gl46core.api.render.FrameOrchestrator;
import com.github.gl46core.api.render.GlobalLightState;
import com.github.gl46core.api.render.PassType;
import com.github.gl46core.api.translate.LegacyDrawTranslator;
import com.github.gl46core.api.translate.LegacyStateInterpreter;
import com.github.gl46core.gl.CoreMatrixStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import org.joml.Vector3f;
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

        // Reset terrain queue stats
        com.github.gl46core.gl.TerrainDrawCollector.INSTANCE.resetFrameStats();

        // Tick model geometry cache (eviction + stats)
        com.github.gl46core.gl.ModelGeometryCache.INSTANCE.tick();

        // Reset dynamic light collector for this frame
        DynamicLightCollector.INSTANCE.beginFrame();
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

        // Capture extended lighting (sun/moon direction, color, environment)
        gl46core$captureExtendedLighting(orch.getFrameContext().getGlobalLight(),
            celestialAngle, sunBrightness, hasSky, dimId, rain, thunder);

        // Collect dynamic lights from registered providers
        DynamicLightCollector.INSTANCE.collect(orch.getFrameContext());
        DynamicLightCollector.INSTANCE.flush();

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
     * Track hand rendering pass.
     */
    @Inject(method = "renderHand", at = @At("HEAD"))
    private void gl46core$onRenderHand(float partialTicks, int pass, CallbackInfo ci) {
        FrameOrchestrator.INSTANCE.setActivePass(PassType.HAND);
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

    // Reusable scratch vectors for extended lighting capture
    private static final Vector3f scratchSunDir  = new Vector3f();
    private static final Vector3f scratchMoonDir = new Vector3f();
    private static final Vector3f scratchSunCol  = new Vector3f();
    private static final Vector3f scratchMoonCol = new Vector3f();

    /**
     * Derive extended lighting state from MC world properties.
     *
     * MC 1.12.2 celestialAngle: 0.0=noon, 0.25=sunset, 0.5=midnight, 0.75=sunrise
     * Sun direction rotates in the XZ plane with Y component from angle.
     */
    private static void gl46core$captureExtendedLighting(
            GlobalLightState light, float celestialAngle, float sunBrightness,
            boolean hasSky, int dimId, float rain, float thunder) {

        // Sun/moon direction from celestial angle
        // MC renders sun rotating around X axis: angle 0 = noon (sun at top)
        float angleRad = celestialAngle * (float)(Math.PI * 2.0);
        float sunY =  (float) Math.cos(angleRad);
        float sunZ = -(float) Math.sin(angleRad);
        scratchSunDir.set(0, sunY, sunZ).normalize();
        scratchMoonDir.set(0, -sunY, -sunZ).normalize();

        // Skylight strength from sun brightness (0 at night, 1 at day)
        float skylightStrength = hasSky ? sunBrightness : 0.0f;

        light.setSunMoon(celestialAngle, scratchSunDir, scratchMoonDir, skylightStrength);

        // Sun color: warm white at noon, orange at sunrise/sunset, dim at night
        // Night detection: celestialAngle > 0.23 && < 0.77 roughly
        boolean isNight = celestialAngle > 0.27f && celestialAngle < 0.73f;
        float dayFactor = Math.max(0, sunBrightness);
        scratchSunCol.set(
            1.0f * dayFactor,
            0.95f * dayFactor,
            0.9f * dayFactor
        );
        // Moon color: cool blue-white, dimmer
        float moonFactor = isNight ? (1.0f - dayFactor) * 0.3f : 0.0f;
        scratchMoonCol.set(
            0.6f * moonFactor,
            0.7f * moonFactor,
            1.0f * moonFactor
        );

        // Weather darkening: rain reduces light, thunder more so
        float weatherDarken = rain * 0.3f + thunder * 0.2f;

        // Block light scale: 1.0 normally, could be modified by dimension
        float blockLightScale = 1.0f;

        // Lighting flags
        int flags = 0;
        if (hasSky)    flags |= GlobalLightState.FLAG_HAS_SKY;
        if (dimId == -1) flags |= GlobalLightState.FLAG_NETHER;
        if (dimId == 1)  flags |= GlobalLightState.FLAG_END;
        if (isNight)     flags |= GlobalLightState.FLAG_NIGHT;
        if (rain > 0)    flags |= GlobalLightState.FLAG_RAINING;
        if (thunder > 0) flags |= GlobalLightState.FLAG_THUNDERING;

        light.setEnvironment(scratchSunCol, scratchMoonCol,
            blockLightScale, weatherDarken, flags);
    }
}
