package com.lunares.blockfield.oracle;

import com.lunares.blockfield.common.combat.*;
import com.lunares.blockfield.common.combat.AnimationEvent.*;
import com.lunares.blockfield.common.combat.AnimationResources.*;
import com.lunares.blockfield.common.combat.AnimationStartReport.*;
import com.lunares.blockfield.common.combat.AnimationTypes.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * U10 mixed-renderer differential (ADR4 §§8, 10 P2-9): one typed observer presentation per case
 * (lossless R placement, A checkpoint, the shared observer reducer's graph with TaCZ/Create
 * capsules or a ParCool family value), evaluated by the separately transformed client (the real
 * {@code LevelRenderer.renderEntity -> EntityRenderDispatcher -> PlayerRenderer} path with every
 * production mixin, captured by the production §8.3 hook) and the server ({@code
 * AnimSyncEval.pose}, the composition the server rewinds with). Both sides compute the inputs here
 * from common code, so a difference is a renderer/port composition difference.
 *
 * <p>Frames visit 30/60/144/240 FPS schedules with A two and a quarter ticks ahead of R, so the two
 * fractions differ. A case that cannot be evaluated on a side is recorded {@code unavailable} with
 * its reason, never as equality.
 */
public final class MixedRendererCases {
    private MixedRendererCases() {}

    public static final AnimationPolicy POLICY =
            new AnimationPolicy(
                    0, PhaseDomain.A, true, true, true, true, AnimationPolicy.registered());
    public static final int[] FPS = {30, 60, 144, 240};
    public static final int ENTITY = 640;

    /**
     * The native {@code tacz:rotation} associated-data winner of the production registration order
     * (U4 fixtures); the client fixture asserts its transformed player agrees.
     */
    public static final LayerTag WINNER = LayerTag.PA_96;

    static final long SEED_TICK = 100, FIRST = 97, LAST = 118;
    static final double WINDOW_SECONDS = .3;
    static final long MS = 1_000_000;

    /**
     * One case. {@code presentation} returns the reduced observer presentation at A (the reducer on
     * the seed and applied capsules, or a fixed ParCool frame); {@code unavailable} is non-null
     * when a side cannot evaluate it (explicitly counted, never a pass).
     */
    public record Case(
            String cohort,
            String name,
            AnimationState.Identity identity,
            NavigableMap<Long, AnimationState> states,
            ObserverSeed seed,
            PaGraph.Catalog catalog,
            LrAssets lr,
            TaczAssets tacz,
            NavigableMap<Long, AnimationState.Placement> rootVehicles,
            boolean slim,
            java.util.function.Function<Time, ObserverReducer.Result> presentation,
            String unavailable) {
        public String rootTransform() {
            var ref = states.firstEntry().getValue().sbw().transformName();
            if (ref == null) return null;
            var bytes =
                    catalog.definitions().stream()
                            .filter(d -> d.ref().equals(ref))
                            .findFirst()
                            .orElseThrow()
                            .value()
                            .value();
            String value = new String(bytes, StandardCharsets.UTF_8);
            if (!value.startsWith("transform:"))
                throw new AssertionError("invalid retained transform");
            return value.substring("transform:".length());
        }
    }

    /** One main frame: its schedule position and the frozen R/A clocks. */
    public record Frame(int fps, int index, Time r, Time a) {}

    /** The evaluated root (16 floats), six part parameters (36) and the double feet origin. */
    public record Sample(float[] root, float[] parts, double[] origin) {}

    @FunctionalInterface
    public interface Sampler {
        /** Null when the side recorded the frame unavailable (with {@link #note}). */
        Sample sample(Case value, Frame frame, ObserverReducer.Result reduced) throws Exception;
    }

    public static AnimationState.Identity identity() {
        return new AnimationState.Identity(
                3,
                17,
                1,
                ENTITY,
                0,
                5,
                9,
                0,
                0,
                0,
                0,
                AnimationSchema.layoutDigest(),
                new Digest(0, 0, 0, 0));
    }

    // --- the frame schedule ---

    /** Frames at {@code fps} over the window from a deterministic phase, A = R + 2.25 ticks. */
    public static List<Frame> frames(int fps) {
        var out = new ArrayList<Frame>();
        long period = 1_000_000_000L / fps;
        long phase = (fps * 7919L % 13) * MS;
        int count = (int) (WINDOW_SECONDS * fps);
        for (int i = 0; i < count; i++) {
            double ticks = (phase + i * period) * 20.0 / 1_000_000_000L;
            out.add(new Frame(fps, i, time(102.6 + ticks), time(104.85 + ticks)));
        }
        return out;
    }

    static Time time(double at) {
        long tick = (long) Math.floor(at);
        return new Time(tick, (int) Math.floor((at - tick) * 65536));
    }

    // --- cases ---

