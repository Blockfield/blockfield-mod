package com.lunares.blockfield.client.voice;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Состояние голосового оверлея: громкость своего микрофона и кто сейчас говорит. Заполняется из
 * потока Simple Voice Chat, читается в кадре — отсюда ConcurrentHashMap и явное время в параметрах
 * (не System.currentTimeMillis внутри): так поведение проверяется юнит-тестом без игры. Никаких
 * классов Minecraft: имя и цвет команды говорящего оверлей разрешает сам по UUID.
 */
public final class VoiceHudState {

    /** Сколько строка говорящего висит после последнего пакета голоса. */
    public static final long SPEAKER_TIMEOUT_MS = 400L;

    /** Тишина дольше этого — свой микрофон считается замолчавшим. */
    private static final long MIC_TIMEOUT_MS = 300L;

    private static final Map<UUID, Speaker> SPEAKERS = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> RADIO = new ConcurrentHashMap<>();

    private static volatile double micPeak;
    private static volatile long micAt;
    private static volatile double micLevel;

    private VoiceHudState() {}

    /** Говорящий: уровень 0..1, radio -- голос идёт по рации, а не «рядом». */
    public static final class Speaker {
        public final UUID id;
        volatile double level;
        volatile boolean radio;
        volatile long at;

        Speaker(UUID id, double level, boolean radio, long at) {
            this.id = id;
            this.level = level;
            this.radio = radio;
            this.at = at;
        }

        public double level() {
            return level;
        }

        public boolean radio() {
            return radio;
        }
    }

    public static void setRadio(UUID id, boolean active) {
        if (id == null) return;
        if (active) RADIO.put(id, true);
        else RADIO.remove(id);
    }

    public static boolean radio(UUID id) {
        return id != null && RADIO.containsKey(id);
    }

    /** Пакет своего микрофона: level -- нормированный RMS. */
    public static void mic(double level, long now) {
        micPeak = clamp(level);
        micAt = now;
    }

    /** Пакет голоса другого игрока. */
    public static void speaking(UUID id, double level, boolean radio, long now) {
        if (id == null) return;
        SPEAKERS.compute(
                id,
                (key, existing) -> {
                    if (existing == null) return new Speaker(key, clamp(level), radio, now);
                    // Пики не гасим мгновенно: полоска дёргалась бы на каждом пакете.
                    existing.level = Math.max(existing.level * 0.4, clamp(level));
                    existing.radio = radio;
                    existing.at = now;
                    return existing;
                });
    }

    /** Сглаживание своего VU-метра и снятие замолчавших; вызывается каждый кадр. */
    public static void tick(long now) {
        micLevel =
                now - micAt < 150
                        ? micLevel * 0.6 + micPeak * 0.4
                        : Math.max(0.0, micLevel * 0.75 - 0.02);
        SPEAKERS.values().removeIf(speaker -> now - speaker.at > SPEAKER_TIMEOUT_MS);
    }

    /** Кто-то из других игроков говорит прямо сейчас (музыка приглушается под голос). */
    public static boolean anyoneSpeaking(long now) {
        for (Speaker speaker : SPEAKERS.values())
            if (now - speaker.at <= SPEAKER_TIMEOUT_MS) return true;
        return false;
    }

    public static double micLevel() {
        return micLevel;
    }

    /** Свой микрофон в эфире: рация зажата или недавно был звук. */
    public static boolean selfActive(boolean radioHeld, long now) {
        return radioHeld || (now - micAt < MIC_TIMEOUT_MS && micLevel > 0.05);
    }

    /** Говорящие в порядке начала фразы: строки не прыгают местами, пока человек говорит. */
    public static List<Speaker> speakers() {
        List<Speaker> list = new ArrayList<>(SPEAKERS.values());
        list.sort(Comparator.comparing(speaker -> speaker.id));
        return list;
    }

    /** Смена мира/выход: чужие UUID больше не наши. */
    public static void clear() {
        SPEAKERS.clear();
        RADIO.clear();
        micPeak = 0;
        micLevel = 0;
        micAt = 0;
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
