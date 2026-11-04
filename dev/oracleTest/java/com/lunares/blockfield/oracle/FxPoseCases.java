package com.lunares.blockfield.oracle;

import com.lunares.blockfield.common.combat.*;
import com.lunares.blockfield.common.combat.AnimationGraphState.*;
import com.lunares.blockfield.common.combat.AnimationTypes.*;
import java.util.*;

/** Final-review counterexamples, kept separate from the original U10 sample inventory. */
public final class FxPoseCases {
    private FxPoseCases() {}

    static List<MixedRendererCases.Case> cases(List<MixedRendererCases.Case> original)
            throws Exception {
        var out = new ArrayList<MixedRendererCases.Case>();
        var elytra =
                original.stream().filter(c -> c.name().equals("elytra")).findFirst().orElseThrow();
        var states = new TreeMap<Long, AnimationState>();
        for (var entry : elytra.states().entrySet()) {
            var placement = entry.getValue().placement();
            placement =
                    with(
                            placement,
                            "deltaMovementOnPreviousTick",
                            new AnimationState.Vec3(.8, -.2, .3));
            placement = with(placement, "deltaMovement", new AnimationState.Vec3(1.2, -.4, -.1));
            states.put(entry.getKey(), with(entry.getValue(), "placement", placement));
        }
        var fast = with(elytra, "name", "elytra-fast-retained-velocity");
        out.add(with(fast, "states", Collections.unmodifiableNavigableMap(states)));

        var base =
                original.stream()
                        .filter(c -> c.name().equals("roll+pa-fire"))
                        .findFirst()
                        .orElseThrow();
        for (var animator : List.of(new JumpCharging(), new ChargeJump())) {
            for (boolean pa : new boolean[] {true, false}) {
                var frame = ParCoolPresentationCases.frame(animator, 3, true);
                var stack =
                        PaGraph.Stack.create(
                                base.catalog(),
                                MixedRendererCases.POLICY,
                                PaGraphNativeCases.SOURCES,
                                MixedRendererCases.WINNER,
                                1000);
                if (pa) {
                    stack.once(
                            LayerTag.PA_95,
                            PaGraphNativeCases.clip("charging_fire", false),
                            false,
                            7001);
                    for (int i = 0; i < 4; i++) stack.tick();
                }
                var graph = stack.freeze(frame.graph());
                var value =
                        with(
                                base,
                                "name",
                                "fx-pose-"
                                        + animator.getClass().getSimpleName()
                                        + (pa ? "+pa-fire" : "+fallback"));
                var assets = taczAssets(base.catalog(), pa);
                var display = assets.resolve("fixture:fx-pose-gun", null);
                var tacz =
                        new AnimationState.Tacz(
                                pa ? EvaluatorKind.PA : EvaluatorKind.NATIVE_FALLBACK,
                                base.catalog().named("tacz.gun", "fixture:fx-pose-gun"),
                                null,
                                display.ref(),
                                false,
                                .7f,
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
                var checkpoints = new TreeMap<Long, AnimationState>();
                base.states()
                        .forEach((tick, state) -> checkpoints.put(tick, with(state, "tacz", tacz)));
                value = with(value, "states", Collections.unmodifiableNavigableMap(checkpoints));
                java.util.function.Function<Time, ObserverReducer.Result> presentation =
                        a ->
                                new ObserverReducer.Result(
                                        new PaProjection.Result(graph, List.of()),
                                        a.tick(),
                                        null,
                                        frame.state(),
                                        List.of());
                out.add(with(value, "presentation", presentation));
            }
        }
        // Client review P0-3: bf24's model tail reads the parachute bit, the main hand and the
        // pose/swimming state; FxPoseLive (client) makes the live entity contradict S[ceil(A)].
        var walk = named(original, "walk-attack");
        out.add(sbw(walk, "parachute-retained-open", s -> with(s, "parachuteOpen", true), v -> v));
        out.add(sbw(walk, "parachute-live-open", s -> s, v -> v));
        var swim = named(original, "swim");
        java.util.function.UnaryOperator<AnimationState.Vanilla> prone =
                v ->
                        with(
                                v,
                                "flags",
                                with(
                                        with(with(v.flags(), "swimming", false), "inWater", false),
                                        "onGround",
                                        true));
        out.add(sbw(swim, "prone-live-gun", s -> s, prone));
        out.add(sbw(swim, "prone-retained-gun", s -> with(s, "gun", true), prone));
        // The live hand holds no gun and the fixture retains no main-hand definition: unavailable.
        out.add(sbw(swim, "prone-retained-gun-live-empty", s -> with(s, "gun", true), prone));
        out.add(sbw(swim, "swim-retained-gun", s -> with(s, "gun", true), v -> v));
        out.add(with(named(original, "passenger-seat"), "name", SBW + "seat-root"));
        return List.copyOf(out);
    }

    /** The {@code fx-pose-sbw-} prefix of the cases whose live client state FxPoseLive sets. */
    public static final String SBW = "fx-pose-sbw-";

    private static MixedRendererCases.Case named(
            List<MixedRendererCases.Case> original, String name) {
        return original.stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
    }

    /** A copy of {@code base} whose every S[t] carries the rewritten SBW and vanilla inputs. */
    private static MixedRendererCases.Case sbw(
            MixedRendererCases.Case base,
            String name,
            java.util.function.UnaryOperator<AnimationState.Sbw> sbw,
            java.util.function.UnaryOperator<AnimationState.Vanilla> vanilla) {
        var states = new TreeMap<Long, AnimationState>();
        base.states()
                .forEach(
                        (tick, state) ->
                                states.put(
                                        tick,
                                        with(
                                                with(state, "sbw", sbw.apply(state.sbw())),
                                                "vanilla",
                                                vanilla.apply(state.vanilla()))));
        var value = with(with(base, "cohort", "sbw"), "name", SBW + name);
        return with(value, "states", Collections.unmodifiableNavigableMap(states));
    }

    /** Additive fixture plumbing: only these cases supply a retained TaCZ display. */
    public static TaczAssets taczAssets(MixedRendererCases.Case value) {
        var tacz = value.states().firstEntry().getValue().tacz();
        return tacz == null
                ? null
                : taczAssets(value.catalog(), tacz.evaluatorKind() == EvaluatorKind.PA);
    }

    private static TaczAssets taczAssets(PaGraph.Catalog catalog, boolean pa) {
        String display = pa ? "fixture:fx-pose-pa" : "fixture:fx-pose-fallback";
        var assets = new TaczAssets(catalog, Map.of("fixture:fx-pose-gun", display));
        assets.register(display, "fixture:fx-pose.player_animation", false, "rifle", pa, Map.of());
        return assets;
    }

    /** Test-only record copying keeps each case on the frozen U1 record layout. */
    static <T extends Record> T with(T original, String field, Object replacement) {
        try {
            var components = original.getClass().getRecordComponents();
            Class<?>[] types = new Class<?>[components.length];
            Object[] values = new Object[components.length];
            boolean found = false;
            for (int i = 0; i < components.length; i++) {
                types[i] = components[i].getType();
                boolean selected = components[i].getName().equals(field);
                found |= selected;
                values[i] = selected ? replacement : components[i].getAccessor().invoke(original);
            }
            if (!found) throw new IllegalArgumentException("Unknown record field " + field);
            @SuppressWarnings("unchecked")
            T result = (T) original.getClass().getDeclaredConstructor(types).newInstance(values);
            return result;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
