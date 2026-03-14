package com.github.gl46core.api.render;

/**
 * Factory and registry for built-in render passes.
 *
 * Creates one concrete {@link RenderPass} per vanilla rendering stage.
 * These passes serve as context objects — they configure {@link PassData}
 * and declare resource requirements, but MC's existing rendering code
 * still issues the actual draw calls through CoreDrawHandler/CoreShaderProgram.
 *
 * The pass graph sorts them by PassType order + priority, providing:
 *   - Shader packs: query/override pass configuration
 *   - Mods: hook before/after specific passes
 *   - F3 overlay: display active pass info
 *   - PerPass UBO: shader-visible pass context
 *
 * Usage:
 *   BuiltinPasses.register(passGraph);   // during buildPassGraph
 *   BuiltinPasses.setActive(passType);   // as MC renders each stage
 */
public final class BuiltinPasses {

    // Singleton pass instances — created once, reused every frame
    private static final TranslationPass SKY              = new TranslationPass("sky", PassType.SKY);
    private static final TranslationPass TERRAIN_OPAQUE   = new TranslationPass("terrain_opaque", PassType.TERRAIN_OPAQUE);
    private static final TranslationPass TERRAIN_CUTOUT   = new TranslationPass("terrain_cutout", PassType.TERRAIN_CUTOUT);
    private static final TranslationPass ENTITY_OPAQUE    = new TranslationPass("entity_opaque", PassType.ENTITY_OPAQUE);
    private static final TranslationPass BLOCK_ENTITY     = new TranslationPass("block_entity", PassType.BLOCK_ENTITY);
    private static final TranslationPass TERRAIN_TRANS    = new TranslationPass("terrain_translucent", PassType.TERRAIN_TRANSLUCENT);
    private static final TranslationPass ENTITY_TRANS     = new TranslationPass("entity_translucent", PassType.ENTITY_TRANSLUCENT);
    private static final TranslationPass WATER            = new TranslationPass("water", PassType.WATER);
    private static final TranslationPass PARTICLES        = new TranslationPass("particles", PassType.PARTICLES);
    private static final TranslationPass WEATHER          = new TranslationPass("weather", PassType.WEATHER);
    private static final TranslationPass HAND             = new TranslationPass("hand", PassType.HAND);
    private static final TranslationPass OUTLINE          = new TranslationPass("outline", PassType.OUTLINE);
    private static final TranslationPass POST_CHAIN       = new TranslationPass("post_chain", PassType.POST_CHAIN);
    private static final TranslationPass UI               = new TranslationPass("ui", PassType.UI);
    private static final TranslationPass DEBUG_OVERLAY    = new TranslationPass("debug_overlay", PassType.DEBUG_OVERLAY);
    private static final TranslationPass SHADOW_OPAQUE    = new TranslationPass("shadow_opaque", PassType.SHADOW_OPAQUE);
    private static final TranslationPass SHADOW_CUTOUT    = new TranslationPass("shadow_cutout", PassType.SHADOW_CUTOUT);

    // Currently active pass — set by mixin hooks as MC renders each stage
    private static volatile PassType activePassType = PassType.TERRAIN_OPAQUE;
    private static volatile TranslationPass activePass = TERRAIN_OPAQUE;

    private BuiltinPasses() {}

    /**
     * Register all built-in passes into the pass graph for this frame.
     * Called during FrameOrchestrator.buildPassGraph().
     */
    public static void register(PassGraph graph) {
        // Shadow passes (execute first when shaderpacks are active)
        graph.addPass(SHADOW_OPAQUE);
        graph.addPass(SHADOW_CUTOUT);

        // Geometry passes
        graph.addPass(SKY);
        graph.addPass(TERRAIN_OPAQUE);
        graph.addPass(TERRAIN_CUTOUT);
        graph.addPass(ENTITY_OPAQUE);
        graph.addPass(BLOCK_ENTITY);
        graph.addPass(TERRAIN_TRANS);
        graph.addPass(ENTITY_TRANS);
        graph.addPass(WATER);
        graph.addPass(PARTICLES);
        graph.addPass(WEATHER);

        // Overlay passes
        graph.addPass(HAND);
        graph.addPass(OUTLINE);

        // Post-processing
        graph.addPass(POST_CHAIN);

        // UI / debug
        graph.addPass(UI);
        graph.addPass(DEBUG_OVERLAY);
    }

    /**
     * Set the currently active pass. Called by mixin hooks at stage boundaries
     * (e.g. before renderSky, before renderBlockLayer, before renderEntities).
     *
     * The active pass determines which PassData is uploaded to the PerPass UBO,
     * giving shaders context about the current rendering stage.
     */
    public static void setActive(PassType type) {
        activePassType = type;
        activePass = resolve(type);
    }

    /**
     * Get the currently active pass type.
     */
    public static PassType getActivePassType() {
        return activePassType;
    }

    /**
     * Get the currently active pass instance.
     */
    public static TranslationPass getActivePass() {
        return activePass;
    }

    /**
     * Resolve a PassType to its built-in pass instance.
     */
    public static TranslationPass resolve(PassType type) {
        return switch (type) {
            case SKY                 -> SKY;
            case TERRAIN_OPAQUE      -> TERRAIN_OPAQUE;
            case TERRAIN_CUTOUT      -> TERRAIN_CUTOUT;
            case ENTITY_OPAQUE       -> ENTITY_OPAQUE;
            case BLOCK_ENTITY        -> BLOCK_ENTITY;
            case TERRAIN_TRANSLUCENT -> TERRAIN_TRANS;
            case ENTITY_TRANSLUCENT  -> ENTITY_TRANS;
            case WATER               -> WATER;
            case PARTICLES           -> PARTICLES;
            case WEATHER             -> WEATHER;
            case HAND                -> HAND;
            case OUTLINE             -> OUTLINE;
            case POST_CHAIN          -> POST_CHAIN;
            case UI                  -> UI;
            case DEBUG_OVERLAY       -> DEBUG_OVERLAY;
            case SHADOW_OPAQUE       -> SHADOW_OPAQUE;
            case SHADOW_CUTOUT       -> SHADOW_CUTOUT;
        };
    }

