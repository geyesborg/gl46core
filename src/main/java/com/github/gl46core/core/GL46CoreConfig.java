package com.github.gl46core.core;

import net.minecraftforge.fml.common.FMLLog;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Runtime configuration loaded from config/gl46core.cfg.
 * Loaded via static init so it's available during ASM transformation and splash.
 */
public final class GL46CoreConfig {

    private GL46CoreConfig() {}

    private static boolean warnDeprecatedGL = true;
    private static boolean pauseOnDeprecatedGL = true;

    static {
        load();
    }

    public static boolean warnDeprecatedGL()  { return warnDeprecatedGL; }
    public static boolean pauseOnDeprecatedGL() { return pauseOnDeprecatedGL; }

    private static void load() {
        File configFile = new File("config", "gl46core.cfg");

        Properties props = new Properties();
        if (configFile.exists()) {
            try (Reader r = new InputStreamReader(new FileInputStream(configFile), StandardCharsets.UTF_8)) {
                props.load(r);
            } catch (IOException e) {
                FMLLog.log.warn("[GL46Core] Could not load config: {}", e.getMessage());
            }
        }

        warnDeprecatedGL  = Boolean.parseBoolean(props.getProperty("warnDeprecatedGL", "true"));
        pauseOnDeprecatedGL = Boolean.parseBoolean(props.getProperty("pauseOnDeprecatedGL", "true"));

        props.setProperty("warnDeprecatedGL", String.valueOf(warnDeprecatedGL));
        props.setProperty("pauseOnDeprecatedGL", String.valueOf(pauseOnDeprecatedGL));

        configFile.getParentFile().mkdirs();
        try (Writer w = new OutputStreamWriter(new FileOutputStream(configFile), StandardCharsets.UTF_8)) {
            props.store(w,
                    "GL46 Core config\n" +
                    "warnDeprecatedGL  - Log warnings when mods use deprecated GL functions (true/false)\n" +
                    "pauseOnDeprecatedGL - Pause loading and show a warning screen when deprecated GL usage is detected (true/false)");
        } catch (IOException e) {
            FMLLog.log.warn("[GL46Core] Could not save config: {}", e.getMessage());
        }
    }
}