    public static List<Case> cases() throws Exception {
        var winner = WINNER;
        var out = new ArrayList<Case>();
        out.add(journal("vanilla", "walk-attack", winner, Variant.WALK_ATTACK, List.of()));
        out.add(journal("vanilla", "crouch-walk", winner, Variant.CROUCH, List.of()));
        out.add(journal("vanilla", "swim", winner, Variant.SWIM, List.of()));
        out.add(journal("vanilla", "elytra", winner, Variant.ELYTRA, List.of()));
        out.add(journal("vanilla", "sleep", winner, Variant.SLEEP, List.of()));
        out.add(journal("vanilla", "death", winner, Variant.DEATH, List.of()));
        out.add(journal("vanilla", "bow-draw", winner, Variant.BOW, List.of()));
        out.add(journal("vanilla", "riptide-spin", winner, Variant.AUTOSPIN, List.of()));
        out.add(journal("vanilla", "frozen-shake", winner, Variant.FROZEN, List.of()));
        out.add(journal("vanilla", "upside-down", winner, Variant.UPSIDE_DOWN, List.of()));
        out.add(journal("vanilla", "scaled", winner, Variant.SCALED, List.of()));
        out.add(journal("blockfield", "medkit", winner, Variant.MEDKIT, List.of()));
        out.add(journal("tacz", "pa-fire-walk", winner, Variant.WALK_ATTACK, List.of(Event.FIRE)));
        out.add(journal("tacz", "pa-fire-crouch", winner, Variant.CROUCH, List.of(Event.FIRE)));
        out.add(journal("create", "hanging", winner, Variant.STAND, List.of(Event.HANG)));
        out.add(
                journal(
                        "create",
                        "hanging-pa-fire",
                        winner,
                        Variant.STAND,
                        List.of(Event.HANG, Event.FIRE)));
        out.add(
                journal(
                        "create",
                        "hanging-slim",
                        winner,
                        Variant.STAND,
                        List.of(Event.HANG),
                        true));
        out.add(lr("idle-attack", winner));
        out.add(lr("local-native-metadata", winner));
        out.add(journal("tacz", "non-pa-hold", winner, Variant.FALLBACK_HOLD, List.of()));
        out.add(journal("tacz", "non-pa-aim", winner, Variant.FALLBACK_AIM, List.of()));
        out.add(
                journal(
                        "blockfield",
                        "medkit-pa-fire",
                        winner,
                        Variant.MEDKIT,
                        List.of(Event.FIRE)));
        var parcool = new LinkedHashMap<String, ParCoolFrame>();
        for (var c : ParCoolPresentationCases.cases()) parcool.put(c.name(), c.frame());
        for (var name :
                List.of(
                        "fast_running_gun@4",
                        "crawl_gun_canceled@2",
                        "roll_gun_arms@5",
                        "kong_vault@3",
                        "horizontal_wall_run_right@3",
                        "dive_sky@12:true",
                        "hang@3",
                        "passive_fall@16")) {
            var frame = parcool.get(name);
            if (frame == null) throw new AssertionError("no ParCool presentation case " + name);
            out.add(parcool(name, winner, frame));
        }
        out.add(parcoolPa("roll+pa-fire", winner, parcool.get("roll_gun_arms@5")));
        out.add(parcoolPa("fast-run+pa-fire", winner, parcool.get("fast_running_gun@4")));
        out.add(journal("sbw", "passenger-seat", winner, Variant.PASSENGER, List.of()));
        out.addAll(FxPoseCases.cases(out));
        for (String transform : List.of("Barrel", "WeaponStation", "WeaponStationBarrel"))
            out.add(
                    journal(
                            "sbw",
                            "seat-matrix-" + transform,
                            winner,
                            Variant.PASSENGER,
                            List.of(),
                            false,
                            transform));
        return FxClientCases.cases(out);
    }

    enum Variant {
        STAND,
        WALK_ATTACK,
        CROUCH,
        SWIM,
        ELYTRA,
        SLEEP,
        DEATH,
        BOW,
        AUTOSPIN,
        FROZEN,
        UPSIDE_DOWN,
        MEDKIT,
        SCALED,
        PASSENGER,
        FALLBACK_HOLD,
        FALLBACK_AIM
    }

    enum Event {
        FIRE,
        HANG
    }

    private static Case journal(
            String cohort, String name, LayerTag winner, Variant variant, List<Event> events)
            throws Exception {
        return journal(cohort, name, winner, variant, events, false);
    }

    private static Case journal(
            String cohort,
            String name,
            LayerTag winner,
            Variant variant,
            List<Event> events,
            boolean slim)
            throws Exception {
        return journal(cohort, name, winner, variant, events, slim, "Turret");
    }

