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
    int uTexEnvMode;            // offset 72
    int uTexGenEnabled;         // offset 76 (bitmask: bit0=S, bit1=T)
    vec4 uTexGenEyePlaneS;      // offset 80
    vec4 uTexGenEyePlaneT;      // offset 96
    vec4 uTexGenObjectPlaneS;   // offset 112
    vec4 uTexGenObjectPlaneT;   // offset 128
    int uTexGenSMode;           // offset 144
    int uTexGenTMode;           // offset 148
    int uClipPlaneEnabled;      // offset 152 (bitmask)
    int _pad0;                  // offset 156
    vec4 uClipPlane[6];         // offset 160 (6 * 16 = 96 bytes)
};

out vec4 vColor;
out vec2 vTexCoord;
out vec2 vLightMap;
out vec3 vNormal;
out float vFogDist;
void main() {
    gl_Position = uModelViewProjection * vec4(aPosition, 1.0);

    vec4 eyePos = uModelView * vec4(aPosition, 1.0);
    vec4 objPos = vec4(aPosition, 1.0);

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

    // TexCoord — start from attribute, then apply TexGen if enabled
    vec2 tc = aTexCoord;

    // TexGen S coordinate
    if ((uTexGenEnabled & 1) != 0) {
        // GL_OBJECT_LINEAR=0x2401, GL_EYE_LINEAR=0x2400, GL_SPHERE_MAP=0x2402
        if (uTexGenSMode == 0x2401) {
            tc.s = dot(objPos, uTexGenObjectPlaneS);
        } else if (uTexGenSMode == 0x2400) {
            tc.s = dot(eyePos, uTexGenEyePlaneS);
        } else if (uTexGenSMode == 0x2402) {
            // Sphere map: reflection-based
            vec3 r = reflect(-normalize(eyePos.xyz), normalize(hasRealNormal ? eyeNormal : vec3(0,0,1)));
            float m = 2.0 * sqrt(r.x*r.x + r.y*r.y + (r.z+1.0)*(r.z+1.0));
            tc.s = r.x / m + 0.5;
        }
    }

    // TexGen T coordinate
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

    vTexCoord = tc;

    // Lightmap always passed through; fragment shader checks uLightMapEnabled.
    vLightMap = aLightMap;

    // Fog distance (eye-space Z)
    vFogDist = length(eyePos.xyz);

    // Clip planes (replaces legacy glClipPlane / GL_CLIP_PLANEn)
    for (int i = 0; i < 6; i++) {
        if ((uClipPlaneEnabled & (1 << i)) != 0) {
            gl_ClipDistance[i] = dot(uClipPlane[i], eyePos);
        } else {
            gl_ClipDistance[i] = 1.0; // never clip
        }
    }
}
