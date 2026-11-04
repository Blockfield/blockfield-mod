package com.lunares.blockfield.common;

import com.lunares.blockfield.common.combat.AnimationState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;

/**
 * A standing/crouching/prone change eases its real dimensions (collision box, eye height, so also
 * every bullet and knife check). HOTFIX-2751 D3: crouch/uncrouch snaps instantly again; only the
 * slower prone (flat) transition eases over {@link #TICKS}. Each side runs it from the pose change
 * it sees, like vanilla pose changes; both JARs must carry it, or the client predicts a box the
 * server does not have.
 */
public final class StanceTransition {
    /** Entity mixin state, shared by real dimensions, body rendering and combat geometry. */
    public interface Access {
        StanceTransition blockfield$stance();
    }

    public static final int TICKS = 8;

    /** Movement speed multiplier while the stance changes. */
    public static final double SLOWDOWN = -0.5;

    /**
     * Vanilla crouch bone offsets at coefficient {@code c}: exact at 0 (standing) and 1 (crouched),
     * linear between. The numbers are the single source both the client model mixin and the server
     * six-box blend from; they match {@code HumanoidModel.setupAnim} (body {@code xRot} .5, arms
     * {@code xRot} +.4, legs {@code z} 4 / {@code y} 12.2, head {@code y} 4.2, torso {@code y} 3.2,
     * arms {@code y} 5.2 over the standing defaults 0/0/0/12/0/0/2).
     */
    public record Crouch(
            float torsoXRot,
            float armXRotAdd,
            float legZ,
            float legY,
            float headY,
            float torsoY,
            float armY) {
        public static Crouch at(float c) {
            return new Crouch(
                    .5f * c, .4f * c, 4f * c, 12f + .2f * c, 4.2f * c, 3.2f * c, 2f + 3.2f * c);
        }
    }

    private EntityDimensions from;
    private EntityDimensions to;
    private int age = TICKS;
    private boolean eased;
    private float fromFlat, toFlat, flat;
    private float fromCrouch, toCrouch, crouch;

    /**
     * Driven remote mirrors never advance from client physics: the authoritative state arrives via
     * {@link #restore} and client ticks must not move it (ADR4 §5.3). The server never sets this.
     */
    private boolean driven;

    /**
     * Dry crawling faces the look direction on both predicted and server players. Vanilla's
     * travel-derived body yaw differs between predicted and packet-driven movement; TaCZ turns that
     * difference into different prone pivots and leaves the camera outside the drawn head.
     */
    public boolean alignProneFacing(Player player) {
        if (driven
                || player.getPose() != Pose.SWIMMING
                || player.isSwimming()
                || player.isInWater()
                || player.isPassenger()) return false;
        player.yBodyRotO = player.yHeadRotO;
        player.yBodyRot = player.yHeadRot;
        return true;
    }

    /**
     * The dimensions to apply now. {@code current} is what the entity has, {@code target} what its
     * pose asks for; {@code eases} is false for poses that must stay instant (swimming in water,
     * elytra, sleeping, spectator), and a change from such a pose snaps too. HOTFIX-2751 D3: the
     * crouch coefficient always snaps (crouch/uncrouch is instant on every layer); only the prone
     * (flat) coefficient and its box ease over {@link #TICKS}. Driven mirrors hold the
     * authoritative box: a client-observed pose must not rewrite it, but the box/eye still ease
     * from the replicated snapshot.
     */
    public EntityDimensions apply(
            EntityDimensions current,
            EntityDimensions target,
            boolean eases,
            boolean prone,
            boolean crouching) {
        // Driven mirrors hold the authoritative box: a client-observed pose must not rewrite it.
        // The eased box still comes from the restored snapshot, never the live pose.
        if (driven) {
            if (from == null || to == null || age >= TICKS) return to != null ? to : current;
            float t = (float) age / TICKS;
            return new EntityDimensions(
                    Mth.lerp(t, from.width(), to.width()),
                    Mth.lerp(t, from.height(), to.height()),
                    Mth.lerp(t, from.eyeHeight(), to.eyeHeight()),
                    current.attachments(),
                    current.fixed());
        }
        boolean ease = eases && eased && to != null;
        eased = eases;
        if (!ease) {
            from = to = target;
            age = TICKS;
            fromFlat = toFlat = flat = prone ? 1 : 0;
            fromCrouch = toCrouch = crouch = crouching ? 1 : 0;
            return target;
        }
        // HOTFIX-2751 D3: crouch snaps while the box/flat below still ease for prone.
        float wantCrouch = crouching ? 1 : 0;
        fromCrouch = toCrouch = crouch = wantCrouch;
        // Nothing left to ease snaps, including a prone flight cancelled before its first tick (a
        // one-tick crawl plus the server's pose echo): a box-only flight would collapse the flat
        // endpoints, which held() reads as a ceiling hold, freezing the old eye height for good.
        if (flat == (prone ? 1 : 0)) {
            from = to = target;
            age = TICKS;
            fromFlat = toFlat = flat;
            return target;
        }
        // Scaled dimensions are new instances on every call; compare the sizes.
        if (target.width() != to.width()
                || target.height() != to.height()
                || target.eyeHeight() != to.eyeHeight()
                || toFlat != (prone ? 1 : 0)) {
            from = current;
            to = target;
            age = 0;
            fromFlat = flat;
            toFlat = prone ? 1 : 0;
        }
        if (age >= TICKS) {
            flat = toFlat;
            return to;
        }
        float t = (float) age / TICKS;
        flat = Mth.lerp(t, fromFlat, toFlat);
        return new EntityDimensions(
                Mth.lerp(t, from.width(), to.width()),
                Mth.lerp(t, from.height(), to.height()),
                Mth.lerp(t, from.eyeHeight(), to.eyeHeight()),
                to.attachments(),
                to.fixed());
    }

