package com.github.gl46core.gl;

import com.github.gl46core.core.DeprecatedUsageTracker;
import com.github.gl46core.core.GL46CoreConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.FileResourcePack;
import net.minecraft.client.resources.FolderResourcePack;
import net.minecraft.client.resources.IResourcePack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.FMLLog;
import net.minecraftforge.fml.common.ProgressManager;
import net.minecraftforge.fml.common.ProgressManager.ProgressBar;
import net.minecraftforge.fml.common.asm.FMLSanityChecker;
import org.apache.commons.lang3.StringUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;

/**
 * Core-profile replacement for Forge's SplashProgress rendering.
 * Uses GLFW + LWJGL3 directly (no lwjglx compat layer).
 *
 * Renders synchronously on the main thread. No separate splash thread
 * (GLFW doesn't support rendering to another window's framebuffer from
 * a shared context the way LWJGL2 SharedDrawable did).
 *
 * The splash is rendered once on start(), and can be re-rendered via
 * renderFrame() which is called from our mixin hooks.
 */
public final class CoreSplashRenderer {

    private CoreSplashRenderer() {}

    // ── GLFW handle ──────────────────────────────────────────────────
    private static long mainWindow = 0;

    // ── Config ───────────────────────────────────────────────────────
    private static Properties config;
    private static boolean enabled;
    private static boolean initialized;
    private static boolean rotate;
    private static int logoOffset;
    private static int backgroundColor;
    private static int fontColor;
    private static int barBorderColor;
    private static int barColor;
    private static int barBackgroundColor;
    private static boolean showMemory;
    private static int memoryGoodColor;
    private static int memoryWarnColor;
    private static int memoryLowColor;
    private static float memoryColorPercent;
    private static long memoryColorChangeTime;
    private static int angle = 0;
    private static long lastFrameNanos = 0;
    private static final long MIN_FRAME_NANOS = 16_666_667L; // ~60fps

    // ── Resource packs for texture loading ────────────────────────────
    private static IResourcePack mcPack;
    private static IResourcePack fmlPack;
    private static IResourcePack miscPack;

    // ── GL objects ───────────────────────────────────────────────────
    private static int splashProgram;
    private static int splashVao;
    private static int splashVbo;
    private static int uMVP;
    private static int uColor;
    private static int uTexture;
    private static int uTextureEnabled;
    private static final FloatBuffer matBuf = MemoryUtil.memAllocFloat(16);

    // ── Textures ─────────────────────────────────────────────────────
    private static int logoTexName, logoTexW, logoTexH, logoTexSize, logoTexFrames;
    private static int forgeTexName, forgeTexW, forgeTexH, forgeTexSize, forgeTexFrames;
    private static int fontTexName, fontTexW, fontTexH, fontTexSize, fontTexFrames;

    // ── Font metrics ─────────────────────────────────────────────────
    private static final int[] CHAR_WIDTH = new int[256];

    // ── Vertex buffer (pos2 + texcoord2 + color4ub = 20 bytes) ──────
    private static final int VERT_SIZE = 20;
    private static final ByteBuffer vertBuf = ByteBuffer.allocateDirect(4096 * VERT_SIZE)
            .order(ByteOrder.nativeOrder());
    private static int vertCount = 0;

    // ═════════════════════════════════════════════════════════════════
    // Public API (called from MixinSplashProgress)
    // ═════════════════════════════════════════════════════════════════

