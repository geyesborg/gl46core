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
// Format flags (hasColor/hasTexCoord/hasNormal) are handled via default vertex
// attributes (glVertexAttrib*) instead of UBO fields — avoids shader branching.
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
    int _pad0;                  // offset 72
    int _pad1;                  // offset 76
};

out vec4 vColor;
out vec2 vTexCoord;
out vec2 vLightMap;
out vec3 vNormal;
out float vFogDist;

void main() {
    gl_Position = uModelViewProjection * vec4(aPosition, 1.0);

    // Color always comes from the attribute. When vertex color is not in the
    // buffer, the draw handler sets glVertexAttrib4f to the current glColor4f.
    vec4 baseColor = aColor;

    // Normal: when attribute is disabled, default is (0,0,0). Use dot product
    // to detect real normals without a UBO flag.
    vec3 eyeNormal = mat3(uModelView) * aNormal;
    bool hasRealNormal = dot(aNormal, aNormal) > 0.0;
    vNormal = hasRealNormal ? eyeNormal : vec3(0.0, 0.0, 1.0);

    // Apply fixed-function lighting only when normals are present and
    // directional lighting is enabled (skip for lightmapped geometry).
    if (hasRealNormal && uLightingEnabled != 0 && uLightMapEnabled == 0) {
        vec3 normEyeNormal = normalize(eyeNormal);
        vec3 lightColor = uLightModelAmbient.rgb;

        vec3 lightDir0 = normalize(uLight0Position.xyz);
        float NdotL0 = max(0.0, dot(normEyeNormal, lightDir0));
        lightColor += uLight0Diffuse.rgb * NdotL0;

        vec3 lightDir1 = normalize(uLight1Position.xyz);
        float NdotL1 = max(0.0, dot(normEyeNormal, lightDir1));
        lightColor += uLight1Diffuse.rgb * NdotL1;

        baseColor.rgb *= clamp(lightColor, 0.0, 1.0);
    }

    vColor = baseColor;

    // TexCoord always from attribute. Default is (0,0) when not in buffer.
    vTexCoord = aTexCoord;

    // Lightmap always passed through; fragment shader checks uLightMapEnabled.
    vLightMap = aLightMap;

    // Fog distance (eye-space Z)
    vec4 eyePos = uModelView * vec4(aPosition, 1.0);
    vFogDist = length(eyePos.xyz);
}
