package com.lunares.blockfield.client.music;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.CompletableFuture;
import net.minecraft.Util;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

/**
 * Один трек саундтрека: поток в категории MUSIC (его громкость -- ползунок «Музыка» в настройках),
 * с плавным изменением громкости. Громкость меняется в tick(), который SoundEngine зовёт каждый
 * тик.
 */
final class MusicSound extends AbstractTickableSoundInstance {
    final String track;
    final long startedAt;

    /** С какой секунды трека начали (продолжение прерванного), 0 -- с начала. */
    final double offset;

    private float gain;
    private float target;
    private float step = 1f;
    private float duck = 1f;
    private boolean fadingOut;

    MusicSound(String track, long now, double offset) {
        super(
                SoundEvent.createVariableRangeEvent(
                        ResourceLocation.fromNamespaceAndPath("blockfield", "music." + track)),
                SoundSource.MUSIC,
                SoundInstance.createUnseededRandom());
        this.track = track;
        this.startedAt = now;
        this.offset = offset;
        this.looping = false;
        this.delay = 0;
        this.relative = true;
        this.attenuation = SoundInstance.Attenuation.NONE;
        this.volume = 0f;
    }

    /** Текущая секунда трека. */
    double position(long now) {
        return offset + (now - startedAt) / 1000.0;
    }

    void fadeTo(float level, double seconds) {
        target = level;
        step =
                seconds <= 0
                        ? 1f
                        : (float) (Math.max(Math.abs(level - gain), 0.05f) / (seconds * 20));
    }

    void fadeOut(double seconds) {
        fadingOut = true;
        fadeTo(0f, seconds);
    }

    boolean fadingOut() {
        return fadingOut;
    }

    void duck(float duck) {
        this.duck = duck;
    }

    float gain() {
        return gain;
    }

    // Трек начинается с нуля и наплывает: без этого SoundEngine не запускает звук с нулевой
    // громкостью.
    @Override
    public boolean canStartSilent() {
        return true;
    }

    @Override
    public CompletableFuture<AudioStream> getAudioStream(
            SoundBufferLibrary loader, ResourceLocation id, boolean repeatInstantly) {
        CompletableFuture<AudioStream> stream = loader.getStream(id, repeatInstantly);
        if (offset <= 0) return stream;
        return stream.thenApplyAsync(
                in -> {
                    try {
                        return new SkippedStream(in, offset);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                },
                Util.backgroundExecutor());
    }

    @Override
    public void tick() {
        gain = gain < target ? Math.min(target, gain + step) : Math.max(target, gain - step);
        volume = gain * duck;
        if (fadingOut && gain <= 0f) stop();
    }
}
