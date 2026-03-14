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
    public static final int BIT_OBJECT_SSBO  = 1 << 8;

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
        if ((key & BIT_OBJECT_SSBO) != 0)  defines.append("#define OBJECT_SSBO\n");

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
        com.github.gl46core.api.debug.RenderProfiler.INSTANCE.recordVariantCompiled();
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
        if ((key & BIT_OBJECT_SSBO) != 0)   sb.append("ssbo ");
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

// ── UBOs (3-way split, same layout for all variants) ──
layout(std140, binding = 0) uniform PerScene {
    vec4 uLight0Position;       // offset 0
    vec4 uLight0Diffuse;        // offset 16
    vec4 uLight1Position;       // offset 32
    vec4 uLight1Diffuse;        // offset 48
    vec4 uLightModelAmbient;    // offset 64
    vec4 uFogColor;             // offset 80
    float uFogDensity;          // offset 96
    float uFogStart;            // offset 100
    float uFogEnd;              // offset 104
    int uFogMode;               // offset 108
};

layout(std140, binding = 1) uniform PerObject {
    mat4 uModelViewProjection;  // offset 0
    mat4 uModelView;            // offset 64
};

layout(std140, binding = 2) uniform PerMaterial {
    float uAlphaRef;            // offset 0
    int uAlphaFunc;             // offset 4
    vec2 uGlobalLightMapCoord;  // offset 8
    int uLightMapEnabled;       // offset 16
    int uTextureEnabled;        // offset 20
    int uAlphaTestEnabled;      // offset 24
    int uFogEnabled;            // offset 28
    int uLightingEnabled;       // offset 32
    int uUseLightMapTexture;    // offset 36
    int uTexEnvMode;            // offset 40
    int uTexGenEnabled;         // offset 44
    vec4 uTexGenEyePlaneS;      // offset 48
    vec4 uTexGenEyePlaneT;      // offset 64
    vec4 uTexGenObjectPlaneS;   // offset 80
    vec4 uTexGenObjectPlaneT;   // offset 96
    int uTexGenSMode;           // offset 112
    int uTexGenTMode;           // offset 116
    int uClipPlaneEnabled;      // offset 120
    int _pad0;                  // offset 124
    vec4 uClipPlane[6];         // offset 128
};

// ── PerPass UBO (binding 3) — pass-specific context ──
layout(std140, binding = 3) uniform PerPass {
    int  gl46_passType;           // PassType ordinal
    int  gl46_passFlags;          // depth/blend/cull bitfield
    int  gl46_fogOverrideMode;    // 0=scene, 1=disabled, 2=custom
    int  gl46_shadowCascade;
    ivec2 gl46_targetSize;        // render target dimensions
    float gl46_exposure;
    float gl46_alphaRefOverride;
    int  gl46_renderLayerMask;
    int  gl46_mediumOverride;
    int  gl46_postEffectFlags;
    int  gl46_lightingMode;       // 0=full, 1=ambient-only, 2=unlit
    vec4 gl46_fogColorOverride;
    float gl46_dynamicLightScale;
    int  gl46_lightingPassFlags;
    int  gl46_maxDynamicLights;
    int  gl46_passPad1;
    vec4 gl46_ambientOverride;    // w=0 means use scene ambient
};

#ifdef OBJECT_SSBO
// ── Object SSBO: per-draw transforms indexed by gl_BaseInstance ──
struct ObjectData {
    mat4 mvp;   // model-view-projection
    mat4 mv;    // model-view
};
layout(std430, binding = 3) readonly buffer ObjectSSBO {
    ObjectData gl46_objects[];
};
#endif

// ── Varyings ──
out vec4 vColor;
out vec2 vTexCoord;
out vec2 vLightMap;
out vec3 vNormal;
out float vFogDist;
out vec3 vEyePos;

