package com.lunares.blockfield.client.workshop;

import com.lunares.blockfield.network.packet.WorkshopAiAudioC2SPacket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Bounded streaming conversion from SVC's 48 kHz mono PCM to a 16 kHz WAV. */
public final class WorkshopRecording {
    public static final int SAMPLE_RATE = 16_000;
    public static final int MAX_SECONDS = WorkshopAiAudioC2SPacket.MAX_SECONDS;
    public static final int MAX_SAMPLES = SAMPLE_RATE * MAX_SECONDS;
    private final short[] samples = new short[MAX_SAMPLES];
    private int size;
    private int partialCount;
    private int partialSum;

    public void append(short[] audio) {
        if (audio == null) return;
        for (short sample : audio) {
            if (full()) break;
            partialSum += sample;
            if (++partialCount == 3) {
                samples[size++] = (short) (partialSum / 3);
                partialSum = partialCount = 0;
            }
        }
    }

    public int samples() {
        return size;
    }

    public boolean full() {
        return size == MAX_SAMPLES;
    }

    public byte[] wav() {
        int pcmBytes = size * 2;
        ByteBuffer wav = ByteBuffer.allocate(44 + pcmBytes).order(ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + pcmBytes);
        wav.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16);
        wav.putShort((short) 1).putShort((short) 1).putInt(SAMPLE_RATE);
        wav.putInt(SAMPLE_RATE * 2).putShort((short) 2).putShort((short) 16);
        wav.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(pcmBytes);
        for (int i = 0; i < size; i++) wav.putShort(samples[i]);
        return wav.array();
    }

    public void clear() {
        Arrays.fill(samples, (short) 0);
        size = partialCount = partialSum = 0;
    }
}
