package com.github.gl46core.gl;

import org.lwjgl.opengl.GL11;

import java.util.HashSet;
import java.util.Set;

/**
 * Defers texture deletion to handle the compatibility-profile pattern of
 * glDeleteTextures(id) followed by glBindTexture(target, id).
 *
 * In compatibility profile, binding a deleted name re-creates the texture
 * object with that name. Core profile does not support this — the name
 * becomes permanently invalid after deletion. This tracker defers the
 * actual glDeleteTextures call so that a subsequent bind can cancel it,
 * keeping the name valid.
 *
 * Pending deletes are flushed once per frame via {@link #flushPendingDeletes()}.
 */
public final class CoreTextureTracker {

    private static final Set<Integer> pendingDeletes = new HashSet<>();

    private CoreTextureTracker() {}

    /**
     * Mark a texture for deferred deletion.  The actual glDeleteTextures
     * call is postponed until {@link #flushPendingDeletes()}.
     */
    public static void markForDeletion(int texture) {
        if (texture > 0) {
            pendingDeletes.add(texture);
        }
    }

    /**
     * If {@code texture} is pending deletion, cancel it (the caller is
     * about to re-use the name).
     *
     * @return true if the texture was rescued from pending deletion
     */
    public static boolean cancelDeletion(int texture) {
        return texture > 0 && pendingDeletes.remove(texture);
    }

    /**
     * Actually delete all textures that were marked and never re-bound.
     * Called once per frame from {@link CoreShaderProgram#endFrame()}.
     */
    public static void flushPendingDeletes() {
        if (pendingDeletes.isEmpty()) return;
        for (int id : pendingDeletes) {
            GL11.glDeleteTextures(id);
        }
        pendingDeletes.clear();
    }
}
