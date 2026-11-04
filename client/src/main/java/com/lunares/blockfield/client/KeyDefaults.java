package com.lunares.blockfield.client;

import static java.util.Map.entry;

import com.mojang.blaze3d.platform.InputConstants;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.slf4j.LoggerFactory;

/** Pack defaults replace untouched bindings; custom bindings survive upgrades. */
public final class KeyDefaults {

    private static final InputConstants.Key NONE = InputConstants.UNKNOWN;

    private static InputConstants.Key keyboard(int code) {
        return InputConstants.Type.KEYSYM.getOrCreate(code);
    }

    static final Map<String, InputConstants.Key> DEFAULTS =
            Map.ofEntries(
                    entry("key.sprint", keyboard(GLFW.GLFW_KEY_LEFT_SHIFT)),
                    entry("key.sneak", keyboard(GLFW.GLFW_KEY_LEFT_CONTROL)),
                    entry("key.inventory", keyboard(GLFW.GLFW_KEY_I)),
                    entry("key.drop", NONE),
                    entry("key.swapOffhand", NONE),
                    entry("key.pickItem", NONE),
                    entry("key.parcool.FastRun", NONE),
                    entry("key.parcool.Crawl", keyboard(GLFW.GLFW_KEY_Z)),
                    entry("key.parcool.Breakfall", keyboard(GLFW.GLFW_KEY_LEFT_CONTROL)),
                    entry("key.parcool.ClingToCliff", keyboard(GLFW.GLFW_KEY_E)),
                    entry("key.parcool.RideZipline", keyboard(GLFW.GLFW_KEY_E)),
                    entry("key.parcool.WallSlide", keyboard(GLFW.GLFW_KEY_E)),
                    entry("key.parcool.Vault", keyboard(GLFW.GLFW_KEY_SPACE)),
                    entry("key.parcool.HangDown", keyboard(GLFW.GLFW_KEY_E)),
                    entry("key.parcool.Dodge", keyboard(GLFW.GLFW_KEY_C)),
                    entry("key.parcool.HorizontalWallRun", keyboard(GLFW.GLFW_KEY_C)),
                    entry("key.parcool.HideInBlock", keyboard(GLFW.GLFW_KEY_Z)),
                    entry("key.parcool.openSetting", NONE),
                    entry("key.tacz.open_config.desc", NONE),
                    entry("key.tacz.crawl.desc", NONE),
                    entry("key.tacz.interact.desc", keyboard(GLFW.GLFW_KEY_E)),
                    entry("key.tacz.fire_select.desc", keyboard(GLFW.GLFW_KEY_B)),
                    entry("key.tacz.refit.desc", NONE),
                    entry("key.tacz.inspect.desc", keyboard(GLFW.GLFW_KEY_F)),
                    entry("key.tacz.zoom.desc", keyboard(GLFW.GLFW_KEY_V)),
                    entry("key.tacz.melee.desc", NONE),
                    entry("key.superbwarfare.config", NONE),
                    entry("key.superbwarfare.fire_mode", keyboard(GLFW.GLFW_KEY_B)),
                    entry("key.superbwarfare.move_shift", keyboard(GLFW.GLFW_KEY_LEFT_CONTROL)),
                    entry("key.superbwarfare.move_ctrl", keyboard(GLFW.GLFW_KEY_LEFT_SHIFT)),
                    entry("key.superbwarfare.breath", keyboard(GLFW.GLFW_KEY_LEFT_SHIFT)),
                    entry("key.superbwarfare.interact", keyboard(GLFW.GLFW_KEY_E)),
                    entry("key.superbwarfare.dismount", keyboard(GLFW.GLFW_KEY_G)),
                    entry("key.superbwarfare.change_seat", keyboard(GLFW.GLFW_KEY_C)),
                    entry("key.superbwarfare.melee", NONE),
                    entry("key.superbwarfare.release_decoy", keyboard(GLFW.GLFW_KEY_X)),
                    entry("key.superbwarfare.vehicle_seek", keyboard(GLFW.GLFW_KEY_Q)),
                    entry("key.superbwarfare.loiter_config", NONE),
                    entry("key.superbwarfare.edit_mode", NONE),
                    entry("key.superbwarfare.mark", NONE),
                    entry("key.superbwarfare.toggle_tactical_map", NONE),
                    entry("key.superbwarfare.free_camera", NONE),
                    entry("create.keyinfo.toolmenu", NONE),
                    entry("create.keyinfo.toolbelt", NONE),
                    entry("key.voice_chat", NONE),
                    entry("key.voice_chat_group", NONE),
                    entry("key.disable_voice_chat", NONE),
                    entry("key.hide_icons", NONE),
                    entry("key.mute_microphone", keyboard(GLFW.GLFW_KEY_COMMA)),
                    entry("key.push_to_talk", keyboard(GLFW.GLFW_KEY_LEFT_ALT)),
                    entry("key.blockfield.radio", keyboard(GLFW.GLFW_KEY_CAPS_LOCK)),
                    entry("key.blockfield.rig", keyboard(GLFW.GLFW_KEY_J)),
                    entry("key.blockfield.tablet", keyboard(GLFW.GLFW_KEY_M)),
                    entry("key.pingwheel.ping_location", InputConstants.Type.MOUSE.getOrCreate(2)),
                    entry("iris.keybind.reload", NONE),
                    entry("iris.keybind.toggleShaders", NONE),
                    entry("iris.keybind.shaderPackSelection", NONE),
                    entry("accessories.key.open_accessories_screen", NONE),
                    entry("key.advancements", NONE),
                    entry("key.socialInteractions", NONE),
                    entry("key.togglePerspective", NONE),
                    entry("key.loadToolbarActivator", NONE),
                    entry("key.saveToolbarActivator", NONE));

