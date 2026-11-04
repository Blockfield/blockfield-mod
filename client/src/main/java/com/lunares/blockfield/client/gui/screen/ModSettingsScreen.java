package com.lunares.blockfield.client.gui.screen;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Ordinary Minecraft options, deliberately independent of the command tablet's theme. */
public final class ModSettingsScreen extends OptionsSubScreen {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModSettingsScreen.class);

    private EditBox search;
    private final List<ModSettingsBridge.Entry> entries = ModSettingsBridge.entries();
    private final Map<ModSettingsBridge.Entry, AbstractWidget> audioWidgets = new LinkedHashMap<>();
    private final List<Runnable> pendingEdits = new ArrayList<>();

    public ModSettingsScreen(Screen parent) {
        super(
                parent,
                Minecraft.getInstance().options,
                Component.translatable("blockfield.settings.title"));
    }

    @Override
    protected void addTitle() {
        layout.setHeaderHeight(60);
        LinearLayout header = layout.addToHeader(LinearLayout.vertical().spacing(8));
        header.addChild(new StringWidget(310, 12, title, font));
        search =
                header.addChild(
                        new EditBox(
                                font,
                                310,
                                20,
                                Component.translatable("blockfield.settings.search")));
        search.setHint(Component.translatable("blockfield.settings.search"));
        search.setMaxLength(80);
        search.setResponder(
                query -> {
                    if (list != null) {
                        finishEditing();
                        pendingEdits.clear();
                        audioWidgets.clear();
                        ((SettingsList) list).clear();
                        addOptions();
                        list.setScrollAmount(0);
                    }
                });
    }

    private void finishEditing() {
        for (Runnable commit : pendingEdits) commit.run();
    }

    @Override
    public void removed() {
        finishEditing();
        super.removed();
    }

    @Override
    protected void addContents() {
        finishEditing();
        pendingEdits.clear();
        audioWidgets.clear();
        list = layout.addToContents(new SettingsList());
        addOptions();
    }

    @Override
    protected void addOptions() {
        String group = "";
        String query = search.getValue().strip().toLowerCase(Locale.ROOT);
        for (ModSettingsBridge.Entry entry : entries) {
            String groupName =
                    Component.translatable("blockfield.settings.group." + entry.modId())
                            .getString();
            String label = Component.translatable(entry.labelKey()).getString();
            if (!query.isEmpty()
                    && !label.toLowerCase(Locale.ROOT).contains(query)
                    && !groupName.toLowerCase(Locale.ROOT).contains(query)
                    && !entry.modId().contains(query)
                    && !entry.labelKey().toLowerCase(Locale.ROOT).contains(query)) continue;
            if (!group.equals(entry.modId())) {
                group = entry.modId();
                ((SettingsList) list)
                        .row(
                                new StringWidget(
                                        310,
                                        20,
                                        Component.translatable(
                                                "blockfield.settings.group." + group),
                                        font));
            }
            try {
                if (entry.restriction() == null
                        && entry.step() == 0
                        && entry.get() instanceof Number number) {
                    StringWidget caption =
                            new StringWidget(
                                            310, 20, Component.translatable(entry.labelKey()), font)
                                    .alignLeft();
                    caption.setTooltip(Tooltip.create(Component.translatable(entry.labelKey())));
                    ((SettingsList) list).row(caption);
                    ((SettingsList) list).row(new NumberField(entry, number));
                    continue;
                }
            } catch (Exception | LinkageError ignored) {
                // widget() displays an honest unavailable row.
            }
            ((SettingsList) list).row(widget(entry));
        }
        if (list.children().isEmpty())
            ((SettingsList) list)
                    .row(
                            new StringWidget(
                                    310,
                                    20,
                                    Component.translatable("blockfield.settings.no_results"),
                                    font));
    }

    private AbstractWidget widget(ModSettingsBridge.Entry entry) {
        Component caption = Component.translatable(entry.labelKey());
        try {
            Object current = entry.get();
            if (entry.restriction() != null) {
                return disabled(
                        Component.translatable("blockfield.settings.locked_label", caption),
                        entry.restriction(),
                        current);
            }
            if (current instanceof Boolean value) {
                // Own wording for the two states («Частицы»/«Без частиц») when the lang has it.
                String on = entry.labelKey() + ".on", tip = entry.labelKey() + ".tooltip";
                CycleButton<Boolean> button =
                        (I18n.exists(on)
                                        ? CycleButton.booleanBuilder(
                                                        Component.translatable(on),
                                                        Component.translatable(
                                                                entry.labelKey() + ".off"))
                                                .withInitialValue(value)
                                        : CycleButton.onOffBuilder(value))
                                .create(
                                        0,
                                        0,
                                        310,
                                        20,
                                        caption,
                                        (control, next) -> save(entry, next, control));
                if (I18n.exists(tip))
                    button.setTooltip(Tooltip.create(Component.translatable(tip)));
                audioWidget(entry, button);
                return button;
            }
            if (current instanceof Enum<?> value) {
                return CycleButton.<Object>builder(ModSettingsScreen::valueLabel)
                        .withValues(
                                Arrays.asList(
                                        (Object[]) value.getDeclaringClass().getEnumConstants()))
                        .withInitialValue(value)
                        .create(
                                0,
                                0,
                                310,
                                20,
                                caption,
                                (button, next) -> save(entry, next, button));
            }
            if (current instanceof Number number) {
                ValueSlider slider = new ValueSlider(entry, number);
                audioWidget(entry, slider);
                return slider;
            }
            return disabled(caption, "blockfield.settings.unavailable", null);
        } catch (Exception | LinkageError e) {
            LOGGER.debug("Unavailable mod option {}", entry.labelKey(), e);
            return disabled(
                    caption,
                    ModSettingsBridge.loaded(entry.modId())
                            ? "blockfield.settings.unavailable"
                            : "blockfield.settings.missing",
                    null);
        }
    }

    private void audioWidget(ModSettingsBridge.Entry entry, AbstractWidget widget) {
        if (!entry.modId().equals("voicechat")) return;
        audioWidgets.put(entry, widget);
        String reason = ModSettingsBridge.audioRestriction(entry);
        widget.active = reason == null;
        widget.setTooltip(reason == null ? null : Tooltip.create(Component.translatable(reason)));
    }

    private Button disabled(Component caption, String reason, Object current) {
        Component value =
                current == null
                        ? Component.translatable("blockfield.settings.unavailable.short")
                        : valueLabel(current);
        Button button =
                Button.builder(
                                Component.translatable("options.generic_value", caption, value),
                                b -> {})
                        .width(310)
                        .build();
        button.active = false;
        button.setTooltip(Tooltip.create(Component.translatable(reason)));
        return button;
    }

    private static Component valueLabel(Object value) {
        if (value instanceof Boolean enabled)
            return enabled ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF;
        if (value instanceof Number number)
            return Component.literal(
                    BigDecimal.valueOf(number.doubleValue()).stripTrailingZeros().toPlainString());
        String name = ((Enum<?>) value).name();
        return Component.translatableWithFallback(
                "blockfield.settings.value." + name.toLowerCase(Locale.ROOT), name);
    }

    @SuppressWarnings("unchecked")
    private boolean save(ModSettingsBridge.Entry entry, Object value, AbstractWidget widget) {
        try {
            entry.set(value);
            for (var control : audioWidgets.entrySet()) {
                String reason = ModSettingsBridge.audioRestriction(control.getKey());
                control.getValue().active = reason == null;
                control.getValue()
                        .setTooltip(
                                reason == null
                                        ? null
                                        : Tooltip.create(Component.translatable(reason)));
            }
            return true;
        } catch (Exception | LinkageError e) {
            LOGGER.warn("Cannot save mod option {}", entry.labelKey(), e);
            SystemToast.addOrUpdate(
                    minecraft.getToasts(),
                    SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                    Component.translatable("blockfield.settings.save_failed"),
                    Component.translatable(entry.labelKey()));
            widget.active = false;
            audioWidgets.remove(entry);
            try {
                Object previous = entry.get();
                if (widget instanceof CycleButton<?> cycle)
                    ((CycleButton<Object>) cycle).setValue(previous);
                else if (widget instanceof ValueSlider slider) slider.restore((Number) previous);
                else if (widget instanceof EditBox edit) edit.setValue(previous.toString());
            } catch (Exception | LinkageError restoreFailure) {
                e.addSuppressed(restoreFailure);
            }
            widget.setMessage(
                    Component.translatable(
                            "options.generic_value",
                            Component.translatable(entry.labelKey()),
                            Component.translatable("blockfield.settings.save_failed")));
            widget.setTooltip(
                    Tooltip.create(Component.translatable("blockfield.settings.save_failed.help")));
            return false;
        }
    }

    private final class NumberField extends EditBox {
        private final ModSettingsBridge.Entry entry;
        private Object persisted;
        private Object pending;

        NumberField(ModSettingsBridge.Entry entry, Number current) {
            super(ModSettingsScreen.this.font, 310, 20, Component.translatable(entry.labelKey()));
            this.entry = entry;
            persisted = current;
            pendingEdits.add(this::commit);
            setMaxLength(24);
            setValue(current.toString());
            setTooltip(
                    Tooltip.create(
                            entry.min() == -Double.MAX_VALUE && entry.max() == Double.MAX_VALUE
                                    ? Component.translatable(
                                            "blockfield.settings.numeric_unbounded")
                                    : Component.translatable(
                                            "blockfield.settings.numeric",
                                            entry.min(),
                                            entry.max())));
            setResponder(
                    text -> {
                        if (!active) return;
                        double value;
                        try {
                            value = Double.parseDouble(text);
                            if (!Double.isFinite(value)
                                    || value < entry.min()
                                    || value > entry.max()
                                    || current instanceof Integer && value != Math.rint(value))
                                throw new NumberFormatException();
                        } catch (NumberFormatException e) {
                            pending = null;
                            setTextColor(0xFF5555);
                            return;
                        }
                        setTextColor(0xE0E0E0);
                        pending = ModSettingsBridge.number(current, value);
                    });
        }

        private void commit() {
            if (active
                    && pending != null
                    && !pending.equals(persisted)
                    && save(entry, pending, this)) persisted = pending;
        }

        @Override
        public void setFocused(boolean focused) {
            if (!focused) commit();
            super.setFocused(focused);
        }

        @Override
        public boolean keyPressed(int key, int scanCode, int modifiers) {
            if (isFocused() && (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER)) {
                commit();
                return true;
            }
            return super.keyPressed(key, scanCode, modifiers);
        }
    }

    private final class ValueSlider extends AbstractSliderButton {
        private final ModSettingsBridge.Entry entry;
        private final Number original;
        private Object persisted;
        private boolean edited;

        ValueSlider(ModSettingsBridge.Entry entry, Number current) {
            super(
                    0,
                    0,
                    310,
                    20,
                    Component.empty(),
                    (current.doubleValue() - entry.min()) / (entry.max() - entry.min()));
            this.entry = entry;
            this.original = current;
            persisted = current;
            pendingEdits.add(this::commit);
            updateMessage();
        }

        private void restore(Number previous) {
            value = (previous.doubleValue() - entry.min()) / (entry.max() - entry.min());
            updateMessage();
        }

        private Object current() {
            double number = entry.min() + value * (entry.max() - entry.min());
            number = Math.round(number / entry.step()) * entry.step();
            return ModSettingsBridge.number(
                    original, Math.max(entry.min(), Math.min(entry.max(), number)));
        }

        @Override
        protected void updateMessage() {
            setMessage(
                    Component.translatable(
                            "options.generic_value",
                            Component.translatable(entry.labelKey()),
                            valueLabel(current())));
        }

        @Override
        protected void applyValue() {
            // Native sliders update throughout a drag; save once when editing finishes.
            edited = true;
        }

        private void commit() {
            if (!edited || !active) return;
            Object next = current();
            if (next.equals(persisted) || save(entry, next, this)) {
                persisted = next;
                edited = false;
            }
        }

        @Override
        public void onRelease(double mouseX, double mouseY) {
            super.onRelease(mouseX, mouseY);
            commit();
        }

        @Override
        public void setFocused(boolean focused) {
            if (!focused) commit();
            super.setFocused(focused);
        }

        @Override
        public boolean keyPressed(int key, int scanCode, int modifiers) {
            boolean handled = super.keyPressed(key, scanCode, modifiers);
            if (handled) commit();
            return handled;
        }
    }

    private final class SettingsList extends OptionsList {
        SettingsList() {
            super(
                    ModSettingsScreen.this.minecraft,
                    ModSettingsScreen.this.width,
                    ModSettingsScreen.this);
        }

        void clear() {
            clearEntries();
        }

        void row(AbstractWidget widget) {
            addEntry(Entry.big(List.of(widget), ModSettingsScreen.this));
        }
    }

    public static void initButton() {
        ModSettingsBridge.init();
        ScreenEvents.AFTER_INIT.register(
                (client, screen, width, height) -> {
                    if (!(screen instanceof OptionsScreen)) return;
                    List<AbstractWidget> buttons = Screens.getButtons(screen);
                    if (buttons.stream()
                            .anyMatch(
                                    b ->
                                            b.getMessage()
                                                    .equals(
                                                            Component.translatable(
                                                                    "blockfield.settings.title"))))
                        return;
                    Button done =
                            buttons.stream()
                                    .filter(Button.class::isInstance)
                                    .map(Button.class::cast)
                                    .filter(b -> b.getMessage().equals(CommonComponents.GUI_DONE))
                                    .findFirst()
                                    .orElse(null);
                    if (done == null) return;
                    Button mods =
                            Button.builder(
                                            Component.translatable("blockfield.settings.title"),
                                            b -> client.setScreen(new ModSettingsScreen(screen)))
                                    .width(150)
                                    .build();
                    buttons.add(mods);
                    positionFooter(screen, done, mods);
                    // OptionsScreen repositions its layout without rebuilding on resize/return.
                    ScreenEvents.beforeRender(screen)
                            .register(
                                    (s, graphics, mouseX, mouseY, delta) ->
                                            positionFooter(s, done, mods));
                });
    }

    private static void positionFooter(Screen screen, Button done, Button mods) {
        done.setWidth(150);
        done.setX(screen.width / 2 + 5);
        mods.setPosition(screen.width / 2 - 155, done.getY());
    }
}
