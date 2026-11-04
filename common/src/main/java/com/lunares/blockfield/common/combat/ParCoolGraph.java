package com.lunares.blockfield.common.combat;

import static com.lunares.blockfield.common.combat.AnimationGraphState.*;
import static com.lunares.blockfield.common.combat.AnimationTypes.*;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.IntSupplier;

/** Lossless U1 animator graph adapter shared by native client scratch and server formula ports. */
public final class ParCoolGraph {
    private static final List<String> NAMES =
            List.of(
                    "BackwardWallJumpAnimator",
                    "CatLeapAnimator",
                    "ChargeJumpAnimator",
                    "ClimbUpAnimator",
                    "ClingToCliffAnimator",
                    "CrawlAnimator",
                    "DiveAnimationHostAnimator",
                    "DiveIntoWaterAnimator",
                    "DodgeAnimator",
                    "FastRunningAnimator",
                    "FastSwimAnimator",
                    "FlippingAnimator",
                    "HideInBlockAnimator",
                    "HorizontalWallRunAnimator",
                    "JumpChargingAnimator",
                    "JumpFromBarAnimator",
                    "HangAnimator",
                    "KongVaultAnimator",
                    "RideZiplineAnimator",
                    "RollAnimator",
                    "SlidingAnimator",
                    "SpeedVaultAnimator",
                    "TapAnimator",
                    "VerticalWallRunAnimator",
                    "WallJumpAnimator",
                    "WallSlideAnimator",
                    "DiveAnimationHostAnimator$DiveAnimator",
                    "DiveAnimationHostAnimator$SkyDiveAnimator");

    private final Map<AnimatorTag, Class<?>> types;
    private final Map<Class<?>, AnimatorTag> tags;

