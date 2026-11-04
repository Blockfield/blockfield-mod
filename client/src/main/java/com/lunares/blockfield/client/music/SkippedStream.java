package com.lunares.blockfield.client.music;

import java.io.IOException;
import java.nio.ByteBuffer;
import javax.sound.sampled.AudioFormat;
import net.minecraft.client.sounds.AudioStream;

/**
 * Поток трека с середины. Minecraft не умеет перематывать OGG, поэтому начало декодируется и
 * выбрасывается -- в конструкторе, который зовут в фоновом потоке, а не в звуковом: декодирование
 * минуты Vorbis заметно задержало бы выстрелы.
 */
final class SkippedStream implements AudioStream {
    private static final int CHUNK = 1 << 16;
    private final AudioStream in;
    private ByteBuffer pending;

    SkippedStream(AudioStream in, double seconds) throws IOException {
        this.in = in;
        AudioFormat format = in.getFormat();
        long skip = (long) (seconds * format.getFrameRate()) * format.getFrameSize();
        while (skip > 0) {
            ByteBuffer buffer = in.read(CHUNK);
            if (!buffer.hasRemaining()) break;
            if (buffer.remaining() > skip) {
                buffer.position(buffer.position() + (int) skip);
                pending = buffer;
                break;
            }
            skip -= buffer.remaining();
        }
    }

    @Override
    public AudioFormat getFormat() {
        return in.getFormat();
    }

    @Override
    public ByteBuffer read(int size) throws IOException {
        if (pending != null) {
            ByteBuffer buffer = pending;
            pending = null;
            return buffer;
        }
        return in.read(size);
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