    private static Case journal(
            String cohort,
            String name,
            LayerTag winner,
            Variant variant,
            List<Event> events,
            boolean slim,
            String seatTransform)
            throws Exception {
        var identity = identity();
        var catalog = new PaGraph.Catalog(0);
        var lr = new LrAssets(catalog);
        var graph =
                PaGraph.Stack.create(catalog, POLICY, PaGraphNativeCases.SOURCES, winner, 1)
                        .freeze(AnimationGraphState.neutral());
        var states = new TreeMap<Long, AnimationState>();
        for (long tick = FIRST; tick <= LAST; tick++)
            states.put(tick, checkpoint(identity, tick, graph, variant));
        TaczAssets tacz = null;
        if (variant == Variant.FALLBACK_HOLD || variant == Variant.FALLBACK_AIM) {
            tacz = new TaczAssets(catalog, Map.of("fixture:gun", "fixture:fallback"));
            var display =
                    tacz.register(
                            "fixture:fallback",
                            "fixture:missing",
                            false,
                            "default",
                            false,
                            Map.of());
            var retained =
                    new AnimationState.Tacz(
                            EvaluatorKind.NATIVE_FALLBACK,
                            catalog.named("tacz.gun", "fixture:gun"),
                            null,
                            display.ref(),
                            false,
                            variant == Variant.FALLBACK_AIM ? .65f : 0f,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            false,
                            false,
                            null,
                            Outcome.NO_CHANGE);
            states.replaceAll((tick, state) -> withFamilies(state, retained, state.sbw()));
        }
        var rootVehicles = new TreeMap<Long, AnimationState.Placement>();
        if (variant == Variant.PASSENGER) {
            var transform =
                    catalog.define(
                            ("transform:" + seatTransform).getBytes(StandardCharsets.UTF_8),
                            List.of());
            states.replaceAll(
                    (tick, state) -> {
                        var seat = state.sbw();
                        var retained =
                                new AnimationState.Sbw(
                                        seat.parachuteOpen(),
                                        seat.gun(),
                                        seat.seatPose(),
                                        seat.directVehicleEntityId(),
                                        seat.rootVehicleEntityId(),
                                        seat.directSeatIndex(),
                                        seat.rootSeatIndex(),
                                        seat.directTargetEpoch(),
                                        seat.directSeatGeneration(),
                                        seat.directTeleportGeneration(),
                                        seat.rootTargetEpoch(),
                                        seat.rootSeatGeneration(),
                                        seat.rootTeleportGeneration(),
                                        seat.directSeatDefinition(),
                                        seat.rootSeatDefinition(),
                                        transform,
                                        seat.scale(),
                                        seat.visibility());
                        return withFamilies(state, state.tacz(), retained);
                    });
            for (long tick = FIRST; tick <= LAST; tick++)
                rootVehicles.put(tick, vehiclePlacement(tick));
        }
        var seed = seed(states.get(SEED_TICK), SEED_TICK * 256 + 1);
        var applied = new ArrayList<ObserverReducer.Applied>();
        long predecessor = 0;
        for (var event : events) {
            // Capsules land inside the window: the hang in bucket 103, the fire in bucket 105,
            // both above A at their acceptance (event tick 2 above the application bucket).
            long bucket = event == Event.HANG ? 103 : 105;
            long sequence = (bucket + 2) * 256 + 1 + applied.size();
            var capsule =
                    event == Event.FIRE
                            ? fire(catalog, identity, winner, bucket + 2, sequence, predecessor)
                            : hang(identity, bucket + 2, sequence, predecessor);
            var apply =
                    new ObserverApply(
                            1000 + sequence,
                            2000 + sequence,
                            1,
                            new Time(bucket + 2, 100),
                            new Time(bucket - 2, 0x3000),
                            new Time(bucket, 0x3000),
                            bucket + 3,
                            sequence);
            applied.add(
                    new ObserverReducer.Applied(
                            capsule, ObserverReducer.template(capsule, apply, POLICY)));
            predecessor = sequence;
        }
        var frozen = List.copyOf(applied);
        var retainedTacz = tacz;
        return new Case(
                cohort,
                name,
                identity,
                Collections.unmodifiableNavigableMap(states),
                seed,
                catalog,
                lr,
                tacz,
                Collections.unmodifiableNavigableMap(rootVehicles),
                slim,
                a -> {
                    var visible = new ArrayList<ObserverReducer.Applied>();
                    for (var entry : frozen)
                        if (a.compareTo(((ObserverApply) entry.proof().proof()).applyA()) >= 0)
                            visible.add(entry);
                    return ObserverReducer.reduce(
                            seed, null, a.tick(), states, visible, retainedTacz, lr, POLICY);
                },
                null);
    }

    /** A ParCool family value composed with the walking placement and neutral PA roots. */
    private static Case parcool(String name, LayerTag winner, ParCoolFrame frame) throws Exception {
        var base = journal("parcool", name, winner, Variant.WALK_ATTACK, List.of());
        return fixed(
                base,
                "parcool",
                name,
                base.lr(),
                stack(base.catalog(), winner).freeze(frame.graph()),
                frame.state());
    }

    /** ParCool composed with a PA once actor a few ticks into its clip (TaCZ layer 95). */
    private static Case parcoolPa(String name, LayerTag winner, ParCoolFrame frame)
            throws Exception {
        var base = journal("mixed", name, winner, Variant.WALK_ATTACK, List.of());
        var stack = stack(base.catalog(), winner);
        stack.once(LayerTag.PA_95, PaGraphNativeCases.clip("mixed_fire", false), false, 7001);
        for (int i = 0; i < 4; i++) stack.tick();
        return fixed(base, "mixed", name, base.lr(), stack.freeze(frame.graph()), frame.state());
    }

