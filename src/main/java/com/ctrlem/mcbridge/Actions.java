package com.ctrlem.mcbridge;

import static java.util.Map.entry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.apache.commons.lang3.StringUtils;

import net.minecraft.SharedConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraftforge.common.ForgeConfigSpec;

/**
 * The commands CtrlEm can send. All of them are things the local client controls — input, camera,
 * hotbar, sounds and text only this player sees — so they behave the same on any server without
 * the mod being installed there.
 *
 * <p>Runs on the game thread only. Each action returns {@code null} on success, or a short reason
 * that CtrlEm shows to the person who sent the command. {@link #run} hands that back as a future,
 * because "Show an image" only knows how it went once the download finishes.
 */
final class Actions {
    private static final int JUMP_TICKS = 4;
    /** Longer messages would run off the screen at title size, so they go in the smaller subtitle. */
    private static final int TITLE_MAX_CHARS = 20;
    private static final int MESSAGE_MAX_CHARS = 80;
    /** What ServerboundChatPacket accepts in 1.18.2; anything longer is cut off by the packet itself. */
    private static final int CHAT_MAX_CHARS = 256;

    private record Sound(Supplier<SoundEvent> event, SoundSource source) {}

    // 1.18.2 sound events are plain SoundEvents rather than Holders, so these are direct references.
    private static final Map<String, Sound> SOUNDS = Map.of(
            "creeper_hiss", new Sound(() -> SoundEvents.CREEPER_PRIMED, SoundSource.HOSTILE),
            "ghast_scream", new Sound(() -> SoundEvents.GHAST_SCREAM, SoundSource.HOSTILE),
            "enderman_stare", new Sound(() -> SoundEvents.ENDERMAN_STARE, SoundSource.HOSTILE),
            // The Warden arrived in 1.19, so the id the plugin sends is served by the nastiest
            // thing 1.18.2 has rather than failing the command outright.
            "warden_emerge", new Sound(() -> SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE),
            "thunder", new Sound(() -> SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER),
            "anvil", new Sound(() -> SoundEvents.ANVIL_LAND, SoundSource.BLOCKS),
            "explosion", new Sound(() -> SoundEvents.GENERIC_EXPLODE, SoundSource.BLOCKS),
            "cave_noise", new Sound(() -> SoundEvents.AMBIENT_CAVE, SoundSource.AMBIENT));

    private static final Map<String, String> LABELS = Map.ofEntries(
            entry("jump", "Jump"),
            entry("hold_key", "Hold a key"),
            entry("turn", "Turn"),
            entry("hotbar", "Switch hotbar slot"),
            entry("drop_item", "Drop held item"),
            entry("drop_inventory", "Drop whole inventory"),
            entry("play_sound", "Play a sound"),
            entry("show_title", "Show a message"),
            entry("chat", "Send a chat message"),
            entry("follow", "Follow a player"),
            entry("show_image", "Show an image"));

    /** When the next command may run, per the player's cooldown setting. */
    private static long readyAtMillis;

    private Actions() {}

    static CompletableFuture<String> run(String action, JsonObject args) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return done("the player isn't in a world");
        if (!Config.ENABLED.get()) return done("the player has CtrlEm commands switched off");

        ForgeConfigSpec.BooleanValue allowed = switch (action) {
            case "jump" -> Config.ALLOW_JUMP;
            case "hold_key" -> Config.ALLOW_HOLD_KEY;
            case "turn" -> Config.ALLOW_TURN;
            case "hotbar" -> Config.ALLOW_HOTBAR;
            case "drop_item" -> Config.ALLOW_DROP_ITEM;
            case "drop_inventory" -> Config.ALLOW_DROP_INVENTORY;
            case "play_sound" -> Config.ALLOW_PLAY_SOUND;
            case "show_title" -> Config.ALLOW_SHOW_TITLE;
            case "chat" -> Config.ALLOW_CHAT;
            case "follow" -> Config.ALLOW_FOLLOW;
            case "show_image" -> Config.ALLOW_SHOW_IMAGE;
            default -> null;
        };
        // An unknown action means the plugin is newer than this copy of the mod.
        if (allowed == null) return done("this command needs a newer version of the CtrlEm mod");
        if (!allowed.get()) return done("the player has switched this command off");

        long now = System.currentTimeMillis();
        if (now < readyAtMillis) return done("on cooldown, try again in a moment");

