package com.lunares.blockfield.common.combat;

import com.lunares.blockfield.common.combat.AnimationResources.*;
import com.lunares.blockfield.common.combat.AnimationTypes.*;

public final class AnimationTypes {
    private AnimationTypes() {}

    public interface Tag {
        int id();
    }

    public enum NativeStage implements Tag {
        INTER_TICK_ACCEPT(0),
        PRE_NATIVE_TICK(1),
        NATIVE_TICK(2),
        POST_NATIVE_TICK(3),
        END_COMMIT(4);
        private final int id;

        NativeStage(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }
    }

    public enum StartKind implements Tag {
        NEW(0),
        REPLACE(1),
        STOP_FADE(2),
        RESUME(3),
        INPUT_UPDATE(4),
        SEED_CONTINUE(5);
        private final int id;

        StartKind(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }
    }

    public enum AnchorClass implements Tag {
        OBSERVER_APPLY(0),
        CANONICAL_TICK(1),
        PROJECTED_SUCCESSOR(2);
        private final int id;

        AnchorClass(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }
    }

    public enum PhaseDomain implements Tag {
        A(0),
        R(1);
        private final int id;

        PhaseDomain(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }
    }

    public enum LayerTag implements Tag {
        PA_40(0),
        PA_41(1),
        PA_42(2),
        PA_93(3),
        PA_94(4),
        PA_95(5),
        PA_96(6),
        PARCOOL_ACTIVE(7),
        PARCOOL_PASSIVE(8),
        VANILLA_ATTACK(9),
        VANILLA_USE(10),
        VANILLA_SWIM(11),
        VANILLA_DEATH(12),
        VANILLA_AUTO_SPIN(13),
        BLOCKFIELD_STANCE(14),
        CREATE_HANGING(15),
        SBW_POSE(16);
        private final int id;

        LayerTag(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }
    }

    public enum Hand implements Tag {
        MAIN_HAND(0),
        OFF_HAND(1);
        private final int id;

        Hand(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }
    }

    public enum Arm implements Tag {
        LEFT(0),
        RIGHT(1);
        private final int id;

        Arm(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }
    }

    public enum Direction implements Tag {
        DOWN(0),
        UP(1),
        NORTH(2),
        SOUTH(3),
        WEST(4),
        EAST(5);
        private final int id;

        Direction(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }
    }

    public enum Pose implements Tag {
        STANDING(0),
        FALL_FLYING(1),
        SLEEPING(2),
        SWIMMING(3),
        SPIN_ATTACK(4),
        CROUCHING(5),
        LONG_JUMPING(6),
        DYING(7),
        CROAKING(8),
        USING_TONGUE(9),
        SITTING(10),
        ROARING(11),
        SNIFFING(12),
        EMERGING(13),
        DIGGING(14),
        SLIDING(15),
        SHOOTING(16),
        INHALING(17);
        private final int id;

        Pose(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }
    }

    public enum ArmPose implements Tag {
        EMPTY(0),
        ITEM(1),
        BLOCK(2),
        BOW_AND_ARROW(3),
        THROW_SPEAR(4),
        CROSSBOW_CHARGE(5),
        CROSSBOW_HOLD(6),
        SPYGLASS(7),
        TOOT_HORN(8),
        BRUSH(9);
        private final int id;

        ArmPose(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }
    }

    public enum UseAnimation implements Tag {
        NONE(0),
        EAT(1),
        DRINK(2),
        BLOCK(3),
        BOW(4),
        SPEAR(5),
        CROSSBOW(6),
        SPYGLASS(7),
        TOOT_HORN(8),
        BRUSH(9);
        private final int id;

        UseAnimation(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }
    }

    public enum FirstPersonMode implements Tag {
        NONE(0),
        VANILLA(1),
        DISABLED(2),
        THIRD_PERSON_MODEL(3);
        private final int id;

        FirstPersonMode(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }
    }

    public enum EvaluatorKind implements Tag {
        PA(0),
        NATIVE_FALLBACK(1);
        private final int id;

