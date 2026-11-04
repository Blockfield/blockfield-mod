package com.lunares.blockfield.client.gui.screen;

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

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

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
                                            b -> openSettings(client, screen))
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

    private static void openSettings(Minecraft client, Screen parent) {
        Screen yacl = null;
        try {
            if (yaclPresent()) yacl = buildYaclScreen(parent);
        } catch (Exception | LinkageError e) {
            LOGGER.debug("YACL settings unavailable, using the vanilla list", e);
        }
        client.setScreen(yacl != null ? yacl : new ModSettingsScreen(parent));
    }

    private static boolean yaclPresent() {
        try {
            Class.forName(
                    "dev.isxander.yacl3.api.YetAnotherConfigLib",
                    false,
                    ModSettingsScreen.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /**
     * Staged YACL save that failed on this entry; entries written earlier in the batch were already
     * restored to their previous values.
     */
    static final class StagedSaveException extends Exception {
        final String labelKey;

        StagedSaveException(String labelKey, Throwable cause) {
            super(cause);
            this.labelKey = labelKey;
        }
    }

    /**
     * Applies staged option values in screen order, restoring every already-written entry when a
     * later one fails (the pendingEdits pattern). Entries equal to the live value are skipped so an
     * unchanged option never touches its config file.
     */
    static void commitStaged(Map<ModSettingsBridge.Entry, Object> staged)
            throws StagedSaveException {
        Map<ModSettingsBridge.Entry, Object> applied = new LinkedHashMap<>();
        for (var edit : staged.entrySet()) {
            ModSettingsBridge.Entry entry = edit.getKey();
            Object previous;
            try {
                previous = entry.get();
            } catch (Exception | LinkageError e) {
                throw rollback(applied, entry, e);
            }
            if (Objects.equals(edit.getValue(), previous)) continue;
            try {
                entry.set(edit.getValue());
            } catch (Exception | LinkageError e) {
                throw rollback(applied, entry, e);
            }
            applied.put(entry, previous);
        }
    }

    private static StagedSaveException rollback(
            Map<ModSettingsBridge.Entry, Object> applied,
            ModSettingsBridge.Entry failed,
            Throwable cause) {
        StagedSaveException failure = new StagedSaveException(failed.labelKey(), cause);
        var done = new ArrayList<>(applied.entrySet());
        for (int i = done.size() - 1; i >= 0; i--) {
            var restored = done.get(i);
            try {
                restored.getKey().set(restored.getValue());
            } catch (Exception | LinkageError restoreFailure) {
                failure.addSuppressed(restoreFailure);
            }
        }
        return failure;
    }

    // YACL chrome. The pack ships YACL 3.8.2 but it is not on the compile classpath, so the
    // screen below goes through its public API by reflection (same house pattern as
    // AnimationPolicyTweaksScreenMixin). The bridged write path is untouched: bindings only
    // stage values, and the save runnable commits them with rollback.
    private static final String YACL_API = "dev.isxander.yacl3.api.";
    private static final String YACL_CONTROLLER = YACL_API + "controller.";

    private static Class<?> yaclClass(String name) throws ClassNotFoundException {
        return Class.forName(YACL_API + name);
    }

    private static Method yaclCall(Object target, String name, int arity)
            throws NoSuchMethodException {
        return yaclCall(target, name, arity, null);
    }

    private static Method yaclCall(
            Object target, String name, int arity, Predicate<Class<?>[]> params)
            throws NoSuchMethodException {
        Class<?> type = target instanceof Class<?> c ? c : target.getClass();
        Method found = null;
        for (Method m : type.getMethods()) {
            if (!m.getName().equals(name)
                    || m.getParameterCount() != arity
                    || m.isBridge()
                    || m.isSynthetic()
                    || (params != null && !params.test(m.getParameterTypes()))) continue;
            if (found != null)
                throw new NoSuchMethodException("Ambiguous " + type.getName() + "#" + name);
            found = m;
        }
        if (found == null)
            throw new NoSuchMethodException(type.getName() + "#" + name + "/" + arity);
        return found;
    }

    private static Screen buildYaclScreen(Screen parent) throws Exception {
        Class<?> optionClass = yaclClass("Option");
        Class<?> categoryClass = yaclClass("ConfigCategory");
        Class<?> descriptionClass = yaclClass("OptionDescription");
        Object lib = yaclCall(yaclClass("YetAnotherConfigLib"), "createBuilder", 0).invoke(null);
        Map<String, List<ModSettingsBridge.Entry>> groups = new LinkedHashMap<>();
        for (var entry : ModSettingsBridge.entries())
            groups.computeIfAbsent(entry.modId(), k -> new ArrayList<>()).add(entry);
        Map<ModSettingsBridge.Entry, Object> staged = new LinkedHashMap<>();
        Map<ModSettingsBridge.Entry, Object> options = new LinkedHashMap<>();
        int built = 0;
        for (var group : groups.entrySet()) {
            Object category = yaclCall(categoryClass, "createBuilder", 0).invoke(null);
            yaclCall(category, "name", 1)
                    .invoke(
                            category,
                            Component.translatable("blockfield.settings.group." + group.getKey()));
            boolean any = false;
            for (var entry : group.getValue()) {
                Object option = yaclOption(optionClass, descriptionClass, entry, staged);
                if (option == null) continue;
                yaclCall(category, "option", 1, p -> p[0].equals(optionClass))
                        .invoke(category, option);
                options.put(entry, option);
                any = true;
                built++;
            }
            if (!any) continue;
            Object done = yaclCall(category, "build", 0).invoke(category);
            yaclCall(lib, "category", 1, p -> p[0].equals(categoryClass)).invoke(lib, done);
        }
        if (built == 0) throw new IllegalStateException("No YACL options built");
        yaclCall(lib, "title", 1).invoke(lib, Component.translatable("blockfield.settings.title"));
        Minecraft minecraft = Minecraft.getInstance();
        yaclCall(lib, "save", 1)
                .invoke(lib, (Runnable) () -> saveYaclStaged(minecraft, staged, options));
        Object config = yaclCall(lib, "build", 0).invoke(lib);
        return (Screen) yaclCall(config, "generateScreen", 1).invoke(config, parent);
    }

    private static Object yaclOption(
            Class<?> optionClass,
            Class<?> descriptionClass,
            ModSettingsBridge.Entry entry,
            Map<ModSettingsBridge.Entry, Object> staged) {
        try {
            Object current = null;
            String reason = entry.restriction();
            try {
                current = entry.get();
            } catch (Exception | LinkageError e) {
                LOGGER.debug("Unavailable mod option {}", entry.labelKey(), e);
                if (reason == null)
                    reason =
                            ModSettingsBridge.loaded(entry.modId())
                                    ? "blockfield.settings.unavailable"
                                    : "blockfield.settings.missing";
            }
            if (reason == null && entry.modId().equals("voicechat")) {
                String audio = ModSettingsBridge.audioRestriction(entry);
                if (audio != null) reason = audio;
            }
            if (reason != null)
                return lockedOption(optionClass, descriptionClass, entry, current, reason, staged);
            if (current instanceof Boolean value)
                return boolOption(optionClass, descriptionClass, entry, value, staged);
            if (current instanceof Enum<?> e)
                return enumOption(optionClass, descriptionClass, entry, e, staged);
            if (current instanceof Number number)
                return numberOption(optionClass, descriptionClass, entry, number, staged);
            return lockedOption(
                    optionClass,
                    descriptionClass,
                    entry,
                    current,
                    "blockfield.settings.unavailable",
                    staged);
        } catch (Exception | LinkageError e) {
            LOGGER.debug("YACL option skipped for {}", entry.labelKey(), e);
            return null;
        }
    }

    private static Object optionBuilder(
            Class<?> optionClass,
            Class<?> descriptionClass,
            ModSettingsBridge.Entry entry,
            Object current,
            Component[] description,
            Map<ModSettingsBridge.Entry, Object> staged)
            throws Exception {
        Object builder = yaclCall(optionClass, "createBuilder", 0).invoke(null);
        yaclCall(builder, "name", 1).invoke(builder, Component.translatable(entry.labelKey()));
        yaclCall(builder, "description", 1, p -> p[0].isAssignableFrom(descriptionClass))
                .invoke(
                        builder,
                        yaclCall(descriptionClass, "of", 1).invoke(null, (Object) description));
        Supplier<Object> getter = () -> readLive(entry, current);
        Consumer<Object> setter = value -> staged.put(entry, value);
        yaclCall(builder, "binding", 3).invoke(builder, current, getter, setter);
        return builder;
    }

    private static Object readLive(ModSettingsBridge.Entry entry, Object fallback) {
        try {
            return entry.get();
        } catch (Exception | LinkageError e) {
            return fallback;
        }
    }

    private static Component[] optionDescription(ModSettingsBridge.Entry entry, Object current) {
        String tip = entry.labelKey() + ".tooltip";
        if (I18n.exists(tip)) return new Component[] {Component.translatable(tip)};
        if (current instanceof Number && entry.step() == 0 && entry.restriction() == null) {
            if (entry.min() == -Double.MAX_VALUE && entry.max() == Double.MAX_VALUE)
                return new Component[] {
                    Component.translatable("blockfield.settings.numeric_unbounded")
                };
            return new Component[] {
                Component.translatable("blockfield.settings.numeric", entry.min(), entry.max())
            };
        }
        return new Component[0];
    }

    private static Object lockedOption(
            Class<?> optionClass,
            Class<?> descriptionClass,
            ModSettingsBridge.Entry entry,
            Object current,
            String reason,
            Map<ModSettingsBridge.Entry, Object> staged)
            throws Exception {
        boolean value = current instanceof Boolean enabled && enabled;
        Object builder =
                optionBuilder(
                        optionClass,
                        descriptionClass,
                        entry,
                        value,
                        new Component[] {Component.translatable(reason)},
                        staged);
        yaclCall(builder, "available", 1).invoke(builder, false);
        yaclCall(builder, "controller", 1)
                .invoke(
                        builder,
                        (Function<Object, Object>)
                                option -> {
                                    try {
                                        return boolController(
                                                option, ModSettingsScreen::valueLabel);
                                    } catch (Exception | LinkageError e) {
                                        throw new IllegalStateException(
                                                "YACL boolean controller failed", e);
                                    }
                                });
        return yaclCall(builder, "build", 0).invoke(builder);
    }

    private static Object boolOption(
            Class<?> optionClass,
            Class<?> descriptionClass,
            ModSettingsBridge.Entry entry,
            Boolean value,
            Map<ModSettingsBridge.Entry, Object> staged)
            throws Exception {
        Object builder =
                optionBuilder(
                        optionClass,
                        descriptionClass,
                        entry,
                        value,
                        optionDescription(entry, value),
                        staged);
        String on = entry.labelKey() + ".on";
        Function<Object, Object> words =
                I18n.exists(on)
                        ? v ->
                                Component.translatable(
                                        Boolean.TRUE.equals(v) ? on : entry.labelKey() + ".off")
                        : ModSettingsScreen::valueLabel;
        yaclCall(builder, "controller", 1)
                .invoke(
                        builder,
                        (Function<Object, Object>)
                                option -> {
                                    try {
                                        return boolController(option, words);
                                    } catch (Exception | LinkageError e) {
                                        throw new IllegalStateException(
                                                "YACL boolean controller failed", e);
                                    }
                                });
        return yaclCall(builder, "build", 0).invoke(builder);
    }

    private static Object boolController(Object option, Function<Object, Object> words)
            throws Exception {
        Object box =
                yaclCall(yaclClass("controller.BooleanControllerBuilder"), "create", 1)
                        .invoke(null, option);
        if (words != null) yaclCall(box, "valueFormatter", 1).invoke(box, words);
        return yaclCall(box, "build", 0).invoke(box);
    }

    private static Object enumOption(
            Class<?> optionClass,
            Class<?> descriptionClass,
            ModSettingsBridge.Entry entry,
            Enum<?> value,
            Map<ModSettingsBridge.Entry, Object> staged)
            throws Exception {
        Object builder =
                optionBuilder(
                        optionClass,
                        descriptionClass,
                        entry,
                        value,
                        optionDescription(entry, value),
                        staged);
        yaclCall(builder, "controller", 1)
                .invoke(
                        builder,
                        (Function<Object, Object>)
                                option -> {
                                    try {
                                        Object box =
                                                yaclCall(
                                                                yaclClass(
                                                                        "controller.EnumControllerBuilder"),
                                                                "create",
                                                                1)
                                                        .invoke(null, option);
                                        yaclCall(box, "enumClass", 1)
                                                .invoke(box, value.getDeclaringClass());
                                        yaclCall(box, "valueFormatter", 1)
                                                .invoke(
                                                        box,
                                                        (Function<Object, Object>)
                                                                ModSettingsScreen::valueLabel);
                                        return yaclCall(box, "build", 0).invoke(box);
                                    } catch (Exception | LinkageError e) {
                                        throw new IllegalStateException(
                                                "YACL enum controller failed", e);
                                    }
                                });
        return yaclCall(builder, "build", 0).invoke(builder);
    }

    private static Object numberOption(
            Class<?> optionClass,
            Class<?> descriptionClass,
            ModSettingsBridge.Entry entry,
            Number number,
            Map<ModSettingsBridge.Entry, Object> staged)
            throws Exception {
        String kind =
                number instanceof Integer
                        ? "Integer"
                        : number instanceof Long
                                ? "Long"
                                : number instanceof Float
                                        ? "Float"
                                        : number instanceof Double ? "Double" : null;
        if (kind == null)
            return lockedOption(
                    optionClass,
                    descriptionClass,
                    entry,
                    number,
                    "blockfield.settings.unavailable",
                    staged);
        boolean slider =
                entry.step() > 0
                        && Double.isFinite(entry.min())
                        && Double.isFinite(entry.max())
                        && entry.max() > entry.min();
        Object builder =
                optionBuilder(
                        optionClass,
                        descriptionClass,
                        entry,
                        number,
                        optionDescription(entry, number),
                        staged);
        String controller =
                YACL_CONTROLLER
                        + kind
                        + (slider ? "SliderControllerBuilder" : "FieldControllerBuilder");
        yaclCall(builder, "controller", 1)
                .invoke(
                        builder,
                        (Function<Object, Object>)
                                option -> {
                                    try {
                                        Object box =
                                                yaclCall(Class.forName(controller), "create", 1)
                                                        .invoke(null, option);
                                        if (slider) {
                                            yaclCall(box, "range", 2)
                                                    .invoke(
                                                            box,
                                                            number(kind, entry.min()),
                                                            number(kind, entry.max()));
                                            yaclCall(box, "step", 1)
                                                    .invoke(box, number(kind, entry.step()));
                                        } else {
                                            if (Double.isFinite(entry.min()))
                                                yaclCall(box, "min", 1)
                                                        .invoke(box, number(kind, entry.min()));
                                            if (Double.isFinite(entry.max()))
                                                yaclCall(box, "max", 1)
                                                        .invoke(box, number(kind, entry.max()));
                                        }
                                        yaclCall(box, "valueFormatter", 1)
                                                .invoke(
                                                        box,
                                                        (Function<Object, Object>)
                                                                ModSettingsScreen::valueLabel);
                                        return yaclCall(box, "build", 0).invoke(box);
                                    } catch (Exception | LinkageError e) {
                                        throw new IllegalStateException(
                                                "YACL number controller failed", e);
                                    }
                                });
        return yaclCall(builder, "build", 0).invoke(builder);
    }

    private static Number number(String kind, double value) {
        return switch (kind) {
            case "Integer" -> (int) value;
            case "Long" -> (long) value;
            case "Float" -> (float) value;
            default -> value;
        };
    }

    private static void saveYaclStaged(
            Minecraft minecraft,
            Map<ModSettingsBridge.Entry, Object> staged,
            Map<ModSettingsBridge.Entry, Object> options) {
        if (!staged.isEmpty()) {
            Map<ModSettingsBridge.Entry, Object> pending = new LinkedHashMap<>(staged);
            staged.clear();
            try {
                commitStaged(pending);
            } catch (StagedSaveException e) {
                LOGGER.warn("Cannot save mod option {}", e.labelKey, e);
                SystemToast.addOrUpdate(
                        minecraft.getToasts(),
                        SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                        Component.translatable("blockfield.settings.save_failed"),
                        Component.translatable(e.labelKey));
            } catch (LinkageError e) {
                LOGGER.warn("Cannot save mod options", e);
                SystemToast.addOrUpdate(
                        minecraft.getToasts(),
                        SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                        Component.translatable("blockfield.settings.save_failed"),
                        Component.translatable("blockfield.settings.save_failed.help"));
            }
        }
        refreshYaclAudio(options);
    }

    private static void refreshYaclAudio(Map<ModSettingsBridge.Entry, Object> options) {
        for (var saved : options.entrySet()) {
            if (!saved.getKey().modId().equals("voicechat")) continue;
            String reason = ModSettingsBridge.audioRestriction(saved.getKey());
            try {
                yaclCall(saved.getValue(), "setAvailable", 1)
                        .invoke(saved.getValue(), reason == null);
            } catch (Exception | LinkageError e) {
                LOGGER.debug("Cannot refresh audio lock", e);
            }
        }
    }
}
