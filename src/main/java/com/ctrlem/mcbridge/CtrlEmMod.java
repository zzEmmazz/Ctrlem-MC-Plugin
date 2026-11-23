package com.ctrlem.mcbridge;

import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Forge entrypoint. Client-only systems are registered through Dist.CLIENT event subscribers. */
@Mod(CtrlEmMod.MODID)
public final class CtrlEmMod {
    public static final String MODID = "ctrlem";
    public static final Logger LOGGER = LoggerFactory.getLogger(MODID);

    public CtrlEmMod() {
        LOGGER.info("CtrlEm Forge 1.20.1 loaded; press F8 for CtrlEm settings");
    }
}
