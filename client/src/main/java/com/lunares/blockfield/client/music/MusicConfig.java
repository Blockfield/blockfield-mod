package com.lunares.blockfield.client.music;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Конфигурация саундтрека -- {@code assets/blockfield/music.json} из ресурсного jar'а
 * blockfield-music (исходник -- {@code music/music.json} в репозитории пака, {@code length} треков
 * дописывает сборка). Новые треки и тюнинг меняются там без изменения кода. Недостающие поля берут
 * значения по умолчанию отсюда.
 */
public final class MusicConfig {
    private static final Gson GSON = new Gson();
    static final StateConfig SILENT = new StateConfig();

    public Map<String, Track> tracks = new LinkedHashMap<>();
    public Map<String, StateConfig> states = new LinkedHashMap<>();
    public Map<String, Stinger> stingers = new LinkedHashMap<>();
    public Combat combat = new Combat();
    public Pressure pressure = new Pressure();
    public Ducking ducking = new Ducking();
    public Rotation rotation = new Rotation();

    public static final class Track {
        /**
         * Длительность готового файла в секундах; 0 -- неизвестна, конец трека узнаём по остановке
         * звука.
         */
        public double length;
    }

    public static final class StateConfig {
        public List<String> tracks = List.of();

        /** Замена {@link #tracks} в режимах без точек (Каждый сам за себя, Командный бой). */
        public List<String> tracksWithoutObjectives = List.of();

        /** Первый трек состояния за сессию (например, главная тема на первом запуске меню). */
        public String first;

        public float volume = 1f;

        /**
         * Тишина между треками, секунды [от, до]. 0 -- следующий трек перекрёстно наплывает на
         * конец текущего.
         */
        public double[] gap = {0, 0};

        public double fadeIn = 2, fadeOut = 4, crossfade = 4;

        /**
         * Гистерезис боевых состояний: войти, если цель держится {@code enter} с; выйти -- {@code
         * exit} с и не раньше {@code hold} с.
         */
        public double enter, exit, hold;

        /** Для состояний-защёлок (высадка, победа, итог): сколько секунд они держатся. */
        public double max;

        /** Один трек за вход в состояние, без повторов. */
        public boolean once;

        /** Начинать с паузы {@link #gap}, а не сразу (фоновые состояния). */
        public boolean delayFirst;

        public List<String> tracks(boolean objectives) {
            return objectives || tracksWithoutObjectives.isEmpty()
                    ? tracks
                    : tracksWithoutObjectives;
        }
    }

    public static final class Stinger {
        public String track;
        public float volume = 1f;

        /** Во сколько раз приглушается музыка, пока звучит стингер. */
        public float duck = 0.5f;

        public double cooldown = 8;
    }

    public static final class Combat {
        public double halfLife = 4, tactical = 1.5, combat = 6;
        public double shot = 0.6, shotCap = 1.5, explosion = 3, hurt = 4, kill = 3;

        /** Полный вес событий ближе {@code near} блоков, линейно до нуля к {@code far}. */
        public double near = 24, far = 80;

        public double enemyRadius = 40, objectiveRadius = 45;
    }

    public static final class Pressure {
        public double criticalShare = 0.25,
                criticalDeficit = 0.15,
                finalShare = 0.10,
                finalGap = 0.20;
        public int finalSeconds = 60, closeFrags = 3, criticalFrags = 5;
    }

    public static final class Ducking {
        public float dead = 0.35f, voice = 0.6f, explosion = 0.55f;
        public double explosionRadius = 20, explosionHold = 1.5;

        /** Скорость приглушения и возврата громкости, секунды. */
        public double attack = 0.15, release = 1.5;
    }

    /** Против приедания: продолжение прерванных треков и отдых от непрерывной музыки. */
    public static final class Rotation {
        /**
         * Вернулись в состояние за столько секунд -- прерванный трек доигрывается с того же места;
         * 0 -- всегда с начала.
         */
        public double resumeWindow = 45;

        /** Не продолжать трек, если до конца осталось меньше стольких секунд. */
        public double resumeMinLeft = 30;

        /**
         * После стольких секунд музыки без паузы боевые состояния плавно стихают до {@code
         * restVolume}, а ближайшая пауза между треками -- не короче {@code restGap}; 0 --
         * выключено.
         */
        public double restAfter = 360;

        public float restVolume = 0.75f;
        public double[] restGap = {45, 90};

        /** Тишина такой длины считается отдыхом и обнуляет счёт. */
        public double restReset = 20;

        /** За сколько секунд громкость уходит к {@code restVolume} и обратно. */
        public double restFade = 10;
    }

    public static MusicConfig parse(Reader reader) {
        MusicConfig config = GSON.fromJson(reader, MusicConfig.class);
        if (config == null) throw new JsonParseException("empty music.json");
        return config;
    }

    public StateConfig state(MusicState state) {
        // Until the music jar ships its own workshop set, the workshop plays the hub's calm tracks.
        if (state == MusicState.WORKSHOP && !states.containsKey(state.id()))
            return states.getOrDefault(MusicState.LOBBY.id(), SILENT);
        return states.getOrDefault(state.id(), SILENT);
    }

    public double length(String track) {
        Track t = tracks.get(track);
        return t == null ? 0 : t.length;
    }

    /** Убирает ссылки на несуществующие треки и состояния; возвращает список проблем для лога. */
    public List<String> validate() {
        List<String> problems = new ArrayList<>();
        states.keySet()
                .removeIf(
                        id -> {
                            boolean unknown = MusicState.byId(id) == null;
                            if (unknown) problems.add("неизвестное состояние " + id);
                            return unknown;
                        });
        for (var entry : states.entrySet()) {
            StateConfig state = entry.getValue();
            state.tracks = known(state.tracks, entry.getKey(), problems);
            state.tracksWithoutObjectives =
                    known(state.tracksWithoutObjectives, entry.getKey(), problems);
            if (state.first != null && !tracks.containsKey(state.first)) {
                problems.add(entry.getKey() + ": нет трека " + state.first);
                state.first = null;
            }
            if (state.gap == null || state.gap.length != 2) state.gap = new double[] {0, 0};
        }
        if (rotation == null) rotation = new Rotation();
        if (rotation.restGap == null || rotation.restGap.length != 2)
            rotation.restGap = new double[] {45, 90};
        stingers.values()
                .removeIf(
                        s -> {
                            boolean missing = s.track == null || !tracks.containsKey(s.track);
                            if (missing) problems.add("стингер без трека " + s.track);
                            return missing;
                        });
        return problems;
    }

    private List<String> known(List<String> ids, String state, List<String> problems) {
        if (ids == null) return List.of();
        List<String> kept = new ArrayList<>();
        for (String id : ids) {
            if (tracks.containsKey(id)) kept.add(id);
            else problems.add(state + ": нет трека " + id);
        }
        return List.copyOf(kept);
    }
}
