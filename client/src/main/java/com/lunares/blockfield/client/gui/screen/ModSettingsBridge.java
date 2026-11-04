package com.lunares.blockfield.client.gui.screen;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lunares.blockfield.client.ClientConfig;
import java.io.IOException;
import java.lang.invoke.MethodHandleProxies;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Optional pack APIs are resolved lazily, using the signatures of the pinned jars. */
public final class ModSettingsBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModSettingsBridge.class);
    private static final Gson GSON =
            new GsonBuilder().serializeNulls().setPrettyPrinting().create();
    private static final String LOCKED = "blockfield.settings.locked";

    private ModSettingsBridge() {}

    @SuppressWarnings("unchecked")
    static void init() {
        if (!loaded("superbwarfare")) return;
        try {
            Class<?> events =
                    Class.forName(
                            "fuzs.forgeconfigapiport.fabric.api.neoforge.v4.NeoForgeModConfigEvents");
            Class<?> listener = Class.forName(events.getName() + "$Reloading");
            Event<Object> event =
                    (Event<Object>)
                            events.getMethod("reloading", String.class)
                                    .invoke(null, "superbwarfare");
            event.register(
                    MethodHandleProxies.asInterfaceInstance(
                            listener,
                            MethodHandles.lookup()
                                    .findStatic(
                                            ModSettingsBridge.class,
                                            "reloadArmorHud",
                                            MethodType.methodType(void.class, Object.class))));
        } catch (Exception | LinkageError error) {
            LOGGER.warn("Cannot register forced armor HUD config reload listener", error);
        }
    }

    private static void reloadArmorHud(Object config) {
        try {
            if (invoke(config, "getFileName").equals("superbwarfare-client.toml"))
                keepArmorHudEnabled(
                        field(
                                "com.atsuishio.superbwarfare.config.client.DisplayConfig",
                                "ARMOR_PLATE_HUD"));
        } catch (Exception | LinkageError error) {
            LOGGER.warn("Cannot restore forced armor HUD after config reload", error);
        }
    }

    static void keepArmorHudEnabled(Object value) throws Exception {
        // Forge's watcher reloads the saved preference after UI writes; preserve the live rule.
        value.getClass().getMethod("set", Object.class).invoke(value, Boolean.TRUE);
    }

    @FunctionalInterface
    public interface Read {
        Object get() throws Exception;
    }

    @FunctionalInterface
    public interface Write {
        void set(Object value) throws Exception;
    }

    @FunctionalInterface
    interface Action {
        void run() throws Exception;
    }

    public record Entry(
            String modId,
            String labelKey,
            double min,
            double max,
            double step,
            Read read,
            Write write,
            String restriction) {
        public Object get() throws Exception {
            if (!loaded(modId)) throw new IllegalStateException("Mod not installed: " + modId);
            return Objects.requireNonNull(read.get(), "Config not initialized");
        }

        public void set(Object value) throws Exception {
            if (restriction != null) throw new IllegalStateException("Setting locked by pack");
            Object previous = get();
            if (previous instanceof Number) {
                if (!(value instanceof Number next)
                        || !Double.isFinite(next.doubleValue())
                        || next.doubleValue() < min
                        || next.doubleValue() > max) {
                    throw new IllegalArgumentException("Value outside native range");
                }
                if (previous instanceof Integer
                        && ((Number) value).doubleValue()
                                != Math.rint(((Number) value).doubleValue()))
                    throw new IllegalArgumentException("Expected integer");
                value = number((Number) previous, ((Number) value).doubleValue());
            } else if (!previous.getClass().isInstance(value)) {
                throw new IllegalArgumentException("Wrong config value type");
            }
            write.set(value);
        }
    }

    static boolean loaded(String id) {
        return id.equals("blockfield") || FabricLoader.getInstance().isModLoaded(id);
    }

    static Object number(Number original, double value) {
        if (original instanceof Integer) return (int) value;
        if (original instanceof Float) return (float) value;
        return value;
    }

    static String audioRestriction(Entry entry) {
        if (entry.labelKey().equals("blockfield.settings.voice_mic_gain")) {
            try {
                Object cfg = field("de.maxhenkel.voicechat.VoicechatClient", "CLIENT_CONFIG");
                boolean enabled = (Boolean) invoke(cfg.getClass().getField("agc").get(cfg), "get");
                boolean supported =
                        (Boolean)
                                Class.forName("de.maxhenkel.voicechat.natives.SpeexManager")
                                        .getMethod("canUseAgc")
                                        .invoke(null);
                return enabled && supported ? "blockfield.settings.auto_gain_enabled" : null;
            } catch (Exception | LinkageError e) {
                return null;
            }
        }
        String nativeClass =
                switch (entry.labelKey()) {
                    case "blockfield.settings.voice_denoiser",
                            "blockfield.settings.voice_detection" ->
                            "de.maxhenkel.voicechat.natives.RNNoiseManager";
                    case "blockfield.settings.voice_auto_gain" ->
                            "de.maxhenkel.voicechat.natives.SpeexManager";
                    default -> null;
                };
        if (nativeClass == null) return null;
        try {
            String method = nativeClass.endsWith("SpeexManager") ? "canUseAgc" : "canUseDenoiser";
            return (Boolean) Class.forName(nativeClass).getMethod(method).invoke(null)
                    ? null
                    : "blockfield.settings.audio_unavailable";
        } catch (Exception | LinkageError e) {
            return "blockfield.settings.audio_unavailable";
        }
    }

    private static Object field(String owner, String name) throws Exception {
        return Class.forName(owner).getField(name).get(null);
    }

    private static Object invoke(Object object, String method) throws Exception {
        return object.getClass().getMethod(method).invoke(object);
    }

    private static Path config(String file) {
        return FabricLoader.getInstance().getConfigDir().resolve(file);
    }

    private static Entry forge(
            String mod,
            String owner,
            String name,
            String label,
            double min,
            double max,
            double step,
            boolean locked) {
        Read read = () -> invoke(field(owner, name), "get");
        Write write =
                value -> {
                    Object entry = field(owner, name);
                    Object previous = invoke(entry, "get");
                    @SuppressWarnings("unchecked")
                    List<String> key = (List<String>) invoke(entry, "getPath");
                    List<String> protectedKey = null;
                    if (mod.equals("superbwarfare")) {
                        @SuppressWarnings("unchecked")
                        List<String> armorKey =
                                (List<String>)
                                        invoke(
                                                field(
                                                        "com.atsuishio.superbwarfare.config.client.DisplayConfig",
                                                        "ARMOR_PLATE_HUD"),
                                                "getPath");
                        protectedKey = armorKey;
                    }
                    try {
                        saveToml(
                                config(mod + "-client.toml"),
                                key,
                                value,
                                protectedKey,
                                () -> {
                                    entry.getClass()
                                            .getMethod("set", Object.class)
                                            .invoke(entry, value);
                                    invoke(entry, "save");
                                });
                    } catch (Exception | LinkageError e) {
                        entry.getClass().getMethod("set", Object.class).invoke(entry, previous);
                        throw e;
                    }
                };
        return new Entry(
                mod,
                "blockfield.settings." + label,
                min,
                max,
                step,
                read,
                write,
                locked ? LOCKED : null);
    }

    private static void forge(
            List<Entry> out,
            String mod,
            String owner,
            String field,
            String label,
            double min,
            double max,
            double step) {
        out.add(forge(mod, owner, field, label, min, max, step, false));
    }

    private static void forge(
            List<Entry> out, String mod, String owner, String field, String label) {
        forge(out, mod, owner, field, label, 0, 0, 0);
    }

    private static Entry own(String key, String label, Object fallback, double min, double max) {
        return new Entry(
                "blockfield",
                "blockfield.settings." + label,
                min,
                max,
                1,
                () ->
                        fallback instanceof Boolean enabled
                                ? ClientConfig.USER.getBoolean(key, enabled)
                                : ClientConfig.USER.getInt(
                                        key, (Integer) fallback, (int) min, (int) max),
                value -> ClientConfig.USER.set(key, value.toString()),
                null);
    }

    private static Entry voice(String name, String label, double min, double max, double step) {
        Read entry =
                () -> {
                    Object cfg = field("de.maxhenkel.voicechat.VoicechatClient", "CLIENT_CONFIG");
                    return cfg.getClass().getField(name).get(cfg);
                };
        return new Entry(
                "voicechat",
                "blockfield.settings." + label,
                min,
                max,
                step,
                () -> invoke(entry.get(), "get"),
                value -> {
                    Object nativeEntry = entry.get();
                    Object previous = invoke(nativeEntry, "get");
                    // The native async saver uses this same monitor; verification must not race it.
                    synchronized (invoke(nativeEntry, "getConfig")) {
                        try {
                            Path file = config("voicechat/voicechat-client.properties");
                            String key = (String) invoke(nativeEntry, "getKey");
                            seedVoiceUnknowns(nativeEntry, file);
                            saveProperties(
                                    file,
                                    key,
                                    value,
                                    () -> {
                                        if (name.equals("disabled")) {
                                            Class<?> manager =
                                                    Class.forName(
                                                            "de.maxhenkel.voicechat.voice.client.ClientManager");
                                            if (manager.getMethod("getClient").invoke(null)
                                                    == null) {
                                                nativeEntry
                                                        .getClass()
                                                        .getMethod("set", Object.class)
                                                        .invoke(nativeEntry, value);
                                            } else {
                                                Object state =
                                                        manager.getMethod("getPlayerStateManager")
                                                                .invoke(null);
                                                state.getClass()
                                                        .getMethod("setDisabled", boolean.class)
                                                        .invoke(state, value);
                                            }
                                        } else
                                            nativeEntry
                                                    .getClass()
                                                    .getMethod("set", Object.class)
                                                    .invoke(nativeEntry, value);
                                        invoke(nativeEntry, "saveSync");
                                        if (name.equals("audioType")) {
                                            Object client =
                                                    Class.forName(
                                                                    "de.maxhenkel.voicechat.voice.client.ClientManager")
                                                            .getMethod("getClient")
                                                            .invoke(null);
                                            if (client != null) invoke(client, "reloadAudio");
                                        }
                                    });
                        } catch (Exception | LinkageError e) {
                            nativeEntry
                                    .getClass()
                                    .getMethod("set", Object.class)
                                    .invoke(nativeEntry, previous);
                            throw e;
                        }
                    }
                },
                null);
    }

    private static Entry ping(
            String field, String getter, String label, double min, double max, double step) {
        Read handler = () -> field("nx.pingwheel.common.config.ClientConfig", "HANDLER");
        Read cfg = () -> invoke(handler.get(), "getConfig");
        return new Entry(
                "pingwheel",
                "blockfield.settings." + label,
                min,
                max,
                step,
                () -> invoke(cfg.get(), getter),
                value -> {
                    Object object = cfg.get();
                    Field property = object.getClass().getDeclaredField(field);
                    // Ping Wheel exposes public setters but its serialized field names are
                    // package-private.
                    Class<?> type = property.getType();
                    var setter =
                            object.getClass()
                                    .getMethod(
                                            "set"
                                                    + Character.toUpperCase(field.charAt(0))
                                                    + field.substring(1),
                                            type);
                    Object previous = invoke(object, getter);
                    try {
                        saveJson(
                                config("pingwheel.json"),
                                field,
                                value,
                                () -> {
                                    setter.invoke(object, value);
                                    invoke(handler.get(), "save");
                                });
                    } catch (Exception | LinkageError e) {
                        setter.invoke(object, previous);
                        throw e;
                    }
                },
                null);
    }

    private static Entry camera(String name, String label) {
        Read cfg =
                () -> {
                    Class.forName("com.electronwill.nightconfig.toml.TomlParser");
                    Class.forName("com.electronwill.nightconfig.toml.TomlWriter");
                    return Class.forName("mirsario.cameraoverhaul.configuration.Configuration")
                            .getMethod("get")
                            .invoke(null);
                };
        Read general = () -> cfg.get().getClass().getField("general").get(cfg.get());
        return new Entry(
                "cameraoverhaul",
                "blockfield.settings." + label,
                -Double.MAX_VALUE,
                Double.MAX_VALUE,
                0,
                () -> general.get().getClass().getField(name).get(general.get()),
                value -> {
                    Object object = general.get();
                    Field property = object.getClass().getField(name);
                    Object previous = property.get(object);
                    try {
                        saveToml(
                                config("cameraoverhaul.toml"),
                                List.of("general", name),
                                value,
                                null,
                                () -> {
                                    property.set(object, value);
                                    Class.forName(
                                                    "mirsario.cameraoverhaul.configuration.Configuration")
                                            .getMethod("saveConfig")
                                            .invoke(null);
                                });
                    } catch (Exception | LinkageError e) {
                        property.set(object, previous);
                        throw e;
                    }
                },
                null);
    }

    private static Entry damage(String name, String label) {
        return new Entry(
                "jeremyseqsdamageindicators",
                "blockfield.settings." + label,
                0,
                0,
                0,
                () -> {
                    Object cfg =
                            field(
                                    "com.jeremyseq.jeremyseqsdamageindicators.DamageIndicatorsConfig",
                                    "INSTANCE");
                    return cfg.getClass().getField(name).get(cfg);
                },
                value -> {
                    throw new IllegalStateException("Setting locked by pack");
                },
                LOCKED);
    }

    public static List<Entry> entries() {
        List<Entry> out = new ArrayList<>();
        out.add(own("minimap.enabled", "minimap_enabled", true, 0, 0));
        out.add(own("minimap.size", "minimap_size", 80, 48, 220));
        out.add(own("minimap.blocks", "minimap_blocks", 96, 24, 512));
        out.add(own("minimap.rotate", "minimap_rotate", true, 0, 0));
        out.add(
                new Entry(
                        "blockfield",
                        "blockfield.settings.workshop_effects",
                        0,
                        0,
                        1,
                        () -> !ClientConfig.USER.getBoolean("workshop.quietEffects", false),
                        value ->
                                ClientConfig.USER.set(
                                        "workshop.quietEffects",
                                        Boolean.toString(!Boolean.TRUE.equals(value))),
                        null));
        String t = "com.tacz.guns.config.client.";
        forge(
                out,
                "tacz",
                t + "ZoomConfig",
                "ZOOM_SENSITIVITY_BASE_MULTIPLIER",
                "tacz_ads",
                0,
                2,
                0.01);
        forge(
                out,
                "tacz",
                t + "ZoomConfig",
                "SCREEN_DISTANCE_COEFFICIENT",
                "tacz_distance",
                0,
                3,
                0.01);
        forge(out, "tacz", t + "KeyConfig", "HOLD_TO_AIM", "tacz_hold_aim");
        forge(out, "tacz", t + "KeyConfig", "HOLD_TO_CRAWL", "tacz_hold_crawl");
        forge(out, "tacz", t + "KeyConfig", "AUTO_RELOAD", "tacz_auto_reload");
        String r = t + "RenderConfig";
        forge(out, "tacz", r, "CROSSHAIR_TYPE", "tacz_crosshair");
        forge(out, "tacz", r, "GUN_HUD_ENABLE", "tacz_gun_hud");
        forge(out, "tacz", r, "KILL_AMOUNT_ENABLE", "tacz_kill_amount");
        forge(out, "tacz", r, "FIRST_PERSON_BULLET_TRACER_ENABLE", "tacz_tracer");
        forge(out, "tacz", r, "DISABLE_INTERACT_HUD_TEXT", "tacz_hide_hint");
        forge(out, "tacz", r, "ENABLE_LASER_FADE_OUT", "tacz_laser_fade");
        forge(out, "tacz", r, "GUN_LOD_RENDER_DISTANCE", "tacz_lod", 0, Integer.MAX_VALUE, 0);
        forge(
                out,
                "tacz",
                r,
                "BULLET_HOLE_PARTICLE_LIFE",
                "tacz_hole_life",
                0,
                Integer.MAX_VALUE,
                0);
        forge(out, "tacz", r, "BULLET_HOLE_PARTICLE_FADE_THRESHOLD", "tacz_hole_fade", 0, 1, 0.01);
        forge(out, "tacz", r, "HIT_MARKET_START_POSITION", "tacz_hit_position", -1024, 1024, 1);
        forge(
                out,
                "tacz",
                r,
                "KILL_AMOUNT_DURATION_SECOND",
                "tacz_kill_duration",
                0,
                Double.MAX_VALUE,
                0);
        forge(
                out,
                "tacz",
                r,
                "TARGET_RENDER_DISTANCE",
                "tacz_target_distance",
                0,
                Integer.MAX_VALUE,
                0);
        forge(
                out,
                "tacz",
                r,
                "DAMAGE_COUNTER_RESET_TIME",
                "tacz_damage_reset",
                10,
                Integer.MAX_VALUE,
                0);
        forge(out, "tacz", r, "AUTO_SELECT_GUN_SMITH_TABLE_FILTER", "tacz_smith_filter");
        forge(out, "tacz", r, "DISABLE_MOVEMENT_ATTRIBUTE_FOV", "tacz_movement_fov");
        forge(out, "tacz", r, "BLOCK_ENTITY_TRANSLUCENT", "tacz_translucent");
        forge(
                out,
                "tacz",
                t + "SoundConfig",
                "FIRST_PERSON_ANIMATION_SOUND_TRACKING",
                "tacz_sound_tracking");
        String s = "com.atsuishio.superbwarfare.config.client.";
        forge(
                out,
                "superbwarfare",
                s + "ControlConfig",
                "MOUSE_SENSITIVITY",
                "sbw_mouse",
                10,
                200,
                1);
        forge(out, "superbwarfare", s + "ControlConfig", "INVERT_AIRCRAFT_CONTROL", "sbw_invert");
        forge(out, "superbwarfare", s + "ReloadConfig", "LEFT_CLICK_RELOAD", "sbw_left_reload");
        forge(
                out,
                "superbwarfare",
                s + "KillMessageConfig",
                "SHOW_KILL_MESSAGE",
                "sbw_kill_message");
        forge(
                out,
                "superbwarfare",
                s + "KillMessageConfig",
                "KILL_MESSAGE_POSITION",
                "sbw_kill_position");
        forge(
                out,
                "superbwarfare",
                s + "KillMessageConfig",
                "KILL_MESSAGE_COUNT",
                "sbw_kill_count",
                1,
                100,
                1);
        forge(
                out,
                "superbwarfare",
                s + "KillMessageConfig",
                "KILL_MESSAGE_MARGIN_X",
                "sbw_kill_x",
                -1000,
                1000,
                1);
        forge(
                out,
                "superbwarfare",
                s + "KillMessageConfig",
                "KILL_MESSAGE_MARGIN_Y",
                "sbw_kill_y",
                -1000,
                1000,
                1);
        String d = s + "DisplayConfig";
        forge(out, "superbwarfare", d, "FLOAT_CROSS_HAIR", "sbw_crosshair");
        forge(out, "superbwarfare", d, "AMMO_HUD", "sbw_ammo_hud");
        forge(out, "superbwarfare", d, "ADVANCED_AMMO_HUD", "sbw_advanced_ammo");
        forge(out, "superbwarfare", d, "VEHICLE_INFO", "sbw_vehicle_info");
        forge(out, "superbwarfare", d, "IFF_HUD", "sbw_iff");
        forge(out, "superbwarfare", d, "CAMERA_ROTATE", "sbw_camera_rotate");
        forge(out, "superbwarfare", d, "KILL_INDICATION", "sbw_kill_indicator");
        out.add(forge("superbwarfare", d, "ARMOR_PLATE_HUD", "sbw_armor", 0, 0, 0, true));
        Entry stamina = forge("superbwarfare", d, "STAMINA_HUD", "sbw_stamina", 0, 0, 0, true);
        out.add(
                new Entry(
                        stamina.modId(),
                        stamina.labelKey(),
                        0,
                        0,
                        0,
                        stamina.read(),
                        stamina.write(),
                        "blockfield.settings.replaced_hud"));
        forge(out, "superbwarfare", d, "DOG_TAG_NAME_VISIBLE", "sbw_dog_name");
        forge(out, "superbwarfare", d, "DOG_TAG_ICON_VISIBLE", "sbw_dog_icon");
        forge(out, "superbwarfare", d, "ENABLE_GUN_LOD", "sbw_lod");
        forge(
                out,
                "superbwarfare",
                d,
                "VEHICLE_LOD_DISTANCE",
                "sbw_vehicle_lod",
                -1,
                Integer.MAX_VALUE,
                0);
        forge(out, "superbwarfare", d, "WEAPON_HUD_X_OFFSET", "sbw_hud_x", -1000, 1000, 1);
        forge(out, "superbwarfare", d, "WEAPON_HUD_Y_OFFSET", "sbw_hud_y", -1000, 1000, 1);
        forge(out, "superbwarfare", d, "ENABLE_HEAT_BAR_HUD", "sbw_heat");
        forge(out, "superbwarfare", d, "HEAT_BAR_HUD_X_OFFSET", "sbw_heat_x", -1000, 1000, 1);
        forge(out, "superbwarfare", d, "HEAT_BAR_HUD_Y_OFFSET", "sbw_heat_y", -1000, 1000, 1);
        forge(out, "superbwarfare", d, "WEAPON_SCREEN_SHAKE", "sbw_weapon_shake", 0, 100, 1);
        forge(out, "superbwarfare", d, "EXPLOSION_SCREEN_SHAKE", "sbw_explosion_shake", 0, 100, 1);
        forge(out, "superbwarfare", d, "SHOCK_SCREEN_SHAKE", "sbw_shock_shake", 0, 100, 1);
        forge(out, "superbwarfare", d, "ENABLE_FIRE_FLASH_LIGHT", "sbw_flash");
        forge(out, "superbwarfare", d, "TACTICAL_MAP_ZOOM", "sbw_map_zoom", 0.05, 20, 0.05);
        String lr = "me.xjqsh.lrtactical.config.ClientConfig";
        forge(out, "lrtactical", lr, "BLACK_FLASH", "lr_black_flash");
        forge(
                out,
                "lrtactical",
                lr,
                "EXPLODE_SCREEN_SHAKE_MULTIPLIER",
                "lr_explosion_shake",
                0,
                128,
                0.05);
        out.add(ping("pingVolume", "getPingVolume", "ping_volume", 0, 100, 1));
        out.add(ping("pingSize", "getPingSize", "ping_size", 40, 300, 10));
        out.add(ping("pingDuration", "getPingDuration", "ping_duration", 1, 60, 1));
        out.add(ping("pingDistance", "getPingDistance", "ping_distance", 0, 2048, 16));
        out.add(ping("correctionPeriod", "getCorrectionPeriod", "ping_correction", 0.1, 5, 0.1));
        out.add(
                ping(
                        "directionIndicatorVisible",
                        "isDirectionIndicatorVisible",
                        "ping_direction",
                        0,
                        0,
                        0));
        out.add(ping("itemIconVisible", "isItemIconVisible", "ping_item", 0, 0, 0));
        out.add(ping("playerInfoMode", "getPlayerInfoMode", "ping_player", 0, 0, 0));
        out.add(ping("teamColorMode", "getTeamColorMode", "ping_team", 0, 0, 0));
        out.add(voice("voiceChatVolume", "voice_volume", 0, 3, 0.01));
        out.add(voice("microphoneGain", "voice_mic_gain", -40, 24, 0.5));
        out.add(voice("microphoneActivationType", "voice_activation", 0, 0, 0));
        out.add(voice("voiceActivationThreshold", "voice_threshold", -127, 0, 1));
        out.add(voice("denoiser", "voice_denoiser", 0, 0, 0));
        out.add(voice("vad", "voice_detection", 0, 0, 0));
        out.add(voice("agc", "voice_auto_gain", 0, 0, 0));
        out.add(voice("muteOnJoin", "voice_mute_join", 0, 0, 0));
        out.add(voice("disabled", "voice_disabled", 0, 0, 0));
        out.add(voice("hideIcons", "voice_hide_icons", 0, 0, 0));
        out.add(voice("showNametagIcons", "voice_names", 0, 0, 0));
        out.add(voice("showHudIcons", "voice_hud", 0, 0, 0));
        out.add(voice("showGroupHud", "voice_group", 0, 0, 0));
        out.add(voice("showOwnGroupIcon", "voice_own_icon", 0, 0, 0));
        out.add(voice("audioType", "voice_audio_type", 0, 0, 0));
        out.add(voice("voiceDeactivationDelay", "voice_delay", 0, 100, 1));
        out.add(voice("pttDeactivationDelay", "voice_ptt_delay", 0, 100, 1));
        out.add(voice("hudIconScale", "voice_hud_scale", 0.01, 10, 0.01));
        out.add(voice("groupHudIconScale", "voice_group_scale", 0.01, 10, 0.01));
        out.add(voice("groupPlayerIconOrientation", "voice_group_orientation", 0, 0, 0));
        for (String name :
                List.of(
                        "enabled",
                        "enableInThirdPerson",
                        "contextTransitionSmoothing",
                        "turningRollAccumulation",
                        "turningRollIntensity",
                        "turningRollSmoothing",
                        "cameraSwayIntensity",
                        "cameraSwayFrequency",
                        "cameraSwayFadeInDelay",
                        "cameraSwayFadeInLength",
                        "cameraSwayFadeOutLength",
                        "screenShakesMaxIntensity",
                        "screenShakesMaxFrequency",
                        "explosionTrauma",
                        "thunderTrauma",
                        "handSwingTrauma")) {
            out.add(camera(name, "camera_" + name));
        }
        out.add(damage("enableBloodOverlay", "damage_blood"));
        out.add(damage("enableHealthBars", "damage_health"));
        return out;
    }

    private static void transaction(Path file, Action action) throws Exception {
        String original = Files.exists(file) ? Files.readString(file) : null;
        try {
            action.run();
        } catch (Exception | LinkageError e) {
            try {
                if (original == null) Files.deleteIfExists(file);
                else replace(file, original);
            } catch (IOException restoreFailure) {
                e.addSuppressed(restoreFailure);
            }
            throw e;
        }
    }

    private static void replace(Path file, String text) throws IOException {
        Path temp =
                Files.createTempFile(
                        file.toAbsolutePath().getParent(), file.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temp, text);
            try {
                Files.move(
                        temp,
                        file,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    static void saveJson(Path file, String key, Object value, Action nativeSave) throws Exception {
        JsonObject original =
                Files.exists(file)
                        ? JsonParser.parseString(Files.readString(file)).getAsJsonObject()
                        : new JsonObject();
        transaction(
                file,
                () -> {
                    nativeSave.run();
                    JsonObject saved =
                            JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                    if (saved.get(key) == null
                            || !(value instanceof Number number
                                    ? sameValue(number, saved.get(key).getAsNumber())
                                    : GSON.toJsonTree(value).equals(saved.get(key))))
                        throw new IOException("Native JSON save did not persist " + key);
                    mergeUnknown(original, saved);
                    replace(file, GSON.toJson(saved));
                });
    }

    static void mergeUnknown(JsonObject original, JsonObject saved) {
        for (var property : original.entrySet()) {
            var current = saved.get(property.getKey());
            if (current == null) saved.add(property.getKey(), property.getValue());
            else if (current.isJsonObject() && property.getValue().isJsonObject())
                mergeUnknown(property.getValue().getAsJsonObject(), current.getAsJsonObject());
        }
    }

    private static Object parseToml(String text) throws Exception {
        Object parser =
                Class.forName("com.electronwill.nightconfig.toml.TomlParser")
                        .getConstructor()
                        .newInstance();
        return parser.getClass().getMethod("parse", String.class).invoke(parser, text);
    }

    private static Class<?> tomlConfig() throws ClassNotFoundException {
        return Class.forName("com.electronwill.nightconfig.core.UnmodifiableConfig");
    }

    @SuppressWarnings("unchecked")
    private static void mergeToml(Object original, Object saved) throws Exception {
        Map<String, Object> before =
                (Map<String, Object>) tomlConfig().getMethod("valueMap").invoke(original);
        Map<String, Object> after =
                (Map<String, Object>) tomlConfig().getMethod("valueMap").invoke(saved);
        for (var property : before.entrySet()) {
            Object current = after.get(property.getKey());
            if (!after.containsKey(property.getKey()))
                after.put(property.getKey(), property.getValue());
            else if (tomlConfig().isInstance(current)
                    && tomlConfig().isInstance(property.getValue()))
                mergeToml(property.getValue(), current);
        }
    }

    static void saveToml(
            Path file, List<String> key, Object value, List<String> protectedKey, Action nativeSave)
            throws Exception {
        Object original = parseToml(Files.exists(file) ? Files.readString(file) : "");
        transaction(
                file,
                () -> {
                    nativeSave.run();
                    Object saved = parseToml(Files.readString(file));
                    Object persisted = tomlConfig().getMethod("get", List.class).invoke(saved, key);
                    Object expected = value instanceof Enum<?> option ? option.name() : value;
                    if (!sameValue(expected, persisted))
                        throw new IOException("Native TOML save did not persist " + key);
                    mergeToml(original, saved);
                    // The live pack override must never become the player's stored armor-HUD
                    // preference.
                    if (protectedKey != null) {
                        Class<?> mutable =
                                Class.forName("com.electronwill.nightconfig.core.Config");
                        if ((Boolean)
                                tomlConfig()
                                        .getMethod("contains", List.class)
                                        .invoke(original, protectedKey)) {
                            Object before =
                                    tomlConfig()
                                            .getMethod("get", List.class)
                                            .invoke(original, protectedKey);
                            mutable.getMethod("set", List.class, Object.class)
                                    .invoke(saved, protectedKey, before);
                        } else mutable.getMethod("remove", List.class).invoke(saved, protectedKey);
                    }
                    Object writer =
                            Class.forName("com.electronwill.nightconfig.toml.TomlWriter")
                                    .getConstructor()
                                    .newInstance();
                    String text =
                            (String)
                                    writer.getClass()
                                            .getMethod("writeToString", tomlConfig())
                                            .invoke(writer, saved);
                    replace(file, text);
                });
    }

    private static boolean sameValue(Object expected, Object actual) {
        if (expected instanceof Number && actual instanceof Number)
            return new BigDecimal(expected.toString()).compareTo(new BigDecimal(actual.toString()))
                    == 0;
        return Objects.equals(expected, actual);
    }

    static void seedVoiceUnknowns(Object entry, Path file) throws Exception {
        if (!Files.exists(file)) return;
        Object cfg = invoke(entry, "getConfig");
        Map<?, ?> existing = (Map<?, ?>) invoke(cfg, "getEntries");
        java.util.Properties original = new java.util.Properties();
        try (var in = Files.newInputStream(file)) {
            original.load(in);
        }
        for (String key : original.stringPropertyNames()) {
            if (!existing.containsKey(key))
                cfg.getClass()
                        .getMethod("set", String.class, String.class, String[].class)
                        .invoke(cfg, key, original.getProperty(key), new String[0]);
        }
    }

    static void saveProperties(Path file, String key, Object value, Action nativeSave)
            throws Exception {
        transaction(
                file,
                () -> {
                    nativeSave.run();
                    java.util.Properties properties = new java.util.Properties();
                    try (var in = Files.newInputStream(file)) {
                        properties.load(in);
                    }
                    String expected =
                            value instanceof Enum<?> option ? option.name() : value.toString();
                    if (!expected.equals(properties.getProperty(key)))
                        throw new IOException("Native properties save did not persist " + key);
                });
    }
}
