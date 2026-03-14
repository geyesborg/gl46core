package com.github.gl46core.shaderpack;

import com.github.gl46core.api.render.PassType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Defines the OptiFine/Iris shaderpack file format conventions.
 *
 * A shaderpack is a .zip file (or folder) containing a {@code shaders/}
 * directory with GLSL source files named by rendering stage. This class
 * maps those file names to our internal {@link PassType} system.
 *
 * File structure:
 * <pre>
 *   shaders/
 *     shaders.properties          (optional config)
 *     gbuffers_basic.vsh/.fsh     (basic geometry — no texture)
 *     gbuffers_textured.vsh/.fsh  (textured geometry)
 *     gbuffers_textured_lit.vsh/.fsh (textured + lit — particles, etc.)
 *     gbuffers_terrain.vsh/.fsh   (terrain blocks)
 *     gbuffers_entities.vsh/.fsh  (entity models)
 *     gbuffers_block.vsh/.fsh     (block entities / tile entities)
 *     gbuffers_hand.vsh/.fsh      (first-person hand)
 *     gbuffers_water.vsh/.fsh     (translucent water/ice)
 *     gbuffers_weather.vsh/.fsh   (rain/snow particles)
 *     gbuffers_skybasic.vsh/.fsh  (sky color gradient)
 *     gbuffers_skytextured.vsh/.fsh (sun/moon/stars)
 *     shadow.vsh/.fsh             (shadow map pass)
 *     composite.vsh/.fsh          (post-process pass 0)
 *     composite1.vsh/.fsh         (post-process pass 1)
 *     ...
 *     composite7.vsh/.fsh         (post-process pass 7)
 *     final.vsh/.fsh              (final output to screen)
 *     deferred.vsh/.fsh           (deferred lighting — Iris extension)
 *     deferred1-15.vsh/.fsh       (additional deferred passes)
 * </pre>
 *
 * Fallback chain (if a program isn't found):
 *   gbuffers_weather    → gbuffers_textured_lit → gbuffers_textured → gbuffers_basic
 *   gbuffers_hand       → gbuffers_textured_lit → gbuffers_textured → gbuffers_basic
 *   gbuffers_entities   → gbuffers_textured_lit → gbuffers_textured → gbuffers_basic
 *   gbuffers_block      → gbuffers_terrain      → gbuffers_textured_lit → gbuffers_textured → gbuffers_basic
 *   gbuffers_water      → gbuffers_terrain      → gbuffers_textured_lit → gbuffers_textured → gbuffers_basic
 *   gbuffers_skybasic   → gbuffers_basic
 *   gbuffers_skytextured → gbuffers_textured    → gbuffers_basic
 */
public final class ShaderpackFormat {

    /** Shaders directory within the zip/folder. */
    public static final String SHADERS_DIR = "shaders/";

    /** Properties file for shaderpack configuration. */
    public static final String PROPERTIES_FILE = "shaders/shaders.properties";

    // ═══════════════════════════════════════════════════════════════════
    // Program Names
    // ═══════════════════════════════════════════════════════════════════

    // G-buffer programs
    public static final String GBUFFERS_BASIC         = "gbuffers_basic";
    public static final String GBUFFERS_TEXTURED      = "gbuffers_textured";
    public static final String GBUFFERS_TEXTURED_LIT  = "gbuffers_textured_lit";
    public static final String GBUFFERS_TERRAIN       = "gbuffers_terrain";
    public static final String GBUFFERS_ENTITIES      = "gbuffers_entities";
    public static final String GBUFFERS_BLOCK         = "gbuffers_block";
    public static final String GBUFFERS_HAND          = "gbuffers_hand";
    public static final String GBUFFERS_WATER         = "gbuffers_water";
    public static final String GBUFFERS_WEATHER       = "gbuffers_weather";
    public static final String GBUFFERS_SKYBASIC      = "gbuffers_skybasic";
    public static final String GBUFFERS_SKYTEXTURED   = "gbuffers_skytextured";

    // Shadow program
    public static final String SHADOW = "shadow";

    // Composite programs (post-processing chain)
    public static final String COMPOSITE  = "composite";
    public static final String COMPOSITE1 = "composite1";
    public static final String COMPOSITE2 = "composite2";
    public static final String COMPOSITE3 = "composite3";
    public static final String COMPOSITE4 = "composite4";
    public static final String COMPOSITE5 = "composite5";
    public static final String COMPOSITE6 = "composite6";
    public static final String COMPOSITE7 = "composite7";

    // Deferred programs (Iris extension)
    public static final String DEFERRED = "deferred";

    // Final output program
    public static final String FINAL = "final";

    // ═══════════════════════════════════════════════════════════════════
    // PassType ↔ Program Mapping
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Map from our internal PassType to the shaderpack program name.
     * Multiple PassTypes can map to the same program (e.g. TERRAIN_OPAQUE
     * and TERRAIN_CUTOUT both use gbuffers_terrain).
     */
    private static final Map<PassType, String> PASS_TO_PROGRAM;

    static {
        Map<PassType, String> m = new LinkedHashMap<>();

        // Shadow
        m.put(PassType.SHADOW_OPAQUE, SHADOW);

        // Sky
        m.put(PassType.SKY, GBUFFERS_SKYBASIC);

        // Terrain — all sub-passes use gbuffers_terrain
        m.put(PassType.TERRAIN_OPAQUE, GBUFFERS_TERRAIN);
        m.put(PassType.TERRAIN_CUTOUT, GBUFFERS_TERRAIN);
        m.put(PassType.TERRAIN_TRANSLUCENT, GBUFFERS_WATER);

        // Entities — all sub-passes use gbuffers_entities
        m.put(PassType.ENTITY_OPAQUE, GBUFFERS_ENTITIES);
        m.put(PassType.ENTITY_TRANSLUCENT, GBUFFERS_ENTITIES);

        // Block entities
        m.put(PassType.BLOCK_ENTITY, GBUFFERS_BLOCK);

        // Water / translucent terrain
        m.put(PassType.WATER, GBUFFERS_WATER);

        // Particles
        m.put(PassType.PARTICLES, GBUFFERS_TEXTURED_LIT);

        // Weather
        m.put(PassType.WEATHER, GBUFFERS_WEATHER);

        // Hand
        m.put(PassType.HAND, GBUFFERS_HAND);

        // Outline
        m.put(PassType.OUTLINE, GBUFFERS_BASIC);

        // Post-processing
        m.put(PassType.POST_CHAIN, COMPOSITE);

        // UI / Debug — no shaderpack program (use built-in)
        m.put(PassType.UI, null);
        m.put(PassType.DEBUG_OVERLAY, null);

        PASS_TO_PROGRAM = Collections.unmodifiableMap(m);
    }

    /**
     * Get the shaderpack program name for a given PassType.
     * Returns null if the pass has no shaderpack program (e.g. UI).
     */
    public static String getProgramForPass(PassType type) {
        return PASS_TO_PROGRAM.get(type);
    }

    /**
     * Get the full map of PassType → program name.
     */
    public static Map<PassType, String> getPassProgramMap() {
        return PASS_TO_PROGRAM;
    }

    // ═══════════════════════════════════════════════════════════════════
    // Fallback Chains
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Fallback chains per program. If the primary isn't found, try
     * each fallback in order until one exists.
     */
    private static final Map<String, String[]> FALLBACK_CHAINS;

    static {
        Map<String, String[]> f = new LinkedHashMap<>();
        f.put(GBUFFERS_WEATHER,      new String[]{GBUFFERS_TEXTURED_LIT, GBUFFERS_TEXTURED, GBUFFERS_BASIC});
        f.put(GBUFFERS_HAND,         new String[]{GBUFFERS_TEXTURED_LIT, GBUFFERS_TEXTURED, GBUFFERS_BASIC});
        f.put(GBUFFERS_ENTITIES,     new String[]{GBUFFERS_TEXTURED_LIT, GBUFFERS_TEXTURED, GBUFFERS_BASIC});
        f.put(GBUFFERS_BLOCK,        new String[]{GBUFFERS_TERRAIN, GBUFFERS_TEXTURED_LIT, GBUFFERS_TEXTURED, GBUFFERS_BASIC});
        f.put(GBUFFERS_WATER,        new String[]{GBUFFERS_TERRAIN, GBUFFERS_TEXTURED_LIT, GBUFFERS_TEXTURED, GBUFFERS_BASIC});
        f.put(GBUFFERS_TERRAIN,      new String[]{GBUFFERS_TEXTURED_LIT, GBUFFERS_TEXTURED, GBUFFERS_BASIC});
        f.put(GBUFFERS_TEXTURED_LIT, new String[]{GBUFFERS_TEXTURED, GBUFFERS_BASIC});
        f.put(GBUFFERS_TEXTURED,     new String[]{GBUFFERS_BASIC});
        f.put(GBUFFERS_SKYBASIC,     new String[]{GBUFFERS_BASIC});
        f.put(GBUFFERS_SKYTEXTURED,  new String[]{GBUFFERS_TEXTURED, GBUFFERS_BASIC});
        FALLBACK_CHAINS = Collections.unmodifiableMap(f);
    }

    /**
     * Get the fallback chain for a program name.
     * Returns empty array if there are no fallbacks.
     */
    public static String[] getFallbackChain(String programName) {
        String[] chain = FALLBACK_CHAINS.get(programName);
        return chain != null ? chain : new String[0];
    }

    // ═══════════════════════════════════════════════════════════════════
    // File extensions
    // ═══════════════════════════════════════════════════════════════════

    public static final String VERTEX_EXT   = ".vsh";
    public static final String FRAGMENT_EXT = ".fsh";
    public static final String GEOMETRY_EXT = ".gsh";
    public static final String COMPUTE_EXT  = ".csh";

    /**
     * Build the full path for a vertex shader within the shaders dir.
     */
    public static String vertexPath(String programName) {
        return SHADERS_DIR + programName + VERTEX_EXT;
    }

    /**
     * Build the full path for a fragment shader within the shaders dir.
     */
    public static String fragmentPath(String programName) {
        return SHADERS_DIR + programName + FRAGMENT_EXT;
    }

    private ShaderpackFormat() {}
}
