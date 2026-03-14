package com.github.gl46core.core;

import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.fml.common.FMLLog;

import java.io.File;

/**
 * Runtime configuration loaded from config/gl46core.cfg.
 * Uses Forge's Configuration format for compatibility with the config GUI.
 */
public final class GL46CoreConfig {

    private GL46CoreConfig() {}

    private static final String CAT = Configuration.CATEGORY_GENERAL;

    private static boolean warnDeprecatedGL = true;
    private static boolean pauseOnDeprecatedGL = true;
    private static String diagnosticsLevel = "auto";

    static {
        load();
    }

    public static boolean warnDeprecatedGL()  { return warnDeprecatedGL; }
    public static boolean pauseOnDeprecatedGL() { return pauseOnDeprecatedGL; }
    /** "auto" = adaptive warm-up, "off" = no per-call checks, "full" = always check */
    public static String diagnosticsLevel() { return diagnosticsLevel; }

    private static void load() {
        try {
            File configFile = new File("config", "gl46core.cfg");
            Configuration config = new Configuration(configFile);
            config.load();

            warnDeprecatedGL = config.getBoolean("warnDeprecatedGL", CAT, true,
                    "Log warnings when mods use deprecated GL functions");
            pauseOnDeprecatedGL = config.getBoolean("pauseOnDeprecatedGL", CAT, true,
                    "Pause loading and show a warning screen when deprecated GL usage is detected");
            diagnosticsLevel = config.getString("diagnosticsLevel", CAT, "auto",
                    "GL error diagnostics: auto (adaptive warm-up), off (disabled), full (always check)",
                    new String[]{"auto", "off", "full"});

            diagnosticsLevel = diagnosticsLevel.toLowerCase().trim();
            if (!diagnosticsLevel.equals("auto") && !diagnosticsLevel.equals("off") && !diagnosticsLevel.equals("full")) {
                FMLLog.log.warn("[GL46Core] Invalid diagnosticsLevel '{}' — defaulting to 'auto'", diagnosticsLevel);
                diagnosticsLevel = "auto";
            }

            if (config.hasChanged()) {
                config.save();
            }
        } catch (Exception e) {
            FMLLog.log.warn("[GL46Core] Could not load config: {}", e.getMessage());
        }
    }
}