    /**
     * A render pass backed by the shader translation layer.
     *
     * MC's legacy rendering flows through CoreDrawHandler → CoreShaderProgram,
     * which reads GL state and selects the correct shader variant. This pass
     * wraps that pipeline with pass-graph metadata so shader packs and mods
     * can hook into it.
     *
     * The pass does NOT issue draw calls in execute() — MC handles that.
     * Instead, it configures PassData and GPU state during setup().
     */
    public static final class TranslationPass implements RenderPass {

        private final String name;
        private final PassType type;
        private final PassData passData = new PassData();

        TranslationPass(String name, PassType type) {
            this.name = name;
            this.type = type;
        }

        @Override
        public String getName() { return name; }

        @Override
        public PassType getType() { return type; }

        @Override
        public void declareResources(PassResourceDeclaration decl) {
            decl.needsSceneData();

            if (type.isShadowPass()) {
                decl.writesShadowMap();
                decl.writesDepth();
                return;
            }

            switch (type) {
                case SKY:
                    decl.writesColor();
                    break;
                case TERRAIN_OPAQUE:
                case TERRAIN_CUTOUT:
                case ENTITY_OPAQUE:
                case BLOCK_ENTITY:
                    decl.writesColor();
                    decl.writesDepth();
                    decl.needsMaterialData();
                    decl.needsLightData();
                    break;
                case TERRAIN_TRANSLUCENT:
                case ENTITY_TRANSLUCENT:
                case WATER:
                    decl.writesColor();
                    decl.readsDepth();
                    decl.needsMaterialData();
                    decl.needsLightData();
                    break;
                case PARTICLES:
                case WEATHER:
                    decl.writesColor();
                    decl.readsDepth();
                    break;
                case HAND:
                    decl.writesColor();
                    decl.writesDepth();
                    break;
                case OUTLINE:
                    decl.writesColor();
                    decl.readsDepth();
                    break;
                case POST_CHAIN:
                    decl.writesColor();
                    decl.readsColor();
                    decl.readsDepth();
                    break;
                case UI:
                case DEBUG_OVERLAY:
                    decl.writesColor();
                    break;
                default:
                    decl.writesColor();
                    break;
            }
        }

        @Override
        public void setup(FrameContext frame, PassData externalPassData) {
            // Configure our internal PassData for this pass type
            int flags = PassData.FLAG_DEPTH_TEST | PassData.FLAG_DEPTH_WRITE;

            switch (type) {
                case SKY:
                    // Sky: no depth write, no cull
                    flags = PassData.FLAG_DEPTH_TEST;
                    passData.setLightingMode(PassData.LIGHTING_UNLIT);
                    break;
                case TERRAIN_OPAQUE:
                case TERRAIN_CUTOUT:
                    flags |= PassData.FLAG_BACKFACE_CULL;
                    passData.setLightingMode(PassData.LIGHTING_FULL);
                    break;
                case ENTITY_OPAQUE:
                case BLOCK_ENTITY:
                    flags |= PassData.FLAG_BACKFACE_CULL;
                    passData.setLightingMode(PassData.LIGHTING_FULL);
                    break;
                case TERRAIN_TRANSLUCENT:
                case ENTITY_TRANSLUCENT:
                case WATER:
                    flags = PassData.FLAG_DEPTH_TEST | PassData.FLAG_BLENDING;
                    passData.setLightingMode(PassData.LIGHTING_FULL);
                    break;
                case PARTICLES:
                case WEATHER:
                    flags = PassData.FLAG_DEPTH_TEST | PassData.FLAG_BLENDING;
                    passData.setLightingMode(PassData.LIGHTING_AMBIENT_ONLY);
                    break;
                case HAND:
                    flags |= PassData.FLAG_BACKFACE_CULL;
                    passData.setLightingMode(PassData.LIGHTING_FULL);
                    passData.setFogOverride(1, 0, 0, 0, 0); // no fog on hand
                    break;
                case OUTLINE:
                    // Selection box: line rendering, depth test, no depth write, blending
                    flags = PassData.FLAG_DEPTH_TEST | PassData.FLAG_BLENDING;
                    passData.setLightingMode(PassData.LIGHTING_UNLIT);
                    break;
                case POST_CHAIN:
                    // Full-screen post-processing: no depth, no cull
                    flags = 0;
                    passData.setLightingMode(PassData.LIGHTING_UNLIT);
                    break;
                case UI:
                case DEBUG_OVERLAY:
                    flags = PassData.FLAG_BLENDING;
                    // Use FULL: MC's RenderItem enables GL_LIGHTING for 3D block
                    // models in GUI. Shader variant LIGHTING_ENABLED #ifdef gates
                    // whether lighting runs; the pass should not suppress it.
                    passData.setLightingMode(PassData.LIGHTING_FULL);
                    break;
                default:
                    break;
            }

            int w = frame.getCamera().getViewportWidth();
            int h = frame.getCamera().getViewportHeight();
            passData.configure(type, flags, w, h);
        }

        @Override
        public void execute(FrameContext frame) {
            // No-op: MC's rendering code issues draw calls through
            // CoreDrawHandler → CoreShaderProgram. This pass only
            // provides context. Future: drive queue-based execution.
        }

        /**
         * Get this pass's PassData (packed for GPU upload).
         */
        public PassData getPassData() {
            return passData;
        }
    }
}
