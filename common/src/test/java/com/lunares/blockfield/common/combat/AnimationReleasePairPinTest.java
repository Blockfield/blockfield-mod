package com.lunares.blockfield.common.combat;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Release-pair pins: TaCZ bf13 and LR bf6. The pair ships together, so the PIN table and the oracle
 * pack snapshot must name both; a one-sided bump fails the server oracle with {@code expected
 * <pinned> but was <unpinned:…>}. Expected bytes are the fork release SHA256SUMS (client pw.toml
 * pins the same jars by sha512).
 */
class AnimationReleasePairPinTest {
    // https://github.com/Blockfield/tacz-refabricated/releases/download/bf13/SHA256SUMS
    private static final String TACZ_BF13_SHA256 =
            "5732bf28067b5cc06de0d75818eb0899378b3b36844d2a856dedfcd1ecb590c6";
    // https://github.com/Blockfield/lr-tactical/releases/download/bf6/SHA256SUMS
    private static final String LR_BF6_SHA256 =
            "e4d43b10ba590d0f7b21b19c9aae9c814921ca31295bbeb210f5819903ed7f60";
    // Same jars by the pack algorithm (client next mods/*.pw.toml).
    private static final String LR_BF6_JAR = "lrtactical-1.21.1-0.4.1-bf6.jar";
    private static final String LR_BF6_SHA512 =
            "6bedf5247c271b83c985eadd5d3290720157f52155ea7bb2c968b38749e5d0a5af68411a436c3a44d80965443a9208d81bce926b4462b01bc0b11a767710557f";
    private static final String TACZ_BF13_JAR = "TACZ-Refabricated-1.21.1-0.7.0-bf13.jar";
    private static final String TACZ_BF13_SHA512 =
            "5ae504cad2371897027fcca59c69d7d886bbdd2da25d5c0594e73452edf6cb0858809ae13dbbcd834c55fac360617b1805c23ba90547b445ccd35b3730217c85";

    @Test
    void pairPinsMatchClientNext() {
        assertEquals(TACZ_BF13_SHA256, AnimationManifest.PINS.get("tacz"), "tacz");
        assertEquals(LR_BF6_SHA256, AnimationManifest.PINS.get("lrtactical"), "lrtactical");
    }

    @Test
    void oracleSnapshotShipsThePair() throws Exception {
        String snapshot =
                Files.readString(Path.of("../dev/oracle-mod-pins.gradle").toAbsolutePath());
        assertTrue(snapshot.contains("'" + TACZ_BF13_JAR + "'"), "tacz bf13 pin");
        assertTrue(snapshot.contains("'" + LR_BF6_JAR + "'"), "lr bf6 pin");
        assertTrue(snapshot.contains("hash: '" + TACZ_BF13_SHA512 + "'"), "tacz bf13 bytes");
        assertTrue(snapshot.contains("hash: '" + LR_BF6_SHA512 + "'"), "lr bf6 bytes");
        assertFalse(
                snapshot.contains("TACZ-Refabricated-1.21.1-0.7.0-bf12.jar"),
                "no stale tacz bf12 pin");
        assertFalse(snapshot.contains("lrtactical-1.21.1-0.4.1-bf5.jar"), "no stale lr bf5 pin");
    }
}