        // The only action that finishes later. The image is its own notice, so no note either.
        if (action.equals("show_image")) {
            String link = string(args, "url", "");
            if (link.isBlank()) return done("no image link given");
            // Counted from the request, so a sender can't queue up downloads.
            readyAtMillis = now + Config.COOLDOWN_SECONDS.get() * 1000L;
            int seconds = (int) clamp(number(args, "seconds", 5), 1, Config.MAX_IMAGE_SECONDS.get());
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
            default -> throw new IllegalStateException(action);
        };
        if (error == null) {
            readyAtMillis = now + Config.COOLDOWN_SECONDS.get() * 1000L;
            // A message is its own notice; everything else gets one so the player knows why.
            if (Config.NOTIFY.get() && !action.equals("show_title")) {
                // displayClientMessage(_, true) is the action bar, just above the hotbar.
                player.displayClientMessage(new TextComponent("CtrlEm: " + LABELS.get(action)), true);
            }
        }
        return done(error);
    }

    private static CompletableFuture<String> done(String error) {
        return CompletableFuture.completedFuture(error);
    }

    private static String jump(Minecraft mc) {
        if (mc.screen != null) return "the player has a menu open";
        // Pressing the real key, rather than calling jumpFromGround(), keeps vanilla's rules —
        // swimming, mounts, the jump cooldown — exactly as if the player had pressed it.
        KeyHolder.hold(mc.options.keyJump, JUMP_TICKS);
        return null;
    }

    private static String holdKey(Minecraft mc, JsonObject args) {
        if (mc.screen != null) return "the player has a menu open";
        KeyMapping key = keyFor(mc.options, string(args, "key", "forward"));
        if (key == null) return "unknown key";
        if (key.isUnbound()) return "the player has no key bound for that";
        int seconds = (int) clamp(number(args, "seconds", 3), 1, Config.MAX_HOLD_SECONDS.get());
        KeyHolder.hold(key, seconds * 20);
        return null;
    }

    private static KeyMapping keyFor(Options options, String name) {
        return switch (name) {
            case "forward" -> options.keyUp;
            case "back" -> options.keyDown;
            case "left" -> options.keyLeft;
            case "right" -> options.keyRight;
            case "jump" -> options.keyJump;
            case "sneak" -> options.keyShift;
            case "sprint" -> options.keySprint;
            case "attack" -> options.keyAttack;
            case "use" -> options.keyUse;
            default -> null;
        };
    }

    private static String turn(LocalPlayer player, JsonObject args) {
        float degrees = (float) clamp(number(args, "degrees", 180), -180, 180);
        float yaw = player.getYRot() + degrees;
        player.setYRot(yaw);
        // Also move the previous-tick angle, so the camera snaps rather than smearing across the turn.
        player.yRotO = yaw;
        return null;
    }

    private static String hotbar(LocalPlayer player, JsonObject args) {
        Inventory inventory = player.getInventory();
        String slot = string(args, "slot", "random");
        int target;
        if (slot.equals("random")) {
            // Always a different slot, so it visibly does something.
            target = (inventory.selected + 1 + ThreadLocalRandom.current().nextInt(8)) % 9;
        } else {
            try {
                target = Integer.parseInt(slot) - 1;
            } catch (NumberFormatException e) {
                return "unknown slot";
            }
            if (target < 0 || target > 8) return "slot must be 1-9";
        }
        // A plain field in 1.18.2, where newer versions have a setter. The game sends the change
        // to the server on its next tick either way.
        inventory.selected = target;
        return null;
    }

    private static String dropItem(LocalPlayer player, JsonObject args) {
        if (player.isSpectator()) return "the player is spectating";
        if (player.getInventory().getSelected().isEmpty()) return "the player isn't holding anything";
        // Same as the vanilla drop key.
        if (player.drop(bool(args, "whole_stack"))) {
            player.swing(InteractionHand.MAIN_HAND);
        }
        return null;
    }

    private static String dropInventory(Minecraft mc, LocalPlayer player, JsonObject args) {
        if (player.isSpectator()) return "the player is spectating";
        if (mc.gameMode == null) return "the player isn't in a world";
        // The slot numbers below belong to the player's own inventory menu; a chest or other
        // container numbers its slots differently.
        if (player.containerMenu != player.inventoryMenu) return "the player has a container open";
        if (InventoryThrower.isThrowing()) return "already throwing everything";

        List<Integer> slots = new ArrayList<>();
        for (int i = InventoryMenu.INV_SLOT_START; i < InventoryMenu.USE_ROW_SLOT_END; i++) slots.add(i);
        if (bool(args, "include_armor")) {
            for (int i = InventoryMenu.ARMOR_SLOT_START; i < InventoryMenu.ARMOR_SLOT_END; i++) slots.add(i);
            slots.add(InventoryMenu.SHIELD_SLOT);
        }
        slots.removeIf(slot -> !player.inventoryMenu.getSlot(slot).hasItem());
        if (slots.isEmpty()) return "the player's inventory is already empty";
        InventoryThrower.start(player, slots);
        return null;
    }

    private static String playSound(Minecraft mc, LocalPlayer player, JsonObject args) {
        Sound sound = SOUNDS.get(string(args, "sound", "creeper_hiss"));
        if (sound == null) return "unknown sound";
        // Two blocks directly behind the player's head. Minecraft's forward vector is
        // (-sin yaw, 0, cos yaw), so behind is its negation.
        double yaw = Math.toRadians(player.getYRot());
        double x = player.getX() + Math.sin(yaw) * 2;
        double z = player.getZ() - Math.cos(yaw) * 2;
        // 1.18.2's SimpleSoundInstance takes no RandomSource argument; it seeds itself.
        mc.getSoundManager().play(new SimpleSoundInstance(
                sound.event().get(), sound.source(), 1.0F, 1.0F, x, player.getEyeY(), z));
        return null;
    }

    private static String showTitle(Minecraft mc, JsonObject args) {
        String text = sanitize(string(args, "text", ""));
        if (text.isEmpty()) return "no message given";
        // Vanilla's default fade/stay times, in case a server left custom ones behind.
        mc.gui.resetTitleTimes();
        if (text.length() <= TITLE_MAX_CHARS) {
            mc.gui.setSubtitle(TextComponent.EMPTY);
            mc.gui.setTitle(new TextComponent(text));
        } else {
            mc.gui.setSubtitle(new TextComponent(text));
            mc.gui.setTitle(TextComponent.EMPTY);
        }
        return null;
    }

    private static String chat(Minecraft mc, LocalPlayer player, JsonObject args) {
        // A plain field in 1.18.2, not an option object.
        if (mc.options.chatVisibility != ChatVisiblity.FULL) return "the player has chat turned off";
        // The chat box's own clean-up, after dropping the characters a server kicks players for.
        // 1.18.2 keeps filterText on SharedConstants and has no trimChatMessage, so the length cap
        // is applied here rather than by a helper.
        String text = StringUtils.normalizeSpace(SharedConstants.filterText(string(args, "text", "")));
        if (text.length() > CHAT_MAX_CHARS) text = text.substring(0, CHAT_MAX_CHARS);
        text = text.trim();
        if (text.isEmpty()) return "no message given";
        // The chat box would run this as a command; never do that on the player's behalf.
        if (text.startsWith("/")) return "commands can't be sent";
        player.chat(text);
        return null;
    }

    private static String follow(Minecraft mc, LocalPlayer player, JsonObject args) {
        String name = string(args, "player", "").strip();
        AbstractClientPlayer target = Follower.findTarget(mc, player, name);
        if (target == null) return name.isEmpty() ? "no other players nearby" : "that player isn't nearby";
        int seconds = (int) clamp(number(args, "seconds", 20), 1, Config.MAX_FOLLOW_SECONDS.get());
        Follower.start(target, seconds * 20);
        return null;
    }

    /** Drops formatting codes and control characters and caps the length — this text comes from other people. */
    private static String sanitize(String text) {
        StringBuilder out = new StringBuilder();
        text.codePoints()
                .filter(c -> c != '§' && !Character.isISOControl(c))
                .limit(MESSAGE_MAX_CHARS)
                .forEach(out::appendCodePoint);
        return out.toString().strip();
    }

    private static String string(JsonObject args, String key, String fallback) {
        JsonElement e = args.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : fallback;
    }

    /** Accepts numbers or numeric strings; anything else falls back. */
    private static double number(JsonObject args, String key, double fallback) {
        JsonElement e = args.get(key);
        if (e == null || !e.isJsonPrimitive()) return fallback;
        try {
            double value = e.getAsDouble();
            return Double.isFinite(value) ? value : fallback;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static boolean bool(JsonObject args, String key) {
        JsonElement e = args.get(key);
        return e != null && e.isJsonPrimitive() && (e.getAsJsonPrimitive().isBoolean() ? e.getAsBoolean() : "true".equals(e.getAsString()));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
