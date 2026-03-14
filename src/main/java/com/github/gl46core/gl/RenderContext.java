package com.github.gl46core.gl;

import com.github.gl46core.GL46Core;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL45;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Central owner of all GL object handles and subsystem references.
 *
 * <p>Every VAO, VBO, UBO, EBO, and shader program in gl46core is created,
 * validated, and destroyed through this singleton.  No other class should
 * call {@code glCreate*} or {@code glDelete*} directly — they request
 * handles from RenderContext and return them when done.</p>
 *
 * <p>Benefits over the previous scattered approach:</p>
 * <ul>
 *   <li>Single init/destroy point — no more hunting for leaked handles</li>
 *   <li>Debug validation — accessing a destroyed or uninitialized handle
 *       logs immediately instead of producing a cryptic GL error later</li>
 *   <li>Discoverable — all GL objects listed in one enum</li>
 *   <li>Zero overhead — handles stored in a flat int array indexed by ordinal</li>
 * </ul>
 *
 * <p>Subsystem access:</p>
 * <pre>
 *   RenderContext.get().state()     → CoreStateTracker
 *   RenderContext.get().matrices()  → CoreMatrixStack
 *   RenderContext.get().shader()    → CoreShaderProgram
 *   RenderContext.get().textures()  → CoreTextureTracker
 *   RenderContext.get().handle(GL.IMMEDIATE_VAO)
 * </pre>
 */
public final class RenderContext {

    private static final RenderContext INSTANCE = new RenderContext();

    public static RenderContext get() { return INSTANCE; }

    // ═══════════════════════════════════════════════════════════════════
    // GL object handle registry
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Named GL object slots.  Every GL handle in gl46core has exactly one
     * entry here.  The ordinal is the index into the flat handle array.
     */
    public enum GL {
        // Shader
        SHADER_PROGRAM,

        // UBOs
        PER_FRAME_UBO,
        PER_DRAW_UBO,

        // CoreDrawHandler (BufferBuilder immediate draws)
        IMMEDIATE_VAO,
        IMMEDIATE_VBO,

        // CoreVboDrawHandler (terrain + legacy vertex arrays)
        TERRAIN_VAO,
        GENERAL_VAO,

        // DisplayListCache replay
        DISPLAY_LIST_VAO,
        DISPLAY_LIST_VBO,

        // Shared quad→triangle index buffer
        QUAD_EBO,

        // 1x1 white dummy texture — bound to unit 1 when no lightmap is active
        DUMMY_TEXTURE,
    }

    private final int[] handles = new int[GL.values().length];
    private final boolean[] alive = new boolean[GL.values().length];

    /**
     * Thread-local VAO/VBO pairs for ImmediateModeEmulator.
     * Key = thread ID, Value = [vao, vbo].
     * VAOs are per-context (not shared between GL contexts).
     */
    private final ConcurrentHashMap<Long, int[]> threadLocalHandles = new ConcurrentHashMap<>();

    // ── Handle lifecycle ─────────────────────────────────────────────

    /**
     * Create a VAO via DSA and store it in the given slot.
     * If the slot already holds a live handle, the old one is deleted first.
     */
    public int createVAO(GL slot) {
        destroyIfAlive(slot);
        int[] vaos = new int[1];
        GL45.glCreateVertexArrays(vaos);
        handles[slot.ordinal()] = vaos[0];
        alive[slot.ordinal()] = true;
        GL46Core.LOGGER.debug("[RenderContext] Created VAO {} = {}", slot, vaos[0]);
        return vaos[0];
    }

    /**
     * Create a buffer (VBO/UBO/EBO) via DSA and store it in the given slot.
     * If the slot already holds a live handle, the old one is deleted first.
     */
    public int createBuffer(GL slot) {
        destroyIfAlive(slot);
        int[] bufs = new int[1];
        GL45.glCreateBuffers(bufs);
        handles[slot.ordinal()] = bufs[0];
        alive[slot.ordinal()] = true;
        GL46Core.LOGGER.debug("[RenderContext] Created buffer {} = {}", slot, bufs[0]);
        return bufs[0];
    }

    /**
     * Create a 1x1 white texture via DSA and store it in the given slot.
     * Used as a fallback on texture units that must have a valid texture bound.
     */
    public int createDummyTexture(GL slot) {
        destroyIfAlive(slot);
        int tex = GL45.glCreateTextures(GL11.GL_TEXTURE_2D);
        GL45.glTextureStorage2D(tex, 1, org.lwjgl.opengl.GL30.GL_RGBA8, 1, 1);
        java.nio.ByteBuffer pixel = java.nio.ByteBuffer.allocateDirect(4).order(java.nio.ByteOrder.nativeOrder());
        pixel.put((byte)0xFF).put((byte)0xFF).put((byte)0xFF).put((byte)0xFF).flip();
        GL45.glTextureSubImage2D(tex, 0, 0, 0, 1, 1,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixel);
        handles[slot.ordinal()] = tex;
        alive[slot.ordinal()] = true;
        GL46Core.LOGGER.debug("[RenderContext] Created dummy texture {} = {}", slot, tex);
        return tex;
    }

    /**
     * Create a shader program and store it in the given slot.
     */
    public int createProgram(GL slot) {
        destroyIfAlive(slot);
        int id = GL20.glCreateProgram();
        handles[slot.ordinal()] = id;
        alive[slot.ordinal()] = true;
        GL46Core.LOGGER.debug("[RenderContext] Created program {} = {}", slot, id);
        return id;
    }

