package com.lunares.blockfield.client.workshop;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class WorkshopVoiceCaptureTest {
    @AfterEach
    void close() {
        WorkshopVoiceCapture.closeScreen();
    }

    @Test
    void openingTheScreenDoesNotRecordAndExplicitHoldIsRequired() {
        WorkshopVoiceCapture.closeScreen();
        WorkshopVoiceCapture.start();
        assertFalse(WorkshopVoiceCapture.recording());
        WorkshopVoiceCapture.openScreen();
        WorkshopVoiceCapture.accept(new short[48_000]);
        assertNull(WorkshopVoiceCapture.finish());
        WorkshopVoiceCapture.start();
        assertTrue(WorkshopVoiceCapture.recording());
        WorkshopVoiceCapture.accept(new short[48_000]);
        assertEquals(32_044, WorkshopVoiceCapture.finish().length);
        assertFalse(WorkshopVoiceCapture.recording());
        assertNull(WorkshopVoiceCapture.finish());
    }

    @Test
    void closeDiscardsAudioAndTheCaptureCapStopsEvenWhileHeld() {
        WorkshopVoiceCapture.openScreen();
        WorkshopVoiceCapture.start();
        WorkshopVoiceCapture.accept(new short[(WorkshopRecording.MAX_SECONDS - 1) * 48_000]);
        assertFalse(WorkshopVoiceCapture.timedOut());
        WorkshopVoiceCapture.accept(new short[48_000]);
        assertTrue(WorkshopVoiceCapture.timedOut());
        WorkshopVoiceCapture.closeScreen();
        assertFalse(WorkshopVoiceCapture.recording());
        assertFalse(WorkshopVoiceCapture.screenOpen());
        assertTrue(
                WorkshopVoiceCapture.privateAudio(), "the last buffered voice frame stays private");
        assertNull(WorkshopVoiceCapture.finish());
    }
}
