package com.github.gl46core.mixin;

import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Intercepts GlStateManager.BooleanState.setEnabled() to filter out
 * legacy GL capabilities that are removed in core profile.
 *
 * Only a small set of capabilities still exist in core profile:
 * GL_DEPTH_TEST, GL_BLEND, GL_CULL_FACE, GL_POLYGON_OFFSET_FILL,
 * GL_SCISSOR_TEST, GL_STENCIL_TEST, GL_MULTISAMPLE, GL_SAMPLE_ALPHA_TO_COVERAGE.
 *
 * Everything else (GL_ALPHA_TEST, GL_LIGHTING, GL_FOG, GL_COLOR_MATERIAL,
 * GL_NORMALIZE, GL_RESCALE_NORMAL, GL_TEXTURE_2D, GL_TEXTURE_GEN_*) is
 * tracked in CoreStateTracker and never sent to GL.
 */
@Mixin(targets = "net.minecraft.client.renderer.GlStateManager$BooleanState", remap = false)
public abstract class MixinBooleanState {

    @Shadow private int capability;

    // Software cache of current GL state — prevents redundant glEnable/glDisable
    // which trigger NVIDIA driver shader recompilation
    private boolean cachedState = false;
    private boolean cacheInitialized = false;

    private boolean isCoreCapability() {
        switch (capability) {
            case GL11.GL_DEPTH_TEST:   // 0x0B71
            case GL11.GL_BLEND:        // 0x0BE2
            case GL11.GL_CULL_FACE:    // 0x0B44
            case 0x8037:               // GL_POLYGON_OFFSET_FILL
            case 0x0C11:               // GL_SCISSOR_TEST
            case 0x0B90:               // GL_STENCIL_TEST
            case 0x809D:               // GL_MULTISAMPLE
            case 0x809E:               // GL_SAMPLE_ALPHA_TO_COVERAGE
                return true;
            default:
                return false;
        }
    }

    /**
     * @author GL46Core
     * @reason Filter legacy caps + cache state to prevent NVIDIA shader recompilation.
     */
    @Overwrite
    public void setEnabled() {
        if (isCoreCapability()) {
            if (!cacheInitialized || !cachedState) {
                GL11.glEnable(capability);
                cachedState = true;
                cacheInitialized = true;
            }
        }
    }

    /**
     * @author GL46Core
     * @reason Filter legacy caps + cache state to prevent NVIDIA shader recompilation.
     */
    @Overwrite
    public void setDisabled() {
        if (isCoreCapability()) {
            if (!cacheInitialized || cachedState) {
                GL11.glDisable(capability);
                cachedState = false;
                cacheInitialized = true;
            }
        }
    }

    /**
     * @author GL46Core
     * @reason Filter legacy caps + cache state to prevent NVIDIA shader recompilation.
     */
    @Overwrite
    public void setState(boolean enabled) {
        if (isCoreCapability()) {
            if (!cacheInitialized || cachedState != enabled) {
                if (enabled) GL11.glEnable(capability);
                else GL11.glDisable(capability);
                cachedState = enabled;
                cacheInitialized = true;
            }
        }
    }
}
