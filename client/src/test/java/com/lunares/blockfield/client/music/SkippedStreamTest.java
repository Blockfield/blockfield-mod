package com.lunares.blockfield.client.music;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import javax.sound.sampled.AudioFormat;
import net.minecraft.client.sounds.AudioStream;
import org.junit.jupiter.api.Test;

class SkippedStreamTest {
    /** 10 кадров в секунду по 4 байта; байт i потока равен i % 256, отдаётся кусками по 7 байт. */
    private static final class Counting implements AudioStream {
        private int next;

        @Override
        public AudioFormat getFormat() {
            return new AudioFormat(10, 16, 2, true, false);
        }

        @Override
        public ByteBuffer read(int size) {
            ByteBuffer buffer = ByteBuffer.allocate(next >= 400 ? 0 : 7);
            while (buffer.hasRemaining()) buffer.put((byte) next++);
            return buffer.flip();
        }

        @Override
        public void close() {}
    }

    @Test
    void startsExactlyAtTheRequestedFrame() throws Exception {
        SkippedStream stream = new SkippedStream(new Counting(), 1.5);
        assertEquals(60, stream.read(4).get());
    }

    @Test
    void skippingPastTheEndGivesAnEmptyStream() throws Exception {
        SkippedStream stream = new SkippedStream(new Counting(), 100);
        assertEquals(0, stream.read(4).remaining());
    }
}
