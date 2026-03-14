package com.github.gl46core.gl;

import com.github.gl46core.GL46Core;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Compiles and caches shader program variants based on GL state.
 *
 * Instead of a single uber-shader with runtime branching, each unique
 * combination of enabled features gets its own program where disabled
 * features are compiled out via preprocessor #ifdef.
 *
 * Variant key bits:
 *   0 — TEXTURE_ENABLED      (texture sampling)
 *   1 — ALPHA_TEST_ENABLED   (discard on alpha)
 *   2 — FOG_ENABLED          (fog blending)
 *   3 — LIGHTING_ENABLED     (per-vertex lighting)
 *   4 — LIGHTMAP_PER_VERTEX  (terrain per-vertex lightmap UVs)
 *   5 — LIGHTMAP_GLOBAL      (entity global lightmap coords)
 *   6 — TEXGEN_ENABLED       (texture coordinate generation)
 *   7 — CLIP_PLANES_ENABLED  (clip distance planes)
 */
public final class ShaderVariants {

    // Variant key bits
    public static final int BIT_TEXTURE      = 1 << 0;
    public static final int BIT_ALPHA_TEST   = 1 << 1;
    public static final int BIT_FOG          = 1 << 2;
    public static final int BIT_LIGHTING     = 1 << 3;
    public static final int BIT_LIGHTMAP_VTX = 1 << 4;
    public static final int BIT_LIGHTMAP_GLB = 1 << 5;
    public static final int BIT_TEXGEN       = 1 << 6;
    public static final int BIT_CLIP_PLANES  = 1 << 7;

    // program cache: variant key → GL program ID (0 = failed)
    private static final ConcurrentHashMap<Integer, Integer> cache = new ConcurrentHashMap<>();
    // set of all our program IDs for isOurProgram() checks
    private static final Set<Integer> allPrograms = ConcurrentHashMap.newKeySet();

    private ShaderVariants() {}

    /**
     * Compute the variant key from current GL state and format flags.
     */
    public static int computeKey(CoreStateTracker state, boolean hasLightMap) {
        int key = 0;
        if (state.isTexture2DEnabled(0))               key |= BIT_TEXTURE;
        if (state.isAlphaTestEnabled())                 key |= BIT_ALPHA_TEST;
        if (state.isFogEnabled())                       key |= BIT_FOG;
        if (state.isLightingEnabled() && !hasLightMap)  key |= BIT_LIGHTING;
        if (hasLightMap) {
            key |= BIT_LIGHTMAP_VTX;
        } else if (state.isTexture2DEnabled(1)) {
            key |= BIT_LIGHTMAP_GLB;
        }
        for (int i = 0; i < 4; i++) {
            if (state.isTexGenEnabled(i)) { key |= BIT_TEXGEN; break; }
        }
        for (int i = 0; i < 6; i++) {
            if (state.isClipPlaneEnabled(i)) { key |= BIT_CLIP_PLANES; break; }
        }
        return key;
    }

    /**
     * Get (or lazily compile) the shader program for the given variant key.
     * Returns 0 if compilation failed.
     */
    public static int getProgram(int key) {
        Integer cached = cache.get(key);
        if (cached != null) return cached;
        return compileVariant(key);
    }

    /**
     * Check if the given program ID belongs to any of our compiled variants.
     */
    public static boolean isOurProgram(int program) {
        return program != 0 && allPrograms.contains(program);
    }

    // ── Compilation ──