    // These bindings were already changed by the previous pack defaults, before this layout.
    private static final Map<String, InputConstants.Key> PREVIOUS_DEFAULTS =
            Map.of(
                    "key.parcool.FastRun", keyboard(GLFW.GLFW_KEY_LEFT_SHIFT),
                    "key.tacz.melee.desc", keyboard(GLFW.GLFW_KEY_V),
                    "key.superbwarfare.fire_mode", NONE,
                    "key.push_to_talk", InputConstants.Type.MOUSE.getOrCreate(4),
                    "key.blockfield.radio", InputConstants.Type.MOUSE.getOrCreate(4));

    private static final Pattern FORGE_MODIFIER = Pattern.compile("^key_.*:(ALT|CONTROL|SHIFT)$");

    private KeyDefaults() {}

    /**
     * Строки Forge-формата (key.keyboard.t:ALT) ваниль 1.21 не разбирает и роняет загрузку всех
     * настроек.
     */
    private static boolean dropForgeModifierLines(Path optionsFile) {
        try {
            if (!Files.exists(optionsFile)) return false;
            List<String> lines = Files.readAllLines(optionsFile);
            List<String> kept =
                    lines.stream().filter(l -> !FORGE_MODIFIER.matcher(l).matches()).toList();
            if (kept.size() == lines.size()) return false;
            Files.write(optionsFile, kept);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Свежая установка: options.txt нет или бинды ещё ни разу не сохранялись. */
    private static boolean hasSavedKeyBinds(Path optionsFile) {
        try {
            if (!Files.exists(optionsFile)) return false;
            return Files.readAllLines(optionsFile).stream().anyMatch(l -> l.startsWith("key_"));
        } catch (IOException e) {
            return true;
        }
    }

    static void init() {
        ClientLifecycleEvents.CLIENT_STARTED.register(KeyDefaults::onClientStarted);
    }

    private static void onClientStarted(Minecraft mc) {
        Path optionsFile = mc.gameDirectory.toPath().resolve("options.txt");
        if (dropForgeModifierLines(optionsFile)) mc.options.load();
        // Свежесть ловится здесь: до первого тика OptionsDefaults успеет сохранить options.txt
        // (вместе со всеми key_-строками ванильных биндов) и признак «биндов ещё не было»
        // потеряется.
        boolean fresh = !hasSavedKeyBinds(optionsFile);
        boolean[] applied = {false};
        // ponytail: rebind+save уезжает на первый тик, чтобы options.save() не обгонял
        // OptionsDefaults,
        // который в CLIENT_STARTED решает по наличию строк в options.txt; слушатель висит на весь
        // сеанс —
        // возвращать на CLIENT_STARTED можно, только если оба обработчика снова станут синхронными.
        ClientTickEvents.START_CLIENT_TICK.register(
                client -> {
                    if (applied[0]) return;
                    applied[0] = true;
                    applyDefaults(client, fresh);
                });
    }

    private static void applyDefaults(Minecraft mc, boolean fresh) {
        Path historyPath =
                mc.gameDirectory.toPath().resolve("config/blockfield-key-defaults.properties");
        KeyDefaultHistory history = null;
        try {
            history = KeyDefaultHistory.load(historyPath);
        } catch (IOException | IllegalArgumentException e) {
            LoggerFactory.getLogger(KeyDefaults.class)
                    .warn("Cannot read key defaults history; preserving current bindings", e);
        }
        boolean changed = false;
        for (KeyMapping mapping : mc.options.keyMappings) {
            InputConstants.Key want = DEFAULTS.get(mapping.getName());
            if (want == null) continue;
            InputConstants.Key bound = KeyBindingHelper.getBoundKeyOf(mapping);
            InputConstants.Key legacy = PREVIOUS_DEFAULTS.get(mapping.getName());
            boolean untouched =
                    fresh
                            || (history != null
                                    && history.shouldUpdate(
                                            mapping.getName(),
                                            bound.getName(),
                                            mapping.getDefaultKey().getName(),
                                            legacy == null ? null : legacy.getName()));
            ((KeyMappingDefaultAccess) mapping).blockfield$setDefaultKey(want);
            if (untouched && !bound.equals(want)) {
                mapping.setKey(want);
                changed = true;
            }
            if (history != null) history.record(mapping.getName(), want.getName());
        }
        if (changed) {
            KeyMapping.resetMapping();
            mc.options.save();
        }
        if (history != null) {
            try {
                history.save(historyPath);
            } catch (IOException e) {
                LoggerFactory.getLogger(KeyDefaults.class)
                        .warn("Cannot save key defaults history", e);
            }
        }
    }
}