void main() {
    // Select transform source: SSBO (queued path) or UBO (immediate path)
#ifdef OBJECT_SSBO
    mat4 gl46_mvp = gl46_objects[gl_BaseInstance].mvp;
    mat4 gl46_mv  = gl46_objects[gl_BaseInstance].mv;
#else
    mat4 gl46_mvp = uModelViewProjection;
    mat4 gl46_mv  = uModelView;
#endif

    gl_Position = gl46_mvp * vec4(aPosition, 1.0);

    // Always compute eye-space position (needed for dynamic lights + fog)
    vec4 eyePos = gl46_mv * vec4(aPosition, 1.0);
    vEyePos = eyePos.xyz;

#ifdef NEED_NORMAL
    vec3 eyeNormal = mat3(gl46_mv) * aNormal;
    bool hasRealNormal = dot(aNormal, aNormal) > 0.0;
    vNormal = hasRealNormal ? eyeNormal : vec3(0.0, 0.0, 1.0);
#else
    vNormal = vec3(0.0, 0.0, 1.0);
#endif

    vec4 baseColor = aColor;

#ifdef LIGHTING_ENABLED
    // gl46_lightingMode: 0=full, 1=ambient-only, 2=unlit
    if (gl46_lightingMode != 2 && hasRealNormal) {
        vec3 n = normalize(eyeNormal);
        // Use pass ambient override when w > 0, otherwise scene ambient
        vec3 ambient = (gl46_ambientOverride.w > 0.0) ? gl46_ambientOverride.rgb : uLightModelAmbient.rgb;
        vec3 lc = ambient;
        if (gl46_lightingMode == 0) {
            // Full lighting: ambient + directional
            lc += uLight0Diffuse.rgb * max(0.0, dot(n, normalize(uLight0Position.xyz)));
            lc += uLight1Diffuse.rgb * max(0.0, dot(n, normalize(uLight1Position.xyz)));
        }
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
in vec3 vEyePos;

layout(binding = 0) uniform sampler2D uTexture;
layout(binding = 1) uniform sampler2D uLightMapTex;

layout(std140, binding = 0) uniform PerScene {
    vec4 uLight0Position;
    vec4 uLight0Diffuse;
    vec4 uLight1Position;
    vec4 uLight1Diffuse;
    vec4 uLightModelAmbient;
    vec4 uFogColor;
    float uFogDensity;
    float uFogStart;
    float uFogEnd;
    int uFogMode;
};

layout(std140, binding = 2) uniform PerMaterial {
    float uAlphaRef;
    int uAlphaFunc;
    vec2 uGlobalLightMapCoord;
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

// ── PerPass UBO (binding 3) — pass-specific context ──
layout(std140, binding = 3) uniform PerPass {
    int  gl46_passType;           // PassType ordinal
    int  gl46_passFlags;          // depth/blend/cull bitfield
    int  gl46_fogOverrideMode;    // 0=scene, 1=disabled, 2=custom
    int  gl46_shadowCascade;
    ivec2 gl46_targetSize;        // render target dimensions
    float gl46_exposure;
    float gl46_alphaRefOverride;
    int  gl46_renderLayerMask;
    int  gl46_mediumOverride;
    int  gl46_postEffectFlags;
    int  gl46_lightingMode;       // 0=full, 1=ambient-only, 2=unlit
    vec4 gl46_fogColorOverride;
    float gl46_dynamicLightScale;
    int  gl46_lightingPassFlags;
    int  gl46_maxDynamicLights;
    int  gl46_passPad1;
    vec4 gl46_ambientOverride;    // w=0 means use scene ambient
};

// ── SceneData UBO (binding 5) — extended scene context ──
layout(std140, binding = 5) uniform SceneData {
    mat4 gl46_viewMatrix;           // offset 0
    mat4 gl46_projectionMatrix;     // offset 64
    mat4 gl46_viewProjection;       // offset 128
    vec4 gl46_cameraPosition;       // xyz=pos, w=0
    vec4 gl46_sunDirection;         // xyz=dir, w=sunAngle
    vec4 gl46_moonDirection;        // xyz=dir, w=skylightStrength
    vec4 gl46_sceneFogColor;        // scene-level fog color
    vec4 gl46_ambientLight;         // rgb=ambient, a=0
    vec4 gl46_light0Pos;            // directional light 0
    vec4 gl46_light0Color;
    vec4 gl46_light1Pos;            // directional light 1
    vec4 gl46_light1Color;
    float gl46_sceneFogDensity;
    float gl46_sceneFogStart;
    float gl46_sceneFogEnd;
    int   gl46_sceneFogMode;
    float gl46_worldTime;
    float gl46_partialTicks;
    float gl46_rainStrength;
    float gl46_thunderStrength;
    ivec2 gl46_viewportSize;
    float gl46_nearPlane;
    float gl46_farPlane;
    float gl46_celestialAngle;
    float gl46_sunBrightness;
    int   gl46_frameIndex;
    int   gl46_lightingFlags;
    vec4  gl46_sunColor;            // rgb=sun, a=blockLightScale
    vec4  gl46_moonColor;           // rgb=moon, a=weatherDarken
    mat4  gl46_prevViewMatrix;
    mat4  gl46_prevProjection;
};

// ── Dynamic Light SSBO (binding 2, separate from UBO binding 2) ──
struct DynLight {
    vec4 positionAndRadius;   // xyz=eyeSpace, w=radius
    vec4 colorAndIntensity;   // rgb=color, a=intensity
    int  lightType;           // 0=point, 1=spot, 2=area
    int  shadowFlags;
    float falloffExponent;
    float spotAngle;
};
layout(std430, binding = 2) readonly buffer LightSSBO {
    int gl46_lightCount;
    int gl46_maxLights;
    int gl46_lightPad0;
    int gl46_lightPad1;
    DynLight gl46_lights[];
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

    // ── Dynamic light accumulation (eye-space, scaled by pass) ──
    if (gl46_lightCount > 0 && gl46_dynamicLightScale > 0.0 && gl46_lightingMode != 2) {
        int maxLights = (gl46_maxDynamicLights > 0) ? min(gl46_lightCount, gl46_maxDynamicLights) : gl46_lightCount;
        vec3 dynLight = vec3(0.0);
        for (int i = 0; i < maxLights; i++) {
            vec3 lPos = gl46_lights[i].positionAndRadius.xyz;
            float lRadius = gl46_lights[i].positionAndRadius.w;
            vec3 lColor = gl46_lights[i].colorAndIntensity.rgb;
            float lIntensity = gl46_lights[i].colorAndIntensity.a;
            float lFalloff = gl46_lights[i].falloffExponent;
            float dist = distance(lPos, vEyePos);
            if (dist < lRadius) {
                float atten = pow(max(1.0 - dist / lRadius, 0.0), lFalloff) * lIntensity;
                dynLight += lColor * atten;
            }
        }
        color.rgb = min(color.rgb * (1.0 + dynLight * gl46_dynamicLightScale), vec3(1.0));
    }

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
    // gl46_fogOverrideMode: 0=use scene fog, 1=disabled, 2=custom color
    if (gl46_fogOverrideMode != 1) {
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
        vec3 fogCol = (gl46_fogOverrideMode == 2) ? gl46_fogColorOverride.rgb : uFogColor.rgb;
        color.rgb = mix(fogCol, color.rgb, fogFactor);
    }
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
