package com.lunares.blockfield.client;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Дефолты настроек пака. Пак больше не содержит options.txt (его перезапись стирала бинды игроков):
 * недостающие ключи один раз проставляет клиент. Ключ, который игрок уже менял (строка в
 * options.txt существует), не трогается. Перезапись биндов лечится сама: KeyDefaults подменяет
 * дефолт KeyMapping, и после вайпа бинды возвращаются к нашим значениям.
 */
public final class OptionsDefaults {
    private static final Logger LOGGER = LoggerFactory.getLogger(OptionsDefaults.class);
    private static final double VANILLA_FOV = 70.0D;
    private static final int PACK_FOV = 90;

    private OptionsDefaults() {}

    private record Default(String key, Consumer<Minecraft> apply) {}

    private static final List<Default> DEFAULTS =
            List.of(
                    new Default("renderDistance", mc -> mc.options.renderDistance().set(20)),
                    new Default(
                            "simulationDistance", mc -> mc.options.simulationDistance().set(20)),
                    new Default("maxFps", mc -> mc.options.framerateLimit().set(120)),
                    new Default("enableVsync", mc -> mc.options.enableVsync().set(false)),
                    new Default("fullscreen", mc -> mc.options.fullscreen().set(true)),
                    new Default(
                            "soundCategory_music",
                            mc ->
                                    mc.options
                                            .getSoundSourceOptionInstance(SoundSource.MUSIC)
                                            .set(0.25D)),
                    new Default("directionalAudio", mc -> mc.options.directionalAudio().set(true)),
                    new Default(
                            "graphicsMode",
                            mc ->
                                    mc.options
                                            .graphicsMode()
                                            .set(net.minecraft.client.GraphicsStatus.FANCY)),
                    new Default(
                            "particles",
                            mc ->
                                    mc.options
                                            .particles()
                                            .set(net.minecraft.client.ParticleStatus.ALL)),
                    new Default("entityShadows", mc -> mc.options.entityShadows().set(true)),
                    new Default("biomeBlendRadius", mc -> mc.options.biomeBlendRadius().set(2)),
                    new Default(
                            "entityDistanceScaling",
                            mc -> mc.options.entityDistanceScaling().set(1.0D)),
                    new Default(
                            "tutorialStep",
                            mc ->
                                    mc.options.tutorialStep =
                                            net.minecraft.client.tutorial.TutorialSteps.NONE),
                    new Default("lang", mc -> mc.options.languageCode = "ru_ru"));

    public static void init() {
        ClientLifecycleEvents.CLIENT_STARTED.register(OptionsDefaults::migrate);
        ClientLifecycleEvents.CLIENT_STARTED.register(mc -> forceSuperbwarfareHud());
        ClientLifecycleEvents.CLIENT_STARTED.register(mc -> forceDamageIndicators());
    }

    /**
     * Полоска прочности бронежилета Superb Warfare включается на каждом запуске: конфиг у игроков
     * preserve, и дефолт пака их не догоняет. Рефлексия: мод в компиляцию не входит.
     */
    private static void forceSuperbwarfareHud() {
        try {
            Object value =
                    Class.forName("com.atsuishio.superbwarfare.config.client.DisplayConfig")
                            .getField("ARMOR_PLATE_HUD")
                            .get(null);
            value.getClass().getMethod("set", Object.class).invoke(value, Boolean.TRUE);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("Не включить armor_plate_hud Superb Warfare", e);
        }
    }

    /**
     * Fabric reads jeremyseqsdamageindicators.json, not the legacy TOML. Existing players keep that
     * file, so apply combat feedback at startup while preserving other settings.
     */
    private static void forceDamageIndicators() {
        try {
            Class<?> config =
                    Class.forName(
                            "com.jeremyseq.jeremyseqsdamageindicators.DamageIndicatorsConfig");
            Object instance = config.getField("INSTANCE").get(null);
            config.getField("enableHealthBars").setBoolean(instance, false);
            config.getField("enableBloodOverlay").setBoolean(instance, true);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("Не применить combat HUD JeremySeq's Damage Indicators", e);
        }
    }

    private static void migrate(Minecraft minecraft) {
        Path marker =
                minecraft
                        .gameDirectory
                        .toPath()
                        .resolve("config/blockfield-options-migration.properties");
        if (Files.exists(marker)) return;

        Path optionsFile = minecraft.gameDirectory.toPath().resolve("options.txt");
        List<String> lines = List.of();
        try {
            if (Files.exists(optionsFile)) lines = Files.readAllLines(optionsFile);
        } catch (IOException e) {
            LOGGER.warn("Не прочитать options.txt; сохраняем настройки игрока без изменений", e);
            return;
        }

        boolean changed = false;
        for (Default def : DEFAULTS) {
            String prefix = def.key() + ":";
            if (lines.stream().anyMatch(l -> l.startsWith(prefix))) continue;
            def.apply().accept(minecraft);
            changed = true;
        }
        // FOV: ваниль 70 -> 90, если игрок не трогал (ползунок хранится в слайдер-шкале, 0.5 = 90).
        if (lines.stream().noneMatch(l -> l.startsWith("fov:"))
                && minecraft.options.fov().get() == VANILLA_FOV) {
            minecraft.options.fov().set(PACK_FOV);
            changed = true;
        }

        try {
            if (changed) minecraft.options.save();
            Properties empty = new Properties();
            Path parent = marker.getParent();
            if (parent != null) parent.toFile().mkdirs();
            try (OutputStream out = Files.newOutputStream(marker)) {
                empty.store(out, "Blockfield one-time options migrations");
            }
        } catch (IOException e) {
            LOGGER.warn("Не записать маркер миграций настроек", e);
        }
    }
}
