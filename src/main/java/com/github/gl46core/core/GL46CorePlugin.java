package com.github.gl46core.core;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;

import java.util.Map;

/**
 * Coremod plugin that registers ASM transformers for legacy GL call redirection.
 * Mixins are registered via MixinConfigs manifest key (production) and
 * crl.dev.mixin system property (dev environment).
 */
@IFMLLoadingPlugin.MCVersion("1.12.2")
@IFMLLoadingPlugin.Name("GL46 Core")
@IFMLLoadingPlugin.SortingIndex(1001)
public class GL46CorePlugin implements IFMLLoadingPlugin {

    @Override
    public String[] getASMTransformerClass() {
        return new String[]{ "com.github.gl46core.core.LegacyGLTransformer" };
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data) {
        // Force core profile GL context — lwjglxx reads this before Display.create()
        try {
            Class<?> cfg = Class.forName("net.minecraftforge.common.ForgeEarlyConfig");
            java.lang.reflect.Field f = cfg.getDeclaredField("OPENGL_COMPAT_PROFILE");
            f.setAccessible(true);
            f.setBoolean(null, false);
        } catch (Exception e) {
            // Not running on Cleanroom — ignore
        }
    }

    @Override
    public String getAccessTransformerClass() {
        return null;
    }
}
