package com.ctrlem.mcbridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.entity.player.Inventory;

/** All 11 actions from the Forge mod, with original names/arguments and result errors. */
final class Actions {
    private static long readyAtMillis;
    private static final Map<String,String> LABELS = Map.ofEntries(
        Map.entry("jump", "Jump"), Map.entry("hold_key", "Hold a key"),
        Map.entry("turn", "Turn"), Map.entry("hotbar", "Switch hotbar slot"),
        Map.entry("drop_item", "Drop held item"), Map.entry("drop_inventory", "Drop whole inventory"),
        Map.entry("play_sound", "Play a sound"), Map.entry("show_title", "Show a message"),
        Map.entry("chat", "Send a chat message"), Map.entry("follow", "Follow a player"),
        Map.entry("show_image", "Show an image"));
    private static final Map<String,String> SOUNDS = Map.of(
        "creeper_hiss", "entity.creeper.primed", "ghast_scream", "entity.ghast.scream",
        "enderman_stare", "entity.enderman.stare", "warden_emerge", "entity.warden.emerge",
        "thunder", "entity.lightning_bolt.thunder", "anvil", "block.anvil.land",
        "explosion", "entity.generic.explode", "cave_noise", "ambient.cave");

    private Actions() {}

    static CompletableFuture<String> run(String action, JsonObject args) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return done("the player isn't in a world");
        if (!Config.INSTANCE.enabled) return done("the player has CtrlEm commands switched off");
        if (!LABELS.containsKey(action)) return done("this command needs a newer version of the CtrlEm mod");
        if (!Config.allows(action)) return done("the player has switched this command off");
        long now = System.currentTimeMillis();
        if (now < readyAtMillis) return done("on cooldown, try again in a moment");

        if ("show_image".equals(action)) {
            String link = string(args, "url", "").strip();
            if (link.isBlank()) return done("no image link given");
            readyAtMillis = now + Config.INSTANCE.cooldownSeconds * 1000L;
            int seconds = (int)clamp(number(args, "seconds", 5), 1, Config.INSTANCE.maxImageSeconds);
            return ImageOverlay.show(link, seconds);
        }

