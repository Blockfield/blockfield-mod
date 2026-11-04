package com.lunares.blockfield.client.music;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;

/**
 * Исполнитель решений {@link MusicBrain}: запускает треки, делает наплывы, паузы тишины и
 * приглушение. Стык двух состояний -- перекрёстное затухание целых треков (стемов нет, темп и такты
 * у сгенерированных треков не согласованы, см. docs/MUSIC.md в паке). Состояния с общим набором
 * треков (фон и тактика) не прерывают трек, а только меняют громкость. Против приедания (настройки
 * -- {@link MusicConfig.Rotation}): прерванный трек при быстром возврате доигрывается с того же
 * места, долгая непрерывная музыка стихает и получает паузу подлиннее, круги мешков переживают
 * перезапуск.
 */
final class MusicPlayer {
    private final Random random = new Random();
    private final Map<String, ShuffleBag> bags = new HashMap<>();
    private final Set<MusicState> firstPlayed = EnumSet.noneOf(MusicState.class);
    private final List<MusicSound> fading = new ArrayList<>();
    private final Map<String, Long> stingerReady = new HashMap<>();
    private final ResumePoints resume = new ResumePoints();
    private final MusicHistory history =
            new MusicHistory(
                    FabricLoader.getInstance()
                            .getConfigDir()
                            .resolve("blockfield-music-history.json"));
    private MusicConfig config = new MusicConfig();
    private MusicSound current;
    private MusicState state = MusicState.SILENT;
    private boolean objectives = true;
    private long nextAt = -1;
    private String previous = "";
    private String last = "";
    private long stingerUntil;
    private float stingerDuck = 1f;
    private float duck = 1f;
    private float rest = 1f;
    private long musicSince = -1;
    private long quietSince = -1;

    void config(MusicConfig config) {
        this.config = config;
        bags.clear();
        resume.clear();
    }

    void apply(MusicState next, boolean objectives, long now) {
        if (next == state && objectives == this.objectives) return;
        MusicState old = state;
        MusicConfig.StateConfig from = config.state(old);
        MusicConfig.StateConfig to = config.state(next);
        state = next;
        this.objectives = objectives;
        List<String> pool = to.tracks(objectives);
        if (current != null && !current.fadingOut() && !to.once && pool.contains(current.track)) {
            current.fadeTo(to.volume, to.fadeIn);
            return;
        }
        boolean down = old.gameplay() && next.gameplay() && next.compareTo(old) < 0;
        if (current != null) {
            if (!from.once) resume.interrupted(current.track, current.position(now), now);
            // Подъём -- быстрый перехлёст, спуск -- медленное затухание в тишину.
            current.fadeOut(down ? from.fadeOut : Math.min(from.fadeOut, 1.5));
            fading.add(current);
            current = null;
        }
        nextAt = pool.isEmpty() ? -1 : down || to.delayFirst ? now + gap(to, now) : now;
    }

    void tick(Minecraft mc, float duckTarget, long now) {
        SoundManager sounds = mc.getSoundManager();
        float wanted = Math.min(duckTarget, now < stingerUntil ? stingerDuck : 1f);
        double seconds = wanted < duck ? config.ducking.attack : config.ducking.release;
        float step = seconds <= 0 ? 1f : (float) (1.0 / (seconds * 20));
        duck = wanted < duck ? Math.max(wanted, duck - step) : Math.min(wanted, duck + step);
        MusicConfig.Rotation rotation = config.rotation;
        // Считается только музыка боя: меню и хаб между матчами -- уже передышка.
        if (playing() && state.gameplay()) {
            if (musicSince < 0) musicSince = now;
            quietSince = -1;
        } else {
            if (quietSince < 0) quietSince = now;
            if (now - quietSince >= ms(rotation.restReset)) musicSince = -1;
        }
        float restTarget = tired(now) && state.gameplay() ? rotation.restVolume : 1f;
        float restStep = rotation.restFade <= 0 ? 1f : (float) (1.0 / (rotation.restFade * 20));
        rest =
                restTarget < rest
                        ? Math.max(restTarget, rest - restStep)
                        : Math.min(restTarget, rest + restStep);

        MusicConfig.StateConfig cfg = config.state(state);
        if (!audible(mc)) {
            // На нулевой громкости SoundEngine глушит канал, но isActive() остаётся true навсегда:
            // трек не узнать
            // доигравшим. Отпускаем его сами и начинаем новый, как только игрок вернёт громкость.
            if (current != null || !fading.isEmpty()) {
                if (current != null) current.fadeOut(0);
                fading.forEach(sound -> sound.fadeOut(0));
                fading.clear();
                current = null;
                nextAt = cfg.once ? -1 : now;
            }
            return;
        }
        fading.removeIf(sound -> !sounds.isActive(sound));
        for (MusicSound sound : fading) sound.duck(duck * rest);

        if (current != null) {
            double length = config.length(current.track);
            boolean crossfade =
                    !cfg.once
                            && cfg.gap[1] <= 0
                            && length > 0
                            && current.position(now) >= length - cfg.crossfade;
            if (crossfade) {
                current.fadeOut(cfg.crossfade);
                fading.add(current);
                current = null;
                nextAt = now;
            } else if (now - current.startedAt > 1000 && !sounds.isActive(current)) {
                // Трек доиграл (или его остановила перезагрузка ресурсов).
                current = null;
                nextAt = cfg.once ? -1 : now + gap(cfg, now);
            } else {
                current.duck(duck * rest);
            }
        }
        if (current == null && nextAt >= 0 && now >= nextAt) {
            ResumePoints.Point resumed =
                    cfg.once
                            ? null
                            : resume.take(
                                    cfg.tracks(objectives),
                                    now,
                                    rotation.resumeWindow,
                                    rotation.resumeMinLeft,
                                    config::length);
            String track = resumed != null ? resumed.track() : pick(cfg);
            nextAt = -1;
            if (track != null) {
                current = new MusicSound(track, now, resumed != null ? resumed.position() : 0);
                current.duck(duck * rest);
                current.fadeTo(cfg.volume, cfg.fadeIn);
                sounds.play(current);
                previous = last;
                last = track;
            }
        }
    }

