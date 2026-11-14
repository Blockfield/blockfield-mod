package com.lunares.blockfield.common.combat;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Release-pair pins: TaCZ bf14 and LR bf6. The pair ships together, so the PIN table and the oracle
 * pack snapshot must name both; a one-sided bump fails the server oracle with {@code expected
 * <pinned> but was <unpinned:…>}. Expected bytes are the fork release SHA256SUMS (client pw.toml
 * pins the same jars by sha512).
 */
class AnimationReleasePairPinTest {
    // https://github.com/Blockfield/tacz-refabricated/releases/download/bf14/SHA256SUMS
    private static final String TACZ_BF14_SHA256 =
            "430909cdcff23ea5f1ce1a1fd53987959d84acfbfc61a01c26b3be3755fe86e9";
    // https://github.com/Blockfield/lr-tactical/releases/download/bf6/SHA256SUMS
    private static final String LR_BF6_SHA256 =
            "e4d43b10ba590d0f7b21b19c9aae9c814921ca31295bbeb210f5819903ed7f60";
    // Same jars by the pack algorithm (client next mods/*.pw.toml).
    private static final String LR_BF6_JAR = "lrtactical-1.21.1-0.4.1-bf6.jar";
    private static final String LR_BF6_SHA512 =
            "6bedf5247c271b83c985eadd5d3290720157f52155ea7bb2c968b38749e5d0a5af68411a436c3a44d80965443a9208d81bce926b4462b01bc0b11a767710557f";
    private static final String TACZ_BF14_JAR = "TACZ-Refabricated-1.21.1-0.7.0-bf14.jar";
    private static final String TACZ_BF14_SHA512 =
            "87708128d0606949aa54d00c6184a6e9f7b1940fee51a1d6c0f97642af7bca54b4c3a28070ea31cf98dce65e486c3c3e423a3383b38845988c5853f8adc3b9b3";

    @Test
    void pairPinsMatchClientNext() {
        assertEquals(TACZ_BF14_SHA256, AnimationManifest.PINS.get("tacz"), "tacz");
        assertEquals(LR_BF6_SHA256, AnimationManifest.PINS.get("lrtactical"), "lrtactical");
    }

    @Test
    void oracleSnapshotShipsThePair() throws Exception {
        String snapshot =
                Files.readString(Path.of("../dev/oracle-mod-pins.gradle").toAbsolutePath());
        assertTrue(snapshot.contains("'" + TACZ_BF14_JAR + "'"), "tacz bf14 pin");
        assertTrue(snapshot.contains("'" + LR_BF6_JAR + "'"), "lr bf6 pin");
        assertTrue(snapshot.contains("hash: '" + TACZ_BF14_SHA512 + "'"), "tacz bf14 bytes");
        assertTrue(snapshot.contains("hash: '" + LR_BF6_SHA512 + "'"), "lr bf6 bytes");
        assertFalse(
                snapshot.contains("TACZ-Refabricated-1.21.1-0.7.0-bf13.jar"),
                "no stale tacz bf13 pin");
        assertFalse(snapshot.contains("lrtactical-1.21.1-0.4.1-bf5.jar"), "no stale lr bf5 pin");
    }
}