    /** Class names come from the pinned implementation, never a packet or resource. */
    public ParCoolGraph(ClassLoader loader, String packageName) {
        if (!packageName.equals("com.alrex.parcool.client.animation.impl.")
                && !packageName.equals("com.lunares.blockfield.server.combat."))
            throw new IllegalArgumentException("Unknown ParCool implementation");
        var byTag = new EnumMap<AnimatorTag, Class<?>>(AnimatorTag.class);
        var byClass = new HashMap<Class<?>, AnimatorTag>();
        try {
            for (AnimatorTag tag : AnimatorTag.values()) {
                Class<?> type = Class.forName(packageName + NAMES.get(tag.id()), false, loader);
                byTag.put(tag, type);
                byClass.put(type, tag);
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Pinned ParCool animator layout unavailable", e);
        }
        types = Map.copyOf(byTag);
        tags = Map.copyOf(byClass);
    }

    public Class<?> type(AnimatorTag tag) {
        return types.get(tag);
    }

    public AnimatorTag tag(Object animator) {
        AnimatorTag tag = tags.get(animator.getClass());
        if (tag == null) throw new IllegalArgumentException("Unregistered ParCool animator");
        return tag;
    }

    public static AnimationState.Passive passive(Object nativePassive) {
        return new AnimationState.Passive(
                integer(nativePassive, "fallingAnimationTick"),
                integer(nativePassive, "flyingAnimationLevelOld"),
                integer(nativePassive, "flyingAnimationLevel"));
    }

    public static Object passive(Class<?> nativeType, AnimationState.Passive state) {
        if (!nativeType
                .getName()
                .equals("com.alrex.parcool.client.animation.PassiveCustomAnimation"))
            throw new IllegalArgumentException("Unknown ParCool passive type");
        Object result = construct(nativeType);
        set(result, "fallingAnimationTick", state.fallingAnimationTick());
        set(result, "flyingAnimationLevelOld", state.flyingAnimationLevelOld());
        set(result, "flyingAnimationLevel", state.flyingAnimationLevel());
        return result;
    }

    public static AnimationState.Option option(Object nativeOption) {
        int allowed = 0;
        var entries = (Map<?, ?>) get(nativeOption, "animationMap");
        if (entries.size() != AnimationPart.values().length)
            throw new IllegalArgumentException("ParCool option part registry changed");
        for (var entry : entries.entrySet())
            if (Boolean.TRUE.equals(entry.getValue()))
                allowed |= 1 << AnimationPart.valueOf(((Enum<?>) entry.getKey()).name()).id();
        return new AnimationState.Option(bool(nativeOption, "animationCanceled"), allowed);
    }

    @SuppressWarnings("unchecked")
    public static Object option(Class<?> nativeType, AnimationState.Option state) {
        if (!nativeType
                .getName()
                .equals("com.alrex.parcool.api.unstable.animation.AnimationOption"))
            throw new IllegalArgumentException("Unknown ParCool option type");
        Object result = construct(nativeType);
        var entries = (Map<Object, Boolean>) get(result, "animationMap");
        for (Object part : entries.keySet())
            entries.put(part, state.allows(AnimationPart.valueOf(((Enum<?>) part).name())));
        set(result, "animationCanceled", state.animationCanceled());
        return result;
    }

    /**
     * Replacement capture, with disjoint IDs and authoritative phase keys supplied by the owner.
     */
    public AnimationGraphState freeze(
            AnimationGraphState base,
            Object animator,
            IntSupplier ids,
            Function<Object, AnimationStartReport.StartKey> starts) {
        var nodes = new ArrayList<Node>();
        for (Node node : base.nodes()) if (!(node.data() instanceof Animator)) nodes.add(node);
        var used = new HashSet<Integer>();
        for (Node node : nodes) used.add(node.id());
        NodeRef root = freeze(animator, nodes, used, ids, starts);
        var roots = new ArrayList<Root>();
        boolean found = false;
        for (Root entry : base.roots()) {
            if (entry.layer() == LayerTag.PARCOOL_ACTIVE) {
                if (found) throw new IllegalArgumentException("Duplicate ParCool root");
                roots.add(new Root(entry.priority(), entry.layer(), root));
                found = true;
            } else roots.add(entry);
        }
        if (!found) throw new IllegalArgumentException("Missing ParCool root");
        return new AnimationGraphState(roots, nodes, base.aliases());
    }

    private NodeRef freeze(
            Object value,
            List<Node> nodes,
            Set<Integer> used,
            IntSupplier ids,
            Function<Object, AnimationStartReport.StartKey> starts) {
        if (value == null) return null;
        AnimatorTag tag = tag(value);
        int id = ids.getAsInt();
        if (id <= 0 || !used.add(id))
            throw new IllegalArgumentException("ParCool node ID collision");
        AnimatorData data =
                switch (tag) {
                    case BACKWARD_WALL_JUMP -> new BackwardWallJump(integer(value, "maxTick"));
                    case CAT_LEAP -> new CatLeap();
                    case CHARGE_JUMP -> new ChargeJump();
                    case CLIMB_UP -> new ClimbUp();
                    case CLING_TO_CLIFF -> new ClingToCliff(number(value, "Lean_Angle"));
                    case CRAWL -> new Crawl();
                    case DIVE_HOST ->
                            new DiveHost(
                                    freeze(get(value, "diveAnimator"), nodes, used, ids, starts),
                                    freeze(get(value, "skyDiveAnimator"), nodes, used, ids, starts),
                                    bool(value, "fromInAir"),
                                    bool(value, "oldSkyDiveDoing"),
                                    bool(value, "transitioning"),
                                    integer(value, "transitionTick"));
                    case DIVE_INTO_WATER -> new DiveIntoWater(bool(value, "fromSkyDive"));
                    case DODGE -> new Dodge(DodgeDirection.valueOf(enumName(value, "direction")));
                    case FAST_RUNNING -> new FastRunning(number(value, "limbSwing"));
                    case FAST_SWIM -> new FastSwim();
                    case FLIPPING ->
                            new Flipping(FlipDirection.valueOf(enumName(value, "direction")));
                    case HIDE_IN_BLOCK ->
                            new HideInBlock(
                                    bool(value, "standing"), bool(value, "startFromDiving"));
                    case HORIZONTAL_WALL_RUN ->
                            new HorizontalWallRun(
                                    bool(value, "wallIsRightSide"), number(value, "limbSwing"));
                    case JUMP_CHARGING -> new JumpCharging();
                    case JUMP_FROM_BAR -> new JumpFromBar();
                    case HANG -> new Hang();
                    case KONG_VAULT ->
                            new KongVault(
                                    number(value, "yRotDifference"),
                                    number(value, "yRotDifferenceOld"));
                    case RIDE_ZIPLINE ->
                            new RideZipline(
                                    decimal(value, "oldAngleRadian"),
                                    decimal(value, "currentAngleRadian"));
                    case ROLL -> new Roll(RollDirection.valueOf(enumName(value, "direction")));
                    case SLIDING -> new Sliding();
                    case SPEED_VAULT -> new SpeedVault(VaultType.valueOf(enumName(value, "type")));
                    case TAP -> new Tap();
                    case VERTICAL_WALL_RUN -> new VerticalWallRun();
                    case WALL_JUMP ->
                            new WallJump(bool(value, "wallRightSide"), integer(value, "maxTick"));
                    case WALL_SLIDE -> new WallSlide();
                    case DIVE_CHILD ->
                            new DiveChild(
                                    decimal(value, "initialYSpeed"),
                                    number(value, "pitchAngle"),
                                    number(value, "oldFactor"));
                    case SKY_DIVE_CHILD -> {
                        if (integer(value, "maxCount") != 8)
                            throw new IllegalArgumentException("ParCool SkyDive maxCount changed");
                        yield new SkyDiveChild(
                                integer(value, "forwardAngleCount"),
                                        integer(value, "forwardAngleCountOld"),
                                integer(value, "rightAngleCount"),
                                        integer(value, "rightAngleCountOld"),
                                number(value, "startPitchAngle"), number(value, "pitchAngle"));
                    }
                };
        nodes.add(
                new Node(
                        id,
                        PhaseDomain.A,
                        starts.apply(value),
                        new Animator(tag, integer(value, "tick"), data)));
        return new NodeRef(id);
    }

    /** Does not tick, select, call Action.start/finish, or dispatch gameplay effects. */
    public Object materialize(AnimationGraphState graph, NodeRef root) {
        if (root == null) return null;
        var nodes = new HashMap<Integer, Node>();
        for (Node node : graph.nodes())
            if (node.id() <= 0 || nodes.put(node.id(), node) != null)
                throw new IllegalArgumentException("Duplicate ParCool node");
        return materialize(nodes, root, new HashSet<>());
    }

    private Object materialize(Map<Integer, Node> nodes, NodeRef ref, Set<Integer> visited) {
        if (ref == null) return null;
        Node node = nodes.get(ref.id());
        if (node == null
                || !visited.add(ref.id())
                || visited.size() > 3
                || node.domain() != PhaseDomain.A
                || !(node.data() instanceof Animator actor))
            throw new IllegalArgumentException("Invalid ParCool subtree");
        Class<?> type = type(actor.tag());
        AnimationSchema.validateFinite(actor);
        Object result =
                switch (actor.state()) {
                    case DiveHost data -> {
                        Object dive = materialize(nodes, data.diveAnimator(), visited);
                        if (dive.getClass() != type(AnimatorTag.DIVE_CHILD))
                            throw new IllegalArgumentException("Invalid Dive child");
                        Object host =
                                construct(type, decimal(dive, "initialYSpeed"), data.fromInAir());
                        set(host, "diveAnimator", dive);
                        Object sky = materialize(nodes, data.skyDiveAnimator(), visited);
                        if (sky != null && sky.getClass() != type(AnimatorTag.SKY_DIVE_CHILD))
                            throw new IllegalArgumentException("Invalid SkyDive child");
                        set(host, "skyDiveAnimator", sky);
                        set(host, "oldSkyDiveDoing", data.oldSkyDiveDoing());
                        set(host, "transitioning", data.transitioning());
                        set(host, "transitionTick", data.transitionTick());
                        yield host;
                    }
                    case DiveChild data -> {
                        Object child = construct(type, data.initialYSpeed());
                        set(child, "pitchAngle", data.pitchAngle());
                        set(child, "oldFactor", data.oldFactor());
                        yield child;
                    }
                    case SkyDiveChild data -> {
                        Object child = construct(type, data.startPitchAngle());
                        set(child, "forwardAngleCount", data.forwardAngleCount());
                        set(child, "forwardAngleCountOld", data.forwardAngleCountOld());
                        set(child, "rightAngleCount", data.rightAngleCount());
                        set(child, "rightAngleCountOld", data.rightAngleCountOld());
                        set(child, "pitchAngle", data.pitchAngle());
                        yield child;
                    }
                    case DiveIntoWater data -> construct(type, data.fromSkyDive());
                    case Dodge data -> constructEnum(type, data.direction().name());
                    case Flipping data -> constructEnum(type, data.direction().name());
                    case Roll data -> constructEnum(type, data.direction().name());
                    case SpeedVault data -> constructEnum(type, data.type().name());
                    case HideInBlock data ->
                            construct(type, data.standing(), data.startFromDiving());
                    case HorizontalWallRun data -> {
                        Object value = construct(type, data.wallIsRightSide());
                        set(value, "limbSwing", data.limbSwing());
                        yield value;
                    }
                    case WallJump data -> construct(type, data.wallRightSide());
                    default -> construct(type);
                };
        if (actor.state() instanceof FastRunning data) set(result, "limbSwing", data.limbSwing());
        if (actor.state() instanceof KongVault data) {
            set(result, "yRotDifference", data.yRotDifference());
            set(result, "yRotDifferenceOld", data.yRotDifferenceOld());
        }
        if (actor.state() instanceof RideZipline data) {
            set(result, "oldAngleRadian", data.oldAngleRadian());
            set(result, "currentAngleRadian", data.currentAngleRadian());
        }
        // A typed checkpoint already contains this bucket's integer zipline step; neither the
        // native animator nor the server port advances it from a render callback.
        set(result, "tick", actor.tick());
        // Constant fields can be inlined in native bytecode; assigning them would hide a bad pin.
        var checkNodes = new ArrayList<Node>();
        freeze(
                result,
                checkNodes,
                new HashSet<>(),
                new java.util.concurrent.atomic.AtomicInteger()::incrementAndGet,
                ignored -> null);
        Animator check = (Animator) checkNodes.getLast().data();
        if (check.tag() != actor.tag() || !sameScalars(actor.state(), check.state()))
            throw new IllegalArgumentException("ParCool tag/state or native constant mismatch");
        return result;
    }

    private static boolean sameScalars(AnimatorData expected, AnimatorData actual) {
        if (expected instanceof DiveHost a && actual instanceof DiveHost b)
            return a.fromInAir() == b.fromInAir()
                    && a.oldSkyDiveDoing() == b.oldSkyDiveDoing()
                    && a.transitioning() == b.transitioning()
                    && a.transitionTick() == b.transitionTick();
        return expected.equals(actual);
    }

    private static Object constructEnum(Class<?> type, String wireName) {
        var constructors = type.getDeclaredConstructors();
        if (constructors.length != 1 || constructors[0].getParameterCount() != 1)
            throw new IllegalStateException("Changed ParCool constructor");
        Class<?> parameter = constructors[0].getParameterTypes()[0];
        if (parameter.isEnum())
            for (Object constant : parameter.getEnumConstants())
                if (((Enum<?>) constant).name().toUpperCase(Locale.ROOT).equals(wireName))
                    return construct(type, constant);
        throw new IllegalArgumentException("Unknown native ParCool enum");
    }

    private static Object construct(Class<?> type, Object... arguments) {
        try {
            var constructors = type.getDeclaredConstructors();
            if (constructors.length != 1 || constructors[0].getParameterCount() != arguments.length)
                throw new IllegalStateException("Changed ParCool constructor");
            constructors[0].setAccessible(true);
            return constructors[0].newInstance(arguments);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot restore pinned ParCool animator", e);
        }
    }

    private static final ClassValue<ConcurrentHashMap<String, Field>> FIELDS =
            new ClassValue<>() {
                @Override
                protected ConcurrentHashMap<String, Field> computeValue(Class<?> type) {
                    return new ConcurrentHashMap<>();
                }
            };

    private static Field field(Object value, String name) {
        return FIELDS.get(value.getClass())
                .computeIfAbsent(
                        name,
                        key -> {
                            for (Class<?> owner = value.getClass();
                                    owner != null;
                                    owner = owner.getSuperclass())
                                for (Field field : owner.getDeclaredFields())
                                    if (field.getName().equals(key)) {
                                        field.setAccessible(true);
                                        return field;
                                    }
                            throw new IllegalStateException(
                                    "Pinned ParCool field unavailable: " + key);
                        });
    }

    public static Object get(Object value, String name) {
        try {
            return field(value, name).get(value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    public static void set(Object value, String name, Object data) {
        try {
            field(value, name).set(value, data);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    static int integer(Object value, String name) {
        return (int) get(value, name);
    }

    static float number(Object value, String name) {
        return (float) get(value, name);
    }

    static double decimal(Object value, String name) {
        return (double) get(value, name);
    }

    static boolean bool(Object value, String name) {
        return (boolean) get(value, name);
    }

    private static String enumName(Object value, String name) {
        return ((Enum<?>) get(value, name)).name().toUpperCase(Locale.ROOT);
    }
}
