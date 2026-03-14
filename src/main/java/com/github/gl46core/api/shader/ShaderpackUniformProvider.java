package com.github.gl46core.api.shader;

import com.github.gl46core.api.render.FrameContext;

/**
 * Callback for uploading shaderpack-specific uniforms to a program.
 *
 * Each shaderpack pass can have a uniform provider that maps the
 * FrameContext data into the pack's expected uniform locations.
 *
 * Common shaderpack uniforms:
 *   - gbufferModelView / gbufferProjection (Optifine-style)
 *   - sunPosition / moonPosition
 *   - worldTime / frameCounter
 *   - rainStrength / wetness
 *   - cameraPosition / previousCameraPosition
 *   - viewWidth / viewHeight
 *   - near / far
 *   - shadowModelView / shadowProjection (for shadow passes)
 */
@FunctionalInterface
public interface ShaderpackUniformProvider {

    /**
     * Upload uniforms to the given program. The program is already bound.
     *
     * @param frame         current frame context with all scene data
     * @param programHandle GL program handle (already bound via glUseProgram)
     */
    void uploadUniforms(FrameContext frame, int programHandle);
}