    /**
     * LR's mixed stack: native idle selection on 40/41 and the rotation layer, then an accepted
     * attack (upper layer) aged three ticks, with its LR body-pose adjustment source on 42.
     */
    private static Case lr(String name, LayerTag winner) throws Exception {
        var base = journal("lr", name, winner, Variant.WALK_ATTACK, List.of());
        var assets = LrSelectorNativeCases.assets(base.catalog());
        var display = assets.display("fixture:bat");
        var stack = stack(base.catalog(), winner);
        var sequence = new java.util.concurrent.atomic.AtomicLong(7100);
        LrPose.tick(
                stack,
                display,
                false,
                LrPose.movement(true, 0, .1, false, false),
                sequence::incrementAndGet);
        stack.tick();
        stack.tick();
        LrPose.attack(stack, display, "hit", 0, sequence::incrementAndGet);
        for (int i = 0; i < 3; i++) stack.tick();
        var neutral = ParCoolCheckpoints.neutral(0, base.identity().targetEpoch());
        return fixed(
                base, "lr", name, assets, stack.freeze(AnimationGraphState.neutral()), neutral);
    }

    private static PaGraph.Stack stack(PaGraph.Catalog catalog, LayerTag winner) {
        return PaGraph.Stack.create(catalog, POLICY, PaGraphNativeCases.SOURCES, winner, 1000);
    }

    /** A fixed composed observer graph (no reducer steps) over the base case's checkpoints. */
    private static Case fixed(
            Case base,
            String cohort,
            String name,
            LrAssets lr,
            AnimationGraphState graph,
            AnimationState.ParCool parcool) {
        return new Case(
                cohort,
                name,
                base.identity(),
                base.states(),
                base.seed(),
                base.catalog(),
                lr,
                base.tacz(),
                base.rootVehicles(),
                base.slim(),
                a ->
                        new ObserverReducer.Result(
                                new PaProjection.Result(graph, List.of()),
                                a.tick(),
                                null,
                                parcool,
                                List.of()),
                null);
    }

    // --- checkpoints ---

