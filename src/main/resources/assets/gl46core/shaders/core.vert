#version 460 core

// Vertex attributes with explicit locations
layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec4 aColor;
layout(location = 2) in vec2 aTexCoord;
layout(location = 3) in vec2 aLightMap;
layout(location = 4) in vec3 aNormal;

// Per-frame UBO — matrices and lighting (changes once or few times per frame)
layout(std140, binding = 0) uniform PerFrame {
    mat4 uModelViewProjection;  // offset 0
    mat4 uModelView;            // offset 64
    vec4 uLight0Position;       // offset 128
    vec4 uLight0Diffuse;        // offset 144
    vec4 uLight1Position;       // offset 160
    vec4 uLight1Diffuse;        // offset 176
    vec4 uLightModelAmbient;    // offset 192 (vec3 padded to vec4 for std140)
};

// Per-draw UBO — state that changes between draw calls
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
    int _pad0;                  // offset 100 (padding to 16-byte alignment)
    int _pad1;                  // offset 104
    int _pad2;                  // offset 108
};

out vec4 vColor;
out vec2 vTexCoord;
out vec2 vLightMap;
out vec3 vNormal;
out float vFogDist;

void main() {
    gl_Position = uModelViewProjection * vec4(aPosition, 1.0);

    // Color: use vertex color if present, otherwise use glColor4f uniform
    vec4 baseColor;
    if (uHasColor != 0) {
        baseColor = aColor;
    } else {
        baseColor = uColor;
    }

    // Apply fixed-function lighting only when no lightmap is present.
    // In-world entities use lightmap for brightness; GUI/inventory entities need directional lights.
    if (uLightingEnabled != 0 && uHasNormal != 0 && uLightMapEnabled == 0) {
        vec3 eyeNormal = normalize(mat3(uModelView) * aNormal);
        vec3 lightColor = uLightModelAmbient.rgb;

        // Light 0
        vec3 lightDir0 = normalize(uLight0Position.xyz);
        float NdotL0 = max(0.0, dot(eyeNormal, lightDir0));
        lightColor += uLight0Diffuse.rgb * NdotL0;

        // Light 1
        vec3 lightDir1 = normalize(uLight1Position.xyz);
        float NdotL1 = max(0.0, dot(eyeNormal, lightDir1));
        lightColor += uLight1Diffuse.rgb * NdotL1;

        baseColor.rgb *= clamp(lightColor, 0.0, 1.0);
    }

    vColor = baseColor;

    // Texcoord
    vTexCoord = (uHasTexCoord != 0) ? aTexCoord : vec2(0.0);

    // Lightmap
    vLightMap = aLightMap;

    // Normal
    if (uHasNormal != 0) {
        vNormal = mat3(uModelView) * aNormal;
    } else {
        vNormal = vec3(0.0, 0.0, 1.0);
    }

    // Fog distance (eye-space Z)
    vec4 eyePos = uModelView * vec4(aPosition, 1.0);
    vFogDist = length(eyePos.xyz);
}
