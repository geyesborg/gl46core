package com.github.gl46core.client;

import com.github.gl46core.GL46Core;
import net.minecraftforge.fml.client.IModGuiFactory;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Scans loaded mods after initialization and builds generic config GUI factories
 * for mods that have .cfg config files but didn't register their own IModGuiFactory.
 *
 * Factories are stored in our own map and served via {@link #getFactory(ModContainer)},
 * which is called from the Mixin on FMLClientHandler.getGuiFactoryFor.
 *
 * Only creates factories for mods that:
 * 1. Have a matching .cfg file in the config directory
 * 2. Don't already have a GUI factory (checked at query time by the Mixin)
 */
public final class ConfigGuiInjector {

    private ConfigGuiInjector() {}

    private static final Map<String, IModGuiFactory> factories = new HashMap<>();

    /**
     * Called from the Mixin on FMLClientHandler.getGuiFactoryFor when the
     * original method returns null.
     */
    public static IModGuiFactory getFactory(ModContainer mod) {
        return factories.get(mod.getModId());
    }

    /**
     * Scan all loaded mods and build factories for those with config files.
     * Called during FMLLoadCompleteEvent.
     */
    public static void inject() {
        File configDir = new File("config");
        if (!configDir.isDirectory()) return;

        int injected = 0;
        for (ModContainer mod : Loader.instance().getModList()) {
            String modId = mod.getModId();

            // Skip Minecraft itself and Forge internals
            if ("minecraft".equals(modId) || "mcp".equals(modId) ||
                "FML".equals(modId) || "forge".equals(modId)) continue;

            // Look for a config file matching this mod
            File cfgFile = findConfigFile(configDir, modId);
            if (cfgFile == null) continue;

            try {
                String modName = mod.getName();
                ConfigGuiFactory factory = new ConfigGuiFactory(modId, modName, cfgFile);
                factories.put(modId, factory);
                injected++;
                GL46Core.LOGGER.debug("ConfigGuiInjector: Registered config GUI for {} ({})", modName, cfgFile.getName());
            } catch (Exception e) {
                GL46Core.LOGGER.debug("ConfigGuiInjector: Failed for {}: {}", modId, e.getMessage());
            }
        }

        if (injected > 0) {
            GL46Core.LOGGER.info("ConfigGuiInjector: Registered config GUIs for {} mod(s)", injected);
        }
    }

    /**
     * Find a .cfg file for the given mod ID. Checks common naming patterns:
     * - modid.cfg (direct match)
     * - modid/modid.cfg (subdirectory)
     * - Fuzzy: any .cfg file whose name (stripped of underscores/hyphens) matches the mod ID
     *   e.g. "modern_splash.cfg" matches mod ID "modernsplash"
     */
    private static File findConfigFile(File configDir, String modId) {
        // Direct match: config/<modid>.cfg
        File direct = new File(configDir, modId + ".cfg");
        if (direct.isFile()) return direct;

        // Subdirectory: config/<modid>/<modid>.cfg
        File subDir = new File(configDir, modId);
        if (subDir.isDirectory()) {
            File sub = new File(subDir, modId + ".cfg");
            if (sub.isFile()) return sub;
        }

        // Fuzzy match: scan .cfg files and match by stripping underscores/hyphens
        // e.g. "modern_splash.cfg" -> "modernsplash" == modId "modernsplash"
        String normalizedId = modId.toLowerCase().replaceAll("[_\\-]", "");
        File[] cfgFiles = configDir.listFiles((dir, name) -> name.endsWith(".cfg"));
        if (cfgFiles != null) {
            for (File f : cfgFiles) {
                String baseName = f.getName().replace(".cfg", "");
                String normalizedName = baseName.toLowerCase().replaceAll("[_\\-]", "");
                if (normalizedName.equals(normalizedId)) {
                    return f;
                }
            }
        }

        return null;
    }
}