    private static boolean audible(Minecraft mc) {
        return mc.options.getSoundSourceVolume(SoundSource.MUSIC) > 0
                && mc.options.getSoundSourceVolume(SoundSource.MASTER) > 0;
    }

    void stinger(Minecraft mc, String id, long now) {
        MusicConfig.Stinger stinger = config.stingers.get(id);
        if (stinger == null || now < stingerReady.getOrDefault(id, 0L)) return;
        stingerReady.put(id, now + (long) (stinger.cooldown * 1000));
        double length = config.length(stinger.track);
        stingerUntil = now + (long) ((length > 0 ? length : 3) * 1000);
        stingerDuck = stinger.duck;
        mc.getSoundManager()
                .play(
                        new SimpleSoundInstance(
                                ResourceLocation.fromNamespaceAndPath(
                                        "blockfield", "music." + stinger.track),
                                SoundSource.MUSIC,
                                stinger.volume,
                                1f,
                                SoundInstance.createUnseededRandom(),
                                false,
                                0,
                                SoundInstance.Attenuation.NONE,
                                0,
                                0,
                                0,
                                true));
    }

    /** Отладка: сразу следующий трек текущего состояния. */
    void skip(long now) {
        if (current == null) {
            nextAt = now;
            return;
        }
        current.fadeOut(1.0);
        fading.add(current);
        current = null;
        nextAt = now;
    }

    String track() {
        return current == null ? null : current.track;
    }

    boolean playing() {
        return current != null || !fading.isEmpty();
    }

    /** Следующий трек: особый первый трек состояния за сессию, иначе мешок без повторов подряд. */
    private String pick(MusicConfig.StateConfig cfg) {
        List<String> pool = cfg.tracks(objectives);
        if (pool.isEmpty()) return null;
        // Мешок на набор треков, а не на состояние: фон и тактика делят треки и не должны повторять
        // друг друга.
        String key = String.join(",", pool);
        ShuffleBag bag =
                bags.computeIfAbsent(key, k -> history.restore(k, new ShuffleBag(pool, random)));
        String track;
        if (cfg.first != null && firstPlayed.add(state)) {
            bag.played(cfg.first);
            track = cfg.first;
        } else {
            track = bag.next();
        }
        history.save(key, bag);
        return track;
    }

    /** Музыка играет без отдыха дольше {@code restAfter}. */
    private boolean tired(long now) {
        double after = config.rotation.restAfter;
        return after > 0 && musicSince >= 0 && now - musicSince >= ms(after);
    }

    /** Пауза между треками; у состояний с паузами после долгой музыки -- не короче restGap. */
    private long gap(MusicConfig.StateConfig cfg, long now) {
        long gap = random(cfg.gap);
        return cfg.gap[1] > 0 && tired(now) ? Math.max(gap, random(config.rotation.restGap)) : gap;
    }

    private long random(double[] range) {
        double min = range[0], max = Math.max(range[0], range[1]);
        return (long) ((min + random.nextDouble() * (max - min)) * 1000);
    }

    private static long ms(double seconds) {
        return (long) (seconds * 1000);
    }

    String debug(long now) {
        String track =
                current == null
                        ? "—"
                        : String.format(
                                java.util.Locale.ROOT,
                                "%s %.0f/%.0f с, громк. %.2f",
                                current.track,
                                current.position(now),
                                config.length(current.track),
                                current.gain());
        String wait =
                current == null && nextAt >= 0
                        ? String.format(
                                java.util.Locale.ROOT,
                                ", следующий через %.0f с",
                                (nextAt - now) / 1000.0)
                        : "";
        return track
                + wait
                + " | до этого: "
                + (previous.isEmpty() ? "—" : previous)
                + " | затухают: "
                + fading.size()
                + String.format(java.util.Locale.ROOT, " | приглушение %.2f", duck)
                + String.format(java.util.Locale.ROOT, " | отдых %.2f", rest);
    }
}
