package com.lunares.blockfield.oracle;

import com.lunares.blockfield.common.combat.*;
import com.lunares.blockfield.common.combat.AnimationGraphState.*;
import com.lunares.blockfield.common.combat.AnimationState.*;
import com.lunares.blockfield.common.combat.AnimationTypes.*;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.*;

/**
 * U7c presentation differential: the same typed ParCool frames, render fractions and model inputs
 * in the separately transformed client (native ParCool through the driven scope) and server (the
 * typed-frame port evaluator) JVMs. Frames are every registered animator, both Dive children,
 * passive falling and option masks, plus every boundary of U5's scripted canonical timeline and its
 * observer projection produced by the production reducer.
 */
public final class ParCoolPresentationCases {
    private ParCoolPresentationCases() {}

    public static final AnimationPolicy POLICY = ParCoolStagesCases.POLICY;
    public static final int[] FPS = {30, 60, 144, 240};

    /** Shared model/entity inputs both sides present: R walk, head angles, body yaw 23. */
    public static final float HEAD_YAW = 13, HEAD_PITCH = -17, BODY_YAW = 23, TICK_COUNT = 12;

    public static final float WALK_POSITION = 7.25f, WALK_SPEED = .4f;

    public record Case(String name, ParCoolFrame frame) {}

    /** The A-fraction schedule a renderer at {@code fps} visits over three ticks, Q16 floor. */
    public static float[] fractions(int fps) {
        int frames = fps * 3 / 20;
        float[] result = new float[frames];
        for (int j = 0; j < frames; j++) {
            long numerator = 20L * j;
            long remainder = numerator % fps;
            int alpha = (int) (remainder * 65536 / fps);
            result[j] = alpha / 65536f;
        }
        return result;
    }

    /** Native {@code WalkAnimationState.position(p)} with speedOld = speed (float order). */
    public static float limb(float partial) {
        return WALK_POSITION - WALK_SPEED * (1.0F - partial);
    }

    public static float age(float partial) {
        return TICK_COUNT + partial;
    }

    public static List<Case> cases() throws Exception {
        var result = new ArrayList<Case>();
        for (var entry : animators().entrySet())
            for (int age : new int[] {0, 3})
                result.add(
                        new Case(entry.getKey() + "@" + age, frame(entry.getValue(), age, false)));
        result.add(new Case("fast_running_gun@4", frame(new FastRunning(limb(0)), 4, true)));
        result.add(new Case("crawl_gun_canceled@2", frame(new Crawl(), 2, true)));
        result.add(new Case("roll_gun_arms@5", frame(new Roll(RollDirection.RIGHT), 5, true)));
        for (boolean fromInAir : new boolean[] {false, true})
            for (int age : new int[] {2, 12}) {
                result.add(new Case("dive@" + age + ":" + fromInAir, dive(age, fromInAir, null)));
                result.add(
                        new Case("dive_sky@" + age + ":" + fromInAir, dive(age, fromInAir, true)));
                result.add(
                        new Case(
                                "dive_sky_exit@" + age + ":" + fromInAir,
                                dive(age, fromInAir, false)));
            }
        result.add(new Case("passive_fall@16", passive(16, false)));
        result.add(new Case("passive_fall_gun@30", passive(30, true)));
        result.add(new Case("inert", passive(3, false)));
        result.addAll(timeline());
        return List.copyOf(result);
    }

    /** The action each registered animator's native predicates and formulas read as doing. */
    static ActionTag action(AnimatorData data) {
        return switch (data) {
            case BackwardWallJump ignored -> ActionTag.WALL_JUMP;
            case CatLeap ignored -> ActionTag.CAT_LEAP;
            case ChargeJump ignored -> ActionTag.CHARGE_JUMP;
            case ClimbUp ignored -> ActionTag.CLIMB_UP;
            case ClingToCliff ignored -> ActionTag.CLING_TO_CLIFF;
            case Crawl ignored -> ActionTag.CRAWL;
            case DiveIntoWater ignored -> ActionTag.DIVE;
            case Dodge ignored -> ActionTag.DODGE;
            case FastRunning ignored -> ActionTag.FAST_RUN;
            case FastSwim ignored -> ActionTag.FAST_SWIM;
            case Flipping ignored -> ActionTag.FLIPPING;
            case Hang ignored -> ActionTag.HANG_DOWN;
            case HideInBlock ignored -> ActionTag.HIDE_IN_BLOCK;
            case HorizontalWallRun ignored -> ActionTag.HORIZONTAL_WALL_RUN;
            case JumpCharging ignored -> ActionTag.CHARGE_JUMP;
            case JumpFromBar ignored -> ActionTag.JUMP_FROM_BAR;
            case KongVault ignored -> ActionTag.VAULT;
            case RideZipline ignored -> ActionTag.RIDE_ZIPLINE;
            case Roll ignored -> ActionTag.ROLL;
            case Sliding ignored -> ActionTag.SLIDE;
            case SpeedVault ignored -> ActionTag.VAULT;
            case Tap ignored -> ActionTag.TAP;
            case VerticalWallRun ignored -> ActionTag.VERTICAL_WALL_RUN;
            case WallJump ignored -> ActionTag.WALL_JUMP;
            case WallSlide ignored -> ActionTag.WALL_SLIDE;
            default -> throw new IllegalArgumentException("Not a single-node animator");
        };
    }

