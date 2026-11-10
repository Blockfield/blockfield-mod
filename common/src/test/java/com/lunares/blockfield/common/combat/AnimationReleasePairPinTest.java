package com.lunares.blockfield.common.combat;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Release-pair pins (client next 6fc675cf, server next a4f06f0c): TaCZ bf12 and LR bf6. The pair
 * ships together, so the PIN table and the oracle pack snapshot must name both; a one-sided bump
 * fails the server oracle with {@code expected <pinned> but was <unpinned:…>}. Expected bytes are
 * the fork release SHA256SUMS (client pw.toml pins the same jars by sha512).
 */
class AnimationReleasePairPinTest {
    // https://github.com/Blockfield/tacz-refabricated/releases/download/bf12/SHA256SUMS
    private static final String TACZ_BF12_SHA256 =
            "b95cd70bae2f5bec56a7bdc14822d3afc63e5ec3102a5b42403376be374ced3d";
    // https://github.com/Blockfield/lr-tactical/releases/download/bf6/SHA256SUMS
    private static final String LR_BF6_SHA256 =
            "e4d43b10ba590d0f7b21b19c9aae9c814921ca31295bbeb210f5819903ed7f60";
    // Same jars by the pack algorithm (client next mods/*.pw.toml).
    private static final String LR_BF6_JAR = "lrtactical-1.21.1-0.4.1-bf6.jar";
    private static final String LR_BF6_SHA512 =
            "6bedf5247c271b83c985eadd5d3290720157f52155ea7bb2c968b38749e5d0a5af68411a436c3a44d80965443a9208d81bce926b4462b01bc0b11a767710557f";
    private static final String TACZ_BF12_JAR = "TACZ-Refabricated-1.21.1-0.7.0-bf12.jar";

    @Test
    void pairPinsMatchClientNext() {
        assertEquals(TACZ_BF12_SHA256, AnimationManifest.PINS.get("tacz"), "tacz");
        assertEquals(LR_BF6_SHA256, AnimationManifest.PINS.get("lrtactical"), "lrtactical");
    }

    @Test
    void oracleSnapshotShipsThePair() throws Exception {
        String snapshot =
                Files.readString(Path.of("../dev/oracle-mod-pins.gradle").toAbsolutePath());
        assertTrue(snapshot.contains("'" + TACZ_BF12_JAR + "'"), "tacz bf12 pin");
        assertTrue(snapshot.contains("'" + LR_BF6_JAR + "'"), "lr bf6 pin");
        assertTrue(snapshot.contains("hash: '" + LR_BF6_SHA512 + "'"), "lr bf6 bytes");
        assertFalse(snapshot.contains("lrtactical-1.21.1-0.4.1-bf5.jar"), "no stale lr bf5 pin");
    }
}
