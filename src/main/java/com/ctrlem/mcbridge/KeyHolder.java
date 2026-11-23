package com.ctrlem.mcbridge;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Holds key mappings down for a number of ticks, as if the player were pressing them.
 *
 * <p>Sneak, sprint, attack and use can be in toggle mode, where {@code setDown(true)} flips the
 * key instead of pressing it. So a key is only pressed when it isn't already down, and released
 * by flipping it back in toggle mode, or by restoring whatever the player is physically doing
 * otherwise.
 */
@EventBusSubscriber(modid = CtrlEmMod.MODID, value = Dist.CLIENT)
final class KeyHolder {
    /** Key → ticks left. Game thread only. */
    private static final Map<KeyMapping, Integer> HELD = new HashMap<>();

    private KeyHolder() {}

    static void hold(KeyMapping key, int ticks) {
        HELD.merge(key, ticks, Math::max);
    }

    // Pre, so the key is already down when this tick's movement reads the input.
    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Pre event) {
        if (HELD.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        boolean inWorld = mc.player != null;

        Iterator<Map.Entry<KeyMapping, Integer>> it = HELD.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<KeyMapping, Integer> entry = it.next();
            KeyMapping key = entry.getKey();
            if (!inWorld || entry.getValue() <= 0) {
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
        if (key == options.keyAttack) return options.toggleAttack().get();
        if (key == options.keyUse) return options.toggleUse().get();
        return false;
    }

    private static boolean isPhysicallyDown(Minecraft mc, KeyMapping key) {
        InputConstants.Key bound = key.getKey();
        return switch (bound.getType()) {
            case KEYSYM -> InputConstants.isKeyDown(mc.getWindow(), bound.getValue());
            case MOUSE -> GLFW.glfwGetMouseButton(mc.getWindow().handle(), bound.getValue()) == GLFW.GLFW_PRESS;
            default -> false;
        };
    }
}