    /** Every registered single-node animator with representative constructor/native fields. */
    static Map<String, AnimatorData> animators() {
        var result = new LinkedHashMap<String, AnimatorData>();
        result.put("backward_wall_jump", new BackwardWallJump(12));
        result.put("cat_leap", new CatLeap());
        result.put("charge_jump", new ChargeJump());
        result.put("climb_up", new ClimbUp());
        result.put("cling_to_cliff", new ClingToCliff(20));
        result.put("crawl", new Crawl());
        result.put("dive_into_water", new DiveIntoWater(true));
        result.put("dodge_left", new Dodge(DodgeDirection.LEFT));
        result.put("dodge_back", new Dodge(DodgeDirection.BACK));
        result.put("fast_running", new FastRunning(limb(0)));
        result.put("fast_swim", new FastSwim());
        result.put("flipping_back", new Flipping(FlipDirection.values()[1]));
        result.put("hang", new Hang());
        result.put("hide_in_block", new HideInBlock(true, false));
        result.put("hide_in_block_dive", new HideInBlock(false, true));
        result.put("horizontal_wall_run_right", new HorizontalWallRun(true, limb(0)));
        result.put("horizontal_wall_run_left", new HorizontalWallRun(false, limb(0)));
        result.put("jump_charging", new JumpCharging());
        result.put("jump_from_bar", new JumpFromBar());
        result.put("kong_vault", new KongVault(7.5f, -2.25f));
        result.put("ride_zipline", new RideZipline(-.125, .25));
        result.put("roll_left", new Roll(RollDirection.LEFT));
        result.put("roll_front", new Roll(RollDirection.FRONT));
        result.put("sliding", new Sliding());
        result.put("speed_vault_left", new SpeedVault(VaultType.LEFT));
        result.put("tap", new Tap());
        result.put("vertical_wall_run", new VerticalWallRun());
        result.put("wall_jump_right", new WallJump(true, 11));
        result.put("wall_slide", new WallSlide());
        return result;
    }

    private static AnimatorTag tag(AnimatorData data) {
        for (AnimatorTag tag : AnimatorTag.values()) {
            var type = tag.name().replace("_", "").toLowerCase(Locale.ROOT);
            if (data.getClass().getSimpleName().toLowerCase(Locale.ROOT).equals(type)) return tag;
        }
        return switch (data) {
            case Hang ignored -> AnimatorTag.HANG;
            default -> throw new IllegalArgumentException("Unknown animator " + data);
        };
    }

    /** The native context rows with {@code doing} active at the animator's age. */
    private static List<ActionState> rows(ActionTag doing, int age, boolean charging) {
        var rows = new ArrayList<ActionState>();
        for (var row : ParCoolNativeValues.context().actions()) {
            if (row.action() == doing && !charging)
                rows.add(new ActionState(row.action(), true, age, 0, age, row.inputs()));
            else rows.add(new ActionState(row.action(), false, 0, 9 + age, -1, row.inputs()));
        }
        return rows;
    }

    private static AnimationGraphState graph(List<Node> nodes, NodeRef root) {
        var roots = new ArrayList<Root>(AnimationGraphState.neutral().roots());
        roots.replaceAll(
                value ->
                        value.layer() == LayerTag.PARCOOL_ACTIVE
                                ? new Root(value.priority(), value.layer(), root)
                                : value);
        return new AnimationGraphState(roots, nodes, List.of());
    }

