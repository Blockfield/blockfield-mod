package com.lunares.blockfield.client.voice;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.ClientReceiveSoundEvent;
import de.maxhenkel.voicechat.api.events.ClientSoundEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;

/**
 * Клиентский плагин Simple Voice Chat: превращает звуковые пакеты в уровни для голосового HUD.
 * Регистрируется через entrypoint "voicechat" в fabric.mod.json (аннотации у fabric-сборки SVC
 * нет).
 */
public class ClientSVCPlugin implements VoicechatPlugin {

    /** Обычная речь ~2000-8000 RMS, крик упирается в потолок; выше — только клиппинг. */
    private static final double FULL_SCALE_RMS = 8000.0;

    @Override
    public String getPluginId() {
        return "blockfield_client";
    }

    @Override
    public void initialize(VoicechatApi api) {}

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(
                ClientSoundEvent.class,
                event -> {
                    if (com.lunares.blockfield.client.workshop.WorkshopVoiceCapture
                            .privateAudio()) {
                        com.lunares.blockfield.client.workshop.WorkshopVoiceCapture.accept(
                                event.getRawAudio());
                        event.cancel();
                        return;
                    }
                    VoiceHudState.mic(level(event.getRawAudio()), System.currentTimeMillis());
                });
        registration.registerEvent(ClientReceiveSoundEvent.EntitySound.class, this::onReceive);
        registration.registerEvent(ClientReceiveSoundEvent.StaticSound.class, this::onReceive);
        registration.registerEvent(ClientReceiveSoundEvent.LocationalSound.class, this::onReceive);
    }

    private void onReceive(ClientReceiveSoundEvent event) {
        // Рация или голос «рядом» -- по состоянию клавиши рации говорящего, которое шлёт сервер:
        // вплотную сокомандники слышны в обоих случаях, и по самому звуку их не различить.
        VoiceHudState.speaking(
                event.getId(),
                level(event.getRawAudio()),
                VoiceHudState.radio(event.getId()),
                System.currentTimeMillis());
    }

    private static double level(short[] audio) {
        if (audio == null || audio.length == 0) return 0.5;
        long sum = 0;
        for (short sample : audio) sum += (long) sample * sample;
        return Math.sqrt((double) sum / audio.length) / FULL_SCALE_RMS;
    }
}
