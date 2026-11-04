package com.lunares.blockfield.common.combat;

/**
 * Create bf4 hanging adapter shared by both evaluators (ADR4 §4.5; U6, moved to common by U8b so
 * both peers hash it into {@code compat.family.create} and the client presents a driven remote with
 * the same phase the server port samples).
 *
 * <p>Native {@code PlayerSkyhookRenderer.setHangingPose} reads the observer-local Catnip clock
 * {@code (float) AnimationTickHolder.getTicks(true) + AnimationTickHolder.getPartialTicks()}. A
 * driven remote replaces that clock by time since accepted membership presentation on the integer A
 * grid: whole age {@code u0 + (n - sA)} through the same {@code i2f}, then {@code + frac(A)}, so
 * the unchanged native formula receives exactly the phase the server port does.
 */
public final class CreateHanging {
    private CreateHanging() {}

    /** Native target of the clock replacement and the membership hooks (bf4 bytecode). */
    public static final String RENDERER =
            "com.simibubi.create.foundation.render.PlayerSkyhookRenderer";

    public static final String CLOCK = "net.createmod.catnip.animation.AnimationTickHolder";

    /**
     * Deterministic hanging phase since accepted membership presentation. New phase zero is the
     * selected constructor rule, never claimed as native. A TTL refresh does not restart it;
     * continuation seeds preserve whole age/u0 and anchor semantics.
     *
     * @param u0 server-owned integer age (0 for a new actor, retained age for continuation)
     * @param anchor whole-tick phase anchor (sA for domain A)
     * @param wholeTick whole consuming tick (n = floor(A), or the canonical tick)
     * @param frac fractional part of the consuming time (frac(A), or 0 on the canonical grid)
     */
    public static float phase(int u0, long anchor, long wholeTick, float frac) {
        return ticks(u0, anchor, wholeTick) + frac;
    }

    /**
     * The whole age the native {@code getTicks(true)} slot receives: native {@code i2f} of an int
     * tick count, so the float sum is formed in the native order.
     */
    public static int ticks(int u0, long anchor, long wholeTick) {
        return Math.toIntExact(Math.addExact(u0, Math.subtractExact(wholeTick, anchor)));
    }

    /** The adapter identity both peers hash ({@code compat.family.create}). */
    public static String identity() {
        return "create.adapter.v1;renderer="
                + RENDERER
                + ";pose=setHangingPose(HumanoidModel);clock="
                + CLOCK
                + ".getTicks(Z)I+getPartialTicks()F;phase=i2f(u0+(n-sA))+frac(A)"
                + ";membership=ttl-enter,stop-exit,tick-expiry;refresh=no-change"
                + ";resets=beforeSetupAnim,afterSetupAnim";
    }
}
