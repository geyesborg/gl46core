package com.github.gl46core.core;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Mixin config plugin that conditionally disables splash-related mixins
 * when Modern Splash (modernsplash) is present.
 *
 * Modern Splash replaces the splash screen with its own implementation
 * that uses legacy GL calls. gl46core's LegacyGLTransformer (ASM) will
 * redirect those legacy calls to our core-profile emulation layer, so
 * Modern Splash's rendering works correctly in core profile — we just
 * need to get out of its way and not overwrite the same methods it hooks.
 */
public class GL46CoreMixinPlugin implements IMixinConfigPlugin {

    private static final Logger LOGGER = LogManager.getLogger("GL46Core");
    private static final boolean MODERN_SPLASH_PRESENT;

    static {
        boolean found = false;
        try {
            // Check for Modern Splash's main class on the classpath
            Class.forName("gkappa.modernsplash.CustomSplash", false,
                    GL46CoreMixinPlugin.class.getClassLoader());
            found = true;
        } catch (ClassNotFoundException ignored) {}
        MODERN_SPLASH_PRESENT = found;
        if (found) {
            LOGGER.info("Modern Splash detected — deferring splash screen to it");
        }
    }

    // Mixins to skip when Modern Splash is handling the splash screen
    private static final Set<String> SPLASH_MIXINS = Set.of(
            "com.github.gl46core.mixin.MixinSplashProgress",
            "com.github.gl46core.mixin.MixinFMLClientHandler"
    );

    @Override
    public void onLoad(String mixinPackage) {}

    @Override
    public String getRefMapperConfig() {
        return "";
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (MODERN_SPLASH_PRESENT && SPLASH_MIXINS.contains(mixinClassName)) {
            LOGGER.info("Skipping {} — Modern Splash will handle this",
                    mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1));
            return false;
        }
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() {
        return List.of();
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
