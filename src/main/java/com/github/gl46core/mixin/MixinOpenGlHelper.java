package com.github.gl46core.mixin;

import com.github.gl46core.gl.CoreStateTracker;
import net.minecraft.client.renderer.OpenGlHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Replaces legacy multi-texturing calls in OpenGlHelper that are removed in core profile:
 * - glClientActiveTexture → no-op (legacy vertex arrays don't exist)
 * - glMultiTexCoord2f → track lightmap coords in CoreStateTracker
 * - glBindBuffer → track VBO binding state in CoreVboDrawHandler
 */
@Mixin(OpenGlHelper.class)
public abstract class MixinOpenGlHelper {

    @Shadow public static int lightmapTexUnit;
    @Shadow public static float lastBrightnessX;
    @Shadow public static float lastBrightnessY;

    /**
     * @author GL46Core
     * @reason Track VBO binding state to avoid glGetInteger sync points
     */
    @Overwrite
    public static void glBindBuffer(int target, int buffer) {
        org.lwjgl.opengl.GL15.glBindBuffer(target, buffer);
        if (target == 0x8892) { // GL_ARRAY_BUFFER
            com.github.gl46core.gl.CoreVboDrawHandler.setVboBound(buffer != 0);
        }
    }

    /**
     * @author GL46Core
     * @reason glClientActiveTexture removed in core profile — no-op
     *         (legacy client-side vertex arrays don't exist in core)
     */
    @Overwrite
    public static void setClientActiveTexture(int texture) {
        // No-op: glClientActiveTexture is removed in core profile.
        // Legacy vertex arrays (glTexCoordPointer etc.) are already no-ops.
    }

    /**
     * @author GL46Core
     * @reason glMultiTexCoord2f removed in core profile — track lightmap
     *         coords in CoreStateTracker for shader uniform upload
     */
    @Overwrite
    public static void setLightmapTextureCoords(int target, float s, float t) {
        // Track lightmap brightness for shader uniforms
        if (target == lightmapTexUnit) {
            lastBrightnessX = s;
            lastBrightnessY = t;
        }
        CoreStateTracker.INSTANCE.setLightmapCoords(s, t);
    }
}
