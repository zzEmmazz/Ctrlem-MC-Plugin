package com.ctrlem.mcbridge;

import com.google.gson.JsonObject;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Tells the bridge what the game is doing, for the plugin's status on CtrlEm.
 *
 * <p>Every change ends up as a server request and an entry in the command sender's Results feed,
 * so this reports only slow-moving facts — nothing like health or position that changes
 * constantly — and nothing that identifies the player: no username, server address or coordinates.
 */
@EventBusSubscriber(modid = CtrlEmMod.MODID, value = Dist.CLIENT)
final class StateReporter {
    private static final int INTERVAL_TICKS = 20;

    /** Game thread only. */
    private static JsonObject lastSent;
    private static int countdown;

    private StateReporter() {}

    /** Called on (re)connect, when the bridge has forgotten everything. */
    static void resend() {
        lastSent = null;
        countdown = 0;
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        if (--countdown > 0) return;
        countdown = INTERVAL_TICKS;

        JsonObject state = snapshot(Minecraft.getInstance());
        if (state.equals(lastSent)) return;
        lastSent = state;

        JsonObject message = new JsonObject();
        message.addProperty("type", "state");
        message.add("state", state);
        BridgeConnection.send(message);
    }

    private static JsonObject snapshot(Minecraft mc) {
        JsonObject state = new JsonObject();
        boolean inWorld = mc.player != null && mc.level != null;
        state.addProperty("in_world", inWorld);
        if (inWorld) {
            Identifier dimension = mc.level.dimension().identifier();
            state.addProperty("dimension", dimension.getNamespace().equals("minecraft") ? dimension.getPath() : dimension.toString());
            state.addProperty("mode", mc.hasSingleplayerServer() ? "singleplayer" : "multiplayer");
        }
        state.addProperty("commands_enabled", Config.SPEC.isLoaded() && Config.ENABLED.get());
        return state;
    }
}
