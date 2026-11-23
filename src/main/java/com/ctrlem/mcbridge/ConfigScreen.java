package com.ctrlem.mcbridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** In-game F8 configuration UI; all settings are client-side. */
final class ConfigScreen extends Screen {
    private static final Map<String, String> LABELS = Map.ofEntries(
        Map.entry("jump", "Jump"), Map.entry("hold_key", "Hold a key"),
        Map.entry("turn", "Turn"), Map.entry("hotbar", "Switch hotbar slot"),
        Map.entry("drop_item", "Drop held item"), Map.entry("drop_inventory", "Drop inventory"),
        Map.entry("play_sound", "Play a sound"), Map.entry("show_title", "Show message"),
        Map.entry("chat", "Send chat"), Map.entry("follow", "Follow player"),
        Map.entry("show_image", "Show image"));
    private static final List<String> COMMANDS = List.of("jump", "hold_key", "turn", "hotbar", "drop_item",
        "drop_inventory", "play_sound", "show_title", "chat", "follow", "show_image");

    private final Screen parent;
    private EditBox sitesText;
    private boolean commandsPage;

    ConfigScreen(Screen parent) {
        super(Component.translatable("ctrlem.configuration.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int contentWidth = Math.min(340, this.width - 24);
        int left = (this.width - contentWidth) / 2;
        int top = 46;
        if (!commandsPage) {
            addToggle(left, top, contentWidth, "Commands", Config.INSTANCE.enabled,
                value -> Config.INSTANCE.enabled = value);
            addToggle(left, top + 25, contentWidth, "Action notifications", Config.INSTANCE.notify,
                value -> Config.INSTANCE.notify = value);
            int half = (contentWidth - 8) / 2;
            addNumber(left, top + 55, half, "Cooldown", "cooldown", 0, 60);
            addNumber(left + half + 8, top + 55, half, "Key hold", "hold", 1, 10);
            addNumber(left, top + 83, half, "Follow", "follow", 5, 300);
            addNumber(left + half + 8, top + 83, half, "Image", "image", 1, 60);
            sitesText = new EditBox(this.font, left, this.height - 72, contentWidth, 20,
                Component.translatable("ctrlem.configuration.imageAllowedSites"));
            sitesText.setMaxLength(1024);
            sitesText.setValue(String.join(", ", Config.INSTANCE.imageAllowedSites));
            addRenderableWidget(sitesText);
        } else {
            sitesText = null;
            int widthPerColumn = (contentWidth - 8) / 2;
            for (int i = 0; i < COMMANDS.size(); i++) {
                String command = COMMANDS.get(i);
                int column = i >= 6 ? 1 : 0;
                int row = i % 6;
                int x = left + column * (widthPerColumn + 8);
                addCommandToggle(command, x, top + row * 24, widthPerColumn);
            }
        }
        addRenderableWidget(Button.builder(
            Component.translatable(commandsPage ? "ctrlem.screen.to_general" : "ctrlem.screen.to_commands"), button -> {
                saveSettings();
                commandsPage = !commandsPage;
                rebuildWidgets();
            }).bounds(left, this.height - 43, contentWidth / 2 - 4, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
            .bounds(left + contentWidth / 2 + 4, this.height - 43, contentWidth / 2 - 4, 20).build());
    }

    private void addToggle(int x, int y, int width, String label, boolean initial, java.util.function.Consumer<Boolean> set) {
        addRenderableWidget(Button.builder(Component.literal(label + ": " + (initial ? "ON" : "OFF")), button -> {
            boolean next = !button.getMessage().getString().endsWith("ON");
            set.accept(next);
            button.setMessage(Component.literal(label + ": " + (next ? "ON" : "OFF")));
            Config.save();
        }).bounds(x, y, width, 20).build());
    }

    private void addCommandToggle(String name, int x, int y, int width) {
        boolean active = Config.allows(name);
        String label = LABELS.getOrDefault(name, name);
        Button button = Button.builder(Component.literal(label + ": " + (active ? "ON" : "OFF")), widget -> {
            boolean next = !Config.allows(name);
            Config.INSTANCE.permissions.put(name, next);
            widget.setMessage(Component.literal(label + ": " + (next ? "ON" : "OFF")));
            Config.save();
        }).bounds(x, y, width, 20).build();
        button.setTooltip(Tooltip.create(Component.translatable("ctrlem.configuration.allow" +
            switch (name) {
                case "hold_key" -> "HoldKey";
                case "drop_item" -> "DropItem";
                case "drop_inventory" -> "DropInventory";
                case "play_sound" -> "PlaySound";
                case "show_title" -> "ShowTitle";
                case "show_image" -> "ShowImage";
                default -> Character.toUpperCase(name.charAt(0)) + name.substring(1);
            } + ".tooltip")));
        addRenderableWidget(button);
    }

    private void addNumber(int x, int y, int width, String label, String option, int minimum, int maximum) {
        int value = optionValue(option);
        int buttonWidth = 34;
        addRenderableWidget(Button.builder(Component.literal("–"), btn -> {
            changeNumber(option, Math.max(minimum, optionValue(option) - 1));
            rebuildWidgets();
        }).bounds(x, y, buttonWidth, 20).build());
        Button readout = Button.builder(Component.literal(label + ": " + value + "s"), btn -> {})
            .bounds(x + buttonWidth + 4, y, width - 2 * buttonWidth - 8, 20).build();
        readout.active = false;
        addRenderableWidget(readout);
        addRenderableWidget(Button.builder(Component.literal("+"), btn -> {
            changeNumber(option, Math.min(maximum, optionValue(option) + 1));
            rebuildWidgets();
        }).bounds(x + width - buttonWidth, y, buttonWidth, 20).build());
    }

    private int optionValue(String name) {
        return switch (name) {
            case "cooldown" -> Config.INSTANCE.cooldownSeconds;
            case "hold" -> Config.INSTANCE.maxHoldSeconds;
            case "follow" -> Config.INSTANCE.maxFollowSeconds;
            default -> Config.INSTANCE.maxImageSeconds;
        };
    }

    private void changeNumber(String name, int value) {
        switch (name) {
            case "cooldown" -> Config.INSTANCE.cooldownSeconds = value;
            case "hold" -> Config.INSTANCE.maxHoldSeconds = value;
            case "follow" -> Config.INSTANCE.maxFollowSeconds = value;
            case "image" -> Config.INSTANCE.maxImageSeconds = value;
            default -> throw new IllegalArgumentException("Unknown option " + name);
        }
        saveSettings();
    }

    private void saveSettings() {
        if (sitesText != null) {
            List<String> domains = new ArrayList<>();
            for (String part : sitesText.getValue().toLowerCase(Locale.ROOT).split("[,\\s]+")) {
                String domain = part.strip();
                if (!domain.isEmpty() && !domains.contains(domain)) domains.add(domain);
            }
            Config.INSTANCE.imageAllowedSites = domains;
        }
        Config.save();
    }

    @Override
    public void onClose() {
        saveSettings();
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(this.font,
            commandsPage ? "CtrlEm — Command Permissions" : "CtrlEm — Settings",
            this.width / 2, 23, 0xFFFFFF);
        if (!commandsPage) graphics.drawCenteredString(this.font,
            "Allowed image domains (comma-separated; empty = any allowed site)",
            this.width / 2, this.height - 85, 0xAAAAAA);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
