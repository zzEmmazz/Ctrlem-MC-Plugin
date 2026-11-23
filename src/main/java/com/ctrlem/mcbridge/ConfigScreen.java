package com.ctrlem.mcbridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;
import net.minecraftforge.client.ConfigGuiHandler;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;

/**
 * Mods screen &gt; CtrlEm &gt; Config: the player's own limits, without editing
 * {@code config/ctrlem-client.toml} by hand.
 *
 * <p>Written out by hand because Forge 1.18.2 has no generated config screen — NeoForge's
 * {@code ConfigurationScreen} builds one from the spec, and there is no equivalent here. It covers
 * the same settings in two pages: the general limits, and which commands are allowed at all.
 *
 * <p>Every widget writes straight through to the config value it belongs to, so switching pages
 * can't lose an edit; the file itself is written once on close. The one exception is the allowed
 * sites box, whose text is held until then so a half-typed domain never briefly becomes the rule.
 */
final class ConfigScreen extends Screen {
    private static final int WIDGET_HEIGHT = 20;
    private static final int ROW_STEP = 24;
    private static final int TOP = 36;
    private static final int GAP = 8;
    private static final int MAX_CONTENT_WIDTH = 324;

    private final Screen parent;
    private final List<Row> rows = new ArrayList<>();
    private int page;
    /** Null until the player touches the box; the config's own value is used until then. */
    private String sitesText;

    /** A widget and the tooltip shown while the mouse is over it. */
    private record Row(AbstractWidget widget, Component tooltip) {}

    private ConfigScreen(Screen parent) {
        super(new TranslatableComponent("ctrlem.screen.general"));
        this.parent = parent;
    }

    /** Called from the mod constructor, on the client only. */
    static void registerFactory() {
        ModLoadingContext.get().registerExtensionPoint(ConfigGuiHandler.ConfigGuiFactory.class,
                () -> new ConfigGuiHandler.ConfigGuiFactory((minecraft, parent) -> new ConfigScreen(parent)));
    }

    @Override
    protected void init() {
        rows.clear();
        int content = Math.min(MAX_CONTENT_WIDTH, width - 20);
        int column = (content - GAP) / 2;
        int left = width / 2 - content / 2;
        int right = left + column + GAP;

        if (page == 0) {
            toggle(left, row(0), column, "enabled", Config.ENABLED);
            toggle(right, row(0), column, "notify", Config.NOTIFY);
            slider(left, row(1), column, "cooldownSeconds", Config.COOLDOWN_SECONDS, 0, 60);
            slider(right, row(1), column, "maxHoldSeconds", Config.MAX_HOLD_SECONDS, 1, 10);
            slider(left, row(2), column, "maxFollowSeconds", Config.MAX_FOLLOW_SECONDS, 5, 300);
            slider(right, row(2), column, "maxImageSeconds", Config.MAX_IMAGE_SECONDS, 1, 60);
            sites(left, row(3) + 10, content);
        } else {
            toggle(left, row(0), column, "allowJump", Config.ALLOW_JUMP);
            toggle(right, row(0), column, "allowHoldKey", Config.ALLOW_HOLD_KEY);
            toggle(left, row(1), column, "allowTurn", Config.ALLOW_TURN);
            toggle(right, row(1), column, "allowHotbar", Config.ALLOW_HOTBAR);
            toggle(left, row(2), column, "allowDropItem", Config.ALLOW_DROP_ITEM);
            toggle(right, row(2), column, "allowDropInventory", Config.ALLOW_DROP_INVENTORY);
            toggle(left, row(3), column, "allowPlaySound", Config.ALLOW_PLAY_SOUND);
            toggle(right, row(3), column, "allowShowTitle", Config.ALLOW_SHOW_TITLE);
            toggle(left, row(4), column, "allowFollow", Config.ALLOW_FOLLOW);
            toggle(right, row(4), column, "allowShowImage", Config.ALLOW_SHOW_IMAGE);
            // Full width and on its own: the only command that speaks under the player's name.
            toggle(left, row(5), content, "allowChat", Config.ALLOW_CHAT);
        }

        addRenderableWidget(new Button(left, height - 28, column, WIDGET_HEIGHT,
                new TranslatableComponent(page == 0 ? "ctrlem.screen.to_commands" : "ctrlem.screen.to_general"),
                button -> {
                    page = 1 - page;
                    clearWidgets();
                    init();
                }));
        addRenderableWidget(new Button(right, height - 28, column, WIDGET_HEIGHT,
                CommonComponents.GUI_DONE, button -> onClose()));
    }

