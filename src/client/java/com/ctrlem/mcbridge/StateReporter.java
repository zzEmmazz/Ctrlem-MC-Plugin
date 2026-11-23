package com.ctrlem.mcbridge;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

final class StateReporter {
    private static JsonObject previous;
    private static int countdown;
    private StateReporter() {}

    static void resend() { previous = null; countdown = 0; }

    static void onEndTick(Minecraft client) {
        if (--countdown > 0) return;
        countdown = 20;
        JsonObject state = snapshot(client);
        if (state.equals(previous)) return;
        previous = state;
        JsonObject message = new JsonObject();
        message.addProperty("type", "state");
        message.add("state", state);
        BridgeConnection.send(message);
    }

    static JsonObject snapshot(Minecraft client) {
        JsonObject state = new JsonObject();
        boolean inWorld = client.player != null && client.level != null;
        state.addProperty("in_world", inWorld);
        if (inWorld) {
            Identifier dimension = client.level.dimension().identifier();
            state.addProperty("dimension", dimension.getNamespace().equals("minecraft") ? dimension.getPath() : dimension.toString());
            state.addProperty("mode", client.hasSingleplayerServer() ? "singleplayer" : "multiplayer");
        }
        state.addProperty("commands_enabled", Config.INSTANCE.enabled);
        return state;
    }
}
