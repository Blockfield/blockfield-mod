package com.lunares.blockfield.client.workshop;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class WorkshopRecordingTest {
    @Test
    void conversionKeepsPartialFramesAcrossVoicePackets() {
        WorkshopRecording audio = new WorkshopRecording();
        audio.append(new short[] {100, 200});
        audio.append(new short[] {300, -100, -200, -300});
        ByteBuffer wav = ByteBuffer.wrap(audio.wav()).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(2, audio.samples());
        assertEquals(48, wav.capacity());
        assertEquals(44 + 4 - 8, wav.getInt(4));
        assertEquals(16_000, wav.getInt(24));
        assertEquals(4, wav.getInt(40));
        assertEquals(200, wav.getShort(44));
        assertEquals(-200, wav.getShort(46));
    }

    @Test
    void maximumRecordingIsThirtySecondsAndIgnoresAdditionalSamples() {
        WorkshopRecording audio = new WorkshopRecording();
        short[] frame = new short[960];
        Arrays.fill(frame, Short.MAX_VALUE);
        for (int i = 0; i < 1550; i++) audio.append(frame);
        assertTrue(audio.full());
        assertEquals(480_000, audio.samples());
        assertEquals(960_044, audio.wav().length);
        audio.append(new short[] {Short.MIN_VALUE, Short.MIN_VALUE, Short.MIN_VALUE});
        assertEquals(
                Short.MAX_VALUE,
                ByteBuffer.wrap(audio.wav()).order(ByteOrder.LITTLE_ENDIAN).getShort(960_042));
        audio.clear();
        assertEquals(0, audio.samples());
        assertEquals(44, audio.wav().length);
    }
}
