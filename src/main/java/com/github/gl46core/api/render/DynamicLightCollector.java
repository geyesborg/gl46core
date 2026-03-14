package com.github.gl46core.api.render;

import com.github.gl46core.api.hook.DynamicLightProvider;
import com.github.gl46core.api.hook.RenderRegistry;
import com.github.gl46core.api.render.gpu.LightBuffer;
import com.github.gl46core.api.render.gpu.LightIndexBuffer;

import java.util.List;

/**
 * Collects dynamic lights from registered providers each frame,
 * uploads them to the Light SSBO, and builds per-chunk spatial indices
 * in the Light Index SSBO.
 *
 * Frame lifecycle:
 *   1. beginFrame()   — clear buffers
 *   2. collect(frame) — iterate providers, add lights to LightBuffer
 *   3. flush()        — upload to GPU, bind SSBOs
 *
 * Spatial indexing is deferred until ChunkRenderProviders populate
 * ChunkData with section coordinates. For now, all lights are global
 * (every chunk sees all lights). Per-chunk indexing will be added when
 * the pass graph drives chunk submission.
 */
public final class DynamicLightCollector {

    public static final DynamicLightCollector INSTANCE = new DynamicLightCollector();

    private final LightBuffer lightBuffer = new LightBuffer();
    private final LightIndexBuffer lightIndexBuffer = new LightIndexBuffer();

    // Pool of reusable LightData to avoid per-frame allocation
    private LightData[] lightPool = new LightData[64];
    private int poolCount;

    // Stats
    private int lastFrameLightCount;
    private int totalProvidersPolled;

    private boolean initialized;

    private DynamicLightCollector() {}

    private void ensureInit() {
        if (initialized) return;
        initialized = true;
        lightBuffer.init(64);
        lightIndexBuffer.init(4096);
        for (int i = 0; i < lightPool.length; i++) {
            lightPool[i] = new LightData();
        }
    }

    /**
     * Reset for a new frame.
     */
    public void beginFrame() {
        ensureInit();
        lightBuffer.clear();
        lightIndexBuffer.clear();
        poolCount = 0;
    }

    /**
     * Collect lights from all registered DynamicLightProviders.
     */
    public void collect(FrameContext frame) {
        ensureInit();

        List<DynamicLightProvider> providers = RenderRegistry.INSTANCE.getDynamicLightProviders();
        totalProvidersPolled = providers.size();

        CollectorImpl collector = new CollectorImpl();

        for (int i = 0; i < providers.size(); i++) {
            DynamicLightProvider provider = providers.get(i);
            try {
                provider.collectLights(frame, collector);
            } catch (Exception e) {
                // Don't let one bad provider crash the frame
                com.github.gl46core.GL46Core.LOGGER.warn(
                    "DynamicLightProvider '{}' threw during collect: {}",
                    provider.getId(), e.getMessage());
            }
        }

    }

    /**
     * Pack collected lights into GPU buffers, upload, and bind SSBOs.
     */
    public void flush() {
        if (!initialized) return;

        // Pack all collected lights into the LightBuffer now that providers
        // have finished populating the LightData objects
        for (int i = 0; i < poolCount; i++) {
            lightBuffer.addLight(lightPool[i]);
        }
        lastFrameLightCount = lightBuffer.getCount();

        lightBuffer.flush();
        lightBuffer.bind();

        // LightIndexBuffer only has data if spatial indexing was performed
        if (lightIndexBuffer.getCount() > 0) {
            lightIndexBuffer.flush();
            lightIndexBuffer.bind();
        }
    }

    // ── Pool management ──

    private LightData acquireFromPool() {
        if (poolCount >= lightPool.length) {
            growPool();
        }
        LightData ld = lightPool[poolCount++];
        // Reset to defaults
        ld.setPosition(0, 0, 0);
        ld.setRadius(16.0f);
        ld.setColor(1, 1, 1);
        ld.setIntensity(1.0f);
        ld.setLightType(LightData.LightType.POINT);
        ld.setShadowFlags(0);
        ld.setFalloffExponent(2.0f);
        ld.setSpotAngle(0.5f);
        ld.setEntityId(-1);
        return ld;
    }

    private void growPool() {
        int newSize = lightPool.length * 2;
        LightData[] newPool = new LightData[newSize];
        System.arraycopy(lightPool, 0, newPool, 0, lightPool.length);
        for (int i = lightPool.length; i < newSize; i++) {
            newPool[i] = new LightData();
        }
        lightPool = newPool;
    }

    // ── Accessor ──

    public LightBuffer getLightBuffer()           { return lightBuffer; }
    public LightIndexBuffer getLightIndexBuffer()  { return lightIndexBuffer; }
    public int getLastFrameLightCount()            { return lastFrameLightCount; }
    public int getTotalProvidersPolled()            { return totalProvidersPolled; }

    /**
     * LightCollector implementation — returns pooled LightData for providers
     * to populate. Actual GPU buffer packing happens in flush().
     */
    private final class CollectorImpl implements DynamicLightProvider.LightCollector {

        @Override
        public LightData addLight() {
            return acquireFromPool();
        }

        @Override
        public int getLightCount() {
            return poolCount;
        }

        @Override
        public int getMaxLights() {
            return lightBuffer.getCapacity();
        }
    }
}
