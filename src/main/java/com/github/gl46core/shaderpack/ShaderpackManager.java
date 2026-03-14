package com.github.gl46core.shaderpack;

import com.github.gl46core.GL46Core;
import com.github.gl46core.api.render.PassType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Manages the active shaderpack — loading, compilation, program
 * selection per pass, and lifecycle.
 *
 * Singleton that bridges the shaderpack system to the rendering pipeline.
 * When a shaderpack is active, {@link #getProgramForPass(PassType)} returns
 * the compiled program for each rendering stage. When no shaderpack is
 * active, it returns null and the built-in shader variants are used.
 *
 * Shaderpacks are loaded from the {@code shaderpacks/} directory in the
 * game directory. The active shaderpack is selected by name.
 *
 * Usage:
 * <pre>
 *   ShaderpackManager.INSTANCE.loadShaderpack("BSL");
 *   // During rendering:
 *   ShaderpackProgram prog = ShaderpackManager.INSTANCE.getProgramForPass(PassType.TERRAIN_OPAQUE);
 *   if (prog != null) {
 *       prog.bind();
 *       // draw...
 *   }
 * </pre>
 */
public final class ShaderpackManager {

    public static final ShaderpackManager INSTANCE = new ShaderpackManager();

    // Compiled programs by program name (e.g. "gbuffers_terrain" → program)
    private final Map<String, ShaderpackProgram> programs = new LinkedHashMap<>();

    // Current shaderpack source
    private ShaderpackSource currentSource;
    private String currentPackName;
    private boolean active;

    // Stats
    private int totalPrograms;
    private int totalUniforms;

    private ShaderpackManager() {}

    /**
     * Load and compile a shaderpack by name.
     * Looks in shaderpacks/ directory for a .zip or folder.
     *
     * @param name shaderpack name (without .zip extension)
     * @return true if loaded and at least one program compiled
     */
    public boolean loadShaderpack(String name) {
        unload();

        Path gameDir = Paths.get(".");
        Path shaderpacksDir = gameDir.resolve("shaderpacks");

        // Try .zip first, then folder
        Path zipPath = shaderpacksDir.resolve(name + ".zip");
        Path folderPath = shaderpacksDir.resolve(name);

        ShaderpackSource source = null;
        if (Files.exists(zipPath)) {
            source = ShaderpackLoader.load(zipPath);
        } else if (Files.isDirectory(folderPath)) {
            source = ShaderpackLoader.load(folderPath);
        } else {
            GL46Core.LOGGER.error("Shaderpack not found: {} (tried {} and {})",
                    name, zipPath, folderPath);
            return false;
        }

        if (source == null || source.getSourceCount() == 0) {
            GL46Core.LOGGER.error("Shaderpack '{}' loaded but contains no shader files", name);
            return false;
        }

        currentSource = source;
        currentPackName = name;

        // Compile all discovered programs
        compileAll();

        if (programs.isEmpty()) {
            GL46Core.LOGGER.error("Shaderpack '{}' failed to compile any programs", name);
            unload();
            return false;
        }

        active = true;
        GL46Core.LOGGER.info("Shaderpack '{}' active: {} programs, {} total uniforms",
                name, totalPrograms, totalUniforms);
        return true;
    }

    /**
     * Load a shaderpack from an explicit path (zip or folder).
     */
    public boolean loadShaderpackFromPath(Path path) {
        unload();

        ShaderpackSource source = ShaderpackLoader.load(path);
        if (source == null || source.getSourceCount() == 0) {
            GL46Core.LOGGER.error("Failed to load shaderpack from: {}", path);
            return false;
        }

        currentSource = source;
        currentPackName = source.getName();

        compileAll();

        if (programs.isEmpty()) {
            GL46Core.LOGGER.error("Shaderpack from '{}' failed to compile any programs", path);
            unload();
            return false;
        }

        active = true;
        GL46Core.LOGGER.info("Shaderpack '{}' active: {} programs, {} total uniforms",
                currentPackName, totalPrograms, totalUniforms);
        return true;
    }

    /**
     * Unload the current shaderpack and destroy all compiled programs.
     */
    public void unload() {
        for (ShaderpackProgram prog : programs.values()) {
            prog.destroy();
        }
        programs.clear();
        currentSource = null;
        currentPackName = null;
        active = false;
        totalPrograms = 0;
        totalUniforms = 0;
    }

    /**
     * Get the compiled program for a given pass type.
     * Returns null if no shaderpack is active or the pass has no program.
     */
    public ShaderpackProgram getProgramForPass(PassType passType) {
        if (!active) return null;

        String programName = ShaderpackFormat.getProgramForPass(passType);
        if (programName == null) return null;

        // Direct lookup
        ShaderpackProgram prog = programs.get(programName);
        if (prog != null) return prog;

        // Try fallback chain
        for (String fallback : ShaderpackFormat.getFallbackChain(programName)) {
            prog = programs.get(fallback);
            if (prog != null) return prog;
        }
        return null;
    }

    /**
     * Get a compiled program by name.
     */
    public ShaderpackProgram getProgram(String programName) {
        return programs.get(programName);
    }

    // ── State ──

    public boolean isActive()         { return active; }
    public String  getPackName()      { return currentPackName; }
    public int     getProgramCount()  { return totalPrograms; }
    public int     getTotalUniforms() { return totalUniforms; }

    public ShaderpackSource getCurrentSource() { return currentSource; }

    // ═══════════════════════════════════════════════════════════════════
    // Internal — compilation
    // ═══════════════════════════════════════════════════════════════════

    private void compileAll() {
        totalPrograms = 0;
        totalUniforms = 0;

        // All known program names to try
        String[] programNames = {
                ShaderpackFormat.GBUFFERS_BASIC,
                ShaderpackFormat.GBUFFERS_TEXTURED,
                ShaderpackFormat.GBUFFERS_TEXTURED_LIT,
                ShaderpackFormat.GBUFFERS_TERRAIN,
                ShaderpackFormat.GBUFFERS_ENTITIES,
                ShaderpackFormat.GBUFFERS_BLOCK,
                ShaderpackFormat.GBUFFERS_HAND,
                ShaderpackFormat.GBUFFERS_WATER,
                ShaderpackFormat.GBUFFERS_WEATHER,
                ShaderpackFormat.GBUFFERS_SKYBASIC,
                ShaderpackFormat.GBUFFERS_SKYTEXTURED,
                ShaderpackFormat.SHADOW,
                ShaderpackFormat.COMPOSITE,
                ShaderpackFormat.COMPOSITE1,
                ShaderpackFormat.COMPOSITE2,
                ShaderpackFormat.COMPOSITE3,
                ShaderpackFormat.COMPOSITE4,
                ShaderpackFormat.COMPOSITE5,
                ShaderpackFormat.COMPOSITE6,
                ShaderpackFormat.COMPOSITE7,
                ShaderpackFormat.DEFERRED,
                ShaderpackFormat.FINAL,
        };

        for (String name : programNames) {
            if (!currentSource.hasProgram(name)) continue;

            String vsh = currentSource.getVertexSource(name);
            String fsh = currentSource.getFragmentSource(name);

            if (vsh == null || fsh == null) {
                GL46Core.LOGGER.warn("Shaderpack program '{}' missing {} shader, skipping",
                        name, vsh == null ? "vertex" : "fragment");
                continue;
            }

            ShaderpackProgram prog = new ShaderpackProgram(name);
            if (prog.compile(vsh, fsh)) {
                programs.put(name, prog);
                totalPrograms++;
                totalUniforms += prog.getActiveUniforms();
            }
        }
    }
}
