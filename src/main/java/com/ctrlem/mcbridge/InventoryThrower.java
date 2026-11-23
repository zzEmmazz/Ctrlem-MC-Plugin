package com.ctrlem.mcbridge;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;

/** Survival inventory slots: 9-44, optional armor 5-8 and offhand 45. */
final class InventoryThrower {
    private static final Deque<Integer> REMAINING = new ArrayDeque<>();
    private static float yawStep, originalPitch;
    private static boolean aimed;
    private InventoryThrower() {}
    static boolean isThrowing() { return !REMAINING.isEmpty(); }
    static void start(LocalPlayer player, List<Integer> slots) {
        REMAINING.clear(); REMAINING.addAll(slots);
        yawStep = 360f / ((slots.size() + 2) / 3);
        originalPitch = player.getXRot();
        player.setXRot(-25f); aimed = false;
    }
    static void onEndTick(Minecraft mc) {
        if (REMAINING.isEmpty()) return;
        LocalPlayer player = mc.player;
        if (player == null || mc.gameMode == null || !player.isAlive() ||
            player.containerMenu != player.inventoryMenu || !Config.INSTANCE.enabled ||
            !Config.allows("drop_inventory")) { REMAINING.clear(); return; }
        if (mc.isPaused()) return;
        if (!aimed) { aimed = true; return; }
        for (int i = 0; i < 3 && !REMAINING.isEmpty(); i++) {
            int slotIndex = REMAINING.removeFirst();
            InventoryMenu menu = player.inventoryMenu;
            Slot slot = menu.getSlot(slotIndex);
            if (slot.hasItem() && slot.mayPickup(player))
                mc.gameMode.handleInventoryMouseClick(menu.containerId, slotIndex, 1, ClickType.THROW, player);
        }
        if (REMAINING.isEmpty()) player.setXRot(originalPitch);
        else player.setYRot(player.getYRot() + yawStep);
    }
}
