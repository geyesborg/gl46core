package com.github.gl46core.shaderpack;

import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Parsed shaderpack source data — shader GLSL source code and properties.
 *
 * Produced by {@link ShaderpackLoader}. Provides named access to vertex
 * and fragment shader source for each program stage, plus parsed
 * shaders.properties for configuration values.
 *
 * This is an immutable snapshot of the shaderpack contents — no file I/O
 * after construction.
 */
public final class ShaderpackSource {

    private final String name;
    private final Map<String, String> sources;   // path → GLSL source
    private final Properties properties;

    ShaderpackSource(String name, Map<String, String> sources, Properties properties) {
        this.name = name;
        this.sources = sources;
        this.properties = properties;
    }

    /**
     * Get the shaderpack display name (derived from filename/folder).
     */
    public String getName() {
        return name;
    }

    /**
     * Get vertex shader source for a program name.
     * @param programName e.g. "gbuffers_terrain"
     * @return GLSL source or null if not present
     */
    public String getVertexSource(String programName) {
        return sources.get(ShaderpackFormat.vertexPath(programName));
    }

    /**
     * Get fragment shader source for a program name.
     * @param programName e.g. "gbuffers_terrain"
     * @return GLSL source or null if not present
     */
    public String getFragmentSource(String programName) {
        return sources.get(ShaderpackFormat.fragmentPath(programName));
    }

    /**
     * Check if a program exists (has at least a vertex or fragment shader).
     */
    public boolean hasProgram(String programName) {
        return getVertexSource(programName) != null
                || getFragmentSource(programName) != null;
    }

    /**
     * Get raw source by full path (e.g. "shaders/gbuffers_terrain.vsh").
     */
    public String getRawSource(String path) {
        return sources.get(path);
    }

    /**
     * Get all source file paths.
     */
    public Set<String> getSourcePaths() {
        return sources.keySet();
    }

    /**
     * Get shaders.properties value.
     */
    public String getProperty(String key) {
        return properties.getProperty(key);
    }

    /**
     * Get shaders.properties value with default.
     */
    public String getProperty(String key, String defaultValue) {
        return properties.getProperty(key, defaultValue);
    }

    /**
     * Get all properties.
     */
    public Properties getProperties() {
        return properties;
    }

    /**
     * Get the number of shader source files.
     */
    public int getSourceCount() {
        return sources.size();
    }

    /**
     * Resolve a program name through the fallback chain.
     * Returns the first program name that has source, or null if none found.
     */
    public String resolveProgram(String programName) {
        if (hasProgram(programName)) return programName;
        for (String fallback : ShaderpackFormat.getFallbackChain(programName)) {
            if (hasProgram(fallback)) return fallback;
        }
        return null;
    }

    @Override
    public String toString() {
        return String.format("ShaderpackSource[%s, %d files, %d props]",
                name, sources.size(), properties.size());
    }
}