    public static void start() {
        File configFile = new File(Minecraft.getMinecraft().gameDir, "config/splash.properties");
        File parent = configFile.getParentFile();
        if (!parent.exists()) parent.mkdirs();

        config = new Properties();
        try (Reader r = new InputStreamReader(new FileInputStream(configFile), StandardCharsets.UTF_8)) {
            config.load(r);
        } catch (IOException e) {
            FMLLog.log.info("Could not load splash.properties, will create a default one");
        }

        enabled         = getBool("enabled", true);
        rotate          = getBool("rotate", false);
        showMemory      = getBool("showMemory", true);
        logoOffset      = getInt("logoOffset", 0);
        backgroundColor = getHex("background", 0xFFFFFF);
        fontColor       = getHex("font", 0x000000);
        barBorderColor  = getHex("barBorder", 0xC0C0C0);
        barColor        = getHex("bar", 0xCB3D35);
        barBackgroundColor = getHex("barBackground", 0xFFFFFF);
        memoryGoodColor = getHex("memoryGood", 0x78CB34);
        memoryWarnColor = getHex("memoryWarn", 0xE6E84A);
        memoryLowColor  = getHex("memoryLow", 0xE42F2F);

        ResourceLocation fontLoc = new ResourceLocation(getString("fontTexture", "textures/font/ascii.png"));
        ResourceLocation logoLoc = new ResourceLocation("textures/gui/title/mojang.png");
        ResourceLocation forgeLoc = new ResourceLocation(getString("forgeTexture", "fml:textures/gui/forge.png"));
        ResourceLocation forgeFallbackLoc = new ResourceLocation("fml:textures/gui/forge.png");

        File miscPackFile = new File(Minecraft.getMinecraft().gameDir, getString("resourcePackPath", "resources"));

        try (Writer w = new OutputStreamWriter(new FileOutputStream(configFile), StandardCharsets.UTF_8)) {
            config.store(w, "Splash screen properties");
        } catch (IOException e) {
            FMLLog.log.error("Could not save the splash.properties file", e);
        }

        // Override colors with ModernSplash config if present and ModernSplash is active
        if (com.github.gl46core.core.GL46CoreMixinPlugin.isModernSplashPresent()) {
            tryLoadModernSplashConfig(new File(Minecraft.getMinecraft().gameDir, "config/modern_splash.cfg"));
        }

        mcPack = Minecraft.getMinecraft().defaultResourcePack;
        fmlPack = createResourcePack(FMLSanityChecker.fmlLocation);
        miscPack = createResourcePack(miscPackFile);

        if (!enabled) return;

        mainWindow = GLFW.glfwGetCurrentContext();
        if (mainWindow == 0) {
            FMLLog.log.error("CoreSplashRenderer: No current GLFW context, disabling splash");
            enabled = false;
            return;
        }

        try {
            initSplashGL();

            int[] result;
            result = loadTexture(fontLoc, null, true);
            fontTexName = result[0]; fontTexW = result[1]; fontTexH = result[2]; fontTexSize = result[3]; fontTexFrames = result[4];
            result = loadTexture(logoLoc, null, false);
            logoTexName = result[0]; logoTexW = result[1]; logoTexH = result[2]; logoTexSize = result[3]; logoTexFrames = result[4];
            result = loadTexture(forgeLoc, forgeFallbackLoc, true);
            forgeTexName = result[0]; forgeTexW = result[1]; forgeTexH = result[2]; forgeTexSize = result[3]; forgeTexFrames = result[4];

            initCharWidths();
            initialized = true;

            FMLLog.log.info("CoreSplashRenderer initialized: logo={}x{} forge={}x{} font={}x{}",
                    logoTexW, logoTexH, forgeTexW, forgeTexH, fontTexW, fontTexH);

            // Render first frame immediately
            renderFrame();
        } catch (Exception e) {
            FMLLog.log.error("CoreSplashRenderer init error:", e);
            enabled = false;
        }
    }

