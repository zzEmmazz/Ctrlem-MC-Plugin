package com.ctrlem.mcbridge;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping.Category;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CtrlEmMod implements ClientModInitializer {
    public static final String MODID = "ctrlem";
    public static final Logger LOGGER = LoggerFactory.getLogger(MODID);
    private static KeyMapping settingsKey;

    @Override
    public void onInitializeClient() {
        Config.load();
        settingsKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.ctrlem.settings", GLFW.GLFW_KEY_F8,
            Category.register(Identifier.fromNamespaceAndPath(MODID, "controls"))));
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            KeyHolder.onStartTick(client);
            Follower.onStartTick(client);
            while (settingsKey.consumeClick()) client.setScreen(new ConfigScreen(client.screen));
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            InventoryThrower.onEndTick(client);
            ImageOverlay.onEndTick(client);
            StateReporter.onEndTick(client);
        });
        ImageOverlay.registerLayer();
        BridgeConnection.start();
        LOGGER.info("CtrlEm Fabric 1.21.11 bridge started. Settings: F8");
    }
}
