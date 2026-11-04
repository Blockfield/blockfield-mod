package com.lunares.blockfield.client;

/**
 * Camera Overhaul's own view tilt in the last rendered frame, degrees (0 without the mod). Written
 * by {@code tacz.CameraOverhaulAimMixin} on the render thread, read by {@code
 * tacz.ShootRotationSyncMixin} when a shot goes out. Lives outside the mixin package: Mixin refuses
 * to load plain classes from it.
 */
public final class CameraOverhaulAim {
    public static volatile float pitch;
    public static volatile float yaw;

    private CameraOverhaulAim() {}
}