    /**
     * Render one splash frame and swap buffers. Safe to call from the main thread
     * at any point during loading. This is called from MixinSplashProgress hooks
     * and can be called externally to update the splash during long operations.
     */
    public static void renderFrame() {
        if (!enabled || !initialized) return;

        // Throttle to ~60fps to avoid slowing down loading
        long now = System.nanoTime();
        if (now - lastFrameNanos < MIN_FRAME_NANOS) return;
        lastFrameNanos = now;

        try {
            // Save GL state that Minecraft's loading may have changed
            int[] savedTex = new int[1];
            int[] savedProg = new int[1];
            int[] savedVao = new int[1];
            int[] savedVbo = new int[1];
            GL11C.glGetIntegerv(GL11C.GL_TEXTURE_BINDING_2D, savedTex);
            GL11C.glGetIntegerv(GL20.GL_CURRENT_PROGRAM, savedProg);
            GL11C.glGetIntegerv(GL30.GL_VERTEX_ARRAY_BINDING, savedVao);
            GL11C.glGetIntegerv(GL15.GL_ARRAY_BUFFER_BINDING, savedVbo);
            boolean blendWas = GL11C.glIsEnabled(GL11C.GL_BLEND);
            boolean depthWas = GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST);

            renderSplashFrame();
            GLFW.glfwSwapBuffers(mainWindow);

            // Restore GL state
            GL20.glUseProgram(savedProg[0]);
            GL30.glBindVertexArray(savedVao[0]);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, savedVbo[0]);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, savedTex[0]);
            if (blendWas) GL11C.glEnable(GL11C.GL_BLEND); else GL11C.glDisable(GL11C.GL_BLEND);
            if (depthWas) GL11C.glEnable(GL11C.GL_DEPTH_TEST); else GL11C.glDisable(GL11C.GL_DEPTH_TEST);
        } catch (Exception e) {
            FMLLog.log.error("CoreSplashRenderer render error:", e);
        }
    }

    public static void pause() {
        // No-op: main thread always has context in synchronous mode
    }

    public static void resume() {
        // Render a frame when resuming to show updated progress
        renderFrame();
    }

    public static void finish() {
        if (!enabled || !initialized) return;
        try {
            showDeprecationWarningIfNeeded();

            GL11C.glDeleteTextures(fontTexName);
            GL11C.glDeleteTextures(logoTexName);
            GL11C.glDeleteTextures(forgeTexName);
            GL20.glDeleteProgram(splashProgram);
            GL30.glDeleteVertexArrays(splashVao);
            GL15.glDeleteBuffers(splashVbo);
            initialized = false;
        } catch (Exception e) {
            FMLLog.log.error("Error finishing CoreSplashRenderer:", e);
        }
    }

    /**
     * If deprecated GL usage was detected during class loading and the config
     * says to pause, render a warning screen and wait for Enter to continue.
     */
    private static void showDeprecationWarningIfNeeded() {
        if (!DeprecatedUsageTracker.hasAnyUsage()) return;

        List<String> lines = DeprecatedUsageTracker.buildSummaryLines();

        if (GL46CoreConfig.warnDeprecatedGL()) {
            FMLLog.log.warn("══════════════════════════════════════════════════════════════");
            FMLLog.log.warn("GL46 Core — Deprecated OpenGL usage detected:");
            for (String line : lines) {
                FMLLog.log.warn("  {}", line);
            }
            FMLLog.log.warn("These features have no core-profile equivalent and are no-ops.");
            FMLLog.log.warn("Affected mods may have missing visuals. Ask mod authors to update.");
            FMLLog.log.warn("══════════════════════════════════════════════════════════════");
        }

        if (!GL46CoreConfig.pauseOnDeprecatedGL()) return;
        if (mainWindow == 0) return;

        while (!GLFW.glfwWindowShouldClose(mainWindow)) {
            renderWarningFrame(lines);
            GLFW.glfwSwapBuffers(mainWindow);
            GLFW.glfwPollEvents();

            if (GLFW.glfwGetKey(mainWindow, GLFW.GLFW_KEY_ENTER) == GLFW.GLFW_PRESS
                    || GLFW.glfwGetKey(mainWindow, GLFW.GLFW_KEY_KP_ENTER) == GLFW.GLFW_PRESS) {
                break;
            }

            try { Thread.sleep(16); } catch (InterruptedException ignored) {}
        }
    }

    private static void renderWarningFrame(List<String> summaryLines) {
        int w, h;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer pw = stack.mallocInt(1);
            IntBuffer ph = stack.mallocInt(1);
            GLFW.glfwGetFramebufferSize(mainWindow, pw, ph);
            w = pw.get(0);
            h = ph.get(0);
        }
        GL11C.glViewport(0, 0, w, h);

        GL11C.glClearColor(0.12f, 0.12f, 0.14f, 1.0f);
        GL11C.glClear(GL11C.GL_COLOR_BUFFER_BIT);
        GL11C.glDisable(GL11C.GL_DEPTH_TEST);
        GL11C.glEnable(GL11C.GL_BLEND);
        GL11C.glBlendFunc(GL11C.GL_SRC_ALPHA, GL11C.GL_ONE_MINUS_SRC_ALPHA);

        float left = 320 - w / 2f;
        float right = 320 + w / 2f;
        float bottom = 240 + h / 2f;
        float top = 240 - h / 2f;
        org.joml.Matrix4f ortho = new org.joml.Matrix4f().ortho(left, right, bottom, top, -1, 1);

        GL20.glUseProgram(splashProgram);
        ortho.get(matBuf);
        GL20.glUniformMatrix4fv(uMVP, false, matBuf);
        GL20.glUniform1i(uTexture, 0);
        GL20.glUniform4f(uColor, 1, 1, 1, 1);

        float scale = 2.0f;
        float lineH = charCellH * scale + 4;

        float panelLeft = left + 30;
        float panelTop = top + 30;

        // Title bar
        setDrawColor(0xE0A020);
        GL20.glUniform1i(uTextureEnabled, 0);
        drawQuad(panelLeft - 10, panelTop - 10, right - 20, panelTop + lineH + 6, 0, 0, 0, 0);

        setDrawColor(0x1E1E24);
        drawQuad(panelLeft - 8, panelTop - 8, right - 22, panelTop + lineH + 4, 0, 0, 0, 0);

        setDrawColor(0xFFCC33);
        drawString(panelLeft, panelTop, "WARNING: Deprecated OpenGL Usage Detected", scale);

        float y = panelTop + lineH + 20;

        setDrawColor(0xDDDDDD);
        drawString(panelLeft, y, "The following mods use legacy GL features that have", scale);
        y += lineH;
        drawString(panelLeft, y, "no core-profile replacement. They will still load,", scale);
        y += lineH;
        drawString(panelLeft, y, "but some visuals may be missing or broken.", scale);
        y += lineH + 10;

        // Feature list
        setDrawColor(0x555566);
        GL20.glUniform1i(uTextureEnabled, 0);
        float listBottom = y + (summaryLines.size() + 1) * lineH + 8;
        drawQuad(panelLeft - 5, y - 5, right - 25, listBottom, 0, 0, 0, 0);

        for (String line : summaryLines) {
            boolean isHeader = !line.startsWith("    ");
            setDrawColor(isHeader ? 0xFF8844 : 0xBBBBBB);
            drawString(panelLeft + (isHeader ? 0 : 10), y, line, scale);
            y += lineH;
        }

        y = listBottom + 20;

        setDrawColor(0xAAAAAA);
        drawString(panelLeft, y, "To disable this warning, set pauseOnDeprecatedGL=false", scale);
        y += lineH;
        drawString(panelLeft, y, "in config/gl46core.cfg", scale);
        y += lineH + 20;

        // Pulsing "Press ENTER" prompt
        float pulse = (float) (0.6 + 0.4 * Math.sin(System.currentTimeMillis() / 400.0));
        int g = (int) (255 * pulse);
        setDrawColor((g << 16) | (0xFF << 8) | g);
        drawString(panelLeft, y, ">>> Press ENTER to continue loading <<<", scale);
    }

    // ═════════════════════════════════════════════════════════════════
    // GL initialization (main thread)
    // ═════════════════════════════════════════════════════════════════

    private static void initSplashGL() {
        String vertSrc = """
                #version 150 core
                in vec2 aPos;
                in vec2 aTex;
                in vec4 aCol;
                uniform mat4 uMVP;
                out vec2 vTex;
                out vec4 vCol;
                void main() {
                    gl_Position = uMVP * vec4(aPos, 0.0, 1.0);
                    vTex = aTex;
                    vCol = aCol;
                }
                """;

        String fragSrc = """
                #version 150 core
                in vec2 vTex;
                in vec4 vCol;
                uniform sampler2D uTexture;
                uniform bool uTextureEnabled;
                uniform vec4 uColor;
                out vec4 fragColor;
                void main() {
                    vec4 color = vCol * uColor;
                    if (uTextureEnabled) {
                        vec4 tex = texture(uTexture, vTex);
                        color *= tex;
                    }
                    if (color.a < 0.01) discard;
                    fragColor = color;
                }
                """;

        int vert = GL20.glCreateShader(GL20.GL_VERTEX_SHADER);
        GL20.glShaderSource(vert, vertSrc);
        GL20.glCompileShader(vert);

        int frag = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
        GL20.glShaderSource(frag, fragSrc);
        GL20.glCompileShader(frag);

        splashProgram = GL20.glCreateProgram();
        GL20.glAttachShader(splashProgram, vert);
        GL20.glAttachShader(splashProgram, frag);
        GL20.glBindAttribLocation(splashProgram, 0, "aPos");
        GL20.glBindAttribLocation(splashProgram, 1, "aTex");
        GL20.glBindAttribLocation(splashProgram, 2, "aCol");
        GL20.glLinkProgram(splashProgram);

        if (GL20.glGetProgrami(splashProgram, GL20.GL_LINK_STATUS) == GL11C.GL_FALSE) {
            FMLLog.log.error("Splash shader link failed: {}", GL20.glGetProgramInfoLog(splashProgram, 4096));
        }

        GL20.glDeleteShader(vert);
        GL20.glDeleteShader(frag);

        uMVP = GL20.glGetUniformLocation(splashProgram, "uMVP");
        uColor = GL20.glGetUniformLocation(splashProgram, "uColor");
        uTexture = GL20.glGetUniformLocation(splashProgram, "uTexture");
        uTextureEnabled = GL20.glGetUniformLocation(splashProgram, "uTextureEnabled");

        splashVao = GL30.glGenVertexArrays();
        splashVbo = GL15.glGenBuffers();

        GL30.glBindVertexArray(splashVao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, splashVbo);

        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(0, 2, GL11C.GL_FLOAT, false, VERT_SIZE, 0);
        GL20.glEnableVertexAttribArray(1);
        GL20.glVertexAttribPointer(1, 2, GL11C.GL_FLOAT, false, VERT_SIZE, 8);
        GL20.glEnableVertexAttribArray(2);
        GL20.glVertexAttribPointer(2, 4, GL11C.GL_UNSIGNED_BYTE, true, VERT_SIZE, 16);

        GL30.glBindVertexArray(0);
    }

    // ═════════════════════════════════════════════════════════════════
    // Frame rendering
    // ═════════════════════════════════════════════════════════════════

    private static void renderSplashFrame() {
        // GL state save/restore is handled by the caller (renderFrame())

        float bgR = ((backgroundColor >> 16) & 0xFF) / 255f;
        float bgG = ((backgroundColor >> 8) & 0xFF) / 255f;
        float bgB = (backgroundColor & 0xFF) / 255f;
        GL11C.glClearColor(bgR, bgG, bgB, 1.0f);
        GL11C.glClear(GL11C.GL_COLOR_BUFFER_BIT);
        GL11C.glDisable(GL11C.GL_DEPTH_TEST);
        GL11C.glEnable(GL11C.GL_BLEND);
        GL11C.glBlendFunc(GL11C.GL_SRC_ALPHA, GL11C.GL_ONE_MINUS_SRC_ALPHA);

        int w, h;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer pw = stack.mallocInt(1);
            IntBuffer ph = stack.mallocInt(1);
            GLFW.glfwGetFramebufferSize(mainWindow, pw, ph);
            w = pw.get(0);
            h = ph.get(0);
        }
        GL11C.glViewport(0, 0, w, h);

        float left = 320 - w / 2f;
        float right = 320 + w / 2f;
        float bottom = 240 + h / 2f;
        float top = 240 - h / 2f;

        org.joml.Matrix4f ortho = new org.joml.Matrix4f().ortho(left, right, bottom, top, -1, 1);

        GL20.glUseProgram(splashProgram);
        ortho.get(matBuf);
        GL20.glUniformMatrix4fv(uMVP, false, matBuf);
        GL20.glUniform1i(uTexture, 0);
        GL20.glUniform4f(uColor, 1, 1, 1, 1);

        // ── Mojang logo (render with white vertex color so texture colors show through) ──
        setDrawColor(0xFFFFFF);
        GL20.glUniform1i(uTextureEnabled, 1);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, logoTexName);
        drawQuad(320 - 256, 240 - 256, 320 + 256, 240 + 256,
                texU(logoTexW, logoTexSize, 0, 0), texV(logoTexH, logoTexSize, 0, 0),
                texU(logoTexW, logoTexSize, 0, 1), texV(logoTexH, logoTexSize, 0, 1));

        // ── Memory bar ──
        if (showMemory) {
            drawMemoryBar();
        }

        // ── Progress bars ──
        ProgressBar first = null, penult = null, last = null;
        Iterator<ProgressBar> iter = ProgressManager.barIterator();
        while (iter.hasNext()) {
            if (first == null) first = iter.next();
            else { penult = last; last = iter.next(); }
        }

        if (first != null) {
            float barX = 320 - 200;
            float barY = 310;
            drawProgressBar(barX, barY, first);
            if (penult != null) drawProgressBar(barX, barY + 55, penult);
            if (last != null) drawProgressBar(barX, barY + (penult != null ? 110 : 55), last);
        }

        // ── Forge/Cleanroom logo ──
        angle++;
        setDrawColor(0xFFFFFF);
        GL20.glUniform4f(uColor, 1, 1, 1, 1);
        GL20.glUniform1i(uTextureEnabled, 1);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, forgeTexName);

        // Match original Forge positioning: fw/fh = half texture size
        float fw = forgeTexW / 2f;
        float fh = forgeTexH / 2f;
        float fx, fy;
        if (rotate) {
            float sh = Math.max(fw, fh);
            fx = 320 + w / 2f - sh - logoOffset;
            fy = 240 + h / 2f - sh - logoOffset;
            org.joml.Matrix4f rotOrtho = new org.joml.Matrix4f(ortho);
            rotOrtho.translate(fx, fy, 0);
            rotOrtho.rotateZ((float) Math.toRadians(angle));
            rotOrtho.get(matBuf);
            GL20.glUniformMatrix4fv(uMVP, false, matBuf);
            fx = 0; fy = 0;
        } else {
            fx = 320 + w / 2f - fw - logoOffset;
            fy = 240 + h / 2f - fh - logoOffset;
        }

        int f = (angle / 5) % Math.max(forgeTexFrames, 1);
        drawQuad(fx - fw, fy - fh, fx + fw, fy + fh,
                texU(forgeTexW, forgeTexSize, f, 0), texV(forgeTexH, forgeTexSize, f, 0),
                texU(forgeTexW, forgeTexSize, f, 1), texV(forgeTexH, forgeTexSize, f, 1));

        if (rotate) {
            ortho.get(matBuf);
            GL20.glUniformMatrix4fv(uMVP, false, matBuf);
        }

    }

    // ═════════════════════════════════════════════════════════════════
    // Progress bar rendering
    // ═════════════════════════════════════════════════════════════════

    private static final int BAR_WIDTH = 400;
    private static final int BAR_HEIGHT = 20;
    private static final int TEXT_HEIGHT = 20;

    private static void drawProgressBar(float x, float y, ProgressBar bar) {
        setDrawColor(fontColor);
        drawString(x, y, bar.getTitle() + " - " + bar.getMessage(), 2.0f);

        setDrawColor(barBorderColor);
        GL20.glUniform1i(uTextureEnabled, 0);
        drawQuad(x, y + TEXT_HEIGHT, x + BAR_WIDTH, y + TEXT_HEIGHT + BAR_HEIGHT, 0, 0, 0, 0);

        setDrawColor(barBackgroundColor);
        drawQuad(x + 1, y + TEXT_HEIGHT + 1, x + BAR_WIDTH - 1, y + TEXT_HEIGHT + BAR_HEIGHT - 1, 0, 0, 0, 0);

        setDrawColor(barColor);
        float fillWidth = (BAR_WIDTH - 2) * (bar.getStep() + 1f) / (bar.getSteps() + 1f);
        drawQuad(x + 1, y + TEXT_HEIGHT + 1, x + 1 + fillWidth, y + TEXT_HEIGHT + BAR_HEIGHT - 1, 0, 0, 0, 0);

        String progress = bar.getStep() + "/" + bar.getSteps();
        float textX = x + (BAR_WIDTH - 2) / 2f - getStringWidth(progress);
        setDrawColor(fontColor);
        drawString(textX, y + TEXT_HEIGHT + 2, progress, 2.0f);
    }

    private static void drawMemoryBar() {
        int maxMem = bytesToMb(Runtime.getRuntime().maxMemory());
        int totalMem = bytesToMb(Runtime.getRuntime().totalMemory());
        int freeMem = bytesToMb(Runtime.getRuntime().freeMemory());
        int usedMem = totalMem - freeMem;
        float usedPercent = usedMem / (float) maxMem;

        long time = System.currentTimeMillis();
        if (usedPercent > memoryColorPercent || (time - memoryColorChangeTime > 1000)) {
            memoryColorChangeTime = time;
            memoryColorPercent = usedPercent;
        }

        int memColor;
        if (memoryColorPercent < 0.75f) memColor = memoryGoodColor;
        else if (memoryColorPercent < 0.85f) memColor = memoryWarnColor;
        else memColor = memoryLowColor;

        float bx = 320 - 200, by = 20;

        setDrawColor(fontColor);
        drawString(bx, by, "Memory Used / Total", 2.0f);

        setDrawColor(barBorderColor);
        GL20.glUniform1i(uTextureEnabled, 0);
        drawQuad(bx, by + TEXT_HEIGHT, bx + BAR_WIDTH, by + TEXT_HEIGHT + BAR_HEIGHT, 0, 0, 0, 0);

        setDrawColor(barBackgroundColor);
        drawQuad(bx + 1, by + TEXT_HEIGHT + 1, bx + BAR_WIDTH - 1, by + TEXT_HEIGHT + BAR_HEIGHT - 1, 0, 0, 0, 0);

        setDrawColor(memoryLowColor);
        float totalX = bx + 1 + (BAR_WIDTH - 2) * totalMem / (float) maxMem - 2;
        drawQuad(totalX, by + TEXT_HEIGHT + 1, totalX + 2, by + TEXT_HEIGHT + BAR_HEIGHT - 1, 0, 0, 0, 0);

        setDrawColor(memColor);
        float usedWidth = (BAR_WIDTH - 2) * usedMem / (float) maxMem;
        drawQuad(bx + 1, by + TEXT_HEIGHT + 1, bx + 1 + usedWidth, by + TEXT_HEIGHT + BAR_HEIGHT - 1, 0, 0, 0, 0);

        String progress = StringUtils.leftPad(Integer.toString(usedMem), 4, ' ') + " MB / "
                + StringUtils.leftPad(Integer.toString(maxMem), 4, ' ') + " MB";
        float textX = bx + (BAR_WIDTH - 2) / 2f - getStringWidth(progress);
        setDrawColor(fontColor);
        drawString(textX, by + TEXT_HEIGHT + 2, progress, 2.0f);
    }

    // ═════════════════════════════════════════════════════════════════
    // Simple font rendering using ascii.png atlas
    // ═════════════════════════════════════════════════════════════════

    private static void initCharWidths() {
        for (int i = 0; i < 256; i++) CHAR_WIDTH[i] = 6;
        CHAR_WIDTH[32] = 4;
        try {
            InputStream is = openResource(new ResourceLocation("textures/font/ascii.png"), null, false);
            if (is != null) {
                BufferedImage img = ImageIO.read(is);
                is.close();
                if (img != null) {
                    int imgW = img.getWidth();
                    int imgH = img.getHeight();
                    charCellW = imgW / 16;
                    charCellH = imgH / 16;
                    for (int ch = 0; ch < 256; ch++) {
                        if (ch == 32) { CHAR_WIDTH[ch] = charCellW / 2; continue; }
                        int cx = (ch % 16) * charCellW;
                        int cy = (ch / 16) * charCellH;
                        int maxX = 0;
                        for (int py = 0; py < charCellH; py++) {
                            for (int px = charCellW - 1; px >= 0; px--) {
                                int argb = img.getRGB(cx + px, cy + py);
                                if ((argb & 0xFF000000) != 0) {
                                    if (px + 2 > maxX) maxX = px + 2;
                                    break;
                                }
                            }
                        }
                        CHAR_WIDTH[ch] = maxX > 0 ? maxX : 1;
                    }
                }
            }
        } catch (Exception e) {
            // Use defaults
        }
    }

    private static float getStringWidth(String text) {
        float w = 0;
        for (int i = 0; i < text.length(); i++) w += CHAR_WIDTH[text.charAt(i) & 0xFF];
        return w;
    }

    private static int charCellW = 8; // pixels per char cell in the atlas
    private static int charCellH = 8;

    private static void drawString(float x, float y, String text, float scale) {
        GL20.glUniform1i(uTextureEnabled, 1);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, fontTexName);
        float cx = x;
        for (int i = 0; i < text.length(); i++) {
            int ch = text.charAt(i) & 0xFF;
            float u0 = (ch % 16) / 16f;
            float v0 = (ch / 16) / 16f;
            // Use full cell width for UV, but only advance by char width for spacing
            float u1 = u0 + (float)CHAR_WIDTH[ch] / (16f * charCellW);
            float v1 = v0 + 1f / 16f;
            drawQuad(cx, y, cx + CHAR_WIDTH[ch] * scale, y + charCellH * scale, u0, v0, u1, v1);
            cx += CHAR_WIDTH[ch] * scale;
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // Low-level quad drawing
    // ═════════════════════════════════════════════════════════════════

    private static int currentR = 255, currentG = 255, currentB = 255, currentA = 255;

    private static void setDrawColor(int rgb) {
        currentR = (rgb >> 16) & 0xFF;
        currentG = (rgb >> 8) & 0xFF;
        currentB = rgb & 0xFF;
        currentA = 255;
    }

    private static void drawQuad(float x0, float y0, float x1, float y1,
                                  float u0, float v0, float u1, float v1) {
        vertBuf.clear();
        vertCount = 0;
        putVertex(x0, y0, u0, v0);
        putVertex(x0, y1, u0, v1);
        putVertex(x1, y1, u1, v1);
        putVertex(x0, y0, u0, v0);
        putVertex(x1, y1, u1, v1);
        putVertex(x1, y0, u1, v0);
        vertBuf.flip();

        GL30.glBindVertexArray(splashVao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, splashVbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertBuf, GL15.GL_STREAM_DRAW);
        GL20.glVertexAttribPointer(0, 2, GL11C.GL_FLOAT, false, VERT_SIZE, 0);
        GL20.glVertexAttribPointer(1, 2, GL11C.GL_FLOAT, false, VERT_SIZE, 8);
        GL20.glVertexAttribPointer(2, 4, GL11C.GL_UNSIGNED_BYTE, true, VERT_SIZE, 16);
        GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, vertCount);
        GL30.glBindVertexArray(0);
    }

    private static void putVertex(float x, float y, float u, float v) {
        vertBuf.putFloat(x).putFloat(y).putFloat(u).putFloat(v);
        vertBuf.put((byte) currentR).put((byte) currentG).put((byte) currentB).put((byte) currentA);
        vertCount++;
    }

    // ═════════════════════════════════════════════════════════════════
    // Texture loading
    // ═════════════════════════════════════════════════════════════════

    private static int[] loadTexture(ResourceLocation loc, ResourceLocation fallback, boolean allowRP) {
        try {
            InputStream s = openResource(loc, fallback, allowRP);
            ImageInputStream stream = ImageIO.createImageInputStream(s);
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new IOException("No suitable reader for " + loc);
            ImageReader reader = readers.next();
            reader.setInput(stream);
            int frameCount = reader.getNumImages(true);
            BufferedImage[] images = new BufferedImage[frameCount];
            for (int i = 0; i < frameCount; i++) images[i] = reader.read(i);
            reader.dispose();
            s.close();

            int width = images[0].getWidth();
            int height = images[0].getHeight();

            if (height > width && height % width == 0) {
                frameCount = height / width;
                BufferedImage original = images[0];
                height = width;
                images = new BufferedImage[frameCount];
                for (int i = 0; i < frameCount; i++) {
                    images[i] = original.getSubimage(0, i * height, width, height);
                }
            }

            int size = 1;
            while ((size / width) * (size / height) < frameCount) size *= 2;

            int name = GL11C.glGenTextures();
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, name);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA, size, size, 0,
                    GL12C.GL_BGRA, GL12C.GL_UNSIGNED_INT_8_8_8_8_REV, (IntBuffer) null);

            IntBuffer texBuf = MemoryUtil.memAllocInt(4 * 1024 * 1024);
            for (int i = 0; i * (size / width) < frameCount; i++) {
                for (int j = 0; i * (size / width) + j < frameCount && j < size / width; j++) {
                    texBuf.clear();
                    BufferedImage image = images[i * (size / width) + j];
                    for (int k = 0; k < height; k++)
                        for (int l = 0; l < width; l++)
                            texBuf.put(image.getRGB(l, k));
                    texBuf.position(0).limit(width * height);
                    GL11C.glTexSubImage2D(GL11C.GL_TEXTURE_2D, 0, j * width, i * height,
                            width, height, GL12C.GL_BGRA, GL12C.GL_UNSIGNED_INT_8_8_8_8_REV, texBuf);
                }
            }
            MemoryUtil.memFree(texBuf);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
            return new int[]{name, width, height, size, frameCount};
        } catch (IOException e) {
            FMLLog.log.error("Error loading splash texture: {}", loc, e);
            int name = GL11C.glGenTextures();
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, name);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA, 1, 1, 0,
                        GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, stack.ints(0xFFFFFFFF));
            }
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
            return new int[]{name, 1, 1, 1, 1};
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // Utility methods
    // ═════════════════════════════════════════════════════════════════

    private static float texU(int texW, int texSize, int frame, float u) {
        if (texSize == 0 || texW == 0) return 0;
        return texW * (frame % (texSize / texW) + u) / texSize;
    }

    private static float texV(int texH, int texSize, int frame, float v) {
        if (texSize == 0 || texH == 0) return 0;
        return texH * (frame / (texSize / texH) + v) / texSize;
    }

    private static InputStream openResource(ResourceLocation loc, ResourceLocation fallback, boolean allowRP) throws IOException {
        if (!allowRP) return mcPack.getInputStream(loc);
        if (miscPack != null && miscPack.resourceExists(loc)) return miscPack.getInputStream(loc);
        if (fmlPack != null && fmlPack.resourceExists(loc)) return fmlPack.getInputStream(loc);
        if (!mcPack.resourceExists(loc) && fallback != null) return openResource(fallback, null, true);
        return mcPack.getInputStream(loc);
    }

    private static IResourcePack createResourcePack(File file) {
        if (file == null) return null;
        if (file.isDirectory()) return new FolderResourcePack(file);
        else if (file.isFile()) return new FileResourcePack(file);
        return null;
    }

    /**
     * Read ModernSplash's config file (Forge Configuration format) and override
     * our splash colors with its values. This lets gl46core's CoreSplashRenderer
     * match ModernSplash's appearance (white text on red/dark background) without
     * needing ModernSplash itself to be active.
     */
    private static void tryLoadModernSplashConfig(File cfgFile) {
        if (!cfgFile.exists()) return;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(cfgFile), StandardCharsets.UTF_8))) {
            // Determine if dark mode is active based on time
            java.time.LocalTime now = java.time.LocalTime.now();
            int nowMinutes = now.getHour() * 100 + now.getMinute(); // HHMM format like config uses

            // Parse all values first, then decide which set to use
            int bgLight = -1, bgDark = -1;
            int fontLight = -1, fontDark = -1;
            int barLight = -1, barDark = -1;
            int barBgLight = -1, barBgDark = -1;
            int barBorderLight = -1, barBorderDark = -1;
            int memGoodLight = -1, memGoodDark = -1;
            int memWarnLight = -1, memWarnDark = -1;
            int memLowLight = -1, memLowDark = -1;
            int darkStart = 2300, darkEnd = 600;
            boolean darkOnly = false;

            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                // Forge config format: S:key=value or I:key=value or B:key=value
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String raw = line.substring(0, eq).trim();
                String val = line.substring(eq + 1).trim();
                // Strip type prefix (S:, I:, B:) and surrounding quotes
                String key = (raw.length() > 2 && raw.charAt(1) == ':') ? raw.substring(2) : raw;
                if (key.startsWith("\"") && key.endsWith("\"")) key = key.substring(1, key.length() - 1);

                try {
                    switch (key) {
                        case "background"       -> bgLight = Integer.decode(val);
                        case "backgroundDark"   -> bgDark = Integer.decode(val);
                        case "font"             -> fontLight = Integer.decode(val);
                        case "fontDark"          -> fontDark = Integer.decode(val);
                        case "bar"              -> barLight = Integer.decode(val);
                        case "barDark"          -> barDark = Integer.decode(val);
                        case "barBackground"    -> barBgLight = Integer.decode(val);
                        case "barBackgroundDark"-> barBgDark = Integer.decode(val);
                        case "barBorder"        -> barBorderLight = Integer.decode(val);
                        case "barBorderDark"    -> barBorderDark = Integer.decode(val);
                        case "memoryGood"       -> memGoodLight = Integer.decode(val);
                        case "memoryGoodDark"   -> memGoodDark = Integer.decode(val);
                        case "memoryWarn"       -> memWarnLight = Integer.decode(val);
                        case "memoryWarnDark"   -> memWarnDark = Integer.decode(val);
                        case "memoryLow"        -> memLowLight = Integer.decode(val);
                        case "memoryLowDark"    -> memLowDark = Integer.decode(val);
                        case "darkStartTime"    -> darkStart = Integer.parseInt(val);
                        case "darkEndTime"      -> darkEnd = Integer.parseInt(val);
                        case "darkModeOnly"     -> darkOnly = Boolean.parseBoolean(val);
                    }
                } catch (NumberFormatException ignored) {}
            }

            boolean dark = darkOnly;
            if (!dark) {
                // Check if current time is in dark period
                if (darkStart > darkEnd) {
                    // Wraps midnight: e.g. 2300-0600
                    dark = nowMinutes >= darkStart || nowMinutes < darkEnd;
                } else {
                    dark = nowMinutes >= darkStart && nowMinutes < darkEnd;
                }
            }

            // Apply colors
            if (dark) {
                if (bgDark >= 0)        backgroundColor = bgDark;
                if (fontDark >= 0)       fontColor = fontDark;
                if (barDark >= 0)        barColor = barDark;
                if (barBgDark >= 0)      barBackgroundColor = barBgDark;
                if (barBorderDark >= 0)  barBorderColor = barBorderDark;
                if (memGoodDark >= 0)    memoryGoodColor = memGoodDark;
                if (memWarnDark >= 0)    memoryWarnColor = memWarnDark;
                if (memLowDark >= 0)     memoryLowColor = memLowDark;
            } else {
                if (bgLight >= 0)        backgroundColor = bgLight;
                if (fontLight >= 0)      fontColor = fontLight;
                if (barLight >= 0)       barColor = barLight;
                if (barBgLight >= 0)     barBackgroundColor = barBgLight;
                if (barBorderLight >= 0) barBorderColor = barBorderLight;
                if (memGoodLight >= 0)   memoryGoodColor = memGoodLight;
                if (memWarnLight >= 0)   memoryWarnColor = memWarnLight;
                if (memLowLight >= 0)    memoryLowColor = memLowLight;
            }

            FMLLog.log.info("CoreSplashRenderer: loaded ModernSplash config (dark={})", dark);
        } catch (IOException e) {
            FMLLog.log.info("CoreSplashRenderer: could not read modern_splash.cfg, using defaults");
        }
    }

    private static String getString(String name, String def) {
        String value = config.getProperty(name, def);
        config.setProperty(name, value);
        return value;
    }

    private static boolean getBool(String name, boolean def) {
        return Boolean.parseBoolean(getString(name, Boolean.toString(def)));
    }

    private static int getInt(String name, int def) {
        return Integer.decode(getString(name, Integer.toString(def)));
    }

    private static int getHex(String name, int def) {
        return Integer.decode(getString(name, "0x" + Integer.toString(def, 16).toUpperCase()));
    }

    private static int bytesToMb(long bytes) {
        return (int) (bytes / 1024L / 1024L);
    }
}
