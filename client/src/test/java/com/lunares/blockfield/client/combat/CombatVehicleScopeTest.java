package com.lunares.blockfield.client.combat;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * U6 driven-vehicle R scope (ADR4 §§4.5/5.3): kind-1 (boat/minecart) and kind-2 (SBW) R endpoints
 * sample at the frame's frozen p_R, never the ambient p_A. Boat attachments use the exact native
 * offset formula over the R yaw; the minecart formula is yaw-free, so it needs no override.
 */
class CombatVehicleScopeTest {
    @AfterEach
    void clear() {
        CombatVehicleScope.clearAll();
    }

    @Test
    void undrivenVehiclesRunTheNativePath() {
        assertNull(CombatVehicleScope.frame(11));
    }

    static CombatVehicleScope.VehicleFrame frame(float partialR) {
        return new CombatVehicleScope.VehicleFrame(
                new Vec3(1, 2, 3),
                new Vec3(4, 6, 8),
                350,
                10,
                -5,
                5,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                partialR,
                9);
    }

    @Test
    void endpointsSampleAtFrozenPR() {
        CombatVehicleScope.install(11, frame(0.5f));
        var installed = CombatVehicleScope.frame(11);
        assertEquals(new Vec3(2.5, 4, 5.5), installed.originAtR());
        // Yaw wraps through 350 -> 10: rotLerp takes the short way and lands on 360, which is
        // angle-equal to 0 (native rotLerp never normalizes; callers wrap for rendering).
        assertEquals(360, installed.yawAtR(), 1e-5);
        assertEquals(0, installed.pitchAtR(), 1e-5);
        CombatVehicleScope.clear(11);
        assertNull(CombatVehicleScope.frame(11));
    }

    @Test
    void framesArePerEntityAndRevisionPinned() {
        CombatVehicleScope.install(11, frame(0.25f));
        CombatVehicleScope.install(12, frame(0.75f));
        assertEquals(
                new Vec3(1, 2, 3).lerp(new Vec3(4, 6, 8), 0.25),
                CombatVehicleScope.frame(11).originAtR());
        assertEquals(
                new Vec3(1, 2, 3).lerp(new Vec3(4, 6, 8), 0.75),
                CombatVehicleScope.frame(12).originAtR());
        assertEquals(9, CombatVehicleScope.frame(12).revision());
    }

    @Test
    void frameValidationFailsClosed() {
        assertThrows(IllegalArgumentException.class, () -> frame(2));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new CombatVehicleScope.VehicleFrame(
                                null, Vec3.ZERO, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0.5f,
                                0));
    }

    @Test
    void boatAttachmentMatchesNativeFormulaOverRYaw() {
        // Native Boat.getPassengerAttachmentPoint: Vec3(0, height, xOffset).yRot(-yaw *
        // DEG_TO_RAD).
        // xOffset 0.2, height 1/3, yaw 90: rotation by -90 about Y maps (0, h, 0.2) to (-0.2, h,
        // 0).
        Vec3 attachment = CombatVehicleScope.boatAttachment(0.2, 1 / 3.0, 90);
        assertEquals(-0.2, attachment.x, 1e-6);
        assertEquals(1 / 3.0, attachment.y, 1e-9);
        assertEquals(0, attachment.z, 1e-6);
        // Zero yaw is the identity on the offset.
        Vec3 straight = CombatVehicleScope.boatAttachment(-0.6, 0.5, 0);
        assertEquals(0, straight.x, 1e-6);
        assertEquals(0.5, straight.y, 1e-9);
        assertEquals(-0.6, straight.z, 1e-6);
        // Matches an independent yRot computation, not the helper's own path.
        float angle = -30 * Mth.DEG_TO_RAD;
        Vec3 turned = CombatVehicleScope.boatAttachment(0.2, 0.5, 30);
        assertEquals(new Vec3(0, 0.5, 0.2).yRot(angle), turned);
    }
}
