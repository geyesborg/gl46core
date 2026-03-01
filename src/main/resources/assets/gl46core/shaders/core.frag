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
    vec4 uColor;                // offset 0
    vec4 uFogColor;             // offset 16
    float uAlphaRef;            // offset 32
    float uFogDensity;          // offset 36
    float uFogStart;            // offset 40
    float uFogEnd;              // offset 44
    vec2 uGlobalLightMapCoord;  // offset 48
    int uAlphaFunc;             // offset 56
    int uFogMode;               // offset 60
    int uHasColor;              // offset 64
    int uHasTexCoord;           // offset 68
    int uHasNormal;             // offset 72
    int uLightMapEnabled;       // offset 76
    int uTextureEnabled;        // offset 80
    int uAlphaTestEnabled;      // offset 84
    int uFogEnabled;            // offset 88
    int uLightingEnabled;       // offset 92
    int uUseLightMapTexture;    // offset 96
    int _pad0;                  // offset 100
    int _pad1;                  // offset 104
    int _pad2;                  // offset 108
};

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 color = vColor;

    // Sample texture if enabled
    if (uTextureEnabled != 0) {
        vec4 texColor = texture(uTexture, vTexCoord);
        color *= texColor;
    }

    // Lightmap — per-vertex UVs (terrain)
    if (uLightMapEnabled != 0) {
        // Lightmap UVs are shorts scaled to 0-65535, convert to 0-1 for sampling
        vec4 lm = texture(uLightMapTex, vLightMap / 256.0);
        color.rgb *= lm.rgb;
    }
    // Lightmap — global coords (entities, no per-vertex UV but lightmap texture is active)
    else if (uUseLightMapTexture != 0) {
        vec4 lm = texture(uLightMapTex, uGlobalLightMapCoord / 256.0);
        color.rgb *= lm.rgb;
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
}
