package com.github.gl46core.api.render;

/**
 * Top-level per-frame rendering context.
 *
 * Created once per frame, holds immutable references to all frame-level
 * state snapshots. This is the single object passed through the render
 * pipeline — every pass, submission, and hook receives it.
 *
 * Lifecycle:
 *   1. beginFrame() — allocate/reset FrameContext
 *   2. capture() — snapshot all state from MC world/renderer
 *   3. collectScene() — passes read from FrameContext
 *   4. execute() — GPU uploads read from FrameContext
 *   5. endFrame() — context becomes stale
 */
public final class FrameContext {

    private long frameIndex;
    private float partialTicks;
    private double worldTime;       // World.getTotalWorldTime + partialTicks

    private final CameraState camera = new CameraState();
    private final DimensionState dimension = new DimensionState();
    private final WeatherState weather = new WeatherState();
    private final FogState fog = new FogState();
    private final GlobalLightState globalLight = new GlobalLightState();
    private final ShadowState shadow = new ShadowState();
    private final RenderCapabilityState capabilities = new RenderCapabilityState();

    // Feature flags for this frame (derived from capabilities + config + dimension)
    private boolean shadowsActive;
    private boolean postProcessActive;

    public FrameContext() {}

    /**
     * Reset for a new frame. Increments frame index.
     */
    public void beginFrame(float partialTicks, double worldTime) {
        this.frameIndex++;
        this.partialTicks = partialTicks;
        this.worldTime = worldTime;
    }

    // ── Sub-state accessors (mutable for capture phase, read-only after) ──

    public CameraState              getCamera()       { return camera; }
    public DimensionState           getDimension()    { return dimension; }
    public WeatherState             getWeather()      { return weather; }
    public FogState                 getFog()          { return fog; }
    public GlobalLightState         getGlobalLight()  { return globalLight; }
    public ShadowState              getShadow()       { return shadow; }
    public RenderCapabilityState    getCapabilities() { return capabilities; }

    // ── Frame-level accessors ──

    public long   getFrameIndex()   { return frameIndex; }
    public float  getPartialTicks() { return partialTicks; }
    public double getWorldTime()    { return worldTime; }

    public boolean isShadowsActive()      { return shadowsActive; }
    public boolean isPostProcessActive()  { return postProcessActive; }

    public void setShadowsActive(boolean v)     { this.shadowsActive = v; }
    public void setPostProcessActive(boolean v) { this.postProcessActive = v; }
}
