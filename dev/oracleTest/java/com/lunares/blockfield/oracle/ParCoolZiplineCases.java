package com.lunares.blockfield.oracle;

import static org.junit.jupiter.api.Assertions.*;

import com.lunares.blockfield.common.combat.*;
import com.lunares.blockfield.common.combat.AnimationGraphState.*;
import com.lunares.blockfield.common.combat.AnimationState.*;
import com.lunares.blockfield.common.combat.AnimationTypes.*;
import java.util.*;

/** Identical U1 inputs and sample schedule in the separately transformed client/server JVMs. */
public final class ParCoolZiplineCases {
    private ParCoolZiplineCases() {}

    public record Case(AnimationGraphState graph, ParCool state) {}

    public static List<Case> checkpoints() {
        var result = new ArrayList<Case>();
        var angle = new RideZipline(-0., Math.nextUp(.125));
        for (int tick = 0; tick <= 12; tick++) {
            var input = input(tick);
            if (tick != 0) angle = ParCoolReducers.ziplineStep(angle, input, tick != 7);
            var roots = new ArrayList<Root>(AnimationGraphState.neutral().roots());
            roots.replaceAll(
                    root ->
                            root.layer() == LayerTag.PARCOOL_ACTIVE
                                    ? new Root(root.priority(), root.layer(), new NodeRef(1))
                                    : root);
            var graph =
                    new AnimationGraphState(
                            roots,
                            List.of(
                                    new Node(
                                            1,
                                            PhaseDomain.A,
                                            null,
                                            new Animator(AnimatorTag.RIDE_ZIPLINE, tick, angle))));
            var context = ParCoolNativeValues.context();
            var actions = new ArrayList<ActionState>(context.actions());
            actions.set(
                    ActionTag.RIDE_ZIPLINE.id(),
                    new ActionState(
                            ActionTag.RIDE_ZIPLINE, tick != 7, tick, 0, tick, List.of(input)));
            result.add(
                    new Case(
                            graph,
                            new ParCool(
                                    new NodeRef(1),
                                    context.passiveStart(),
                                    context.option(),
                                    context.passive(),
                                    actions,
                                    context.hangGrid(),
                                    context.diveGrid())));
        }
        return List.copyOf(result);
    }

    public static ZiplineInput input(int tick) {
        Vec3 offset = tick == 8 ? null : tick == 9 ? new Vec3(0, 0, 0) : new Vec3(.3, -.2, .9);
        return new ZiplineInput(
                offset,
                tick % 2 == 0 ? .17 : -.23,
                tick < 6 ? -.31 : .47,
                tick < 4 ? .08 : tick < 8 ? -0. : -.01,
                false,
                null);
    }

    @FunctionalInterface
    public interface Sampler {
        int[] sample(float fraction, boolean modelFirst) throws Exception;
    }

    public static List<Map<String, Object>> samples(Sampler sampler) throws Exception {
        var rows = new ArrayList<Map<String, Object>>();
        int[] expected = sampler.sample(.375f, false);
        for (float fraction : new float[] {0, 1 / 65536f, .125f, .375f, .75f, 65535 / 65536f}) {
            int[] bits = sampler.sample(fraction, false);
            assertArrayEquals(bits, sampler.sample(fraction, true), "root/model order");
            assertArrayEquals(bits, sampler.sample(fraction, false), "repeat invocation");
            for (int bit : bits) assertTrue(Float.isFinite(Float.intBitsToFloat(bit)));
            rows.add(
                    Map.of(
                            "fractionBits",
                            Float.floatToRawIntBits(fraction),
                            "rootAndPartsBits",
                            Arrays.stream(bits).boxed().toList()));
        }
        for (int fps : new int[] {30, 60, 144, 240}) {
            for (int pass = 0; pass < fps * 2; pass++)
                sampler.sample((fps - 1 - pass % fps) / (float) fps, (pass & 1) != 0);
            assertArrayEquals(expected, sampler.sample(.375f, false), "FPS/culling/pass history");
        }
        return rows;
    }
}