    /** Advances one tick; true while the dimensions still have to be refreshed. */
    public boolean tick() {
        if (driven || age >= TICKS || held()) return false;
        age++;
        return true;
    }

    public boolean changing() {
        return age < TICKS;
    }

    /**
     * A ceiling hold freezes the transition mid-flight: the endpoints collapse onto the current box
     * and coefficient, but the frozen age stays so a shared-coefficient sampler (LR sneak) keeps
     * its progress instead of following a separate clock. Ticks stay suppressed until the
     * transition resumes or settles; {@link #changing} still reports the frozen flight.
     */
    public EntityDimensions hold(EntityDimensions current) {
        from = to = current;
        fromFlat = toFlat = flat;
        fromCrouch = toCrouch = crouch;
        return current;
    }

    /** True while a ceiling hold freezes the transition: endpoints collapsed, age mid-flight. */
    private boolean held() {
        return eased && age < TICKS && fromFlat == toFlat && fromCrouch == toCrouch;
    }

    public float flatness() {
        return flat;
    }

    /**
     * The flat coefficient at the end of this tick's segment (HOTFIX-2751 D5): the eased flight's
     * value one tick ahead, clamped at its end. A render-partial lerp from {@link #flatness} to
     * this reproduces the eased flat per rendered frame instead of stepping once per tick. Frozen
     * while held or settled. Driven mirrors project their replicated flight the same way; the next
     * snapshot corrects any interruption.
     */
    public float flatSegmentEnd() {
        return flatAt(age, fromFlat, toFlat, flat, 1);
    }

    /** Current-to-next tick stance segment, shared by native Crawl and the server body port. */
    public static float flatAt(int age, float from, float to, float current, float partialTick) {
        if (age >= TICKS || from == to) return current;
        float end = Mth.lerp(Math.min(age + 1, TICKS) / (float) TICKS, from, to);
        return Mth.lerp(partialTick, current, end);
    }

    public float crouchness() {
        return crouch;
    }

    public boolean eases() {
        return eased;
    }

    /**
     * Immutable authoritative stance checkpoint (ADR4 §4.2 stance block). {@link EntityDimensions}
     * is a record, so the dimension refs are immutable values, never live mutable state.
     */
    public record Snapshot(
            int age,
            boolean eased,
            float fromFlat,
            float toFlat,
            float flat,
            float fromCrouch,
            float toCrouch,
            float crouch,
            EntityDimensions from,
            EntityDimensions to) {}

    /** Exports the full transition state; the ceiling-hold state round-trips exactly. */
    public Snapshot snapshot() {
        return new Snapshot(
                age, eased, fromFlat, toFlat, flat, fromCrouch, toCrouch, crouch, from, to);
    }

    /**
     * Builds a restorable snapshot from a replicated stance checkpoint plus its converted endpoint
     * boxes. Single mapping shared by the driven remote restore and the equality fixtures, so the
     * snapshot triple can never drift from the wire triple.
     */
    public static Snapshot snapshotOf(
            AnimationState.Stance stance, EntityDimensions from, EntityDimensions to) {
        return new Snapshot(
                stance.age(),
                stance.eased(),
                stance.fromFlat(),
                stance.toFlat(),
                stance.flat(),
                stance.fromCrouch(),
                stance.toCrouch(),
                stance.crouch(),
                from,
                to);
    }

    /**
     * Replaces the state with an authoritative snapshot (ADR4 §2.3: restore is replacement, never
     * an additional tick). A null snapshot is a fresh idle transition. The crouch triple rides the
     * replicated stance block (schema manifest 5), so a mirror restores it eased — no inference
     * from boxes.
     */
    public void restore(Snapshot snapshot) {
        if (snapshot == null) {
            from = to = null;
            age = TICKS;
            eased = false;
            fromFlat = toFlat = flat = 0;
            fromCrouch = toCrouch = crouch = 0;
            return;
        }
        age = snapshot.age();
        eased = snapshot.eased();
        fromFlat = snapshot.fromFlat();
        toFlat = snapshot.toFlat();
        flat = snapshot.flat();
        fromCrouch = snapshot.fromCrouch();
        toCrouch = snapshot.toCrouch();
        crouch = snapshot.crouch();
        from = snapshot.from();
        to = snapshot.to();
    }

    /**
     * Marks this mirror as authoritatively driven: {@link #tick} no-ops and {@link #apply} holds
     * the current box instead of easing toward a client-observed pose. Cleared on Remove/respawn
     * with the entity; the local player and the server never set it.
     */
    public void setDriven(boolean driven) {
        this.driven = driven;
        if (driven) {
            from = to = null;
            age = TICKS;
        }
    }

    public boolean isDriven() {
        return driven;
    }
}
