package com.github.gl46core;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GLDebugMessageCallbackI;
import org.lwjgl.system.MemoryUtil;

@Mod(modid = GL46Core.MODID, name = GL46Core.NAME, version = GL46Core.VERSION)
public class GL46Core {
    public static final String MODID = "gl46core";
    public static final String NAME = "GL46 Core";
    public static final String VERSION = "0.2.0";

    public static final Logger LOGGER = LogManager.getLogger(MODID);

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        LOGGER.info("GL46 Core initializing — replacing legacy GL with core profile equivalents");

        // Check GL version — we require OpenGL 4.5 for DSA, UBOs with explicit binding, etc.
        String glVersionStr = GL11.glGetString(GL11.GL_VERSION);
        LOGGER.info("GL version: {} | Renderer: {}", glVersionStr, GL11.glGetString(GL11.GL_RENDERER));
        checkGLVersion(glVersionStr);

        // GL_CONTEXT_PROFILE_MASK = 0x9126
        int profileMask = GL11.glGetInteger(0x9126);
        // GL_CONTEXT_CORE_PROFILE_BIT = 0x1, GL_CONTEXT_COMPATIBILITY_PROFILE_BIT = 0x2
        String profile = (profileMask & 0x1) != 0 ? "CORE" : (profileMask & 0x2) != 0 ? "COMPAT" : "UNKNOWN(" + profileMask + ")";
        LOGGER.info("GL context profile: {}", profile);
        setupDebugOutput();
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
            if (major > 4 || (major == 4 && minor >= 5)) {
                return; // GL 4.5+ — all good
            }
            String renderer = GL11.glGetString(GL11.GL_RENDERER);
            throw new RuntimeException(
                "GL46 Core requires OpenGL 4.5 or higher.\n" +
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

    private void setupDebugOutput() {
        try {
            // GL_DEBUG_OUTPUT = 0x92E0 (async — no sync stalls)
            GL11.glEnable(0x92E0);
            // Do NOT enable GL_DEBUG_OUTPUT_SYNCHRONOUS (0x8242) — it forces driver sync on every GL call
            GL43.glDebugMessageCallback((GLDebugMessageCallbackI) (source, type, id, severity, length, message, userParam) -> {
                // GL_DEBUG_TYPE_ERROR = 0x824C, GL_DEBUG_SEVERITY_HIGH = 0x9146
                // Only log actual errors, not performance warnings (which spam on NVIDIA)
                if (type == 0x824C || severity == 0x9146) {
                    String msg = MemoryUtil.memUTF8(message, length);
                    LOGGER.error("[GL DEBUG] type=0x{} sev=0x{}: {}",
                        Integer.toHexString(type), Integer.toHexString(severity), msg);
                }
            }, 0L);
            LOGGER.info("GL debug output enabled (async, errors only)");
        } catch (Throwable e) {
            LOGGER.warn("Failed to enable GL debug output: {}", e.getMessage());
        }
    }
}
