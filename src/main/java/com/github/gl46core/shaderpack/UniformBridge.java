package com.github.gl46core.shaderpack;

import com.github.gl46core.api.render.FrameOrchestrator;
import com.github.gl46core.api.render.FrameContext;
import com.github.gl46core.api.render.SceneData;
import com.github.gl46core.api.render.gpu.RenderTargetManager;
import com.github.gl46core.api.render.gpu.RenderTarget;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.MobEffects;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.EnumHand;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.lwjgl.opengl.GL20;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps OptiFine/Iris uniform names to gl46core data sources and uploads
 * them to shaderpack programs each frame.
 *
 * OptiFine shaderpacks expect a set of well-known uniform variables
 * (gbufferModelView, cameraPosition, sunPosition, etc.). This bridge
 * resolves their locations in compiled shaderpack programs and uploads
 * the corresponding values from our {@link SceneData} UBO, {@link FrameContext},
 * and {@link RenderTargetManager}.
 *
 * Uniform categories:
 *   - Matrix uniforms (gbufferModelView, gbufferProjection, etc.)
 *   - Camera/position uniforms (cameraPosition, eyePosition)
 *   - Lighting uniforms (sunPosition, moonPosition, sunAngle)
 *   - Time uniforms (worldTime, frameTimeCounter)
 *   - Weather uniforms (rainStrength, wetness)
 *   - Viewport uniforms (viewWidth, viewHeight)
 *   - Fog uniforms (fogColor, fogMode, fogStart, fogEnd)
 *   - Sampler uniforms (colortex0-15, depthtex0-2, shadowtex0-1, etc.)
 *   - Previous frame uniforms (gbufferPreviousModelView, gbufferPreviousProjection)
 *
 * Usage:
 * <pre>
 *   UniformBridge bridge = new UniformBridge();
 *   bridge.resolve(programId);  // find uniform locations
 *   bridge.upload(frameContext); // upload values each frame
 * </pre>
 */
public final class UniformBridge {

    /**
     * A single uniform entry: name, GL location, and upload type.
     */
    private static class Entry {
        final String name;
        final UploadType type;
        int location = -1;

        Entry(String name, UploadType type) {
            this.name = name;
            this.type = type;
        }
    }

    /**
     * How to upload the uniform value.
     */
    enum UploadType {
        // Matrices (mat4)
        GBUFFER_MODEL_VIEW,
        GBUFFER_PROJECTION,
        GBUFFER_MODEL_VIEW_INVERSE,
        GBUFFER_PROJECTION_INVERSE,
        GBUFFER_PREVIOUS_MODEL_VIEW,
        GBUFFER_PREVIOUS_PROJECTION,
        SHADOW_MODEL_VIEW,
        SHADOW_PROJECTION,
        SHADOW_MODEL_VIEW_INVERSE,
        SHADOW_PROJECTION_INVERSE,

        // Vectors
        CAMERA_POSITION,
        PREVIOUS_CAMERA_POSITION,
        SUN_POSITION,
        MOON_POSITION,
        UP_POSITION,
        FOG_COLOR,
        SKY_COLOR,

        // Scalars — float
        SUN_ANGLE,
        CELESTIAL_ANGLE,
        RAIN_STRENGTH,
        WETNESS,
        FRAME_TIME_COUNTER,
        NEAR,
        FAR,
        FOG_START,
        FOG_END,
        FOG_DENSITY,
        ASPECT_RATIO,
        EYE_ALTITUDE,
        SCREEN_BRIGHTNESS,
        NIGHT_VISION,
        BLINDNESS,
        SHADOW_DISTANCE,

        // Scalars — int
        WORLD_TIME,
        WORLD_DAY,
        MOON_PHASE,
        FOG_MODE,
        IS_EYE_IN_WATER,
        FRAME_COUNTER,
        HIDE_GUI,
        VIEW_WIDTH,
        VIEW_HEIGHT,
        HELD_ITEM_ID,
        HELD_BLOCK_LIGHT_VALUE,
        HELD_ITEM_ID2,
        HELD_BLOCK_LIGHT_VALUE2,