    private static int row(int index) {
        return TOP + index * ROW_STEP;
    }

    private void toggle(int x, int y, int columnWidth, String key, ForgeConfigSpec.BooleanValue value) {
        Button button = new Button(x, y, columnWidth, WIDGET_HEIGHT, onOff(key, value.get()), pressed -> {
            boolean next = !value.get();
            value.set(next);
            pressed.setMessage(onOff(key, next));
        });
        add(button, key);
    }

    private void slider(int x, int y, int columnWidth, String key, ForgeConfigSpec.IntValue value, int min, int max) {
        add(new IntSlider(x, y, columnWidth, key, value, min, max), key);
    }

    private void sites(int x, int y, int boxWidth) {
        EditBox box = new EditBox(font, x + 1, y, boxWidth - 2, WIDGET_HEIGHT,
                new TranslatableComponent("ctrlem.configuration.imageAllowedSites"));
        box.setMaxLength(1024);
        box.setValue(sitesText != null ? sitesText : String.join(", ", Config.IMAGE_ALLOWED_SITES.get()));
        box.setResponder(text -> sitesText = text);
        add(box, "imageAllowedSites");
    }

    private void add(AbstractWidget widget, String key) {
        addRenderableWidget(widget);
        rows.add(new Row(widget, new TranslatableComponent("ctrlem.configuration." + key + ".tooltip")));
    }

    private static Component onOff(String key, boolean on) {
        return new TranslatableComponent("ctrlem.configuration." + key + ".short")
                .append(": ")
                .append(on ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF);
    }

    @Override
    public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
        renderBackground(poseStack);
        drawCenteredString(poseStack, font,
                new TranslatableComponent(page == 0 ? "ctrlem.screen.general" : "ctrlem.screen.commands"),
                width / 2, 14, 0xFFFFFF);
        if (page == 0) {
            int content = Math.min(MAX_CONTENT_WIDTH, width - 20);
            drawString(poseStack, font, new TranslatableComponent("ctrlem.screen.sites_hint"),
                    width / 2 - content / 2 + 1, row(3), 0xA0A0A0);
        }
        super.render(poseStack, mouseX, mouseY, partialTick);

        for (Row entry : rows) {
            AbstractWidget widget = entry.widget();
            if (mouseX >= widget.x && mouseX < widget.x + widget.getWidth()
                    && mouseY >= widget.y && mouseY < widget.y + WIDGET_HEIGHT) {
                renderTooltip(poseStack, font.split(entry.tooltip(), Math.max(width / 2, 180)), mouseX, mouseY);
                break;
            }
        }
    }

    @Override
    public void tick() {
        for (Row entry : rows) {
            if (entry.widget() instanceof EditBox box) box.tick(); // the cursor blink
        }
    }

    @Override
    public void onClose() {
        commit();
        minecraft.setScreen(parent);
    }

    private void commit() {
        if (!Config.SPEC.isLoaded()) return;
        if (sitesText != null) {
            List<String> sites = new ArrayList<>();
            for (String part : sitesText.toLowerCase(Locale.ROOT).split("[,\\s]+")) {
                String site = part.strip();
                if (!site.isEmpty() && !sites.contains(site)) sites.add(site);
            }
            Config.IMAGE_ALLOWED_SITES.set(sites);
        }
        Config.SPEC.save();
    }

    /** A whole-number slider over a config range; 1.18.2 has no ready-made one for mod configs. */
    private static final class IntSlider extends AbstractSliderButton {
        private final String key;
        private final ForgeConfigSpec.IntValue option;
        private final int min;
        private final int max;

        IntSlider(int x, int y, int columnWidth, String key, ForgeConfigSpec.IntValue option, int min, int max) {
            super(x, y, columnWidth, WIDGET_HEIGHT, TextComponent.EMPTY, (option.get() - min) / (double) (max - min));
            this.key = key;
            this.option = option;
            this.min = min;
            this.max = max;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(new TranslatableComponent("ctrlem.configuration." + key + ".short").append(": " + current() + "s"));
        }

        @Override
        protected void applyValue() {
            option.set(current());
        }

        private int current() {
            return min + (int) Math.round(value * (max - min));
        }
    }
}