    private static synchronized int compileVariant(int key) {
        // Double-check after acquiring lock (another thread may have compiled it)
        Integer cached = cache.get(key);
        if (cached != null) return cached;

        StringBuilder defines = new StringBuilder("#version 460 core\n");
        if ((key & BIT_TEXTURE) != 0)      defines.append("#define TEXTURE_ENABLED\n");
        if ((key & BIT_ALPHA_TEST) != 0)   defines.append("#define ALPHA_TEST_ENABLED\n");
        if ((key & BIT_FOG) != 0)          defines.append("#define FOG_ENABLED\n");
        if ((key & BIT_LIGHTING) != 0)     defines.append("#define LIGHTING_ENABLED\n");
        if ((key & BIT_LIGHTMAP_VTX) != 0) defines.append("#define LIGHTMAP_PER_VERTEX\n");
        if ((key & BIT_LIGHTMAP_GLB) != 0) defines.append("#define LIGHTMAP_GLOBAL\n");
        if ((key & BIT_TEXGEN) != 0)       defines.append("#define TEXGEN_ENABLED\n");
        if ((key & BIT_CLIP_PLANES) != 0)  defines.append("#define CLIP_PLANES_ENABLED\n");

        String defs = defines.toString();
        String vertSrc = defs + VERT_TEMPLATE;
        String fragSrc = defs + FRAG_TEMPLATE;

        int vert = compile(GL20.GL_VERTEX_SHADER, vertSrc, key);
        int frag = compile(GL20.GL_FRAGMENT_SHADER, fragSrc, key);
        if (vert == 0 || frag == 0) {
            if (vert != 0) GL20.glDeleteShader(vert);
            if (frag != 0) GL20.glDeleteShader(frag);
            cache.put(key, 0);
            return 0;
        }

        int prog = GL20.glCreateProgram();
        GL20.glAttachShader(prog, vert);
        GL20.glAttachShader(prog, frag);
        GL20.glLinkProgram(prog);
        GL20.glDeleteShader(vert);
        GL20.glDeleteShader(frag);

        if (GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetProgramInfoLog(prog, 4096);
            GL46Core.LOGGER.error("Variant 0x{} link failed:\n{}", Integer.toHexString(key), log);
            GL20.glDeleteProgram(prog);
            cache.put(key, 0);
            return 0;
        }

        GL46Core.LOGGER.info("Compiled shader variant 0x{} ({})", Integer.toHexString(key), describeKey(key));
        cache.put(key, prog);
        allPrograms.add(prog);
        return prog;
    }

    static String describeKey(int key) {
        if (key == 0) return "flat-color";
        StringBuilder sb = new StringBuilder();
        if ((key & BIT_TEXTURE) != 0)      sb.append("tex ");
        if ((key & BIT_ALPHA_TEST) != 0)   sb.append("alpha ");
        if ((key & BIT_FOG) != 0)          sb.append("fog ");
        if ((key & BIT_LIGHTING) != 0)     sb.append("lit ");
        if ((key & BIT_LIGHTMAP_VTX) != 0) sb.append("lmVtx ");
        if ((key & BIT_LIGHTMAP_GLB) != 0) sb.append("lmGlb ");
        if ((key & BIT_TEXGEN) != 0)       sb.append("texgen ");
        if ((key & BIT_CLIP_PLANES) != 0)  sb.append("clip ");
        return sb.toString().trim();
    }

