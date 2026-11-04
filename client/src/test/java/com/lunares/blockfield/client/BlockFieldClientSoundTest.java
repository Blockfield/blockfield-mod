package com.lunares.blockfield.client;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BlockFieldClientSoundTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /**
     * The engine resolves the file-backed Sound at play time, so getVolume()/getPitch() NPE
     * headless; assert the stored gains directly.
     */
    private static float[] gains(
            net.minecraft.client.resources.sounds.SimpleSoundInstance instance) {
        try {
            var volume =
                    net.minecraft.client.resources.sounds.AbstractSoundInstance.class
                            .getDeclaredField("volume");
            var pitch =
                    net.minecraft.client.resources.sounds.AbstractSoundInstance.class
                            .getDeclaredField("pitch");
            volume.setAccessible(true);
            pitch.setAccessible(true);
            return new float[] {volume.getFloat(instance), pitch.getFloat(instance)};
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void rejectsMalformedAndUnknownSoundIds() {
        assertNull(BlockFieldClient.soundInstance(":::", 1f, 1f));
        assertNull(BlockFieldClient.soundInstance("", 1f, 1f));
        assertNull(BlockFieldClient.soundInstance("no_such_sound_xyz", 1f, 1f));
    }

    @Test
    void clampsVolumeAndPitchToUiBounds() {
        var loud =
                BlockFieldClient.soundInstance(
                        "minecraft:entity.player.levelup", Float.MAX_VALUE, 1f);
        assertNotNull(loud);
        assertEquals(1f, gains(loud)[0]);
        var nan =
                BlockFieldClient.soundInstance(
                        "minecraft:entity.player.levelup", Float.NaN, Float.NaN);
        assertNotNull(nan);
        assertArrayEquals(new float[] {1f, 1f}, gains(nan));
        var extreme = BlockFieldClient.soundInstance("minecraft:entity.player.levelup", -5f, 100f);
        assertNotNull(extreme);
        assertArrayEquals(new float[] {0f, 2f}, gains(extreme));
        var quiet = BlockFieldClient.soundInstance("minecraft:entity.player.levelup", 0.5f, 0.01f);
        assertNotNull(quiet);
        assertEquals(0.5f, gains(quiet)[1]);
    }

    @Test
    void keepsSaneValues() {
        var sane = BlockFieldClient.soundInstance("minecraft:entity.player.levelup", 0.8f, 1.2f);
        assertNotNull(sane);
        assertArrayEquals(new float[] {0.8f, 1.2f}, gains(sane));
    }
}
