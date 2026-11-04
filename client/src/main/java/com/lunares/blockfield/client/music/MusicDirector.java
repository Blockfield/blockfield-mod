package com.lunares.blockfield.client.music;

import com.google.gson.JsonObject;
import com.lunares.blockfield.client.ClientConfig;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.sounds.SoundSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Адаптивный саундтрек Blockfield: одна точка, которая решает, что, когда и как звучит. Сигналы --
 * {@link MusicSignals}, решение -- {@link MusicBrain}, воспроизведение -- {@link MusicPlayer}. Пока
 * ресурсный jar blockfield-music не загружен (нет music.json), ничего не делает и ванильная музыка
 * работает как раньше. С ним ванильный MusicManager молчит ({@code MusicManagerMixin}). Громкость у
 * игрока -- ванильный ползунок «Музыка»; документация -- docs/MUSIC.md в репозитории пака.
 */
public final class MusicDirector {
    private static final Logger LOGGER = LoggerFactory.getLogger("BlockField-Music");
    private static final ResourceLocation CONFIG =
            ResourceLocation.fromNamespaceAndPath("blockfield", "music.json");

    private static final boolean DEBUG =
            FabricLoader.getInstance().isDevelopmentEnvironment()
                    || ClientConfig.USER.getBoolean("music.debug", false);
    private static final MusicSignals SIGNALS = new MusicSignals();
    private static final MusicBrain BRAIN = new MusicBrain();
    private static final MusicPlayer PLAYER = new MusicPlayer();
    private static MusicConfig config;
    private static MusicContext last;
    private static boolean overlay;

    private MusicDirector() {}

