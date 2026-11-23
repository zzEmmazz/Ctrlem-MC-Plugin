package com.ctrlem.mcbridge;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * "Drop whole inventory": throws the stacks out in a circle over a few ticks, turning the player a
 * full 360° as it goes, aimed a little upward so they land a few blocks away.
 *
 * <p>Dropping everything in one go leaves it all at the player's feet, and the game hands thrown
 * items back after two seconds to anyone standing on them — so it looked like nothing happened.
 * The server throws items the way the player faces, so the direction has to reach it before each
 * batch: a turn set during one tick is sent with that tick's movement, and the batch goes out in
 * the next tick's Post, after it. That's also why nothing is sent as extra packets.
 */
@EventBusSubscriber(modid = CtrlEmMod.MODID, value = Dist.CLIENT)
final class InventoryThrower {
    private static final int STACKS_PER_TICK = 3;
    /** Degrees above level; throws arc further than from a flat or downward look. */
    private static final float THROW_PITCH = -25.0F;

    /** Game thread only. Empty when not throwing. */
    private static final Deque<Integer> REMAINING = new ArrayDeque<>();
    private static float yawStep;
    private static float originalPitch;
    /** Whether this tick's movement packet has carried the first direction yet. */
    private static boolean aimed;

    private InventoryThrower() {}

    static boolean isThrowing() {
        return !REMAINING.isEmpty();
    }

    /** {@code slots} are inventory-menu slot numbers holding something. */
    static void start(LocalPlayer player, List<Integer> slots) {
        REMAINING.clear();
        REMAINING.addAll(slots);
        int batches = (slots.size() + STACKS_PER_TICK - 1) / STACKS_PER_TICK;
        yawStep = 360.0F / batches;
        originalPitch = player.getXRot();
        player.setXRot(THROW_PITCH);
        aimed = false;
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        if (REMAINING.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        // A different container numbers its slots differently; stop rather than throw the wrong ones.
        if (player == null || mc.gameMode == null || player.isDeadOrDying() || player.containerMenu != player.inventoryMenu) {
            REMAINING.clear();
            return;
        }
        // Paused: the player doesn't tick, so no direction reaches the server — and in singleplayer
        // the server isn't running either. Carry on once they're back.
        if (mc.isPaused()) return;
        if (!aimed) {
            aimed = true; // the start() pitch went out with this tick's movement
            return;
        }

        for (int i = 0; i < STACKS_PER_TICK && !REMAINING.isEmpty(); i++) {
            throwStack(mc, player, REMAINING.poll());
        }
        if (REMAINING.isEmpty()) {
            player.setXRot(originalPitch); // a full circle leaves the yaw where it started
        } else {
            player.setYRot(player.getYRot() + yawStep);
        }
    }

    private static void throwStack(Minecraft mc, LocalPlayer player, int slotIndex) {
        InventoryMenu menu = player.inventoryMenu;
        Slot slot = menu.getSlot(slotIndex);
        if (!slot.hasItem() || !slot.mayPickup(player)) return; // Curse of Binding stays put
        if (player.hasInfiniteMaterials()) {
            // Creative's own way, as its inventory screen does it: a normal throw would also fire
            // creative's drop, and every stack would land twice.
            ItemStack thrown = slot.remove(slot.getItem().getCount());
            player.drop(thrown, true);
            mc.gameMode.handleCreativeModeItemDrop(thrown);
            mc.gameMode.handleCreativeModeItemAdd(slot.getItem(), slotIndex);
        } else {
            // Exactly what Ctrl+Q over a slot sends, so the server applies its own rules as usual.
            mc.gameMode.handleContainerInput(menu.containerId, slotIndex, 1, ContainerInput.THROW, player);
        }
    }
}
