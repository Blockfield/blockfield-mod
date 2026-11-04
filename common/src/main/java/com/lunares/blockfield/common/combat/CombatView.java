package com.lunares.blockfield.common.combat;

/**
 * Immutable ADR4 receipt with its complete animation. {@code fireEye} is the shooter's
 * render-interpolated vanilla eye at the TaCZ fire frame (null: none, e.g. a non-gun attack or an
 * old client); the server only uses it after checking it against its own eye path. {@code fireAim}
 * (only with a fire eye) is the rotation the shot was sent with, Camera Overhaul tilt included: the
 * server's deferred TaCZ shot may run after a newer rotation packet replaced it.
 */
public record CombatView(
        long sequence,
        long observerStreamEpoch,
        long receivedSnapshotTick,
        long tick,
        int alpha,
        Receipt animation,
        net.minecraft.world.phys.Vec3 fireEye,
        Aim fireAim) {
    /** Shot rotation in degrees, vanilla convention (pitch -90..90). */
    public record Aim(float yaw, float pitch) {
        public Aim {
            if (!Float.isFinite(yaw) || !(pitch >= -90 && pitch <= 90))
                throw new IllegalArgumentException("CombatView fire aim out of range");
        }
    }

    public CombatView(
            long sequence,
            long observerStreamEpoch,
            long receivedSnapshotTick,
            long tick,
            int alpha,
            Receipt animation,
            net.minecraft.world.phys.Vec3 fireEye) {
        this(
                sequence,
                observerStreamEpoch,
                receivedSnapshotTick,
                tick,
                alpha,
                animation,
                fireEye,
                null);
    }

    public CombatView(
            long sequence,
            long observerStreamEpoch,
            long receivedSnapshotTick,
            long tick,
            int alpha,
            Receipt animation) {
        this(sequence, observerStreamEpoch, receivedSnapshotTick, tick, alpha, animation, null);
    }

    public CombatView {
        if (fireEye != null
                && !(Double.isFinite(fireEye.x)
                        && Double.isFinite(fireEye.y)
                        && Double.isFinite(fireEye.z)))
            throw new IllegalArgumentException("CombatView fire eye must be finite");
        if (fireAim != null && fireEye == null)
            throw new IllegalArgumentException("CombatView fire aim needs its fire eye");
        if (sequence < 0 || observerStreamEpoch < 0 || receivedSnapshotTick < 0 || tick < 0)
            throw new IllegalArgumentException("CombatView ticks must be non-negative");
        if (alpha < 0 || alpha > 0xFFFF)
            throw new IllegalArgumentException("CombatView alpha out of u16 range: " + alpha);
        java.util.Objects.requireNonNull(animation, "ADR4 receipt required");
    }

    public record Receipt(
            long connectionId,
            long sceneGeneration,
            long frameId,
            long presentationRevision,
            AnimationTypes.Time a,
            long resourceGeneration,
            long policyGeneration,
            AnimationResources.Digest compatibilityDigest,
            AnimationStartReport starts) {
        public Receipt {
            for (long value :
                    new long[] {
                        connectionId,
                        sceneGeneration,
                        frameId,
                        presentationRevision,
                        resourceGeneration,
                        policyGeneration
                    }) AnimationSchema.nonnegative(value, "receipt identity");
            java.util.Objects.requireNonNull(a);
            java.util.Objects.requireNonNull(compatibilityDigest);
            // Null: no complete victim report this revision (protocol 9); the server rewinds the
            // victims' retained server poses to R instead of replaying the client's animation.
            if (starts != null) AnimationStartReport.validate(starts);
        }
    }

    public CombatView withFireEye(net.minecraft.world.phys.Vec3 eye) {
        return withFire(eye, fireAim);
    }

    public CombatView withFire(net.minecraft.world.phys.Vec3 eye, Aim aim) {
        return new CombatView(
                sequence,
                observerStreamEpoch,
                receivedSnapshotTick,
                tick,
                alpha,
                animation,
                eye,
                aim);
    }

    public AnimationTypes.Time r() {
        return new AnimationTypes.Time(tick, alpha);
    }

    public void validateAnimation() {
        if (r().compareTo(animation.a()) > 0
                || animation.a().compareTo(AnimationTypes.Time.whole(receivedSnapshotTick)) > 0)
            throw new IllegalArgumentException("Receipt requires R <= A <= H");
    }

    /** Absolute rendered time R. */
    public double renderTime() {
        return SnapshotWireMath.renderTime(tick, alpha);
    }
}
