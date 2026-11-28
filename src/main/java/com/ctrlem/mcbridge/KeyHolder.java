package com.ctrlem.mcbridge;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import net.minecraft.client.Options;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.lwjgl.glfw.GLFW;

/** Tick-based synthetic key holds, with physical-key restoration on completion. */
@EventBusSubscriber(modid = CtrlEmMod.MODID, value = Dist.CLIENT)
public final class KeyHolder {
    private static final Map<KeyMapping, Integer> HELD = new HashMap<>();

    private KeyHolder() {}

    static void hold(KeyMapping key, int ticks) { HELD.merge(key, ticks, Math::max); }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Pre event) {
        if (HELD.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        boolean active = mc.player != null && mc.screen == null && Config.INSTANCE.enabled;

        Iterator<Map.Entry<KeyMapping, Integer>> it = HELD.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<KeyMapping, Integer> entry = it.next();
            KeyMapping key = entry.getKey();
            if (!active || entry.getValue() <= 0) {
                release(mc, key);
                it.remove();
            } else {
                // Re-asserted every tick: a physical key-up or a menu closing would otherwise cut it short.
                if (!key.isDown()) key.setDown(true);
                entry.setValue(entry.getValue() - 1);
            }
        }
    }

    private static void release(Minecraft mc, KeyMapping key) {
        if (isToggleMode(mc.options, key)) {
            if (key.isDown()) key.setDown(true);
        } else {
            key.setDown(isPhysicallyDown(mc, key));
        }
    }

    private static boolean isToggleMode(Options options, KeyMapping key) {
        if (key == options.keyShift) return options.toggleCrouch().get();
        if (key == options.keySprint) return options.toggleSprint().get();
        return false;
    }

    private static boolean isPhysicallyDown(Minecraft mc, KeyMapping key) {
        if (key.isUnbound()) return false;
        InputConstants.Key bound = key.getKey();
        long windowHandle = mc.getWindow().getWindow();

        return switch (bound.getType()) {
            case KEYSYM -> InputConstants.isKeyDown(windowHandle, bound.getValue());
            case MOUSE -> GLFW.glfwGetMouseButton(windowHandle, bound.getValue()) == GLFW.GLFW_PRESS;
            default -> false;
        };
    }
}
