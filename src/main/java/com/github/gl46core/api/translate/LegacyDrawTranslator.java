package com.github.gl46core.api.translate;

import com.github.gl46core.api.render.*;
import com.github.gl46core.api.hook.LegacyTranslationHandler;
import com.github.gl46core.api.hook.RenderRegistry;

import com.github.gl46core.api.render.gpu.MaterialBuffer;

import java.util.HashMap;
import java.util.List;

/**
 * Converts legacy BufferBuilder/Tessellator draw calls into modern
 * {@link RenderSubmission} entries in the appropriate {@link RenderQueue}.
 *
 * This is the main entry point for the translation layer. When
 * ImmediateModeEmulator or CoreDrawHandler issues a draw call,
 * it flows through here to be classified and submitted.
 *
 * Translation flow:
 *   1. Check registered LegacyTranslationHandlers (mod overrides)
 *   2. If no handler claims it, use default translation:
 *      a. Infer MaterialData from CoreStateTracker via LegacyStateInterpreter
 *      b. Infer PassType (opaque/cutout/translucent)
 *      c. Build sort key
 *      d. Submit into the appropriate RenderQueue
 *
 * This class does NOT issue GL draw calls — it only populates queues.
 * The actual draw calls happen during FrameOrchestrator.executePasses().
 */
public final class LegacyDrawTranslator {

    public static final LegacyDrawTranslator INSTANCE = new LegacyDrawTranslator();

    private final LegacyStateInterpreter interpreter = LegacyStateInterpreter.INSTANCE;

    // Submission counter per frame (used as sort key tiebreaker)
    private int submissionIndex;

    // Per-frame material deduplication: materialId hash → SSBO index
    private final HashMap<Integer, Integer> materialIndexMap = new HashMap<>();
    private int nextMaterialIndex;

    private LegacyDrawTranslator() {}

    /**
     * Reset per-frame state. Call at beginFrame().
     */
    public void beginFrame() {
        submissionIndex = 0;
        materialIndexMap.clear();
        nextMaterialIndex = 0;
    }

    /**
     * Translate a legacy draw call into a render submission.
     *
     * Called from CoreDrawHandler.draw() or ImmediateModeEmulator.flush()
     * after vertices have been uploaded to a VBO.
     *
     * @param drawMode      GL draw mode (GL_TRIANGLES, GL_QUADS, etc.)
     * @param vertexCount   number of vertices
     * @param vboOffset     byte offset into the bound VBO
     * @param hasColor      vertex format includes color
     * @param hasTexCoord   vertex format includes tex coords
     * @param hasNormal     vertex format includes normals
     * @param hasLightMap   vertex format includes lightmap coords
     * @param depthEstimate estimated eye-space depth for sorting (0 = unknown)
     */
    public void translateDraw(int drawMode, int vertexCount, int vboOffset,
                              boolean hasColor, boolean hasTexCoord,
                              boolean hasNormal, boolean hasLightMap,
                              float depthEstimate) {
        // 1. Try registered legacy handlers first
        FrameContext frame = FrameOrchestrator.INSTANCE.getFrameContext();
        List<LegacyTranslationHandler> handlers = RenderRegistry.INSTANCE.getLegacyHandlers();
        for (LegacyTranslationHandler handler : handlers) {
            if (handler.handleLegacyDraw(frame, drawMode, vertexCount,
                    hasTexCoord, hasLightMap)) {
                return; // Consumed by handler
            }
        }

        // 2. Default translation path
        PassType passType = interpreter.inferPassType();
        MaterialData material = interpreter.inferMaterial(hasColor, hasTexCoord,
                                                          hasNormal, hasLightMap);

        // 3. Register material in SSBO (deduplicate by hash)
        int matHash = material.getMaterialId();
        int ssboIndex = registerMaterial(matHash, material);

        // 4. Acquire submission slot
        FrameOrchestrator orchestrator = FrameOrchestrator.INSTANCE;
        RenderSubmission sub = orchestrator.submit(passType);

        // 5. Configure submission with SSBO index
        sub.setMaterialIndex(ssboIndex);
        sub.setMesh(vboOffset, vertexCount, -1, drawMode);

        // 5. Record per-pass draw count for profiler
        com.github.gl46core.api.debug.RenderProfiler.INSTANCE.recordPassDrawCount(passType, 1);

        // 6. Build sort key
        int idx = submissionIndex++;
        if (passType.isTranslucent()) {
            sub.buildTranslucentKey(2, material.getMaterialId() & 0xFF,
                                    depthEstimate, idx);
        } else if (passType == PassType.TERRAIN_CUTOUT) {
            sub.buildOpaqueKey(1, material.getMaterialId() & 0xFF,
                               depthEstimate, idx);
        } else {
            sub.buildOpaqueKey(0, material.getMaterialId() & 0xFF,
                               depthEstimate, idx);
        }
    }

    /**
     * Simplified translate for immediate-mode draws that don't have depth info.
     */
    public void translateDraw(int drawMode, int vertexCount, int vboOffset,
                              boolean hasColor, boolean hasTexCoord,
                              boolean hasNormal, boolean hasLightMap) {
        translateDraw(drawMode, vertexCount, vboOffset,
                      hasColor, hasTexCoord, hasNormal, hasLightMap, 0.0f);
    }

    /**
     * Register a material in the MaterialBuffer SSBO.
     * Deduplicates by material hash — returns existing SSBO index if already registered.
     */
    private int registerMaterial(int matHash, MaterialData material) {
        Integer existing = materialIndexMap.get(matHash);
        if (existing != null) return existing;

        int idx = nextMaterialIndex++;
        materialIndexMap.put(matHash, idx);

        // Write to MaterialBuffer if available
        MaterialBuffer matBuf = FrameOrchestrator.INSTANCE.getMaterialBuffer();
        if (matBuf != null) {
            matBuf.setMaterial(idx, material);
        }
        return idx;
    }

    public int getSubmissionCount()    { return submissionIndex; }
    public int getUniqueMaterialCount() { return nextMaterialIndex; }
}
