package com.ctrlem.mcbridge;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.network.NetworkConstants;

/**
 * Client-only entry point. The mod adds no blocks, items or network payloads, so it can join any
 * server — vanilla included — and never needs to be installed server-side.
 *
 * <p>It connects out to the CtrlEm "mcbridge" plugin running alongside the CtrlEm client, and
 * carries out the commands that arrive (see {@link Actions}).
 */
@Mod(CtrlEmMod.MODID)
public class CtrlEmMod {
    public static final String MODID = "ctrlem";
    public static final Logger LOGGER = LogUtils.getLogger();

    public CtrlEmMod() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, Config.SPEC);
        // How a 1.18.2 mod says "client-only". Forge 40's @Mod has no dist argument and its
        // mods.toml has no per-mod side key, so without this a server that has never heard of the
        // mod would show players a red X, and a server that happens to carry the jar would demand
        // it of everyone. IGNORESERVERONLY keeps the mod out of the server's list entirely, and
        // the (remote, isServer) -> true says any version on the other end is acceptable.
        ModLoadingContext.get().registerExtensionPoint(IExtensionPoint.DisplayTest.class,
                () -> new IExtensionPoint.DisplayTest(() -> NetworkConstants.IGNORESERVERONLY, (remote, isServer) -> true));
        // Mods screen > CtrlEm > Config, so the player can switch commands off without editing
        // files. Behind DistExecutor because ConfigScreen reaches into client-only classes, which
        // a dedicated server has no copy of.
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> ConfigScreen::registerFactory);
        FMLJavaModLoadingContext.get().getModEventBus().addListener(CtrlEmMod::onClientSetup);
    }

    private static void onClientSetup(FMLClientSetupEvent event) {
        // Not in the constructor: the connection hands work to Minecraft.getInstance(), which
        // doesn't exist yet while mods are being constructed.
        event.enqueueWork(() -> {
            ImageOverlay.registerLayer();
            BridgeConnection.start();
        });
    }
}
