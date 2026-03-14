package com.github.gl46core.gl;

import com.github.gl46core.GL46Core;
import org.lwjgl.opengl.GL20;

/**
 * Converts GLSL shader source to GLSL 460 core.
 *
 * <p>Three tiers:
 * <ul>
 *   <li><b>Legacy (&lt; 330)</b>: full conversion — attributes, varyings, outputs,
 *       texture functions, built-in matrices/attributes, ftransform()</li>
 *   <li><b>Modern (330–459)</b>: version bump only — already core-compatible syntax</li>
 *   <li><b>Current (460+)</b>: passthrough — no changes needed</li>
 * </ul>
 */
public final class ShaderConverter {

    private ShaderConverter() {}

    /**
     * Convert shader source to GLSL 460 core.
     * <ul>
     *   <li>Version &lt; 330: full legacy-to-core conversion</li>
     *   <li>Version 330–459: version bump only (already core syntax)</li>
     *   <li>Version 460+ or no version: passthrough</li>
     * </ul>
     *
     * @param shaderType GL_VERTEX_SHADER or GL_FRAGMENT_SHADER
     * @param source     original GLSL source
     * @return converted source (or original if no conversion needed)
     */
    public static String convert(int shaderType, String source) {
        if (source == null || source.isEmpty()) return source;

        int version = parseVersion(source);
        if (version == 0 || version >= 460) return source;

        // Modern shaders (330-459): just bump the version line
        if (version >= 330) {
            String result = source.replaceFirst("#version\\s+\\d+(\\s+\\w+)?", "#version 460 core");
            GL46Core.LOGGER.debug("[ShaderConverter] Bumped GLSL {} \u2192 460 core", version);
            return result;
        }

        // Legacy shaders (< 330): full conversion

        boolean isVertex = (shaderType == GL20.GL_VERTEX_SHADER);
        boolean isFragment = (shaderType == GL20.GL_FRAGMENT_SHADER);

        StringBuilder sb = new StringBuilder(source.length() + 512);

        // Replace version line
        String body = source.replaceFirst("#version\\s+\\d+(\\s+\\w+)?", "").trim();

        sb.append("#version 460 core\n");
        sb.append("// [gl46core] Auto-converted from GLSL ").append(version).append("\n\n");

        // Inject matrix uniforms if referenced
        boolean needsMVP = body.contains("gl_ModelViewProjectionMatrix") || body.contains("ftransform");
        boolean needsMV = body.contains("gl_ModelViewMatrix");
        boolean needsProj = body.contains("gl_ProjectionMatrix");
        boolean needsNormalMatrix = body.contains("gl_NormalMatrix");

        if (needsMVP || needsMV || needsProj || needsNormalMatrix) {
            sb.append("// Injected by gl46core — replaces built-in matrix uniforms\n");
            if (needsMVP) sb.append("uniform mat4 gl_ModelViewProjectionMatrix;\n");
            if (needsMV) sb.append("uniform mat4 gl_ModelViewMatrix;\n");
            if (needsProj) sb.append("uniform mat4 gl_ProjectionMatrix;\n");
            if (needsNormalMatrix) sb.append("uniform mat3 gl_NormalMatrix;\n");
            sb.append("\n");
        }

        // Inject built-in vertex attribute replacements
        if (isVertex) {
            boolean needsVertex = body.contains("gl_Vertex");
            boolean needsNormal = body.contains("gl_Normal");
            boolean needsColor = body.contains("gl_Color");
            boolean needsTexCoord0 = body.contains("gl_MultiTexCoord0");
            boolean needsTexCoord1 = body.contains("gl_MultiTexCoord1");

            if (needsVertex || needsNormal || needsColor || needsTexCoord0 || needsTexCoord1) {
                sb.append("// Injected by gl46core — replaces built-in vertex attributes\n");
                if (needsVertex) sb.append("in vec4 gl_Vertex;\n");
                if (needsColor) sb.append("in vec4 gl_Color;\n");
                if (needsTexCoord0) sb.append("in vec4 gl_MultiTexCoord0;\n");
                if (needsTexCoord1) sb.append("in vec4 gl_MultiTexCoord1;\n");
                if (needsNormal) sb.append("in vec3 gl_Normal;\n");
                sb.append("\n");
            }
        }

        // Fragment output
        boolean needsFragColor = false;
        boolean needsFragData = false;
        int maxFragData = 0;

        if (isFragment) {
            needsFragColor = body.contains("gl_FragColor");
            if (body.contains("gl_FragData")) {
                needsFragData = true;
                // Find max index: gl_FragData[0], gl_FragData[1], etc.
                for (int i = 7; i >= 0; i--) {
                    if (body.contains("gl_FragData[" + i + "]")) {
                        maxFragData = i;
                        break;
                    }
                }
            }

            if (needsFragColor) {
                sb.append("out vec4 gl46_FragColor;\n");
            }
            if (needsFragData) {
                for (int i = 0; i <= maxFragData; i++) {
                    sb.append("layout(location = ").append(i).append(") out vec4 gl46_FragData").append(i).append(";\n");
                }
            }
            if (needsFragColor || needsFragData) sb.append("\n");
        }

        // Apply text transformations to the body
        if (isVertex) {
            // attribute → in (only at line start / after whitespace)
            body = body.replaceAll("(?m)^(\\s*)attribute\\s+", "$1in ");
            // varying → out
            body = body.replaceAll("(?m)^(\\s*)varying\\s+", "$1out ");
        } else if (isFragment) {
            // varying → in
            body = body.replaceAll("(?m)^(\\s*)varying\\s+", "$1in ");
        }

        // ftransform() → gl_ModelViewProjectionMatrix * gl_Vertex
        body = body.replace("ftransform()", "(gl_ModelViewProjectionMatrix * gl_Vertex)");

        // Texture function renames
        body = body.replaceAll("\\btexture2D\\s*\\(", "texture(");
        body = body.replaceAll("\\btexture3D\\s*\\(", "texture(");
        body = body.replaceAll("\\btextureCube\\s*\\(", "texture(");
        body = body.replaceAll("\\bshadow2D\\s*\\(", "texture(");
        body = body.replaceAll("\\bshadow2DProj\\s*\\(", "textureProj(");
        body = body.replaceAll("\\btexture2DProj\\s*\\(", "textureProj(");
        body = body.replaceAll("\\btexture2DLod\\s*\\(", "textureLod(");
        body = body.replaceAll("\\btexture3DLod\\s*\\(", "textureLod(");
        body = body.replaceAll("\\btextureCubeLod\\s*\\(", "textureLod(");

        // Fragment output renames
        if (needsFragColor) {
            body = body.replace("gl_FragColor", "gl46_FragColor");
        }
        if (needsFragData) {
            for (int i = 0; i <= maxFragData; i++) {
                body = body.replace("gl_FragData[" + i + "]", "gl46_FragData" + i);
            }
        }

        sb.append(body);

        String result = sb.toString();
        GL46Core.LOGGER.debug("[ShaderConverter] Converted GLSL {} → 460 core ({} shader, {} chars → {} chars)",
                version, isVertex ? "vertex" : "fragment", source.length(), result.length());
        return result;
    }

    /**
     * Parse the GLSL version from a {@code #version NNN} directive.
     * Returns 0 if not found.
     */
    private static int parseVersion(String source) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("#version\\s+(\\d+)")
                .matcher(source);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }
}
