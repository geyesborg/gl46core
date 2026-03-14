package com.github.gl46core.api.hook;

import com.github.gl46core.api.render.RenderPass;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Central registry for all render hook providers.
 *
 * Mods register their providers here during initialization. The renderer
 * queries providers each frame during the appropriate lifecycle stage.
 *
 * Thread-safe for registration (mods may register from any thread during
 * init). Iteration during rendering is lock-free via CopyOnWriteArrayList.
 *
 * Usage:
 *   // During mod init:
 *   RenderRegistry.INSTANCE.registerSceneDataProvider(myProvider);
 *   RenderRegistry.INSTANCE.registerDynamicLightProvider(myLightMod);
 *
 *   // During frame:
 *   for (SceneDataProvider p : RenderRegistry.INSTANCE.getSceneDataProviders()) {
 *       p.collectSceneData(frame);
 *   }
 */
public final class RenderRegistry {

    public static final RenderRegistry INSTANCE = new RenderRegistry();

    // ── Provider lists (sorted by priority on access) ──

    private final CopyOnWriteArrayList<SceneDataProvider> sceneDataProviders = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<ChunkRenderProvider> chunkRenderProviders = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<MaterialProvider> materialProviders = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<DynamicLightProvider> dynamicLightProviders = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<FogModifier> fogModifiers = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<RenderableSubmitter> renderableSubmitters = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<LegacyTranslationHandler> legacyHandlers = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<RenderPass> registeredPasses = new CopyOnWriteArrayList<>();

    // ── Event listener lists ──

    private final CopyOnWriteArrayList<RenderEventListener> eventListeners = new CopyOnWriteArrayList<>();

    private RenderRegistry() {}

    // ═══════════════════════════════════════════════════════════════════
    // Registration
    // ═══════════════════════════════════════════════════════════════════

    public void registerSceneDataProvider(SceneDataProvider provider) {
        sceneDataProviders.add(provider);
        sortByPriority(sceneDataProviders);
    }

    public void registerChunkRenderProvider(ChunkRenderProvider provider) {
        chunkRenderProviders.add(provider);
        sortByPriority(chunkRenderProviders);
    }

    public void registerMaterialProvider(MaterialProvider provider) {
        materialProviders.add(provider);
        sortByPriority(materialProviders);
    }

    public void registerDynamicLightProvider(DynamicLightProvider provider) {
        dynamicLightProviders.add(provider);
        sortByPriority(dynamicLightProviders);
    }

    public void registerFogModifier(FogModifier modifier) {
        fogModifiers.add(modifier);
        sortByPriority(fogModifiers);
    }

    public void registerRenderableSubmitter(RenderableSubmitter submitter) {
        renderableSubmitters.add(submitter);
        sortByPriority(renderableSubmitters);
    }

    public void registerLegacyTranslationHandler(LegacyTranslationHandler handler) {
        legacyHandlers.add(handler);
        sortByPriority(legacyHandlers);
    }

    public void registerRenderPass(RenderPass pass) {
        registeredPasses.add(pass);
    }

    public void registerEventListener(RenderEventListener listener) {
        eventListeners.add(listener);
        sortByPriority(eventListeners);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Unregistration
    // ═══════════════════════════════════════════════════════════════════

    public void unregisterSceneDataProvider(String id) {
        sceneDataProviders.removeIf(p -> p.getId().equals(id));
    }

    public void unregisterChunkRenderProvider(String id) {
        chunkRenderProviders.removeIf(p -> p.getId().equals(id));
    }

    public void unregisterDynamicLightProvider(String id) {
        dynamicLightProviders.removeIf(p -> p.getId().equals(id));
    }

    public void unregisterFogModifier(String id) {
        fogModifiers.removeIf(p -> p.getId().equals(id));
    }

    public void unregisterRenderableSubmitter(String id) {
        renderableSubmitters.removeIf(p -> p.getId().equals(id));
    }

    public void unregisterEventListener(String id) {
        eventListeners.removeIf(p -> p.getId().equals(id));
    }

    // ═══════════════════════════════════════════════════════════════════
    // Query
    // ═══════════════════════════════════════════════════════════════════

    public List<SceneDataProvider> getSceneDataProviders()        { return sceneDataProviders; }
    public List<ChunkRenderProvider> getChunkRenderProviders()    { return chunkRenderProviders; }
    public List<MaterialProvider> getMaterialProviders()           { return materialProviders; }
    public List<DynamicLightProvider> getDynamicLightProviders()  { return dynamicLightProviders; }
    public List<FogModifier> getFogModifiers()                    { return fogModifiers; }
    public List<RenderableSubmitter> getRenderableSubmitters()    { return renderableSubmitters; }
    public List<LegacyTranslationHandler> getLegacyHandlers()    { return legacyHandlers; }
    public List<RenderPass> getRegisteredPasses()                 { return registeredPasses; }
    public List<RenderEventListener> getEventListeners()          { return eventListeners; }

    /**
     * Get the highest-priority chunk render provider, or null if none registered.
     */
    public ChunkRenderProvider getActiveChunkProvider() {
        return chunkRenderProviders.isEmpty() ? null : chunkRenderProviders.get(0);
    }

    // ═══════════════════════════════════════════════════════════════════
    // Internal
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Sort a provider list by priority (ascending). Uses a temporary
     * ArrayList to avoid ConcurrentModificationException on COWAL sort.
     */
    private <T> void sortByPriority(CopyOnWriteArrayList<T> list) {
        if (list.size() <= 1) return;
        ArrayList<T> temp = new ArrayList<>(list);
        temp.sort(Comparator.comparingInt(this::extractPriority));
        // Atomic replacement
        list.clear();
        list.addAll(temp);
    }

    @SuppressWarnings("unchecked")
    private int extractPriority(Object provider) {
        if (provider instanceof SceneDataProvider p) return p.getPriority();
        if (provider instanceof ChunkRenderProvider p) return p.getPriority();
        if (provider instanceof MaterialProvider p) return p.getPriority();
        if (provider instanceof DynamicLightProvider p) return p.getPriority();
        if (provider instanceof FogModifier p) return p.getPriority();
        if (provider instanceof RenderableSubmitter p) return p.getPriority();
        if (provider instanceof LegacyTranslationHandler p) return p.getPriority();
        if (provider instanceof RenderEventListener p) return p.getPriority();
        return 0;
    }
}
