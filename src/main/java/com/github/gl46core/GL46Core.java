package com.github.gl46core;

import com.github.gl46core.core.DeprecatedUsageTracker;
import com.github.gl46core.core.GL46CoreConfig;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GLDebugMessageCallbackI;
import org.lwjgl.system.MemoryUtil;

import net.minecraftforge.fml.common.event.FMLLoadCompleteEvent;

import java.util.Map;
import java.util.Set;

@Mod(modid = GL46Core.MODID, name = GL46Core.NAME, version = GL46Core.VERSION)
public class GL46Core {
    public static final String MODID = "gl46core";
    public static final String NAME = "GL46 Core";
    public static final String VERSION = "0.5.0";

    public static final Logger LOGGER = LogManager.getLogger(MODID);

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        LOGGER.info("GL46 Core v{} initializing — replacing legacy GL with core profile equivalents", VERSION);

        String glVersionStr = GL11.glGetString(GL11.GL_VERSION);
        LOGGER.info("GL version: {} | Renderer: {}", glVersionStr, GL11.glGetString(GL11.GL_RENDERER));
        checkGLVersion(glVersionStr);

        int profileMask = GL11.glGetInteger(0x9126);
        String profile = (profileMask & 0x1) != 0 ? "CORE" : (profileMask & 0x2) != 0 ? "COMPAT" : "UNKNOWN(" + profileMask + ")";
        LOGGER.info("GL context profile: {}", profile);
        setupDebugOutput();

        // Explicitly init the shader program here so compilation cost is
        // front-loaded during mod init (when the user expects waiting)
        // instead of stuttering on the first rendered frame.
        com.github.gl46core.gl.CoreShaderProgram.INSTANCE.ensureInitialized();

        // Run GL diagnostics — probes limits, extensions, common pitfalls
        com.github.gl46core.gl.GLDiagnostics.INSTANCE.runStartupChecks();

        // Print final error summary on JVM shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            com.github.gl46core.gl.GLDiagnostics.INSTANCE.printFinalSummary();
        }, "gl46core-diag-shutdown"));

        // Register debug overlay keybind (F4)
        com.github.gl46core.client.DebugOverlayHandler.register();

        // Register built-in dynamic light provider (held items, blazes, etc.)
        com.github.gl46core.api.hook.RenderRegistry.INSTANCE.registerDynamicLightProvider(
            com.github.gl46core.api.render.VanillaDynamicLightProvider.INSTANCE);

        logDeprecatedUsageSummary();
    }

    @Mod.EventHandler
    public void onLoadComplete(FMLLoadCompleteEvent event) {
        // Inject config GUIs for mods that have .cfg files but no GUI factory
        com.github.gl46core.client.ConfigGuiInjector.inject();
    }

    private void logDeprecatedUsageSummary() {
        if (!DeprecatedUsageTracker.hasAnyUsage()) {
            LOGGER.info("No deprecated GL usage detected — all mods are core-profile compatible");
            return;
        }
        if (!GL46CoreConfig.warnDeprecatedGL()) return;

        Map<String, Set<String>> usage = DeprecatedUsageTracker.getUsage();
        LOGGER.warn("Deprecated GL usage summary ({} feature categories):", usage.size());
        for (var entry : usage.entrySet()) {
            LOGGER.warn("  {} — {} class(es)", entry.getKey(), entry.getValue().size());
        }
    }

    /**
     * Verify that the GPU/driver supports at least OpenGL 4.5.
     * If not, throw a RuntimeException that Forge will catch and display
     * on the dirt-background error screen with a descriptive message.
     */
    private void checkGLVersion(String glVersionStr) {
        try {
            // GL version string format: "4.6.0 NVIDIA 560.81" or "4.5 Mesa 23.1"
            // Parse the major.minor from the start of the string
            String[] parts = glVersionStr.split("[\\s.]");
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);
            if (major > 4 || (major == 4 && minor >= 6)) {
                return; // GL 4.6+ — all good
            }
            String renderer = GL11.glGetString(GL11.GL_RENDERER);
            throw new RuntimeException(
                "GL46 Core requires OpenGL 4.6 or higher.\n" +
                "Your GPU/driver only supports OpenGL " + major + "." + minor + ".\n" +
                "GPU: " + renderer + "\n\n" +
                "Please update your graphics drivers, or remove gl46core from your mods folder.\n" +
                "Minimum supported GPUs: NVIDIA GeForce 400+, AMD Radeon HD 7000+, Intel HD 520+"
            );
        } catch (RuntimeException e) {
            throw e; // re-throw our own exception
        } catch (Exception e) {
            LOGGER.warn("Could not parse GL version string '{}': {}", glVersionStr, e.getMessage());
        }
    }

    // ── Centralized GL diagnostics ────────────────────────────────────

    /**
     * Check for GL errors after a direct GL call.
     * Delegates to {@link com.github.gl46core.gl.GLDiagnostics} which handles
     * adaptive detail levels, per-label dedup, and periodic summaries.
     */
    public static void glCheck(String label) {
        com.github.gl46core.gl.GLDiagnostics.INSTANCE.check(label);
    }

    /**
     * Called once per frame (from clear()) to drive warm-up countdown
     * and periodic error summaries.
     */
    public static void onFrameTick() {
        com.github.gl46core.gl.GLDiagnostics.INSTANCE.onFrameTick();
    }

    private void setupDebugOutput() {
        try {
            // GL_DEBUG_OUTPUT = 0x92E0
            GL11.glEnable(0x92E0);
            // NOTE: GL_DEBUG_OUTPUT_SYNCHRONOUS (0x8242) intentionally NOT enabled.
            // It forces GPU/CPU sync on every GL call, causing massive FPS drops
            // especially with performance mods (Celeritas, Sodium, etc.).

            // Ensure ALL debug messages are enabled (NVIDIA may filter some by default)
            GL43.glDebugMessageControl(GL11.GL_DONT_CARE, GL11.GL_DONT_CARE, GL11.GL_DONT_CARE,
                    (int[]) null, true);

            // Suppress known NVIDIA driver noise at the GL level:
            //  131218 = "Vertex shader being recompiled based on GL state" (one-time warm-up)
            //  131169 = "Framebuffer detailed info: driver allocated storage for renderbuffer"
            //  131204 = "Texture state usage warning: texture object (0) on unit 1 has no base level"
            GL43.glDebugMessageControl(GL11.GL_DONT_CARE, GL11.GL_DONT_CARE, GL11.GL_DONT_CARE,
                    new int[]{131218, 131169, 131204}, false);

            GL43.glDebugMessageCallback((GLDebugMessageCallbackI) (source, type, id, severity, length, message, userParam) -> {
                String msg = MemoryUtil.memUTF8(message, length);
                com.github.gl46core.gl.GLDiagnostics.INSTANCE.recordDebugMessage(source, type, id, severity, msg);
            }, 0L);

            // Drain any errors generated by the setup itself
            while (GL11.glGetError() != 0) {}

            LOGGER.info("GL debug output enabled (async)");
        } catch (Throwable e) {
            LOGGER.warn("Failed to enable GL debug output: {}", e.getMessage());
        }
    }

    /**
     * Disable GL debug output entirely — called when diagnostics transitions to OFF
     * to eliminate any remaining callback overhead.
     */
    public static void disableDebugOutput() {
        try {
            GL11.glDisable(0x92E0); // GL_DEBUG_OUTPUT
            LOGGER.info("GL debug output disabled (diagnostics OFF)");
        } catch (Throwable e) {
            LOGGER.warn("Failed to disable GL debug output: {}", e.getMessage());
        }
    }
}
