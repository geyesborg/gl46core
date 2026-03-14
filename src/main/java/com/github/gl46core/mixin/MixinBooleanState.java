package com.github.gl46core.mixin;

import com.github.gl46core.gl.CoreStateTracker;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Intercepts GlStateManager.BooleanState.setEnabled() to route ALL capabilities
 * through CoreStateTracker.
 *
 * <p>Tracked core caps (depth, blend, cull, polygon offset) go through
 * CoreStateTracker's dirty-flagged methods which skip redundant GL calls.
 * Untracked core caps (scissor, stencil, multisample) go direct to GL
 * with a local cache to prevent NVIDIA driver shader recompilation.
 * Removed caps (alpha test, lighting, fog, etc.) are software-only.</p>
 */
@Mixin(targets = "net.minecraft.client.renderer.GlStateManager$BooleanState")
public abstract class MixinBooleanState {

    @Shadow private int field_179202_a; // capability

    // Local cache only for untracked core caps (scissor, stencil, multisample)
    private boolean cachedState = false;
    private boolean cacheInitialized = false;

    /**
     * @author GL46Core
     * @reason Route all caps through CoreStateTracker or filtered GL.
     */
    @Overwrite
    public void func_179200_b() { // setEnabled
        gl46core$setState(true);
    }

    /**
     * @author GL46Core
     * @reason Route all caps through CoreStateTracker or filtered GL.
     */
    @Overwrite
    public void func_179198_a() { // setDisabled
        gl46core$setState(false);
    }

    /**
     * @author GL46Core
     * @reason Route all caps through CoreStateTracker or filtered GL.
     */
    @Overwrite
    public void func_179199_a(boolean enabled) { // setState
        gl46core$setState(enabled);
    }

    private void gl46core$setState(boolean enabled) {
        CoreStateTracker st = CoreStateTracker.INSTANCE;
        switch (field_179202_a) {
            // Removed state — software-tracked only
            case 0x0DE1 -> { // GL_TEXTURE_2D
                if (enabled) st.enableTexture2D(st.getActiveTextureUnit());
                else st.disableTexture2D(st.getActiveTextureUnit());
            }
            case 0x0BC0 -> { if (enabled) st.enableAlphaTest(); else st.disableAlphaTest(); }  // GL_ALPHA_TEST
            case 0x0B50 -> { if (enabled) st.enableLighting(); else st.disableLighting(); }    // GL_LIGHTING
            case 0x0B60 -> { if (enabled) st.enableFog(); else st.disableFog(); }              // GL_FOG
            case 0x0B57 -> { if (enabled) st.enableColorMaterial(); else st.disableColorMaterial(); } // GL_COLOR_MATERIAL
            case 0x0BA1 -> { if (enabled) st.enableNormalize(); else st.disableNormalize(); }  // GL_NORMALIZE
            case 0x803A -> { if (enabled) st.enableRescaleNormal(); else st.disableRescaleNormal(); } // GL_RESCALE_NORMAL

            // Tracked core state — dirty-flagged, skips redundant GL
            case 0x0B71 -> st.enableDepthTest(enabled);     // GL_DEPTH_TEST
            case 0x0BE2 -> st.enableBlend(enabled);          // GL_BLEND
            case 0x0B44 -> st.enableCull(enabled);            // GL_CULL_FACE
            case 0x8037 -> st.enablePolygonOffset(enabled);   // GL_POLYGON_OFFSET_FILL

            // Untracked core state — direct GL with local cache
            default -> {
                if (!cacheInitialized || cachedState != enabled) {
                    if (enabled) GL11.glEnable(field_179202_a);
                    else GL11.glDisable(field_179202_a);
                    cachedState = enabled;
                    cacheInitialized = true;
                }
            }
        }
    }
}
