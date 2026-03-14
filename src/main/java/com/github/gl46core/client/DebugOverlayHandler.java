package com.github.gl46core.client;

import com.github.gl46core.api.debug.RenderDebugOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Handles the debug overlay keybind and HUD rendering.
 *
 * Press F4 to toggle the gl46core debug overlay showing FPS,
 * draw calls, shader switches, buffer uploads, and compat stats.
 */
public final class DebugOverlayHandler {

    private static final KeyBinding KEY_TOGGLE_OVERLAY = new KeyBinding(
            "key.gl46core.debugOverlay", GLFW.GLFW_KEY_F4, "key.categories.gl46core"
    );

    private DebugOverlayHandler() {}

    public static void register() {
        ClientRegistry.registerKeyBinding(KEY_TOGGLE_OVERLAY);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new DebugOverlayHandler());
    }

    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent event) {
        if (KEY_TOGGLE_OVERLAY.isPressed()) {
            RenderDebugOverlay.INSTANCE.toggle();
        }
    }

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Text event) {
        RenderDebugOverlay overlay = RenderDebugOverlay.INSTANCE;
        if (!overlay.isEnabled()) return;

        Minecraft mc = Minecraft.getMinecraft();
        int y = 2;
        for (String line : overlay.getLines()) {
            mc.fontRenderer.drawStringWithShadow(line, 2, y, 0xFFFFFF);
            y += 10;
        }
    }
}