    private static ParCool state(
            NodeRef animator,
            AnimatorTag tag,
            List<ActionState> rows,
            Passive passive,
            boolean gun) {
        var context = ParCoolNativeValues.context();
        return new ParCool(
                animator,
                new AnimationStartReport.StartKey(1, 1),
                ParCoolReducers.option(tag, gun),
                passive,
                rows,
                context.hangGrid(),
                context.diveGrid());
    }

    static ParCoolFrame frame(AnimatorData data, int age, boolean gun) {
        var tag = tag(data);
        var node = new Node(1, PhaseDomain.A, null, new Animator(tag, age, data));
        boolean charging = data instanceof JumpCharging;
        var rows = rows(action(data), age, charging);
        return new ParCoolFrame(
                graph(List.of(node), new NodeRef(1)),
                state(new NodeRef(1), tag, rows, new Passive(0, 0, 0), gun));
    }

    /**
     * A Dive host with its Dive child, optionally a SkyDive child: entering ({@code sky} true,
     * transitioning toward 90) or exiting ({@code false}, the retained child fading back). The Dive
     * grid covers the child's age.
     */
    static ParCoolFrame dive(int age, boolean fromInAir, Boolean sky) {
        var nodes = new ArrayList<Node>();
        NodeRef skyRef = sky == null ? null : new NodeRef(3);
        boolean doing = Boolean.TRUE.equals(sky);
        var host =
                new DiveHost(
                        new NodeRef(2), skyRef, fromInAir, doing, sky != null, sky == null ? 0 : 2);
        nodes.add(new Node(1, PhaseDomain.A, null, new Animator(AnimatorTag.DIVE_HOST, age, host)));
        nodes.add(
                new Node(
                        2,
                        PhaseDomain.A,
                        null,
                        new Animator(AnimatorTag.DIVE_CHILD, age, new DiveChild(-.4, 36, .2f))));
        if (skyRef != null)
            nodes.add(
                    new Node(
                            3,
                            PhaseDomain.A,
                            null,
                            new Animator(
                                    AnimatorTag.SKY_DIVE_CHILD,
                                    2,
                                    new SkyDiveChild(3, 2, -1, 0, 36, 61))));
        var rows = new ArrayList<ActionState>(rows(ActionTag.DIVE, age, false));
        rows.replaceAll(
                row ->
                        row.action() == ActionTag.SKY_DIVE
                                ? new ActionState(
                                        row.action(),
                                        doing,
                                        doing ? 2 : 0,
                                        doing ? 0 : 2,
                                        doing ? 2 : -1,
                                        List.of(new IntentInput(true, false, false, true)))
                                : row);
        var context = ParCoolNativeValues.context();
        long base = 3L * age;
        var grid = new DiveGrid(new Grid4(base, .18f, .2f, .23f, .27f, .32f));
        return new ParCoolFrame(
                graph(nodes, new NodeRef(1)),
                new ParCool(
                        new NodeRef(1),
                        new AnimationStartReport.StartKey(1, 1),
                        ParCoolReducers.option(AnimatorTag.DIVE_HOST, false),
                        new Passive(0, 0, 0),
                        rows,
                        context.hangGrid(),
                        grid));
    }

    /** No animator: bf7 PassiveCustomAnimation falling from tick 14 (the native start line). */
    static ParCoolFrame passive(int falling, boolean gun) {
        var rows = rows(ActionTag.BREAKFALL_READY, 0, true);
        return new ParCoolFrame(
                graph(List.of(), null), state(null, null, rows, new Passive(falling, 0, 0), gun));
    }

    /** Every boundary of U5's scripted canonical timeline and its two-bucket-early projection. */
    static List<Case> timeline() throws Exception {
        var timeline = new ParCoolStagesCases.Timeline();
        var samples = ParCoolStagesCases.samples(timeline);
        var canonicalJournal = ParCoolStagesCases.canonicalJournal(timeline);
        var canonical =
                new ParCoolStages(
                        ParCoolStages.Mode.CANONICAL, samples, canonicalJournal.starts(), POLICY);
        var observerJournal = ParCoolStagesCases.observerJournal(timeline, 2, Map.of());
        var observer =
                new ParCoolStages(
                        ParCoolStages.Mode.OBSERVER, samples, observerJournal.starts(), POLICY);
        var result = new ArrayList<Case>();
        var c = ParCoolStagesCases.initial(timeline);
        var o = c;
        for (long n = ParCoolStagesCases.FIRST + 1; n <= ParCoolStagesCases.LAST; n++) {
            c = ParCoolStagesCases.unfurled(c, n - 1, n, canonicalJournal, canonical);
            o = ParCoolStagesCases.unfurled(o, n - 1, n, observerJournal, observer);
            if (n % 3 == 0 || c.frame().state().animator() != null)
                result.add(new Case("canonical@" + n, c.frame()));
            if (o.frame().state().animator() != null)
                result.add(new Case("observer@" + n, o.frame()));
        }
        return result;
    }