    /** Walking +x at 4.2 m/s with a turning body and head; variants change the A state. */
    static AnimationState.Placement placement(long tick, Variant variant) {
        double t = tick - SEED_TICK, previous = t - 1;
        boolean still = variant == Variant.SLEEP || variant == Variant.DEATH;
        double speed = still ? 0 : variant == Variant.CROUCH ? .065 : .21;
        float walkSpeed = still ? 0 : variant == Variant.CROUCH ? .3f : .62f;
        return new AnimationState.Placement(
                new AnimationState.Vec3(10.25 + speed * previous, 64, -3.5 + .05 * previous),
                new AnimationState.Vec3(10.25 + speed * t, 64, -3.5 + .05 * t),
                new AnimationState.Angles(
                        yaw(previous) + 18,
                        yaw(t) + 18,
                        (float) (-12 + 2.5 * previous),
                        (float) (-12 + 2.5 * t),
                        yaw(previous),
                        yaw(t),
                        yaw(previous) + 18,
                        yaw(t) + 18),
                new AnimationState.Walk((float) (walkSpeed * t), walkSpeed * .9f, walkSpeed),
                new AnimationState.Vec3(speed, variant == Variant.ELYTRA ? -.08 : 0, .05),
                new AnimationState.Vec3(speed, variant == Variant.ELYTRA ? -.09 : 0, .05),
                new AnimationState.VehicleAngles(0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                List.of());
    }

    /** Body yaw sweeping through the ±180 wrap over the window. */
    private static float yaw(double t) {
        return (float) Mth.wrap(150 + 9 * t);
    }

    static AnimationState checkpoint(
            AnimationState.Identity identity,
            long tick,
            AnimationGraphState graph,
            Variant variant) {
        var placement = placement(tick, variant);
        var base = Defaults.of(AnimationState.class);
        return new AnimationState(
                identity,
                tick,
                0,
                tick * 256,
                placement,
                vanilla(tick, variant),
                base.stance(),
                base.equipment(),
                null,
                base.lr(),
                graph,
                ParCoolCheckpoints.neutral(identity.resourceGeneration(), identity.targetEpoch()),
                new AnimationState.Create(false, null, 0, 0, AnchorClass.CANONICAL_TICK),
                new AnimationState.Sbw(
                        false,
                        false,
                        variant == Variant.PASSENGER ? "Pilot" : null,
                        variant == Variant.PASSENGER ? ENTITY + 1 : -1,
                        variant == Variant.PASSENGER ? ENTITY + 1 : -1,
                        variant == Variant.PASSENGER ? 0 : -1,
                        variant == Variant.PASSENGER ? 0 : -1,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        null,
                        null,
                        null,
                        variant == Variant.PASSENGER ? .75f : 1,
                        Visibility.VISIBLE),
                // U7c: every S[t] closes its journal with the ParCool boundary marker.
                List.of(
                        ParCoolCheckpoints.marker(
                                tick,
                                tick * 256,
                                new InputRef(
                                        identity.resourceGeneration(),
                                        identity.targetEpoch(),
                                        tick,
                                        Digest.hash(AnimationSchema.encode(placement))))));
    }

    private static AnimationState withFamilies(
            AnimationState state, AnimationState.Tacz tacz, AnimationState.Sbw sbw) {
        return new AnimationState(
                state.identity(),
                state.serverTick(),
                state.sampleRevision(),
                state.eventWatermark(),
                state.placement(),
                state.vanilla(),
                state.stance(),
                state.equipment(),
                tacz,
                state.lr(),
                state.graph(),
                state.parcool(),
                state.create(),
                sbw,
                state.decisions());
    }

    /** Retained root-vehicle R endpoints; both JVMs consume the decoded values, not live angles. */
    private static AnimationState.Placement vehiclePlacement(long tick) {
        float t = tick - SEED_TICK;
        var origin = new AnimationState.Vec3(10, 63, -4);
        return new AnimationState.Placement(
                origin,
                origin,
                new AnimationState.Angles(
                        10 + 2 * (t - 1), 10 + 2 * t, -5 + t - 1, -5 + t, 0, 0, 0, 0),
                new AnimationState.Walk(0, 0, 0),
                new AnimationState.Vec3(0, 0, 0),
                new AnimationState.Vec3(0, 0, 0),
                new AnimationState.VehicleAngles(
                        2 + t - 1, 2 + t, 20 + 3 * (t - 1), 20 + 3 * t, 1, 3, 50, 70, -1, -3),
                List.of(
                        vehicleConstant("getTurretCustomPitch", 4),
                        vehicleConstant("getPassengerWeaponMinPitch", -30),
                        vehicleConstant("getPassengerWeaponMaxPitch", 30),
                        vehicleConstant("getPassengerRenderScale", .75f),
                        vehicleRead(
                                SbwLayouts.ROTATE_OFFSET_HEIGHT, new AnimationState.DoubleValue(0)),
                        vehicleRead(SbwLayouts.HAS_TURRET, new AnimationState.BooleanValue(true)),
                        vehicleRead(
                                SbwLayouts.TURRET_POS,
                                new AnimationState.VectorValue(SEAT_WITNESS_TURRETS.get(0))),
                        vehicleRead(
                                SbwLayouts.BARREL_POSITION + SbwLayouts.PRESENT,
                                new AnimationState.BooleanValue(true)),
                        vehicleRead(
                                SbwLayouts.BARREL_POSITION,
                                new AnimationState.VectorValue(new AnimationState.Vec3(0, 2, 1))),
                        vehicleRead(
                                SbwLayouts.WEAPON_STATION_POSITION + SbwLayouts.PRESENT,
                                new AnimationState.BooleanValue(true)),
                        vehicleRead(
                                SbwLayouts.WEAPON_STATION_POSITION,
                                new AnimationState.VectorValue(new AnimationState.Vec3(1, 1, 0))),
                        vehicleRead(
                                SbwLayouts.WEAPON_STATION_BARREL_POSITION + SbwLayouts.PRESENT,
                                new AnimationState.BooleanValue(true)),
                        vehicleRead(
                                SbwLayouts.WEAPON_STATION_BARREL_POSITION,
                                new AnimationState.VectorValue(new AnimationState.Vec3(1, 1, 1)))));
    }

    private static AnimationState.VehicleRead vehicleRead(
            String member, AnimationState.ReadValue value) {
        var slot = SbwLayouts.slot(member);
        return new AnimationState.VehicleRead(
                new AnimationState.ReadSlot(
                        slot.declaringClass(), slot.member(), slot.descriptor(), slot.role()),
                value);
    }

    // --- FX-SEAT translation witness, shared by the native client and the server port ---

    /** Retained turret position, then a changed one; only the second changes native float yaw. */
    public static final List<AnimationState.Vec3> SEAT_WITNESS_TURRETS =
            List.of(new AnimationState.Vec3(0, 2, 0), new AnimationState.Vec3(4, 3, -2));

    /** Native seat-yaw bits of {@link #SEAT_WITNESS_TURRETS} (client witness fragment). */
    public static final List<Integer> SEAT_WITNESS_NATIVE_YAW_BITS =
            List.of(1090147837, 1090147838);

    public static final double SEAT_WITNESS_X = 16_777_216.0, SEAT_WITNESS_Z = -16_777_216.0;
    public static final int SEAT_WITNESS_YAW_BITS = 1101363960;

    /** Pinned by the transformed native client, with all three retained named positions. */
    public static final Map<String, Integer> NAMED_SEAT_WITNESS_NATIVE_YAW_BITS =
            Map.of(
                    "Barrel",
                    1090564608,
                    "WeaponStation",
                    1110615499,
                    "WeaponStationBarrel",
                    1110573878);

    /** The 30 FPS first frame of {@code passenger-seat}, where the witness is sampled. */
    public static Frame seatWitnessFrame() {
        return frames(30).getFirst();
    }

    /**
     * The root-vehicle record the witness renders from: the case endpoint of {@link
     * #seatWitnessFrame} with the witness origin and a constant witness yaw, its turret position
     * replaced by {@code turret}.
     */
    public static AnimationState.Placement seatWitnessVehicle(AnimationState.Vec3 turret) {
        var p = vehiclePlacement(AnimationTiming.endpoint(seatWitnessFrame().r()).tick());
        var origin = new AnimationState.Vec3(SEAT_WITNESS_X, 63, SEAT_WITNESS_Z);
        float yaw = Float.intBitsToFloat(SEAT_WITNESS_YAW_BITS);
        var a = p.angles();
        var reads = new ArrayList<AnimationState.VehicleRead>();
        for (var read : p.reads())
            reads.add(
                    read.slot().memberId().equals(SbwLayouts.TURRET_POS)
                            ? vehicleRead(
                                    SbwLayouts.TURRET_POS, new AnimationState.VectorValue(turret))
                            : read);
        return new AnimationState.Placement(
                origin,
                origin,
                new AnimationState.Angles(
                        yaw,
                        yaw,
                        a.xRotO(),
                        a.xRot(),
                        a.yBodyRotO(),
                        a.yBodyRot(),
                        a.yHeadRotO(),
                        a.yHeadRot()),
                p.walk(),
                p.deltaMovementOnPreviousTick(),
                p.deltaMovement(),
                p.vehicleAngles(),
                List.copyOf(reads));
    }

    private static AnimationState.VehicleRead vehicleConstant(String member, float value) {
        return new AnimationState.VehicleRead(
                new AnimationState.ReadSlot(
                        "com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity",
                        member,
                        "()F",
                        Role.CONSTANT),
                new AnimationState.FloatValue(value));
    }

    static AnimationState.Vanilla vanilla(long tick, Variant variant) {
        int t = (int) (tick - SEED_TICK);
        boolean attack = variant == Variant.WALK_ATTACK;
        float swingNow = attack ? Math.floorMod(t, 6) / 6f : 0;
        float swingOld = attack ? Math.floorMod(t - 1, 6) / 6f : 0;
        var pose =
                switch (variant) {
                    case CROUCH -> Pose.CROUCHING;
                    case SWIM -> Pose.SWIMMING;
                    case ELYTRA -> Pose.FALL_FLYING;
                    case SLEEP -> Pose.SLEEPING;
                    case DEATH -> Pose.DYING;
                    case AUTOSPIN -> Pose.SPIN_ATTACK;
                    default -> Pose.STANDING;
                };
        boolean swim = variant == Variant.SWIM;
        boolean elytra = variant == Variant.ELYTRA;
        boolean dead = variant == Variant.DEATH;
        boolean bow = variant == Variant.BOW;
        boolean spin = variant == Variant.AUTOSPIN;
        boolean frozen = variant == Variant.FROZEN;
        boolean medkit = variant == Variant.MEDKIT;
        float scale = variant == Variant.SCALED ? .75f : 1;
        return new AnimationState.Vanilla(
                new AnimationState.Attack(
                        swingOld,
                        swingNow,
                        attack,
                        attack ? Math.floorMod(t, 6) : 0,
                        attack ? Hand.MAIN_HAND : null),
                swim ? .8f : 0,
                swim ? 1 : 0,
                0,
                0,
                new AnimationState.Vital(
                        (int) tick,
                        dead ? Math.min(19, t + 5) : 0,
                        0,
                        0,
                        elytra ? t + 10 : 0,
                        frozen ? 140 : 0),
                pose,
                new AnimationState.Flags(
                        !dead,
                        false,
                        false,
                        variant == Variant.CROUCH,
                        swim,
                        swim,
                        elytra,
                        spin,
                        !(swim || elytra || spin),
                        swim,
                        false,
                        frozen,
                        variant == Variant.PASSENGER,
                        false,
                        false,
                        false,
                        variant == Variant.UPSIDE_DOWN),
                0,
                Arm.RIGHT,
                variant == Variant.SLEEP ? Direction.EAST : null,
                new AnimationState.Size(
                        scale,
                        (variant == Variant.CROUCH ? 1.5f : 1.8f) * scale,
                        .6f * scale,
                        1.62f * scale,
                        (variant == Variant.CROUCH ? 1.27f : 1.62f) * scale),
                .08,
                new AnimationState.ItemUse(
                        bow,
                        Hand.MAIN_HAND,
                        bow ? 72_000 - (t + 10) : 0,
                        bow ? t + 10 : 0,
                        bow ? ArmPose.BOW_AND_ARROW : ArmPose.EMPTY,
                        ArmPose.EMPTY,
                        bow ? UseAnimation.BOW : UseAnimation.NONE,
                        bow ? 72_000 : 0,
                        25),
                medkit,
                medkit ? Hand.MAIN_HAND : null);
    }

    static ObserverSeed seed(AnimationState checkpoint, long id) {
        var identity = checkpoint.identity();
        return new ObserverSeed(
                identity,
                new SeedRef(0, identity.targetEpoch(), id, new Digest(id, 1, 2, 3)),
                null,
                checkpoint,
                checkpoint.graph(),
                checkpoint.serverTick(),
                checkpoint.eventWatermark(),
                checkpoint.eventWatermark(),
                List.of(),
                List.of(),
                ObserverReducer.DIGEST);
    }

    // --- capsules (U7b/U8b shapes, payload unwrapped) ---

    private static AnimationEvent fire(
            PaGraph.Catalog catalog,
            AnimationState.Identity identity,
            LayerTag winner,
            long tick,
            long sequence,
            long predecessor) {
        var author = PaGraph.Stack.create(catalog, POLICY, PaGraphNativeCases.SOURCES, winner, 1);
        author.once(LayerTag.PA_95, PaGraphNativeCases.clip("mixed_fire", false), false, sequence);
        var patch =
                new Patch(
                        LayerTag.PA_95,
                        StartKind.NEW,
                        Outcome.SELECTED,
                        author.fragment(LayerTag.PA_95),
                        List.of(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);
        return event(
                identity,
                tick,
                sequence,
                predecessor,
                NativeFamily.TACZ,
                EventKind.TACZ_FIRE,
                patch);
    }

    private static AnimationEvent hang(
            AnimationState.Identity identity, long tick, long sequence, long predecessor) {
        var membership =
                new AnimationState.Create(
                        true, new StartKey(sequence, 1), tick, 0, AnchorClass.OBSERVER_APPLY);
        var patch =
                new Patch(
                        LayerTag.CREATE_HANGING,
                        StartKind.NEW,
                        Outcome.SELECTED,
                        new AnimationGraphState(
                                List.of(
                                        new AnimationGraphState.Root(
                                                0, LayerTag.CREATE_HANGING, null)),
                                List.of()),
                        List.of(),
                        null,
                        null,
                        null,
                        null,
                        membership,
                        null,
                        null,
                        null);
        return event(
                identity,
                tick,
                sequence,
                predecessor,
                NativeFamily.CREATE,
                EventKind.CREATE_ENTER,
                patch);
    }

    private static AnimationEvent event(
            AnimationState.Identity identity,
            long tick,
            long sequence,
            long predecessor,
            NativeFamily family,
            EventKind kind,
            Patch patch) {
        byte[] marker = "native.unwrapped".getBytes(StandardCharsets.UTF_8);
        var hash = Digest.hash(marker);
        var payload = new DefinitionRef(0, 0, hash.a() & Long.MAX_VALUE, hash);
        long acceptedNs = tick * 50 * MS;
        return new AnimationEvent(
                identity,
                new EventId(tick, sequence, NativeStage.INTER_TICK_ACCEPT),
                predecessor,
                family,
                kind,
                NativeStage.END_COMMIT,
                acceptedNs,
                acceptedNs + 2 * MS,
                new SeedRef(0, identity.targetEpoch(), tick - 1, new Digest(0, 1, 2, 3)),
                patch,
                List.of(new Definition(payload, new Bytes(marker), List.of())),
                new NativePayload(payload, new Bytes(new byte[0])));
    }

    // --- run and record ---

    /** Bits of the 48 corners from the root and part parameters (common rig, both sides). */
    public static float[] corners(float[] root16, float[] params36) {
        return ParCoolPresentationCases.corners(root16, params36);
    }

    /**
     * Every case at every FPS schedule. Per sample: the frame clocks and the raw bits of root,
     * parts, corners and the double origin, hex-packed; plus per-case digests. A side's unavailable
     * case or frame is listed with its reason.
     */
    public static Map<String, Object> run(Sampler sampler) throws Exception {
        var winner = WINNER;
        var rows = new ArrayList<Map<String, Object>>();
        var all = MessageDigest.getInstance("SHA-256");
        int samples = 0, unavailableSamples = 0;
        var nanos = new ArrayList<Long>();
        var allocations = new ArrayList<Long>();
        for (var value : cases()) {
            var row = new LinkedHashMap<String, Object>();
            row.put("cohort", value.cohort());
            row.put("case", value.name());
            if (value.unavailable() != null) {
                row.put("status", "unavailable");
                row.put("reason", value.unavailable());
                rows.add(row);
                continue;
            }
            var perFps = new LinkedHashMap<String, Object>();
            var reasons = new TreeSet<String>();
            for (int fps : FPS) {
                var packed = new ArrayList<String>();
                for (var frame : frames(fps)) {
                    var reduced = value.presentation().apply(frame.a());
                    NOTES.remove();
                    long bytes0 = allocated(), nanos0 = System.nanoTime();
                    var sample = sampler.sample(value, frame, reduced);
                    nanos.add(System.nanoTime() - nanos0);
                    allocations.add(allocated() - bytes0);
                    if (sample == null) {
                        String why = String.valueOf(NOTES.get());
                        reasons.add(why);
                        packed.add("unavailable:" + why);
                        unavailableSamples++;
                        continue;
                    }
                    String hex = pack(frame, sample);
                    all.update(hex.getBytes(StandardCharsets.US_ASCII));
                    packed.add(hex);
                    samples++;
                }
                perFps.put(String.valueOf(fps), packed);
            }
            row.put("status", reasons.isEmpty() ? "evaluated" : "partial");
            row.put("unavailableReasons", List.copyOf(reasons));
            row.put("samples", perFps);
            rows.add(row);
        }
        var out = new LinkedHashMap<String, Object>();
        out.put("cases", rows);
        out.put("caseCount", rows.size());
        out.put("sampleCount", samples);
        out.put("unavailableSampleCount", unavailableSamples);
        out.put("fps", Arrays.stream(FPS).boxed().toList());
        out.put("rotationWinner", winner.name());
        // Report only (ADR4 §10 P2-7): no gate. Client: install plus every pass of the frame.
        out.put("costNanosPerSample", stats(nanos));
        out.put("costAllocatedBytesPerSample", stats(allocations));
        out.put("allSamplesSha256", HexFormat.of().formatHex(all.digest()));
        out.put(
                "sampleLayout",
                "R(tick,alpha) A(tick,alpha) | root16 parts36 corners144 as float bits |"
                        + " origin3 as double bits; hex, '|' separated");
        return out;
    }

    private static final ThreadLocal<String> NOTES = new ThreadLocal<>();

    /** This thread's allocated bytes (HotSpot), or -1 when the JVM cannot report them. */
    private static long allocated() {
        var bean = java.lang.management.ManagementFactory.getThreadMXBean();
        return bean instanceof com.sun.management.ThreadMXBean hotspot
                ? hotspot.getThreadAllocatedBytes(Thread.currentThread().threadId())
                : -1;
    }

    private static Map<String, Object> stats(List<Long> values) {
        var sorted = values.stream().sorted().toList();
        var out = new LinkedHashMap<String, Object>();
        out.put("count", sorted.size());
        if (sorted.isEmpty()) return out;
        out.put("p50", sorted.get(sorted.size() / 2));
        out.put("p95", sorted.get(Math.min(sorted.size() - 1, sorted.size() * 95 / 100)));
        out.put("max", sorted.getLast());
        out.put("mean", sorted.stream().mapToLong(Long::longValue).average().orElse(0));
        return out;
    }

    /** Records why a sampler returned null for the current frame. */
    public static void note(String reason) {
        NOTES.set(reason);
    }

    static String pack(Frame frame, Sample sample) {
        if (sample.root().length != 16
                || sample.parts().length != 36
                || sample.origin().length != 3) throw new AssertionError("sample layout");
        var corners = corners(sample.root(), sample.parts());
        var floats = ByteBuffer.allocate((16 + 36 + corners.length) * 4);
        for (float value : sample.root()) floats.putInt(Float.floatToRawIntBits(value));
        for (float value : sample.parts()) floats.putInt(Float.floatToRawIntBits(value));
        for (float value : corners) {
            if (!Float.isFinite(value))
                throw new AssertionError("nonfinite sample is not equality evidence");
            floats.putInt(Float.floatToRawIntBits(value));
        }
        var doubles = ByteBuffer.allocate(24);
        for (double value : sample.origin()) doubles.putLong(Double.doubleToRawLongBits(value));
        return frame.r().tick()
                + ","
                + frame.r().alpha()
                + ","
                + frame.a().tick()
                + ","
                + frame.a().alpha()
                + "|"
                + HexFormat.of().formatHex(floats.array())
                + "|"
                + HexFormat.of().formatHex(doubles.array());
    }

    /** Native {@code Mth.wrapDegrees} for doubles without a Minecraft dependency. */
    private static final class Mth {
        static double wrap(double degrees) {
            double d = degrees % 360.0;
            if (d >= 180.0) d -= 360.0;
            if (d < -180.0) d += 360.0;
            return d;
        }
    }

    /** Reflective schema defaults for the sections a case leaves neutral (stance, refs, LR). */
    static final class Defaults {
        @SuppressWarnings("unchecked")
        static <T> T of(Class<T> type) {
            try {
                return (T) value(type);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }

        private static Object value(java.lang.reflect.Type type)
                throws ReflectiveOperationException {
            if (type instanceof java.lang.reflect.ParameterizedType) return List.of();
            Class<?> c = (Class<?>) type;
            if (c == int.class) return 0;
            if (c == long.class) return 0L;
            if (c == float.class) return 0f;
            if (c == double.class) return 0d;
            if (c == boolean.class) return false;
            if (c == String.class) return "fixture";
            if (c == Bytes.class) return new Bytes(new byte[] {1});
            if (c == AnimationPolicy.class) return POLICY;
            if (c == AnimationGraphState.class) return AnimationGraphState.neutral();
            if (c.isEnum()) return c.getEnumConstants()[0];
            if (c.isInterface()) return value(c.getPermittedSubclasses()[0]);
            var fields = c.getRecordComponents();
            var args = new Object[fields.length];
            var types = new Class<?>[fields.length];
            for (int i = 0; i < fields.length; i++) {
                types[i] = fields[i].getType();
                args[i] =
                        fields[i].isAnnotationPresent(AnimationSchema.Nullable.class)
                                ? null
                                : value(fields[i].getGenericType());
            }
            return c.getConstructor(types).newInstance(args);
        }
    }
}
