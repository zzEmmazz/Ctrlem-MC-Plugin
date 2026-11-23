package com.ctrlem.mcbridge;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Runtime hooks on Forge's client-side gameplay/event bus. */
@Mod.EventBusSubscriber(modid = CtrlEmMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientEvents {
    private ClientEvents() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (event.phase == TickEvent.Phase.START) {
            Follower.onStartTick(mc);
            KeyHolder.onStartTick(mc);
            while (ClientSetup.SETTINGS_KEY.consumeClick()) {
                mc.setScreen(new ConfigScreen(mc.screen));
            }
        } else {
            InventoryThrower.onEndTick(mc);
            ImageOverlay.onEndTick(mc);
            StateReporter.onEndTick(mc);
        }
    }

    @SubscribeEvent
    public static void onHudRender(RenderGuiEvent.Post event) {
        ImageOverlay.render(event.getGuiGraphics());
    }
}