    public static void init() {
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES)
                .registerReloadListener(
                        new SimpleSynchronousResourceReloadListener() {
                            @Override
                            public ResourceLocation getFabricId() {
                                return ResourceLocation.fromNamespaceAndPath("blockfield", "music");
                            }

                            @Override
                            public void onResourceManagerReload(ResourceManager manager) {
                                load(manager);
                            }
                        });
        ClientTickEvents.END_CLIENT_TICK.register(MusicDirector::tick);
        ClientEntityEvents.ENTITY_LOAD.register(
                (entity, level) -> {
                    if (config != null)
                        SIGNALS.entity(entity, Minecraft.getInstance(), Util.getMillis());
                });
        ClientLifecycleEvents.CLIENT_STARTED.register(
                mc ->
                        mc.getSoundManager()
                                .addListener(
                                        (sound, events, range) -> {
                                            if (config != null)
                                                SIGNALS.sound(sound, mc, Util.getMillis());
                                        }));
        HudRenderCallback.EVENT.register(
                (graphics, delta) -> {
                    if (!overlay) return;
                    Minecraft mc = Minecraft.getInstance();
                    List<String> lines = debugLines();
                    int width = lines.stream().mapToInt(mc.font::width).max().orElse(0);
                    // Ниже миникарты и её строки координат, на подложке: поверх неба текст не
                    // читается.
                    int y = 100;
                    graphics.fill(2, y - 2, width + 6, y + lines.size() * 10, 0xA0000000);
                    for (String line : lines) {
                        graphics.drawString(mc.font, line, 4, y, 0xFFFFFF, true);
                        y += 10;
                    }
                });
        // Ручное управление музыкой -- инструмент настройки, игрокам не показывается без флага.
        if (DEBUG) {
            ClientCommandRegistrationCallback.EVENT.register(
                    (dispatcher, registry) ->
                            dispatcher.register(
                                    ClientCommandManager.literal("music")
                                            .then(
                                                    ClientCommandManager.literal("debug")
                                                            .executes(
                                                                    ctx -> {
                                                                        overlay = !overlay;
                                                                        return 1;
                                                                    }))
                                            .then(
                                                    ClientCommandManager.literal("auto")
                                                            .executes(
                                                                    ctx -> {
                                                                        BRAIN.force(null);
                                                                        return 1;
                                                                    }))
                                            .then(
                                                    ClientCommandManager.literal("next")
                                                            .executes(
                                                                    ctx -> {
                                                                        PLAYER.skip(
                                                                                Util.getMillis());
                                                                        return 1;
                                                                    }))
                                            .then(
                                                    ClientCommandManager.literal("state")
                                                            .then(
                                                                    ClientCommandManager.argument(
                                                                                    "state",
                                                                                    StringArgumentType
                                                                                            .word())
                                                                            .executes(
                                                                                    ctx -> {
                                                                                        MusicState
                                                                                                state =
                                                                                                        MusicState
                                                                                                                .byId(
                                                                                                                        StringArgumentType
                                                                                                                                .getString(
                                                                                                                                        ctx,
                                                                                                                                        "state"));
                                                                                        if (state
                                                                                                == null)
                                                                                            return 0;
                                                                                        BRAIN.force(
                                                                                                state);
                                                                                        return 1;
                                                                                    })))
                                            .then(
                                                    ClientCommandManager.literal("sting")
                                                            .then(
                                                                    ClientCommandManager.argument(
                                                                                    "id",
                                                                                    StringArgumentType
                                                                                            .word())
                                                                            .executes(
                                                                                    ctx -> {
                                                                                        PLAYER
                                                                                                .stinger(
                                                                                                        Minecraft
                                                                                                                .getInstance(),
                                                                                                        StringArgumentType
                                                                                                                .getString(
                                                                                                                        ctx,
                                                                                                                        "id"),
                                                                                                        Util
                                                                                                                .getMillis());
                                                                                        return 1;
                                                                                    })))
                                            .executes(
                                                    ctx -> {
                                                        // Системным сообщением, а не sendFeedback:
                                                        // так ответ видят и бот стенда, и лог
                                                        // клиента.
                                                        debugLines()
                                                                .forEach(
                                                                        line -> {
                                                                            LOGGER.info(
                                                                                    "music: {}",
                                                                                    line);
                                                                            Minecraft.getInstance()
                                                                                    .getChatListener()
                                                                                    .handleSystemMessage(
                                                                                            Component
                                                                                                    .literal(
                                                                                                            line),
                                                                                            false);
                                                                        });
                                                        return 1;
                                                    })));
        }
    }

    public static boolean active() {
        return config != null;
    }

    /**
     * Смена измерения стирает все звуки; музыку оставляем, иначе трек рвался бы на каждой смерти и
     * высадке.
     */
    public static boolean keepMusicAcrossLevels() {
        return config != null && PLAYER.playing();
    }

    public static void stopAllButMusic(SoundManager sounds) {
        for (SoundSource source : SoundSource.values()) {
            if (source != SoundSource.MUSIC) sounds.stop(null, source);
        }
    }

    public static void onPresence(JsonObject presence) {
        SIGNALS.presence(presence, Util.getMillis());
    }

    public static void onMatchResult(String result) {
        BRAIN.matchResult(result, Util.getMillis());
    }

    public static void onKills(int kills) {
        SIGNALS.kills(kills, Util.getMillis());
    }

    private static void load(ResourceManager manager) {
        var resource = manager.getResource(CONFIG);
        if (resource.isEmpty()) {
            config = null;
            return;
        }
        try (Reader reader = resource.get().openAsReader()) {
            MusicConfig loaded = MusicConfig.parse(reader);
            loaded.validate().forEach(problem -> LOGGER.warn("music.json: {}", problem));
            config = loaded;
            SIGNALS.config(loaded);
            PLAYER.config(loaded);
            LOGGER.info(
                    "Саундтрек: {} треков, {} состояний",
                    loaded.tracks.size(),
                    loaded.states.size());
        } catch (IOException | RuntimeException e) {
            LOGGER.error("Не прочитать music.json, адаптивная музыка выключена", e);
            config = null;
        }
    }

    private static void tick(Minecraft mc) {
        if (config == null) return;
        long now = Util.getMillis();
        last =
                SIGNALS.context(
                        mc,
                        now,
                        id -> {
                            if (DEBUG) LOGGER.info("music: стингер {}", id);
                            PLAYER.stinger(mc, id, now);
                        });
        MusicState before = BRAIN.state();
        MusicState state = BRAIN.update(last, config, now);
        if (DEBUG && state != before)
            LOGGER.info("music: {} -> {} ({})", before.id(), state.id(), BRAIN.reason());
        PLAYER.apply(state, last.objectives(), now);
        String track = PLAYER.track();
        PLAYER.tick(mc, SIGNALS.duck(mc, state, now), now);
        if (DEBUG && PLAYER.track() != null && !PLAYER.track().equals(track))
            LOGGER.info("music: трек {}", PLAYER.debug(now));
    }

    private static List<String> debugLines() {
        long now = Util.getMillis();
        List<String> lines = new ArrayList<>();
        if (config == null) {
            lines.add("§eМузыка: music.json не загружен (нет blockfield-music)");
            return lines;
        }
        lines.add(
                "§eМузыка: §f"
                        + BRAIN.state().id()
                        + (BRAIN.forced() ? " §c(принудительно)" : "")
                        + String.format(Locale.ROOT, " §7%.0f с", BRAIN.stateSeconds(now))
                        + " §7→ цель §f"
                        + BRAIN.target().id()
                        + " §7"
                        + BRAIN.timer(now));
        lines.add("§7причина: §f" + BRAIN.reason());
        lines.add("§7трек: §f" + PLAYER.debug(now));
        lines.addAll(SIGNALS.debug(now));
        if (last != null) {
            MusicConfig.Combat c = config.combat;
            lines.add(
                    String.format(
                            Locale.ROOT,
                            "§7бой §f%.1f §7(тактика ≥ %.1f, бой ≥ %.1f) · враги %d · жив %b · техника %b",
                            last.combat(),
                            c.tactical,
                            c.combat,
                            last.enemiesNear(),
                            last.alive(),
                            last.inVehicle()));
            lines.add(
                    "§7точка: спорная "
                            + last.contested()
                            + ", рядом чужая "
                            + last.nearObjective()
                            + " · точки в режиме "
                            + last.objectives());
            lines.add(
                    "§7матч: "
                            + last.pressure().state().id()
                            + " ("
                            + last.pressure().reason()
                            + ") · "
                            + SIGNALS.score()
                            + (BRAIN.ending() == null ? "" : " · итог " + BRAIN.ending().id()));
        }
        return lines;
    }
}