        // Samplers (int — texture unit)
        SAMPLER_TEXTURE,
        SAMPLER_LIGHTMAP,
        SAMPLER_NORMALS,
        SAMPLER_SPECULAR,
        SAMPLER_COLORTEX0,
        SAMPLER_COLORTEX1,
        SAMPLER_COLORTEX2,
        SAMPLER_COLORTEX3,
        SAMPLER_COLORTEX4,
        SAMPLER_COLORTEX5,
        SAMPLER_COLORTEX6,
        SAMPLER_COLORTEX7,
        SAMPLER_DEPTHTEX0,
        SAMPLER_DEPTHTEX1,
        SAMPLER_DEPTHTEX2,
        SAMPLER_SHADOWTEX0,
        SAMPLER_SHADOWTEX1,
        SAMPLER_SHADOWCOLOR0,
        SAMPLER_SHADOWCOLOR1,
        SAMPLER_NOISETEX,
    }

    // All known uniforms
    private final Entry[] entries;

    // Reusable float buffer for matrix uploads (16 floats)
    private final float[] matBuf = new float[16];

    // Scratch matrix for computing inverses
    private final Matrix4f scratchMat = new Matrix4f();

    public UniformBridge() {
        // Define all known OptiFine/Iris uniforms
        Map<String, UploadType> defs = new LinkedHashMap<>();

        // Matrices
        defs.put("gbufferModelView",            UploadType.GBUFFER_MODEL_VIEW);
        defs.put("gbufferProjection",            UploadType.GBUFFER_PROJECTION);
        defs.put("gbufferModelViewInverse",      UploadType.GBUFFER_MODEL_VIEW_INVERSE);
        defs.put("gbufferProjectionInverse",     UploadType.GBUFFER_PROJECTION_INVERSE);
        defs.put("gbufferPreviousModelView",     UploadType.GBUFFER_PREVIOUS_MODEL_VIEW);
        defs.put("gbufferPreviousProjection",    UploadType.GBUFFER_PREVIOUS_PROJECTION);
        defs.put("shadowModelView",              UploadType.SHADOW_MODEL_VIEW);
        defs.put("shadowProjection",             UploadType.SHADOW_PROJECTION);
        defs.put("shadowModelViewInverse",       UploadType.SHADOW_MODEL_VIEW_INVERSE);
        defs.put("shadowProjectionInverse",      UploadType.SHADOW_PROJECTION_INVERSE);

        // Vectors
        defs.put("cameraPosition",         UploadType.CAMERA_POSITION);
        defs.put("previousCameraPosition", UploadType.PREVIOUS_CAMERA_POSITION);
        defs.put("sunPosition",            UploadType.SUN_POSITION);
        defs.put("moonPosition",           UploadType.MOON_POSITION);
        defs.put("upPosition",             UploadType.UP_POSITION);
        defs.put("fogColor",               UploadType.FOG_COLOR);
        defs.put("skyColor",               UploadType.SKY_COLOR);

        // Float scalars
        defs.put("sunAngle",         UploadType.SUN_ANGLE);
        defs.put("celestialAngle",   UploadType.CELESTIAL_ANGLE);
        defs.put("rainStrength",     UploadType.RAIN_STRENGTH);
        defs.put("wetness",          UploadType.WETNESS);
        defs.put("frameTimeCounter", UploadType.FRAME_TIME_COUNTER);
        defs.put("near",             UploadType.NEAR);
        defs.put("far",              UploadType.FAR);
        defs.put("fogStart",         UploadType.FOG_START);
        defs.put("fogEnd",           UploadType.FOG_END);
        defs.put("fogDensity",       UploadType.FOG_DENSITY);
        defs.put("aspectRatio",      UploadType.ASPECT_RATIO);
        defs.put("eyeAltitude",      UploadType.EYE_ALTITUDE);
        defs.put("screenBrightness", UploadType.SCREEN_BRIGHTNESS);
        defs.put("nightVision",      UploadType.NIGHT_VISION);
        defs.put("blindness",        UploadType.BLINDNESS);
        defs.put("shadowDistance",   UploadType.SHADOW_DISTANCE);

        // Int scalars
        defs.put("worldTime",           UploadType.WORLD_TIME);
        defs.put("worldDay",            UploadType.WORLD_DAY);
        defs.put("moonPhase",           UploadType.MOON_PHASE);
        defs.put("fogMode",             UploadType.FOG_MODE);
        defs.put("isEyeInWater",        UploadType.IS_EYE_IN_WATER);
        defs.put("frameCounter",        UploadType.FRAME_COUNTER);
        defs.put("hideGUI",             UploadType.HIDE_GUI);
        defs.put("viewWidth",           UploadType.VIEW_WIDTH);
        defs.put("viewHeight",          UploadType.VIEW_HEIGHT);
        defs.put("heldItemId",          UploadType.HELD_ITEM_ID);
        defs.put("heldBlockLightValue", UploadType.HELD_BLOCK_LIGHT_VALUE);
        defs.put("heldItemId2",         UploadType.HELD_ITEM_ID2);
        defs.put("heldBlockLightValue2",UploadType.HELD_BLOCK_LIGHT_VALUE2);

        // Samplers
        defs.put("texture",      UploadType.SAMPLER_TEXTURE);
        defs.put("lightmap",     UploadType.SAMPLER_LIGHTMAP);
        defs.put("normals",      UploadType.SAMPLER_NORMALS);
        defs.put("specular",     UploadType.SAMPLER_SPECULAR);
        defs.put("colortex0",    UploadType.SAMPLER_COLORTEX0);
        defs.put("colortex1",    UploadType.SAMPLER_COLORTEX1);
        defs.put("colortex2",    UploadType.SAMPLER_COLORTEX2);
        defs.put("colortex3",    UploadType.SAMPLER_COLORTEX3);
        defs.put("colortex4",    UploadType.SAMPLER_COLORTEX4);
        defs.put("colortex5",    UploadType.SAMPLER_COLORTEX5);
        defs.put("colortex6",    UploadType.SAMPLER_COLORTEX6);
        defs.put("colortex7",    UploadType.SAMPLER_COLORTEX7);
        defs.put("depthtex0",    UploadType.SAMPLER_DEPTHTEX0);
        defs.put("depthtex1",    UploadType.SAMPLER_DEPTHTEX1);
        defs.put("depthtex2",    UploadType.SAMPLER_DEPTHTEX2);
        defs.put("shadowtex0",   UploadType.SAMPLER_SHADOWTEX0);
        defs.put("shadowtex1",   UploadType.SAMPLER_SHADOWTEX1);
        defs.put("shadowcolor0", UploadType.SAMPLER_SHADOWCOLOR0);
        defs.put("shadowcolor1", UploadType.SAMPLER_SHADOWCOLOR1);
        defs.put("noisetex",     UploadType.SAMPLER_NOISETEX);

        // Legacy sampler aliases
        defs.put("gcolor",    UploadType.SAMPLER_COLORTEX0);
        defs.put("gdepth",    UploadType.SAMPLER_COLORTEX1);
        defs.put("gnormal",   UploadType.SAMPLER_COLORTEX2);
        defs.put("composite", UploadType.SAMPLER_COLORTEX3);
        defs.put("gaux1",     UploadType.SAMPLER_COLORTEX4);
        defs.put("gaux2",     UploadType.SAMPLER_COLORTEX5);
        defs.put("gaux3",     UploadType.SAMPLER_COLORTEX6);
        defs.put("gaux4",     UploadType.SAMPLER_COLORTEX7);
        defs.put("shadow",    UploadType.SAMPLER_SHADOWTEX0);

        entries = new Entry[defs.size()];
        int i = 0;
        for (Map.Entry<String, UploadType> e : defs.entrySet()) {
            entries[i++] = new Entry(e.getKey(), e.getValue());
        }
    }