    // --- Six boxes ---

    /**
     * The 48 corners (feet-relative, model space) of the six skin boxes for the ParCool root and
     * six part params through the common rig, both sides computing the same function.
     */
    public static float[] corners(float[] root16, float[] params36) {
        var root = new org.joml.Matrix4f().set(root16);
        var bones = new org.joml.Matrix4f[6];
        for (int i = 0; i < 6; i++) bones[i] = new org.joml.Matrix4f();
        BodyRig.bones(root, params36, bones);
        var boxes = BodyGeometry.skin();
        var out = new float[6 * 8 * 3];
        var corners = new org.joml.Vector3f[8];
        for (int i = 0; i < 8; i++) corners[i] = new org.joml.Vector3f();
        int k = 0;
        for (int i = 0; i < 6; i++) {
            BodyRig.corners(bones[i], boxes.get(i), corners);
            for (var corner : corners) {
                out[k++] = corner.x;
                out[k++] = corner.y;
                out[k++] = corner.z;
            }
        }
        return out;
    }

    /** Root (16), six parts (x,y,z,xRot,yRot,zRot) and their 48 corners as raw float bits. */
    public static int[] bits(float[] root16, float[] params36) {
        var corners = corners(root16, params36);
        int[] result = new int[16 + 36 + corners.length];
        int k = 0;
        for (float value : root16) result[k++] = Float.floatToRawIntBits(value);
        for (float value : params36) result[k++] = Float.floatToRawIntBits(value);
        for (float value : corners) result[k++] = Float.floatToRawIntBits(value);
        for (int bit : result)
            if (!Float.isFinite(Float.intBitsToFloat(bit)))
                throw new AssertionError("nonfinite sample is not equality evidence");
        return result;
    }

    @FunctionalInterface
    public interface Sampler {
        int[] sample(Case value, float fraction) throws Exception;
    }

    /**
     * Every case at every FPS schedule, each sample twice (fresh scratch per invocation); per
     * sample a digest of its raw bits, plus the first sample's raw bits per case.
     */
    public static Map<String, Object> run(Sampler sampler) throws Exception {
        var rows = new ArrayList<Map<String, Object>>();
        var all = MessageDigest.getInstance("SHA-256");
        int samples = 0;
        for (var value : cases()) {
            var perFps = new LinkedHashMap<String, Object>();
            int[] first = null;
            for (int fps : FPS) {
                var digests = new ArrayList<String>();
                for (float fraction : fractions(fps)) {
                    int[] bits = sampler.sample(value, fraction);
                    if (!Arrays.equals(bits, sampler.sample(value, fraction)))
                        throw new AssertionError(value.name() + ": repeat sample differs");
                    if (first == null) first = bits;
                    var digest = MessageDigest.getInstance("SHA-256");
                    var buffer = ByteBuffer.allocate(bits.length * 4 + 4);
                    buffer.putInt(Float.floatToRawIntBits(fraction));
                    for (int bit : bits) buffer.putInt(bit);
                    byte[] bytes = buffer.array();
                    all.update(bytes);
                    digests.add(HexFormat.of().formatHex(digest.digest(bytes)).substring(0, 16));
                    samples++;
                }
                perFps.put(String.valueOf(fps), digests);
            }
            rows.add(
                    Map.of(
                            "case",
                            value.name(),
                            "samples",
                            perFps,
                            "firstBits",
                            Arrays.stream(first).boxed().toList()));
        }
        return Map.of(
                "cases",
                rows,
                "caseCount",
                rows.size(),
                "sampleCount",
                samples,
                "fps",
                Arrays.stream(FPS).boxed().toList(),
                "allSamplesSha256",
                HexFormat.of().formatHex(all.digest()),
                "scalarOrder",
                "root16,parts6x(x,y,z,xRot,yRot,zRot),corners48x(x,y,z)");
    }
}
