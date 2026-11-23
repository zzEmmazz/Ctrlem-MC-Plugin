package com.ctrlem.mcbridge;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import org.lwjgl.glfw.GLFW;

/** Tick-based synthetic key holds, with physical-key restoration on completion. */
final class KeyHolder {
    private static final Map<KeyMapping, Integer> HELD = new HashMap<>();
    private KeyHolder() {}
    static void hold(KeyMapping key, int ticks) { HELD.merge(key, ticks, Math::max); }
    static void onStartTick(Minecraft client) {
        Iterator<Map.Entry<KeyMapping, Integer>> iterator = HELD.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<KeyMapping, Integer> e = iterator.next();
            KeyMapping key = e.getKey();
            if (client.player == null || client.screen != null || !Config.INSTANCE.enabled || e.getValue() <= 0) {
                // Do not leave movement keys pressed after the timer expires or a screen opens.
                key.setDown(physicallyDown(client, key));
                iterator.remove();
            } else {
                key.setDown(true);
                e.setValue(e.getValue() - 1);
            }
        }
    }
    private static boolean physicallyDown(Minecraft mc, KeyMapping key) {
        // Forge 1.20.1 exposes the currently bound key; restore its real physical state.
        if (key.isUnbound()) return false;
        InputConstants.Key bound = key.getKey();
        return switch (bound.getType()) {
            case KEYSYM -> InputConstants.isKeyDown(mc.getWindow().getWindow(), bound.getValue());
            case MOUSE -> GLFW.glfwGetMouseButton(mc.getWindow().getWindow(), bound.getValue()) == GLFW.GLFW_PRESS;
            default -> false;
        };
    }
}