        EvaluatorKind(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }
    }

    public enum Outcome implements Tag {
        SELECTED(0),
        NO_CHANGE(1),
        STOPPED(2),
        ACTIVE_ONCE_IGNORED(3),
        MISSING_CLIP(4),
        MISSING_CONFIG(5);
        private final int id;

        Outcome(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }
    }

    public enum FadeType implements Tag {
        STANDARD(0),
        FUNCTIONAL(1),
        LR_FORCED(2);
        private final int id;

        FadeType(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }
    }

    /**
     * Native PA {@code Ease} ids. These are the {@code getId()} byte values from the pinned PA
     * {@code Ease} constant initializer (0, 1, 6..37; ids 2..5 unassigned), NOT declaration
     * ordinals. Unknown wire ids fail closed; native {@code getEase(byte)} falls back to LINEAR,
     * which the reducer must never silently imitate.
     */
    public enum Ease implements Tag {
        LINEAR(0),
        CONSTANT(1),
        INSINE(6),
        OUTSINE(7),
        INOUTSINE(8),
        INCUBIC(9),
        OUTCUBIC(10),
        INOUTCUBIC(11),
        INQUAD(12),
        OUTQUAD(13),
        INOUTQUAD(14),
        INQUART(15),
        OUTQUART(16),
        INOUTQUART(17),
        INQUINT(18),
        OUTQUINT(19),
        INOUTQUINT(20),
        INEXPO(21),
        OUTEXPO(22),
        INOUTEXPO(23),
        INCIRC(24),
        OUTCIRC(25),
        INOUTCIRC(26),
        INBACK(27),
        OUTBACK(28),
        INOUTBACK(29),
        INELASTIC(30),
        OUTELASTIC(31),
        INOUTELASTIC(32),
        INBOUNCE(33),
        OUTBOUNCE(34),
        INOUTBOUNCE(35),
        CATMULLROM(36),
        STEP(37);
        private final int id;

        Ease(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }
    }

    public enum AdjustmentSource implements Tag {
        HELD_WEAPON_POSE(0),
        LR_BODY_POSE(1);
        private final int id;

        AdjustmentSource(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }
    }

    public enum DodgeDirection implements Tag {
        FRONT(0),
        BACK(1),
        LEFT(2),
        RIGHT(3);
        private final int id;

        DodgeDirection(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }
    }

    public enum RollDirection implements Tag {
        FRONT(0),
        BACK(1),
        LEFT(2),
        RIGHT(3);
        private final int id;

        RollDirection(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }
    }

    public enum FlipDirection implements Tag {
        FRONT(0),
        BACK(1);
        private final int id;

        FlipDirection(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }
    }

    public enum VaultType implements Tag {
        RIGHT(0),
        LEFT(1);
        private final int id;

        VaultType(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }
    }

    public enum BarAxis implements Tag {
        X(0),
        Z(1);
        private final int id;

        BarAxis(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }
    }

    public enum FacingDirection implements Tag {
        TO_WALL(0),
        RIGHT_AGAINST_WALL(1),
        LEFT_AGAINST_WALL(2);
        private final int id;

        FacingDirection(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }
    }

    public enum Visibility implements Tag {
        VISIBLE(0),
        HIDDEN(1),
        CULLED(2),
        NOT_RENDERED(3);
        private final int id;

        Visibility(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }
    }

    public enum Role implements Tag {
        OLD(0),
        CURRENT(1),
        CONSTANT(2);
        private final int id;

        Role(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }
    }

    public enum EventKind implements Tag {
        TACZ_FIRE(0),
        TACZ_RELOAD(1),
        TACZ_MELEE(2),
        TACZ_DRAW(3),
        TACZ_SELECTION(4),
        LR_ATTACK(5),
        LR_SELECTION(6),
        PARCOOL_START(7),
        PARCOOL_NORMAL(8),
        PARCOOL_STOP(9),
        INPUT(10),
        EQUIPMENT(11),
        OPTIONS(12),
        CREATE_ENTER(13),
        CREATE_EXIT(14),
        CREATE_REFRESH(15),
        STANCE(16),
        VANILLA_ATTACK(17),
        VANILLA_USE(18),
        SBW_MEMBERSHIP(19),
        NO_CHANGE(20);
        private final int id;

