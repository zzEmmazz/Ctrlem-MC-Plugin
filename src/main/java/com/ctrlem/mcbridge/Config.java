package com.ctrlem.mcbridge;

import java.util.List;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * The player's own limits on what CtrlEm may do to their game. Everything here is checked on the
 * game side, so it holds no matter what the plugin or the person sending commands asks for.
 *
 * <p>Same keys, defaults and ranges as the NeoForge build, so {@code config/ctrlem-client.toml}
 * carries over between the two unchanged.
 */
public final class Config {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.BooleanValue ENABLED = BUILDER
            .comment("Master switch. When off, the mod stays connected but refuses every command.")
            .define("enabled", true);

    public static final ForgeConfigSpec.BooleanValue NOTIFY = BUILDER
            .comment("Show a short note above the hotbar whenever a command runs, so you know why something happened.")
            .define("notify", true);

    public static final ForgeConfigSpec.IntValue COOLDOWN_SECONDS = BUILDER
            .comment("Minimum number of seconds between two commands.")
            .defineInRange("cooldownSeconds", 2, 0, 60);

    public static final ForgeConfigSpec.IntValue MAX_HOLD_SECONDS = BUILDER
            .comment("Longest time \"Hold a key\" may hold a key down.")
            .defineInRange("maxHoldSeconds", 10, 1, 10);

    public static final ForgeConfigSpec.IntValue MAX_FOLLOW_SECONDS = BUILDER
            .comment("Longest time \"Follow a player\" may make you follow someone.")
            .defineInRange("maxFollowSeconds", 60, 5, 300);

    public static final ForgeConfigSpec.IntValue MAX_IMAGE_SECONDS = BUILDER
            .comment("Longest time \"Show an image\" may cover your screen.")
            .defineInRange("maxImageSeconds", 15, 1, 60);

    // 1.18.2's ForgeConfigSpec takes the path as a list and has no new-element supplier, so this
    // reads differently from the NeoForge build while defining exactly the same setting.
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> IMAGE_ALLOWED_SITES = BUILDER
            .comment("Only load images from these sites (and their subdomains), e.g. [\"i.imgur.com\", \"cdn.discordapp.com\"].",
                    "Empty means any site not on the built-in blocklist of IP grabbers and link shorteners.",
                    "Whatever site an image comes from sees your IP address, as with CtrlEm's own image popups.")
            .defineListAllowEmpty(List.of("imageAllowedSites"), () -> List.<String>of(), site -> site instanceof String);

    public static final ForgeConfigSpec.BooleanValue ALLOW_JUMP = allow("jump", "Jump");
    public static final ForgeConfigSpec.BooleanValue ALLOW_HOLD_KEY = allow("holdKey", "Hold a key");
    public static final ForgeConfigSpec.BooleanValue ALLOW_TURN = allow("turn", "Turn");
    public static final ForgeConfigSpec.BooleanValue ALLOW_HOTBAR = allow("hotbar", "Switch hotbar slot");
    public static final ForgeConfigSpec.BooleanValue ALLOW_DROP_ITEM = allow("dropItem", "Drop held item");
    public static final ForgeConfigSpec.BooleanValue ALLOW_DROP_INVENTORY = allow("dropInventory", "Drop whole inventory");
    public static final ForgeConfigSpec.BooleanValue ALLOW_PLAY_SOUND = allow("playSound", "Play a sound");
    public static final ForgeConfigSpec.BooleanValue ALLOW_SHOW_TITLE = allow("showTitle", "Show a message");
    public static final ForgeConfigSpec.BooleanValue ALLOW_FOLLOW = allow("follow", "Follow a player");
    public static final ForgeConfigSpec.BooleanValue ALLOW_SHOW_IMAGE = allow("showImage", "Show an image");

    public static final ForgeConfigSpec.BooleanValue ALLOW_CHAT = BUILDER
            .comment("Allow the \"Send a chat message\" command. Off by default: the messages go out under your",
                    "name, everyone on the server sees them, and the server holds you responsible for them.")
            .define("allowChat", false);

    static final ForgeConfigSpec SPEC = BUILDER.build();

    private static ForgeConfigSpec.BooleanValue allow(String key, String label) {
        return BUILDER
                .comment("Allow the \"" + label + "\" command.")
                .define("allow" + Character.toUpperCase(key.charAt(0)) + key.substring(1), true);
    }

    private Config() {}
}