    /**
     * Resolve uniform locations for a compiled program.
     * Call once after linking.
     *
     * @param programId GL program ID
     * @return number of active uniforms found
     */
    public int resolve(int programId) {
        int found = 0;
        for (Entry e : entries) {
            e.location = GL20.glGetUniformLocation(programId, e.name);
            if (e.location >= 0) found++;
        }
        return found;
    }

    /**
     * Upload all resolved uniform values for the current frame.
     * Call after glUseProgram() and before draw calls.
     */
    public void upload() {
        FrameContext ctx = FrameOrchestrator.INSTANCE.getFrameContext();
        SceneData scene = FrameOrchestrator.INSTANCE.getSceneData();
        RenderTargetManager rtm = RenderTargetManager.INSTANCE;

        for (Entry e : entries) {
            if (e.location < 0) continue;
            uploadEntry(e, ctx, scene, rtm);
        }
    }

    /**
     * Get the number of active uniforms (location >= 0).
     * Only valid after {@link #resolve(int)}.
     */
    public int getActiveCount() {
        int count = 0;
        for (Entry e : entries) {
            if (e.location >= 0) count++;
        }
        return count;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Per-entry upload dispatch
    // ═══════════════════════════════════════════════════════════════════

    private void uploadEntry(Entry e, FrameContext ctx, SceneData scene, RenderTargetManager rtm) {
        java.nio.ByteBuffer buf = scene.getBuffer();

        switch (e.type) {
            // ── Matrices ──
            case GBUFFER_MODEL_VIEW:
                readMat4(buf, 0); // viewMatrix at offset 0
                GL20.glUniformMatrix4fv(e.location, false, matBuf);
                break;
            case GBUFFER_PROJECTION:
                readMat4(buf, 64); // projectionMatrix at offset 64
                GL20.glUniformMatrix4fv(e.location, false, matBuf);
                break;
            case GBUFFER_PREVIOUS_MODEL_VIEW:
                readMat4(buf, 432); // prevViewMatrix at offset 432
                GL20.glUniformMatrix4fv(e.location, false, matBuf);
                break;
            case GBUFFER_PREVIOUS_PROJECTION:
                readMat4(buf, 496); // prevProjection at offset 496
                GL20.glUniformMatrix4fv(e.location, false, matBuf);
                break;
            case GBUFFER_MODEL_VIEW_INVERSE:
                readMat4(buf, 0); // viewMatrix at offset 0
                scratchMat.set(
                    matBuf[0], matBuf[1], matBuf[2], matBuf[3],
                    matBuf[4], matBuf[5], matBuf[6], matBuf[7],
                    matBuf[8], matBuf[9], matBuf[10], matBuf[11],
                    matBuf[12], matBuf[13], matBuf[14], matBuf[15]
                ).invert();
                scratchMat.get(matBuf);
                GL20.glUniformMatrix4fv(e.location, false, matBuf);
                break;
            case GBUFFER_PROJECTION_INVERSE:
                readMat4(buf, 64); // projectionMatrix at offset 64
                scratchMat.set(
                    matBuf[0], matBuf[1], matBuf[2], matBuf[3],
                    matBuf[4], matBuf[5], matBuf[6], matBuf[7],
                    matBuf[8], matBuf[9], matBuf[10], matBuf[11],
                    matBuf[12], matBuf[13], matBuf[14], matBuf[15]
                ).invert();
                scratchMat.get(matBuf);
                GL20.glUniformMatrix4fv(e.location, false, matBuf);
                break;
            case SHADOW_MODEL_VIEW:
                ctx.getShadow().getShadowViewMatrix().get(matBuf);
                GL20.glUniformMatrix4fv(e.location, false, matBuf);
                break;
            case SHADOW_PROJECTION:
                ctx.getShadow().getShadowProjectionMatrix().get(matBuf);
                GL20.glUniformMatrix4fv(e.location, false, matBuf);
                break;
            case SHADOW_MODEL_VIEW_INVERSE:
                ctx.getShadow().getShadowViewInverse().get(matBuf);
                GL20.glUniformMatrix4fv(e.location, false, matBuf);
                break;
            case SHADOW_PROJECTION_INVERSE:
                ctx.getShadow().getShadowProjectionInverse().get(matBuf);
                GL20.glUniformMatrix4fv(e.location, false, matBuf);
                break;

            // ── Vectors ──
            case CAMERA_POSITION:
                GL20.glUniform3f(e.location,
                        buf.getFloat(192), buf.getFloat(196), buf.getFloat(200));
                break;
            case PREVIOUS_CAMERA_POSITION: {
                Vector3d prev = ctx.getCamera().getPrevPosition();
                GL20.glUniform3f(e.location, (float) prev.x, (float) prev.y, (float) prev.z);
                break;
            }
            case SUN_POSITION:
                GL20.glUniform3f(e.location,
                        buf.getFloat(208), buf.getFloat(212), buf.getFloat(216));
                break;
            case MOON_POSITION:
                GL20.glUniform3f(e.location,
                        buf.getFloat(224), buf.getFloat(228), buf.getFloat(232));
                break;
            case UP_POSITION:
                GL20.glUniform3f(e.location, 0.0f, 1.0f, 0.0f);
                break;
            case FOG_COLOR: {
                com.github.gl46core.api.render.FogState fog = ctx.getFog();
                GL20.glUniform3f(e.location, fog.getR(), fog.getG(), fog.getB());
                break;
            }
            case SKY_COLOR: {
                com.github.gl46core.api.render.DimensionState dim = ctx.getDimension();
                GL20.glUniform3f(e.location, dim.getSkyColorR(), dim.getSkyColorG(), dim.getSkyColorB());
                break;
            }

            // ── Float scalars ──
            case SUN_ANGLE:
                GL20.glUniform1f(e.location, buf.getFloat(220)); // sunDirection.w
                break;
            case CELESTIAL_ANGLE:
                GL20.glUniform1f(e.location, buf.getFloat(384)); // celestialAngle at offset 384
                break;
            case RAIN_STRENGTH:
                GL20.glUniform1f(e.location, buf.getFloat(360)); // rainStrength at offset 360
                break;
            case WETNESS:
                GL20.glUniform1f(e.location, buf.getFloat(360)); // same as rain for now (offset 360)
                break;
            case FRAME_TIME_COUNTER:
                GL20.glUniform1f(e.location, buf.getFloat(352)); // worldTime at offset 352
                break;
            case NEAR:
                GL20.glUniform1f(e.location, buf.getFloat(376)); // nearPlane at offset 376
                break;
            case FAR:
                GL20.glUniform1f(e.location, buf.getFloat(380)); // farPlane at offset 380
                break;
            case FOG_START: {
                com.github.gl46core.api.render.FogState fog = ctx.getFog();
                GL20.glUniform1f(e.location, fog.getStart());
                break;
            }
            case FOG_END: {
                com.github.gl46core.api.render.FogState fog = ctx.getFog();
                GL20.glUniform1f(e.location, fog.getEnd());
                break;
            }
            case FOG_DENSITY: {
                com.github.gl46core.api.render.FogState fog = ctx.getFog();
                GL20.glUniform1f(e.location, fog.getDensity());
                break;
            }
            case ASPECT_RATIO:
                if (rtm.isInitialized() && rtm.getScreenHeight() > 0) {
                    GL20.glUniform1f(e.location, (float) rtm.getScreenWidth() / rtm.getScreenHeight());
                } else {
                    GL20.glUniform1f(e.location, 1.0f);
                }
                break;
            case EYE_ALTITUDE:
                GL20.glUniform1f(e.location, buf.getFloat(196)); // cameraPosition.y at offset 196
                break;
            case SCREEN_BRIGHTNESS:
                GL20.glUniform1f(e.location, Minecraft.getMinecraft().gameSettings.gammaSetting);
                break;
            case NIGHT_VISION:
                GL20.glUniform1f(e.location, getNightVisionStrength());
                break;
            case BLINDNESS:
                GL20.glUniform1f(e.location, getBlindnessStrength());
                break;
            case SHADOW_DISTANCE:
                GL20.glUniform1f(e.location, ctx.getShadow().getShadowDistance());
                break;

            // ── Int scalars ──
            case WORLD_TIME:
                GL20.glUniform1i(e.location, (int)(buf.getFloat(352) * 24000) % 24000);
                break;
            case WORLD_DAY:
                GL20.glUniform1i(e.location, (int)(buf.getFloat(352) * 24000) / 24000);
                break;
            case MOON_PHASE:
                GL20.glUniform1i(e.location, getMoonPhase());
                break;
            case FOG_MODE: {
                com.github.gl46core.api.render.FogState fog = ctx.getFog();
                GL20.glUniform1i(e.location, fog.getMode());
                break;
            }
            case IS_EYE_IN_WATER:
                GL20.glUniform1i(e.location, getEyeInWaterState());
                break;
            case FRAME_COUNTER:
                GL20.glUniform1i(e.location, buf.getInt(392)); // frameIndex at offset 392
                break;
            case HIDE_GUI:
                GL20.glUniform1i(e.location, 0);
                break;
            case VIEW_WIDTH:
                GL20.glUniform1i(e.location, rtm.isInitialized() ? rtm.getScreenWidth() : 0);
                break;
            case VIEW_HEIGHT:
                GL20.glUniform1i(e.location, rtm.isInitialized() ? rtm.getScreenHeight() : 0);
                break;
            case HELD_ITEM_ID:
                GL20.glUniform1i(e.location, getHeldItemId());
                break;
            case HELD_BLOCK_LIGHT_VALUE:
                GL20.glUniform1i(e.location, getHeldBlockLightValue());
                break;
            case HELD_ITEM_ID2:
                GL20.glUniform1i(e.location, getHeldItemId(EnumHand.OFF_HAND));
                break;
            case HELD_BLOCK_LIGHT_VALUE2:
                GL20.glUniform1i(e.location, getHeldBlockLightValue(EnumHand.OFF_HAND));
                break;

            // ── Samplers ──
            case SAMPLER_TEXTURE:      GL20.glUniform1i(e.location, 0);  break;
            case SAMPLER_LIGHTMAP:     GL20.glUniform1i(e.location, 1);  break;
            case SAMPLER_NORMALS:      GL20.glUniform1i(e.location, 2);  break;
            case SAMPLER_SPECULAR:     GL20.glUniform1i(e.location, 3);  break;
            case SAMPLER_COLORTEX0:    GL20.glUniform1i(e.location, 4);  break;
            case SAMPLER_COLORTEX1:    GL20.glUniform1i(e.location, 5);  break;
            case SAMPLER_COLORTEX2:    GL20.glUniform1i(e.location, 6);  break;
            case SAMPLER_COLORTEX3:    GL20.glUniform1i(e.location, 7);  break;
            case SAMPLER_COLORTEX4:    GL20.glUniform1i(e.location, 8);  break;
            case SAMPLER_COLORTEX5:    GL20.glUniform1i(e.location, 9);  break;
            case SAMPLER_COLORTEX6:    GL20.glUniform1i(e.location, 10); break;
            case SAMPLER_COLORTEX7:    GL20.glUniform1i(e.location, 11); break;
            case SAMPLER_DEPTHTEX0:    GL20.glUniform1i(e.location, 12); break;
            case SAMPLER_DEPTHTEX1:    GL20.glUniform1i(e.location, 13); break;
            case SAMPLER_DEPTHTEX2:    GL20.glUniform1i(e.location, 14); break;
            case SAMPLER_SHADOWTEX0:   GL20.glUniform1i(e.location, 15); break;
            case SAMPLER_SHADOWTEX1:   GL20.glUniform1i(e.location, 16); break;
            case SAMPLER_SHADOWCOLOR0: GL20.glUniform1i(e.location, 17); break;
            case SAMPLER_SHADOWCOLOR1: GL20.glUniform1i(e.location, 18); break;
            case SAMPLER_NOISETEX:     GL20.glUniform1i(e.location, 19); break;
        }
    }

    /**
     * Read a mat4 from the SceneData buffer at the given byte offset into matBuf.
     */
    private void readMat4(java.nio.ByteBuffer buf, int offset) {
        for (int i = 0; i < 16; i++) {
            matBuf[i] = buf.getFloat(offset + i * 4);
        }
    }

    /**
     * Detect if the camera is inside water or lava.
     * OptiFine convention: 0 = air, 1 = water, 2 = lava.
     */
    private static int getEyeInWaterState() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null) return 0;
        if (mc.player.isInsideOfMaterial(Material.WATER)) return 1;
        if (mc.player.isInsideOfMaterial(Material.LAVA))  return 2;
        return 0;
    }

    /**
     * Get the numeric item ID of the item held in the main hand.
     * Returns -1 if empty (OptiFine convention).
     */
    private static int getHeldItemId() {
        return getHeldItemId(EnumHand.MAIN_HAND);
    }

    /**
     * Get the numeric item ID of the item held in the given hand.
     * Returns -1 if empty (OptiFine convention).
     */
    private static int getHeldItemId(EnumHand hand) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null) return -1;
        ItemStack stack = mc.player.getHeldItem(hand);
        if (stack.isEmpty()) return -1;
        return Item.getIdFromItem(stack.getItem());
    }

    /**
     * Get the block light value of the item held in the main hand.
     * Returns 0 if the held item is not a block or is empty.
     */
    private static int getHeldBlockLightValue() {
        return getHeldBlockLightValue(EnumHand.MAIN_HAND);
    }

    /**
     * Get the block light value of the item held in the given hand.
     * Returns 0 if the held item is not a block or is empty.
     */
    private static int getHeldBlockLightValue(EnumHand hand) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null) return 0;
        ItemStack stack = mc.player.getHeldItem(hand);
        if (stack.isEmpty()) return 0;
        Item item = stack.getItem();
        if (item instanceof ItemBlock) {
            return ((ItemBlock) item).getBlock().getDefaultState().getLightValue();
        }
        return 0;
    }

    /**
     * Get the night vision potion strength (0.0 = none, 1.0 = full).
     * OptiFine uses the amplifier + duration fraction.
     */
    private static float getNightVisionStrength() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null) return 0.0f;
        PotionEffect effect = mc.player.getActivePotionEffect(MobEffects.NIGHT_VISION);
        if (effect == null) return 0.0f;
        // OptiFine convention: 1.0 when active, scaled by remaining duration in last 10 seconds
        int remaining = effect.getDuration();
        if (remaining > 200) return 1.0f; // more than 10 seconds left
        return remaining / 200.0f; // fade out in last 10 seconds
    }

    /**
     * Get the blindness potion strength (0.0 = none, 1.0 = full).
     */
    private static float getBlindnessStrength() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null) return 0.0f;
        PotionEffect effect = mc.player.getActivePotionEffect(MobEffects.BLINDNESS);
        if (effect == null) return 0.0f;
        // Ramp up over first 20 ticks
        int duration = effect.getDuration();
        if (duration > 20) return 1.0f;
        return duration / 20.0f;
    }

    /**
     * Get the current moon phase (0-7).
     */
    private static int getMoonPhase() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null) return 0;
        return mc.world.getMoonPhase();
    }
}
