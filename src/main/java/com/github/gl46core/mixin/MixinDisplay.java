package com.github.gl46core.mixin;

import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sets GLFW window hints to request an OpenGL 4.6 core profile context
 * before Cleanroom's Display shim calls glfwCreateWindow().
 *
 * This replaces the old ForgeEarlyConfig reflection approach which wrote
 * to forge_early.cfg on disk — causing Minecraft to stay in core profile
 * mode even after mod removal, crashing on glAlphaFunc.
 *
 * GLFW window hints are purely in-process state, reset by glfwInit() on
 * every JVM launch. When this mod is removed the mixin never runs, the
 * hints are never set, and Cleanroom falls back to its default compat
 * profile as if the mod was never installed.
 */
@Mixin(targets = "org.lwjgl.opengl.Display", remap = false)
public class MixinDisplay {

    private static void gl46core$applyHints() {
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 6);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
    }

    /**
     * Set hints early — may be overwritten by Cleanroom's own hint setup,
     * but serves as fallback if the INVOKE injections below don't match.
     */
    @Inject(method = "create*", at = @At("HEAD"), require = 0)
    private static void gl46core$setCoreProfileHintsHead(CallbackInfo ci) {
        gl46core$applyHints();
    }

    /**
     * Set hints right before glfwCreateWindow (CharSequence overload) —
     * after Cleanroom's own hint setup, so ours take priority.
     */
    @Inject(method = "create*",
            at = @At(value = "INVOKE",
                     target = "Lorg/lwjgl/glfw/GLFW;glfwCreateWindow(IILjava/lang/CharSequence;JJ)J"),
            require = 0)
    private static void gl46core$setCoreProfileHints(CallbackInfo ci) {
        gl46core$applyHints();
    }

    /**
     * Fallback for ByteBuffer overload of glfwCreateWindow.
     */
    @Inject(method = "create*",
            at = @At(value = "INVOKE",
                     target = "Lorg/lwjgl/glfw/GLFW;glfwCreateWindow(IILjava/nio/ByteBuffer;JJ)J"),
            require = 0)
    private static void gl46core$setCoreProfileHintsBB(CallbackInfo ci) {
        gl46core$applyHints();
    }

}
