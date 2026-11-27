package com.ctrlem.mcbridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.neoforged.fml.loading.FMLPaths;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Client-side configuration. Defaults mirror the decompiled Forge version. */
public final class Config {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FMLPaths.CONFIGDIR.get().resolve("ctrlem-client.json");

    public boolean enabled = true;
    public boolean notify = true;
    public int cooldownSeconds = 2;
    public int maxHoldSeconds = 10;
    public int maxFollowSeconds = 60;
    public int maxImageSeconds = 15;
    public List<String> imageAllowedSites = new ArrayList<>();
    public Map<String, Boolean> permissions = defaults();
    public static Config INSTANCE = new Config();

    private static Map<String, Boolean> defaults() {
        Map<String, Boolean> all = new LinkedHashMap<>();
        for (String name : new String[]{"jump", "hold_key", "turn", "hotbar", "drop_item", "drop_inventory", "play_sound", "show_title", "follow", "show_image"})
            all.put(name, true);
        all.put("chat", false);
        return all;
    }

    public static void load() {
        try {
            if (Files.exists(FILE)) {
                Config input = GSON.fromJson(Files.readString(FILE, StandardCharsets.UTF_8), Config.class);
                if (input != null) INSTANCE = input;
            }
            if (INSTANCE.permissions == null) INSTANCE.permissions = defaults();
            defaults().forEach((key, value) -> INSTANCE.permissions.putIfAbsent(key, value));
            if (INSTANCE.imageAllowedSites == null) INSTANCE.imageAllowedSites = new ArrayList<>();
            INSTANCE.clampSettings();
            save();
        } catch (Exception ex) {
            CtrlEmMod.LOGGER.warn("Failed to load CtrlEm config; using defaults", ex);
            INSTANCE = new Config();
        }
    }

    private void clampSettings() {
        cooldownSeconds = clamp(cooldownSeconds, 0, 60);
        maxHoldSeconds = clamp(maxHoldSeconds, 1, 10);
        maxFollowSeconds = clamp(maxFollowSeconds, 5, 300);
        maxImageSeconds = clamp(maxImageSeconds, 1, 60);
    }

    static int clamp(int number, int min, int max) { return Math.max(min, Math.min(max, number)); }

    public static void save() {
        try {
            INSTANCE.clampSettings();
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(INSTANCE), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            CtrlEmMod.LOGGER.warn("Failed to save CtrlEm config", ex);
        }
    }

    static boolean allows(String action) { return INSTANCE.permissions.getOrDefault(action, false); }
}
