package com.lunares.blockfield.oracle;

import static com.lunares.blockfield.oracle.FxPoseCases.with;

import com.lunares.blockfield.common.combat.*;
import com.lunares.blockfield.common.combat.AnimationResources.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Retained item-class counterexamples for bf24's native arm hooks and prone tail. */
public final class FxClientCases {
    private FxClientCases() {}

    public static final String ARMS = "fx-client-sbw-arm-";

    static List<MixedRendererCases.Case> cases(List<MixedRendererCases.Case> original) {
        var out = new ArrayList<>(original);
        var base =
                original.stream()
                        .filter(c -> c.name().equals("walk-attack"))
                        .findFirst()
                        .orElseThrow();
        for (String item : List.of("lunge_mine", "minigun", "m2hb", "super_star_shooter")) {
            for (boolean retained : new boolean[] {true, false}) {
                var value =
                        with(
                                with(base, "cohort", "sbw"),
                                "name",
                                ARMS + item + (retained ? "-retained" : "-live"));
                out.add(value);
            }
        }
        return out.stream().map(FxClientCases::equipment).toList();
    }

    public static MixedRendererCases.Case yawPairs(MixedRendererCases.Case base) {
        var old = base.rootVehicles().get(104L);
        var a = old.angles();
        var angles =
                new AnimationState.Angles(
                        123.456f,
                        .001f,
                        a.xRotO(),
                        a.xRot(),
                        a.yBodyRotO(),
                        a.yBodyRot(),
                        a.yHeadRotO(),
                        a.yHeadRot());
        var pairs = new AnimationState.VehicleAngles(0, 0, 179, -179, -12, 17, -179, 179, -3, 5);
        var placement = with(with(old, "angles", angles), "vehicleAngles", pairs);
        var vehicles = new TreeMap<>(base.rootVehicles());
        vehicles.put(104L, placement);
        return with(base, "rootVehicles", Collections.unmodifiableNavigableMap(vehicles));
    }

    public static String retainedItem(MixedRendererCases.Case value) {
        if (value.name().startsWith(ARMS) && value.name().endsWith("-retained"))
            return value.name()
                    .substring(ARMS.length(), value.name().length() - "-retained".length());
        return value.name().startsWith(FxPoseCases.SBW) && value.name().contains("retained-gun")
                ? "glock_17"
                : null;
    }

    private static MixedRendererCases.Case equipment(MixedRendererCases.Case value) {
        var states = new TreeMap<Long, AnimationState>();
        String item = retainedItem(value);
        value.states()
                .forEach(
                        (tick, state) -> {
                            var definition = definition(state.identity(), item);
                            var gear = with(state.equipment(), "mainHandRef", definition.ref());
                            state = with(state, "equipment", gear);
                            // LungeMine is a special arm item, not a GunItem.
                            if (value.name().startsWith(ARMS))
                                state =
                                        with(
                                                state,
                                                "sbw",
                                                with(
                                                        state.sbw(),
                                                        "gun",
                                                        item != null
                                                                && !item.equals("lunge_mine")));
                            states.put(tick, state);
                        });
        return with(value, "states", Collections.unmodifiableNavigableMap(states));
    }

    public static Definition definition(AnimationState.Identity identity, String item) {
        String text = item == null ? "empty" : "{count:1,id:\"superbwarfare:" + item + "\"}";
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        var digest = Digest.hash(bytes);
        return new Definition(
                new ItemRef(
                        identity.resourceGeneration(),
                        identity.targetEpoch(),
                        digest.a() >>> 1,
                        digest),
                new Bytes(bytes),
                List.of());
    }
}