        String error = switch (action) {
            case "jump" -> jump(mc);
            case "hold_key" -> holdKey(mc, args);
            case "turn" -> turn(player, args);
            case "hotbar" -> hotbar(player, args);
            case "drop_item" -> dropItem(player, args);
            case "drop_inventory" -> dropInventory(mc, player, args);
            case "play_sound" -> playSound(mc, player, args);
            case "show_title" -> showTitle(mc, args);
            case "chat" -> chat(mc, player, args);
            case "follow" -> follow(mc, player, args);
            default -> "unknown command";
        };
        if (error == null) {
            readyAtMillis = now + Config.INSTANCE.cooldownSeconds * 1000L;
            if (Config.INSTANCE.notify && !action.equals("show_title"))
                player.displayClientMessage(Component.literal("CtrlEm: " + LABELS.get(action)), true);
        }
        return done(error);
    }

    private static CompletableFuture<String> done(String error) { return CompletableFuture.completedFuture(error); }
    private static String jump(Minecraft mc) {
        if (mc.screen != null) return "the player has a menu open";
        KeyHolder.hold(mc.options.keyJump, 4);
        return null;
    }
    private static String holdKey(Minecraft mc, JsonObject args) {
        if (mc.screen != null) return "the player has a menu open";
        KeyMapping key = keyFor(mc.options, string(args, "key", "forward"));
        if (key == null) return "unknown key";
        if (key.isUnbound()) return "the player has no key bound for that";
        int seconds = (int)clamp(number(args, "seconds", 3), 1, Config.INSTANCE.maxHoldSeconds);
        KeyHolder.hold(key, seconds * 20);
        return null;
    }
    private static KeyMapping keyFor(Options o, String name) {
        return switch (name) {
            case "forward" -> o.keyUp;
            case "back" -> o.keyDown;
            case "left" -> o.keyLeft;
            case "right" -> o.keyRight;
            case "jump" -> o.keyJump;
            case "sneak" -> o.keyShift;
            case "sprint" -> o.keySprint;
            case "attack" -> o.keyAttack;
            case "use" -> o.keyUse;
            default -> null;
        };
    }
    private static String turn(LocalPlayer player, JsonObject args) {
        float delta = (float)clamp(number(args, "degrees", 180), -180, 180);
        float yaw = player.getYRot() + delta;
        player.setYRot(yaw);
        player.yRotO = yaw;
        return null;
    }
    private static String hotbar(LocalPlayer player, JsonObject args) {
        Inventory inventory = player.getInventory();
        String slot = string(args, "slot", "random");
        int selected;
        if (slot.equals("random")) selected = (inventory.selected + 1 + ThreadLocalRandom.current().nextInt(8)) % 9;
        else {
            try { selected = Integer.parseInt(slot) - 1; }
            catch (NumberFormatException ex) { return "unknown slot"; }
            if (selected < 0 || selected > 8) return "slot must be 1-9";
        }
        inventory.selected = selected;
        return null;
    }
    private static String dropItem(LocalPlayer player, JsonObject args) {
        if (player.isSpectator()) return "the player is spectating";
        if (player.getInventory().getSelected().isEmpty()) return "the player isn't holding anything";
        if (player.drop(bool(args,"whole_stack"))) player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        return null;
    }
    private static String dropInventory(Minecraft mc, LocalPlayer player, JsonObject args) {
        if (player.isSpectator()) return "the player is spectating";
        if (mc.gameMode == null) return "the player isn't in a world";
        if (player.containerMenu != player.inventoryMenu) return "the player has a container open";
        if (InventoryThrower.isThrowing()) return "already throwing everything";
        // Creative inventory interactions are server-authoritative and differ from survival click packets.
        if (player.getAbilities().instabuild) return "drop_inventory is only supported in survival mode";
        ArrayList<Integer> slots = new ArrayList<>();
        for (int i = 9; i < 45; i++) slots.add(i);
        if (bool(args, "include_armor")) {
            for (int i = 5; i < 9; i++) slots.add(i);
            slots.add(45);
        }
        slots.removeIf(i -> !player.inventoryMenu.getSlot(i).hasItem());
        if (slots.isEmpty()) return "the player's inventory is already empty";
        InventoryThrower.start(player, slots);
        return null;
    }
    private static String playSound(Minecraft mc, LocalPlayer player, JsonObject args) {
        String name = string(args, "sound", "creeper_hiss");
        String id = SOUNDS.get(name);
        if (id == null) return "unknown sound";
        double yaw = Math.toRadians(player.getYRot());
        double x = player.getX() + Math.sin(yaw)*2;
        double z = player.getZ() - Math.cos(yaw)*2;
        SoundSource source = switch (name) {
            case "thunder" -> SoundSource.WEATHER;
            case "anvil", "explosion" -> SoundSource.BLOCKS;
            case "cave_noise" -> SoundSource.AMBIENT;
            default -> SoundSource.HOSTILE;
        };
        SoundEvent event = net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.get(new ResourceLocation(id));
        mc.level.playLocalSound(x, player.getY(), z, event, source, 1f, 1f, false);
        return null;
    }
    private static String showTitle(Minecraft mc, JsonObject args) {
        String message = sanitize(string(args, "text", ""));
        if (message.isEmpty()) return "no message given";
        mc.gui.clear();
        if (message.length() <= 20) {
            mc.gui.setSubtitle(Component.empty());
            mc.gui.setTitle(Component.literal(message));
        } else {
            mc.gui.setSubtitle(Component.literal(message));
            mc.gui.setTitle(Component.empty());
        }
        return null;
    }
    private static String chat(Minecraft mc, LocalPlayer player, JsonObject args) {
        if (mc.options.chatVisibility().get() != ChatVisiblity.FULL) return "the player has chat turned off";
        String text = string(args, "text", "").replaceAll("\\s+", " ").strip();
        text = sanitizeChat(text);
        if (text.length() > 256) text = text.substring(0, 256);
        if (text.isEmpty()) return "no message given";
        if (text.startsWith("/")) return "commands can't be sent";
        player.connection.sendChat(text);
        return null;
    }
    private static String follow(Minecraft mc, LocalPlayer player, JsonObject args) {
        String name = string(args, "player", "").strip();
        AbstractClientPlayer target = Follower.findTarget(mc, player, name);
        if (target == null) return name.isEmpty() ? "no other players nearby" : "that player isn't nearby";
        int seconds = (int)clamp(number(args,"seconds",20),1,Config.INSTANCE.maxFollowSeconds);
        Follower.start(target, seconds*20);
        return null;
    }
    private static String sanitize(String in) {
        StringBuilder b = new StringBuilder();
        in.codePoints().filter(cp -> cp != 167 && !Character.isISOControl(cp)).limit(80)
          .forEach(b::appendCodePoint);
        return b.toString().strip();
    }
    private static String sanitizeChat(String text) {
        StringBuilder out = new StringBuilder();
        text.codePoints().filter(cp -> cp != 167 && !Character.isISOControl(cp)).forEach(out::appendCodePoint);
        return out.toString().strip();
    }
    private static String string(JsonObject args, String key, String fallback) {
        JsonElement e = args.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : fallback;
    }
    private static double number(JsonObject args, String key, double fallback) {
        JsonElement e = args.get(key);
        if (e == null || !e.isJsonPrimitive()) return fallback;
        try { double d = e.getAsDouble(); return Double.isFinite(d) ? d : fallback; }
        catch (RuntimeException ex) { return fallback; }
    }
    private static boolean bool(JsonObject args, String key) {
        JsonElement e = args.get(key);
        return e != null && e.isJsonPrimitive() && (e.getAsJsonPrimitive().isBoolean() ? e.getAsBoolean() : "true".equals(e.getAsString()));
    }
    private static double clamp(double n, double min, double max) { return Math.max(min, Math.min(max, n)); }
}

