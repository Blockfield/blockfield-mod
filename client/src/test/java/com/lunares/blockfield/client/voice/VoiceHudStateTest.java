package com.lunares.blockfield.client.voice;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VoiceHudStateTest {
    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());

    @BeforeEach
    void reset() {
        VoiceHudState.clear();
    }

    @Test
    void speakerDisappearsAfterTheFadeTimeout() {
        VoiceHudState.speaking(ALICE, 0.5, false, 1_000);
        VoiceHudState.tick(1_000);
        assertEquals(1, VoiceHudState.speakers().size());

        VoiceHudState.tick(1_000 + VoiceHudState.SPEAKER_TIMEOUT_MS);
        assertEquals(1, VoiceHudState.speakers().size(), "на границе таймаута строка ещё держится");

        VoiceHudState.tick(1_000 + VoiceHudState.SPEAKER_TIMEOUT_MS + 1);
        assertTrue(VoiceHudState.speakers().isEmpty());
    }

    @Test
    void continuedSpeechKeepsTheRowAliveAndHoldsThePeak() {
        VoiceHudState.speaking(ALICE, 0.9, false, 1_000);
        VoiceHudState.speaking(ALICE, 0.0, false, 1_300);
        VoiceHudState.tick(1_300);
        assertEquals(1, VoiceHudState.speakers().size());
        assertEquals(0.36, VoiceHudState.speakers().get(0).level(), 1e-9);
    }

    @Test
    void radioBadgeFollowsTheServerState() {
        assertFalse(VoiceHudState.radio(ALICE));
        VoiceHudState.setRadio(ALICE, true);
        VoiceHudState.speaking(ALICE, 0.5, VoiceHudState.radio(ALICE), 1_000);
        VoiceHudState.speaking(BOB, 0.5, VoiceHudState.radio(BOB), 1_000);
        VoiceHudState.tick(1_000);

        var speakers = VoiceHudState.speakers();
        assertEquals(2, speakers.size());
        assertTrue(
                speakers.stream()
                        .filter(s -> s.id.equals(ALICE))
                        .allMatch(VoiceHudState.Speaker::radio));
        assertTrue(
                speakers.stream()
                        .filter(s -> s.id.equals(BOB))
                        .noneMatch(VoiceHudState.Speaker::radio));

        VoiceHudState.setRadio(ALICE, false);
        assertFalse(VoiceHudState.radio(ALICE));
    }

    @Test
    void ownMicRisesWithSoundAndDecaysToSilence() {
        VoiceHudState.mic(1.0, 1_000);
        for (int i = 0; i < 20; i++) VoiceHudState.tick(1_000);
        assertTrue(VoiceHudState.micLevel() > 0.9, "уровень догоняет громкий вход");
        assertTrue(VoiceHudState.selfActive(false, 1_100));

        long now = 1_000;
        for (int i = 0; i < 40; i++) VoiceHudState.tick(now += 50);
        assertEquals(0.0, VoiceHudState.micLevel());
        assertFalse(VoiceHudState.selfActive(false, now));
    }

    @Test
    void heldRadioKeyShowsTheRowEvenInSilence() {
        assertFalse(VoiceHudState.selfActive(false, 10_000));
        assertTrue(VoiceHudState.selfActive(true, 10_000));
    }

    @Test
    void volumesAreClampedToTheMeterRange() {
        VoiceHudState.speaking(ALICE, 12.0, false, 1_000);
        VoiceHudState.speaking(BOB, -3.0, false, 1_000);
        VoiceHudState.tick(1_000);
        assertEquals(
                1.0,
                VoiceHudState.speakers().stream()
                        .filter(s -> s.id.equals(ALICE))
                        .findFirst()
                        .orElseThrow()
                        .level());
        assertEquals(
                0.0,
                VoiceHudState.speakers().stream()
                        .filter(s -> s.id.equals(BOB))
                        .findFirst()
                        .orElseThrow()
                        .level());
    }
}
