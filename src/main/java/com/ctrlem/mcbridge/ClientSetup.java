package com.ctrlem.mcbridge;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.lwjgl.glfw.GLFW;

/** Events fired by the mod bus on physical clients only. */
@Mod.EventBusSubscriber(modid = CtrlEmMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ClientSetup {
    static final KeyMapping SETTINGS_KEY = new KeyMapping(
        "key.ctrlem.settings", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F8, "key.category.ctrlem.controls");

    private ClientSetup() {}

    @SubscribeEvent
    public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.register(SETTINGS_KEY);
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            Config.load();
            BridgeConnection.start();
            CtrlEmMod.LOGGER.info("CtrlEm local desktop bridge started. Settings: F8");
        });
    }
}
