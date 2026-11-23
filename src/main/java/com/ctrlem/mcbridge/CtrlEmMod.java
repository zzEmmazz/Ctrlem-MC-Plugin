package com.ctrlem.mcbridge;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * Client-only entry point. The mod adds no blocks, items or network payloads, so it can join any
 * server — vanilla included — and never needs to be installed server-side.
 *
 * <p>It connects out to the CtrlEm "mcbridge" plugin running alongside the CtrlEm client, and
 * carries out the commands that arrive (see {@link Actions}).
 */
@Mod(value = CtrlEmMod.MODID, dist = Dist.CLIENT)
public class CtrlEmMod {
    public static final String MODID = "ctrlem";
    public static final Logger LOGGER = LogUtils.getLogger();

    public CtrlEmMod(IEventBus modEventBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, Config.SPEC);
        // Mods screen > CtrlEm > Config, so the player can switch commands off without editing files.
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        modEventBus.addListener(CtrlEmMod::onClientSetup);
    }

    private static void onClientSetup(FMLClientSetupEvent event) {
        // Not in the constructor: the connection hands work to Minecraft.getInstance(), which
        // doesn't exist yet while mods are being constructed.
        BridgeConnection.start();
    }
}
