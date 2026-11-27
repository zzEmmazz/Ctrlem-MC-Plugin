package com.ctrlem.mcbridge;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

/** Runtime hooks on Forge's client-side gameplay/event bus. */
@EventBusSubscriber(modid = CtrlEmMod.MODID, value = Dist.CLIENT)
public final class ClientEvents {
    private ClientEvents() {}

    @SubscribeEvent
    public static void onClientTickPre(ClientTickEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        Follower.onStartTick(mc);
        while (ClientSetup.SETTINGS_KEY.consumeClick()) {
            mc.setScreen(new ConfigScreen(mc.screen));
        }
    }

    @SubscribeEvent
    public static void onClientTickPost(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        InventoryThrower.onEndTick(mc);
        ImageOverlay.onEndTick(mc);
        StateReporter.onEndTick(mc);
    }

    @SubscribeEvent
    public static void onHudRender(RenderGuiLayerEvent.Post event) {
        ImageOverlay.render(event.getGuiGraphics());
    }
}
