package com.github.gl46core.api.shader;

import com.github.gl46core.api.render.FrameContext;
import com.github.gl46core.api.render.PassGraph;

import java.nio.file.Path;
import java.util.List;

/**
 * Interface for loading and managing shader packs.
 *
 * Implemented by the gl46-shaderpack module. Handles pack discovery,
 * parsing, shader compilation, and pass/uniform registration.
 *
 * A shaderpack can:
 *   - Override existing passes with custom shaders
 *   - Add new post-processing passes
 *   - Define custom uniforms and resource bindings
 *   - Negotiate feature requirements with the renderer
 */
public interface ShaderpackLoader {

    /**
     * Unique identifier for this loader implementation.
     */
    String getId();

    /**
     * Discover available shaderpacks from the shaderpacks directory.
     */
    List<ShaderpackInfo> discoverPacks(Path shaderpacksDir);

    /**
     * Load and compile a shaderpack. Returns true on success.
     */
    boolean loadPack(ShaderpackInfo pack);

    /**
     * Unload the current shaderpack and revert to defaults.
     */
    void unloadPack();

    /**
     * Register pack-defined passes into the pass graph.
     * Called during buildPassGraph phase each frame.
     */
    void registerPasses(PassGraph passGraph, FrameContext frame);

    /**
     * Update pack uniforms for the current frame.
     * Called during collectScene after scene data is captured.
     */
    void updateUniforms(FrameContext frame);

    /**
     * Whether a shaderpack is currently loaded and active.
     */
    boolean isPackLoaded();

    /**
     * Get info about the currently loaded pack, or null.
     */
    ShaderpackInfo getActivePack();

    /**
     * Metadata about a discovered shaderpack.
     */
    record ShaderpackInfo(
        String name,
        String version,
        String author,
        Path path,
        List<String> requiredFeatures,
        boolean supportsDeferred,
        boolean supportsShadows,
        boolean supportsPostProcessing
    ) {}
}
