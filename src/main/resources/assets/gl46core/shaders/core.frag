#version 460 core

in vec4 vColor;
in vec2 vTexCoord;
in vec2 vLightMap;
in vec3 vNormal;
in float vFogDist;

// Texture samplers with explicit bindings
layout(binding = 0) uniform sampler2D uTexture;
layout(binding = 1) uniform sampler2D uLightMapTex;

// Per-draw UBO — shared with vertex shader
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
    int _pad0;                  // offset 152
    int _pad1;                  // offset 156
};

layout(location = 0) out vec4 fragColor;   // colortex0: final lit color
layout(location = 1) out vec4 fragData1;   // colortex1: raw lightmap data for Iris

void main() {
    vec4 color = vColor;

    // Sample texture and apply TexEnv mode
    if (uTextureEnabled != 0) {
        vec4 texColor = texture(uTexture, vTexCoord);

        // GL_MODULATE=0x2100, GL_REPLACE=0x1E01, GL_DECAL=0x2101,
        // GL_BLEND=0x0BE2, GL_ADD=0x0104
        if (uTexEnvMode == 0x1E01) {
            // GL_REPLACE: output = texture color
            color = texColor;
        } else if (uTexEnvMode == 0x2101) {
            // GL_DECAL: blend texture over vertex color using texture alpha
            color.rgb = mix(color.rgb, texColor.rgb, texColor.a);
            // alpha unchanged
        } else if (uTexEnvMode == 0x0BE2) {
            // GL_BLEND: Cf*(1-Ct) + Cc*Ct  (Cc = texture env color, not implemented yet → use white)
            color.rgb = color.rgb * (1.0 - texColor.rgb) + texColor.rgb;
            color.a *= texColor.a;
        } else if (uTexEnvMode == 0x0104) {
            // GL_ADD: Cf + Ct, clamped
            color.rgb = min(color.rgb + texColor.rgb, vec3(1.0));
            color.a *= texColor.a;
        } else {
            // GL_MODULATE (0x2100) or default: multiply
            color *= texColor;
        }
    }

    // Lightmap — per-vertex UVs (terrain)
    if (uLightMapEnabled != 0) {
        vec4 lm = texture(uLightMapTex, vLightMap / 256.0);
        color.rgb *= lm.rgb;
    }
    // Lightmap — global coords (entities, no per-vertex UV but lightmap texture is active)
    else if (uUseLightMapTexture != 0) {
        vec4 lm = texture(uLightMapTex, uGlobalLightMapCoord / 256.0);
        if (uLightingEnabled != 0) {
            color.rgb *= min(lm.rgb + 0.05, vec3(1.0));
        } else {
            color.rgb *= lm.rgb;
        }
    }

    // Alpha test emulation
    if (uAlphaTestEnabled != 0) {
        bool pass = true;
        // GL_NEVER=512, GL_LESS=513, GL_EQUAL=514, GL_LEQUAL=515,
        // GL_GREATER=516, GL_NOTEQUAL=517, GL_GEQUAL=518, GL_ALWAYS=519
        if (uAlphaFunc == 512) pass = false;                    // NEVER
        else if (uAlphaFunc == 513) pass = color.a < uAlphaRef; // LESS
        else if (uAlphaFunc == 514) pass = color.a == uAlphaRef; // EQUAL
        else if (uAlphaFunc == 515) pass = color.a <= uAlphaRef; // LEQUAL
        else if (uAlphaFunc == 516) pass = color.a > uAlphaRef;  // GREATER
        else if (uAlphaFunc == 517) pass = color.a != uAlphaRef; // NOTEQUAL
        else if (uAlphaFunc == 518) pass = color.a >= uAlphaRef; // GEQUAL
        // 519 = ALWAYS → pass stays true

        if (!pass) discard;
    }

    // Fog emulation
    if (uFogEnabled != 0) {
        float fogFactor = 1.0;
        if (uFogMode == 9729) {
            // LINEAR
            fogFactor = clamp((uFogEnd - vFogDist) / (uFogEnd - uFogStart), 0.0, 1.0);
        } else if (uFogMode == 2048) {
            // EXP
            fogFactor = exp(-uFogDensity * vFogDist);
            fogFactor = clamp(fogFactor, 0.0, 1.0);
        } else if (uFogMode == 2049) {
            // EXP2
            float f = uFogDensity * vFogDist;
            fogFactor = exp(-f * f);
            fogFactor = clamp(fogFactor, 0.0, 1.0);
        }
        color.rgb = mix(uFogColor.rgb, color.rgb, fogFactor);
    }

    fragColor = color;

    // colortex1: raw lightmap data for Iris shader pack composite passes
    if (uLightMapEnabled != 0) {
        fragData1 = vec4(vLightMap.x / 256.0, vLightMap.y / 256.0, 0.0, 1.0);
    } else if (uUseLightMapTexture != 0) {
        fragData1 = vec4(uGlobalLightMapCoord.x / 256.0, uGlobalLightMapCoord.y / 256.0, 0.0, 1.0);
    } else {
        fragData1 = vec4(1.0, 1.0, 0.0, 1.0);
    }
}