        EventKind(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }
    }

    public enum NativeFamily implements Tag {
        VANILLA(0),
        BLOCKFIELD(1),
        TACZ(2),
        LR(3),
        PARCOOL(4),
        CREATE(5),
        SBW(6);
        private final int id;

        NativeFamily(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }
    }

    public enum AnimationPart implements Tag {
        HEAD(0),
        RIGHT_ARM(1),
        LEFT_ARM(2),
        RIGHT_LEG(3),
        LEFT_LEG(4),
        ROTATION(5),
        CAMERA(6);
        private final int id;

        AnimationPart(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }
    }

    public enum ActionTag implements Tag {
        BREAKFALL_READY(0),
        CAT_LEAP(1),
        CHARGE_JUMP(2),
        CLIMB_POLES(3),
        CLIMB_UP(4),
        CLING_TO_CLIFF(5),
        HIDE_IN_BLOCK(6),
        CRAWL(7),
        DIVE(8),
        DODGE(9),
        FAST_RUN(10),
        FAST_SWIM(11),
        FLIPPING(12),
        HANG_DOWN(13),
        HORIZONTAL_WALL_RUN(14),
        JUMP_FROM_BAR(15),
        QUICK_TURN(16),
        RIDE_ZIPLINE(17),
        ROLL(18),
        SKY_DIVE(19),
        SLIDE(20),
        TAP(21),
        VAULT(22),
        VERTICAL_WALL_RUN(23),
        WALL_JUMP(24),
        WALL_SLIDE(25),
        START_SWIM_BY_CRAWL(26);
        private final int id;

        ActionTag(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }
    }

    public enum AnimatorTag implements Tag {
        BACKWARD_WALL_JUMP(0),
        CAT_LEAP(1),
        CHARGE_JUMP(2),
        CLIMB_UP(3),
        CLING_TO_CLIFF(4),
        CRAWL(5),
        DIVE_HOST(6),
        DIVE_INTO_WATER(7),
        DODGE(8),
        FAST_RUNNING(9),
        FAST_SWIM(10),
        FLIPPING(11),
        HIDE_IN_BLOCK(12),
        HORIZONTAL_WALL_RUN(13),
        JUMP_CHARGING(14),
        JUMP_FROM_BAR(15),
        HANG(16),
        KONG_VAULT(17),
        RIDE_ZIPLINE(18),
        ROLL(19),
        SLIDING(20),
        SPEED_VAULT(21),
        TAP(22),
        VERTICAL_WALL_RUN(23),
        WALL_JUMP(24),
        WALL_SLIDE(25),
        DIVE_CHILD(26),
        SKY_DIVE_CHILD(27);
        private final int id;

        AnimatorTag(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }
    }

    public record Time(long tick, int alpha) implements Comparable<Time> {
        public Time {
            if (tick < 0 || alpha < 0 || alpha > 65535)
                throw new IllegalArgumentException("Invalid Q16 time");
        }

        public int compareTo(Time b) {
            int c = Long.compare(tick, b.tick);
            return c == 0 ? Integer.compare(alpha, b.alpha) : c;
        }

        public double difference(Time b) {
            return Math.subtractExact(tick, b.tick) + (alpha - b.alpha) / 65536.0;
        }

        public Time plusWhole(long ticks) {
            return new Time(Math.addExact(tick, ticks), alpha);
        }

        public static Time min(Time a, Time b) {
            return a.compareTo(b) <= 0 ? a : b;
        }

        public static Time max(Time a, Time b) {
            return a.compareTo(b) >= 0 ? a : b;
        }

        public static Time whole(long tick) {
            return new Time(tick, 0);
        }

        public float fraction() {
            return alpha / 65536f;
        }
    }
}