    private static int compile(int type, String src, int key) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, src);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shader, 4096);
            String typeName = type == GL20.GL_VERTEX_SHADER ? "vertex" : "fragment";
            GL46Core.LOGGER.error("{} shader compile failed for variant 0x{}:\n{}",
                    typeName, Integer.toHexString(key), log);
            GL20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  GLSL Templates  (no #version — prepended per variant with defines)
    //
    //  All variants share the same UBO layout declarations so they can
    //  reuse the same PerFrame and PerDraw buffer objects.
    //  Disabled features are compiled out by the preprocessor.
    // ═══════════════════════════════════════════════════════════════════

    private static final String VERT_TEMPLATE = """

// Derived feature flags
#if defined(LIGHTING_ENABLED) || defined(FOG_ENABLED) || defined(TEXGEN_ENABLED) || defined(CLIP_PLANES_ENABLED)
#define NEED_EYE_POS
#endif
#if defined(LIGHTING_ENABLED) || defined(TEXGEN_ENABLED)
#define NEED_NORMAL
#endif

// ── Attributes ──
layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec4 aColor;
layout(location = 2) in vec2 aTexCoord;
layout(location = 3) in vec2 aLightMap;
layout(location = 4) in vec3 aNormal;

// ── UBOs (same layout for all variants) ──
layout(std140, binding = 0) uniform PerFrame {
    mat4 uModelViewProjection;  // offset 0
    mat4 uModelView;            // offset 64
    vec4 uLight0Position;       // offset 128
    vec4 uLight0Diffuse;        // offset 144
    vec4 uLight1Position;       // offset 160
    vec4 uLight1Diffuse;        // offset 176
    vec4 uLightModelAmbient;    // offset 192
};

layout(std140, binding = 1) uniform PerDraw {
    vec4 uFogColor;             // offset 0
    float uAlphaRef;            // offset 16
    float uFogDensity;          // offset 20
    float uFogStart;            // offset 24
    float uFogEnd;              // offset 28
    vec2 uGlobalLightMapCoord;  // offset 32
    int uAlphaFunc;             // offset 40
    int uFogMode;               // offset 44
    int uLightMapEnabled;       // offset 48
    int uTextureEnabled;        // offset 52
    int uAlphaTestEnabled;      // offset 56
    int uFogEnabled;            // offset 60
    int uLightingEnabled;       // offset 64
    int uUseLightMapTexture;    // offset 68
    int uTexEnvMode;            // offset 72
    int uTexGenEnabled;         // offset 76
    vec4 uTexGenEyePlaneS;      // offset 80
    vec4 uTexGenEyePlaneT;      // offset 96
    vec4 uTexGenObjectPlaneS;   // offset 112
    vec4 uTexGenObjectPlaneT;   // offset 128
    int uTexGenSMode;           // offset 144
    int uTexGenTMode;           // offset 148
    int uClipPlaneEnabled;      // offset 152
    int _pad0;                  // offset 156
    vec4 uClipPlane[6];         // offset 160
};

// ── Varyings ──
out vec4 vColor;
out vec2 vTexCoord;
out vec2 vLightMap;
out vec3 vNormal;
out float vFogDist;

void main() {
    gl_Position = uModelViewProjection * vec4(aPosition, 1.0);

#ifdef NEED_EYE_POS
    vec4 eyePos = uModelView * vec4(aPosition, 1.0);
#endif

#ifdef NEED_NORMAL
    vec3 eyeNormal = mat3(uModelView) * aNormal;
    bool hasRealNormal = dot(aNormal, aNormal) > 0.0;
    vNormal = hasRealNormal ? eyeNormal : vec3(0.0, 0.0, 1.0);
#else
    vNormal = vec3(0.0, 0.0, 1.0);
#endif

    vec4 baseColor = aColor;

#ifdef LIGHTING_ENABLED
    if (hasRealNormal) {
        vec3 n = normalize(eyeNormal);
        vec3 lc = uLightModelAmbient.rgb;
        lc += uLight0Diffuse.rgb * max(0.0, dot(n, normalize(uLight0Position.xyz)));
        lc += uLight1Diffuse.rgb * max(0.0, dot(n, normalize(uLight1Position.xyz)));
        baseColor.rgb *= clamp(lc, 0.0, 1.0);
    }
#endif

    vColor = baseColor;

#ifdef TEXTURE_ENABLED
    vec2 tc = aTexCoord;
  #ifdef TEXGEN_ENABLED
    vec4 objPos = vec4(aPosition, 1.0);
    // TexGen S
    if ((uTexGenEnabled & 1) != 0) {
        if (uTexGenSMode == 0x2401) {
            tc.s = dot(objPos, uTexGenObjectPlaneS);
        } else if (uTexGenSMode == 0x2400) {
            tc.s = dot(eyePos, uTexGenEyePlaneS);
        } else if (uTexGenSMode == 0x2402) {
            vec3 r = reflect(-normalize(eyePos.xyz), normalize(hasRealNormal ? eyeNormal : vec3(0,0,1)));
            float m = 2.0 * sqrt(r.x*r.x + r.y*r.y + (r.z+1.0)*(r.z+1.0));
            tc.s = r.x / m + 0.5;
        }
    }
    // TexGen T
    if ((uTexGenEnabled & 2) != 0) {
        if (uTexGenTMode == 0x2401) {
            tc.t = dot(objPos, uTexGenObjectPlaneT);
        } else if (uTexGenTMode == 0x2400) {
            tc.t = dot(eyePos, uTexGenEyePlaneT);
        } else if (uTexGenTMode == 0x2402) {
            vec3 r = reflect(-normalize(eyePos.xyz), normalize(hasRealNormal ? eyeNormal : vec3(0,0,1)));
            float m = 2.0 * sqrt(r.x*r.x + r.y*r.y + (r.z+1.0)*(r.z+1.0));
            tc.t = r.y / m + 0.5;
        }
    }
  #endif
    vTexCoord = tc;
#else
    vTexCoord = vec2(0.0);
#endif

    vLightMap = aLightMap;

#ifdef FOG_ENABLED
    vFogDist = length(eyePos.xyz);
#else
    vFogDist = 0.0;
#endif

#ifdef CLIP_PLANES_ENABLED
    for (int i = 0; i < 6; i++) {
        if ((uClipPlaneEnabled & (1 << i)) != 0) {
            gl_ClipDistance[i] = dot(uClipPlane[i], eyePos);
        } else {
            gl_ClipDistance[i] = 1.0;
        }
    }
#else
    for (int i = 0; i < 6; i++) gl_ClipDistance[i] = 1.0;
#endif
}
""";

    private static final String FRAG_TEMPLATE = """

in vec4 vColor;
in vec2 vTexCoord;
in vec2 vLightMap;
in vec3 vNormal;
in float vFogDist;

layout(binding = 0) uniform sampler2D uTexture;
layout(binding = 1) uniform sampler2D uLightMapTex;

layout(std140, binding = 1) uniform PerDraw {
    vec4 uFogColor;
    float uAlphaRef;
    float uFogDensity;
    float uFogStart;
    float uFogEnd;
    vec2 uGlobalLightMapCoord;
    int uAlphaFunc;
    int uFogMode;
    int uLightMapEnabled;
    int uTextureEnabled;
    int uAlphaTestEnabled;
    int uFogEnabled;
    int uLightingEnabled;
    int uUseLightMapTexture;
    int uTexEnvMode;
    int uTexGenEnabled;
    vec4 uTexGenEyePlaneS;
    vec4 uTexGenEyePlaneT;
    vec4 uTexGenObjectPlaneS;
    vec4 uTexGenObjectPlaneT;
    int uTexGenSMode;
    int uTexGenTMode;
    int uClipPlaneEnabled;
    int _pad0;
    vec4 uClipPlane[6];
};

layout(location = 0) out vec4 fragColor;
layout(location = 1) out vec4 fragData1;

void main() {
    vec4 color = vColor;

#ifdef TEXTURE_ENABLED
    vec4 texColor = texture(uTexture, vTexCoord);
    // TexEnv: GL_MODULATE=0x2100, GL_REPLACE=0x1E01, GL_DECAL=0x2101,
    //         GL_BLEND=0x0BE2, GL_ADD=0x0104
    if (uTexEnvMode == 0x1E01) {
        color = texColor;
    } else if (uTexEnvMode == 0x2101) {
        color.rgb = mix(color.rgb, texColor.rgb, texColor.a);
    } else if (uTexEnvMode == 0x0BE2) {
        color.rgb = color.rgb * (1.0 - texColor.rgb) + texColor.rgb;
        color.a *= texColor.a;
    } else if (uTexEnvMode == 0x0104) {
        color.rgb = min(color.rgb + texColor.rgb, vec3(1.0));
        color.a *= texColor.a;
    } else {
        color *= texColor;
    }
#endif

#ifdef LIGHTMAP_PER_VERTEX
    color.rgb *= texture(uLightMapTex, vLightMap / 256.0).rgb;
#elif defined(LIGHTMAP_GLOBAL)
    vec4 lm = texture(uLightMapTex, uGlobalLightMapCoord / 256.0);
  #ifdef LIGHTING_ENABLED
    color.rgb *= min(lm.rgb + 0.05, vec3(1.0));
  #else
    color.rgb *= lm.rgb;
  #endif
#endif

#ifdef ALPHA_TEST_ENABLED
    // GL_NEVER=512, GL_LESS=513, GL_EQUAL=514, GL_LEQUAL=515,
    // GL_GREATER=516, GL_NOTEQUAL=517, GL_GEQUAL=518, GL_ALWAYS=519
    bool pass = true;
    if (uAlphaFunc == 512) pass = false;
    else if (uAlphaFunc == 513) pass = color.a < uAlphaRef;
    else if (uAlphaFunc == 514) pass = color.a == uAlphaRef;
    else if (uAlphaFunc == 515) pass = color.a <= uAlphaRef;
    else if (uAlphaFunc == 516) pass = color.a > uAlphaRef;
    else if (uAlphaFunc == 517) pass = color.a != uAlphaRef;
    else if (uAlphaFunc == 518) pass = color.a >= uAlphaRef;
    if (!pass) discard;
#endif

#ifdef FOG_ENABLED
    float fogFactor = 1.0;
    if (uFogMode == 9729) {
        // GL_LINEAR
        fogFactor = clamp((uFogEnd - vFogDist) / (uFogEnd - uFogStart), 0.0, 1.0);
    } else if (uFogMode == 2048) {
        // GL_EXP
        fogFactor = clamp(exp(-uFogDensity * vFogDist), 0.0, 1.0);
    } else if (uFogMode == 2049) {
        // GL_EXP2
        float f = uFogDensity * vFogDist;
        fogFactor = clamp(exp(-f * f), 0.0, 1.0);
    }
    color.rgb = mix(uFogColor.rgb, color.rgb, fogFactor);
#endif

    fragColor = color;

    // colortex1: raw lightmap data for Iris shader pack composite passes
#ifdef LIGHTMAP_PER_VERTEX
    fragData1 = vec4(vLightMap / 256.0, 0.0, 1.0);
#elif defined(LIGHTMAP_GLOBAL)
    fragData1 = vec4(uGlobalLightMapCoord / 256.0, 0.0, 1.0);
#else
    fragData1 = vec4(1.0, 1.0, 0.0, 1.0);
#endif
}
""";
}
