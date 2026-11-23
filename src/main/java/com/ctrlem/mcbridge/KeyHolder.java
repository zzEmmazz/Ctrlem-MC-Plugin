package com.ctrlem.mcbridge;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Holds key mappings down for a number of ticks, as if the player were pressing them.
 *
 * <p>Sneak and sprint can be in toggle mode, where {@code setDown(true)} flips the key instead of
 * pressing it. So a key is only pressed when it isn't already down, and released by flipping it
 * back in toggle mode, or by restoring whatever the player is physically doing otherwise.
 */
@Mod.EventBusSubscriber(modid = CtrlEmMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
final class KeyHolder {
    /** Key → ticks left. Game thread only. */
    private static final Map<KeyMapping, Integer> HELD = new HashMap<>();

    private KeyHolder() {}

    static void hold(KeyMapping key, int ticks) {
        HELD.merge(key, ticks, Math::max);
    }

    // START, so the key is already down when this tick's movement reads the input.
    @SubscribeEvent
    static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
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
        // 1.18.2 only offers toggle mode for sneak and sprint, and they are plain boolean fields
        // rather than the option objects newer versions use. Attack and use gained toggles later,
        // so unlike the NeoForge build there is nothing to check for them here.
        if (key == options.keyShift) return options.toggleCrouch;
        if (key == options.keySprint) return options.toggleSprint;
        return false;
    }

    private static boolean isPhysicallyDown(Minecraft mc, KeyMapping key) {
        InputConstants.Key bound = key.getKey();
        switch (bound.getType()) {
            // 1.18.2 takes the raw GLFW window handle here, not the Window object.
            case KEYSYM:
                return InputConstants.isKeyDown(mc.getWindow().getWindow(), bound.getValue());
            case MOUSE:
                return GLFW.glfwGetMouseButton(mc.getWindow().getWindow(), bound.getValue()) == GLFW.GLFW_PRESS;
            default:
                return false;
        }
    }
}