    /**
     * Store an externally-created handle (e.g. from glCreateProgram already called).
     * Use sparingly — prefer the create* methods.
     */
    public void store(GL slot, int handle) {
        destroyIfAlive(slot);
        handles[slot.ordinal()] = handle;
        alive[slot.ordinal()] = handle != 0;
    }

    /**
     * Replace a buffer handle in a slot (e.g. when re-allocating an immutable VBO).
     * Deletes the old buffer and stores the new one.
     */
    public void replaceBuffer(GL slot, int newHandle) {
        int old = handles[slot.ordinal()];
        if (old != 0 && alive[slot.ordinal()]) {
            GL45.glDeleteBuffers(old);
        }
        handles[slot.ordinal()] = newHandle;
        alive[slot.ordinal()] = newHandle != 0;
    }

    /**
     * Get a handle, with debug validation.
     * Returns 0 if the slot was never initialized (callers should check).
     */
    public int handle(GL slot) {
        int h = handles[slot.ordinal()];
        if (h == 0 && alive[slot.ordinal()]) {
            GL46Core.LOGGER.warn("[RenderContext] Handle {} is marked alive but value is 0!", slot);
        }
        return h;
    }

    /**
     * Get a handle, asserting it is alive and non-zero.
     * Use on the hot path only when you're certain the object was created.
     */
    public int require(GL slot) {
        int h = handles[slot.ordinal()];
        if (h == 0) {
            throw new IllegalStateException("[RenderContext] Required handle " + slot + " is 0 (not created or already destroyed)");
        }
        return h;
    }

    /**
     * Check if a slot holds a live, non-zero handle.
     */
    public boolean isAlive(GL slot) {
        return alive[slot.ordinal()] && handles[slot.ordinal()] != 0;
    }

    /**
     * Destroy the GL object in a slot (VAO, buffer, or program).
     */
    public void destroy(GL slot) {
        destroyIfAlive(slot);
    }

    private void destroyIfAlive(GL slot) {
        int h = handles[slot.ordinal()];
        if (h == 0 || !alive[slot.ordinal()]) return;

        switch (slot) {
            case SHADER_PROGRAM -> GL20.glDeleteProgram(h);
            case IMMEDIATE_VAO, TERRAIN_VAO, GENERAL_VAO, DISPLAY_LIST_VAO -> GL30.glDeleteVertexArrays(h);
            case DUMMY_TEXTURE -> GL11.glDeleteTextures(h);
            default -> GL45.glDeleteBuffers(h);
        }
        handles[slot.ordinal()] = 0;
        alive[slot.ordinal()] = false;
        GL46Core.LOGGER.debug("[RenderContext] Destroyed {} (was {})", slot, h);
    }

    // ── Thread-local handles (ImmediateModeEmulator) ─────────────────

    /**
     * Get or create a per-thread VAO+VBO pair for immediate mode emulation.
     * Returns int[2] = {vao, vbo}. Both are guaranteed non-zero after this call.
     */
    public int[] threadLocalVaoVbo() {
        long tid = Thread.currentThread().getId();
        return threadLocalHandles.computeIfAbsent(tid, k -> {
            int[] vaos = new int[1];
            GL45.glCreateVertexArrays(vaos);
            int[] bufs = new int[1];
            GL45.glCreateBuffers(bufs);
            GL46Core.LOGGER.debug("[RenderContext] Created thread-local VAO={} VBO={} for thread {}",
                    vaos[0], bufs[0], tid);
            return new int[]{vaos[0], bufs[0]};
        });
    }

    // ── Full lifecycle ───────────────────────────────────────────────

    /**
     * Destroy ALL GL objects owned by this context.
     * Called on shutdown or context loss.
     */
    public void destroyAll() {
        for (GL slot : GL.values()) {
            destroyIfAlive(slot);
        }
        // Thread-local handles
        for (int[] pair : threadLocalHandles.values()) {
            if (pair[0] != 0) GL30.glDeleteVertexArrays(pair[0]);
            if (pair[1] != 0) GL45.glDeleteBuffers(pair[1]);
        }
        threadLocalHandles.clear();
        GL46Core.LOGGER.info("[RenderContext] All GL objects destroyed");
    }

    // ═══════════════════════════════════════════════════════════════════
    // Subsystem accessors — convenient typed access to existing singletons
    // ═══════════════════════════════════════════════════════════════════

    public CoreStateTracker state() { return CoreStateTracker.INSTANCE; }
    public CoreMatrixStack matrices() { return CoreMatrixStack.INSTANCE; }
    public CoreShaderProgram shader() { return CoreShaderProgram.INSTANCE; }
    // CoreTextureTracker is accessed via static methods (markForDeletion, cancelDeletion, flushPendingDeletes)

    // ═══════════════════════════════════════════════════════════════════
    // Debug
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Log all live handles — useful for debugging leaks.
     */
    public void dumpHandles() {
        StringBuilder sb = new StringBuilder("[RenderContext] Live handles:\n");
        for (GL slot : GL.values()) {
            if (alive[slot.ordinal()] && handles[slot.ordinal()] != 0) {
                sb.append("  ").append(slot).append(" = ").append(handles[slot.ordinal()]).append('\n');
            }
        }
        sb.append("  Thread-local pairs: ").append(threadLocalHandles.size());
        GL46Core.LOGGER.info(sb.toString());
    }

    private RenderContext() {}
}
