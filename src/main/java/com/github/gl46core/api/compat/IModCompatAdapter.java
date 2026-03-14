package com.github.gl46core.api.compat;

/**
 * Generic compatibility adapter interface for mod-specific integrations.
 *
 * Each mod that needs special rendering treatment can provide an adapter
 * that registers appropriate hooks with gl46core. This is the base
 * interface — specific mods extend it with their own methods.
 *
 * The adapter lifecycle:
 *   1. Mod detected during init → adapter instantiated
 *   2. install() called → registers providers/listeners with RenderRegistry
 *   3. Adapter active during gameplay
 *   4. uninstall() called on shutdown or mod unload
 */
public interface IModCompatAdapter {

    /**
     * Mod ID this adapter is for (e.g. "celeritas", "optifine", "dynamiclights").
     */
    String getModId();

    /**
     * Human-readable name for logging.
     */
    String getDisplayName();

    /**
     * Whether the target mod is loaded and this adapter can function.
     */
    boolean isModPresent();

    /**
     * Install hooks and register with RenderRegistry.
     */
    void install();

    /**
     * Remove hooks and unregister from RenderRegistry.
     */
    void uninstall();

    /**
     * Whether this adapter is currently active.
     */
    boolean isActive();
}
