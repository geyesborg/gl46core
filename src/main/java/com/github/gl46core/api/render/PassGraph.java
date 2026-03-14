package com.github.gl46core.api.render;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;

/**
 * Ordered graph of render passes for a frame.
 *
 * Manages pass registration, dependency-based ordering, and per-frame
 * enable/disable logic. The pass graph is rebuilt each frame to allow
 * dynamic pass insertion (e.g. shaderpack post passes, debug overlays).
 *
 * Passes are sorted by:
 *   1. PassType default order (shadow < geometry < translucent < post < UI)
 *   2. Priority within the same PassType (lower = earlier)
 *
 * Usage:
 *   passGraph.clear();
 *   passGraph.addPass(terrainOpaquePass);
 *   passGraph.addPass(entityOpaquePass);
 *   passGraph.addPass(customModPass);
 *   passGraph.build(frameContext);
 *   for (RenderPass pass : passGraph.getExecutionOrder()) { ... }
 */
public final class PassGraph {

    private final List<RenderPass> registeredPasses = new ArrayList<>();
    private final List<RenderPass> executionOrder = new ArrayList<>();
    private final EnumMap<PassType, List<RenderPass>> passesByType = new EnumMap<>(PassType.class);

    // Resource declarations cached per pass
    private final List<PassResourceDeclaration> declarations = new ArrayList<>();

    private boolean built = false;

    public PassGraph() {
        for (PassType type : PassType.values()) {
            passesByType.put(type, new ArrayList<>());
        }
    }

    /**
     * Clear all passes for a new frame.
     */
    public void clear() {
        registeredPasses.clear();
        executionOrder.clear();
        declarations.clear();
        for (List<RenderPass> list : passesByType.values()) {
            list.clear();
        }
        built = false;
    }

    /**
     * Register a pass for this frame.
     */
    public void addPass(RenderPass pass) {
        registeredPasses.add(pass);
    }

    /**
     * Build the execution order. Filters disabled passes, sorts by
     * type order then priority, and caches resource declarations.
     *
     * @param frame current frame context for isEnabled() checks
     */
    public void build(FrameContext frame) {
        executionOrder.clear();
        declarations.clear();
        for (List<RenderPass> list : passesByType.values()) {
            list.clear();
        }

        // Filter and bucket by type
        for (RenderPass pass : registeredPasses) {
            if (pass.isEnabled(frame)) {
                passesByType.get(pass.getType()).add(pass);
            }
        }

        // Sort each bucket by priority, then flatten into execution order
        Comparator<RenderPass> byPriority = Comparator.comparingInt(RenderPass::getPriority);
        for (PassType type : PassType.values()) {
            List<RenderPass> bucket = passesByType.get(type);
            if (!bucket.isEmpty()) {
                bucket.sort(byPriority);
                executionOrder.addAll(bucket);
            }
        }

        // Cache resource declarations
        for (RenderPass pass : executionOrder) {
            PassResourceDeclaration decl = new PassResourceDeclaration();
            pass.declareResources(decl);
            declarations.add(decl);
        }

        built = true;
    }

    /**
     * Get the sorted execution order. Must call build() first.
     */
    public List<RenderPass> getExecutionOrder() {
        return executionOrder;
    }

    /**
     * Get the resource declaration for a pass at the given execution index.
     */
    public PassResourceDeclaration getDeclaration(int executionIndex) {
        return declarations.get(executionIndex);
    }

    /**
     * Get all passes of a specific type (in priority order after build).
     */
    public List<RenderPass> getPassesByType(PassType type) {
        return passesByType.get(type);
    }

    /**
     * Find a pass by name, or null if not present.
     */
    public RenderPass findPass(String name) {
        for (RenderPass pass : executionOrder) {
            if (pass.getName().equals(name)) return pass;
        }
        return null;
    }

    public int getPassCount() { return executionOrder.size(); }
    public boolean isBuilt()  { return built; }
}
