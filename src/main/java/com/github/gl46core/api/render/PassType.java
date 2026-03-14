package com.github.gl46core.api.render;

/**
 * Canonical render pass types.
 *
 * Defines the ordered stages of the render pipeline. Each pass type
 * has a default execution order; the pass graph can reorder or skip
 * passes based on capabilities and configuration.
 */
public enum PassType {

    // ── Shadow passes ──
    SHADOW_OPAQUE(0, "shadow_opaque", true),
    SHADOW_CUTOUT(1, "shadow_cutout", true),

    // ── Geometry passes ──
    SKY(10, "sky", false),
    TERRAIN_OPAQUE(20, "terrain_opaque", false),
    TERRAIN_CUTOUT(21, "terrain_cutout", false),
    ENTITY_OPAQUE(30, "entity_opaque", false),
    BLOCK_ENTITY(31, "block_entity", false),

    // ── Translucent passes ──
    TERRAIN_TRANSLUCENT(40, "terrain_translucent", false),
    ENTITY_TRANSLUCENT(41, "entity_translucent", false),
    WATER(42, "water", false),
    PARTICLES(50, "particles", false),
    WEATHER(51, "weather", false),

    // ── Overlay passes ──
    HAND(60, "hand", false),
    OUTLINE(61, "outline", false),

    // ── Post-processing ──
    POST_CHAIN(70, "post_chain", false),

    // ── UI / debug ──
    UI(80, "ui", false),
    DEBUG_OVERLAY(90, "debug_overlay", false);

    private final int defaultOrder;
    private final String id;
    private final boolean isShadowPass;

    PassType(int defaultOrder, String id, boolean isShadowPass) {
        this.defaultOrder = defaultOrder;
        this.id = id;
        this.isShadowPass = isShadowPass;
    }

    public int    getDefaultOrder() { return defaultOrder; }
    public String getId()           { return id; }
    public boolean isShadowPass()   { return isShadowPass; }
    public boolean isTranslucent()  {
        return this == TERRAIN_TRANSLUCENT || this == ENTITY_TRANSLUCENT
            || this == WATER || this == PARTICLES || this == WEATHER;
    }
}
