package com.lunares.blockfield.client.combat;

import static org.junit.jupiter.api.Assertions.*;

import com.lunares.blockfield.common.combat.AnimationTypes.AdjustmentSource;
import com.lunares.blockfield.common.combat.AnimationTypes.LayerTag;
import com.lunares.blockfield.common.combat.PaAdjustments;
import com.lunares.blockfield.common.combat.PaGraph;
import dev.kosmx.playerAnim.api.TransformType;
import dev.kosmx.playerAnim.core.util.Vec3f;
import java.util.Optional;
import org.junit.jupiter.api.Test;

// P3 residuals of the anim-pipeline perf review: sample-only escape-hatch guards (P3-1) and
// the production-config equality claim with a live adjustment source (P3-3).
class PaSampleOnlyGuardsTest {
    static PaGraph.Sources none() {
        return id -> part -> Optional.empty();
    }

    static PaGraph.Stack sampleOnly(PaGraph.Sources sources) {
        return PaGraph.Stack.sampleOnly(
                ExactFixtures.graph(), new PaGraph.Catalog(4242), ExactFixtures.policy(), sources);
    }

    @Test
    void sampleOnlyRefusesEscapeHatches() {
        var stack = sampleOnly(none());
        assertThrows(IllegalStateException.class, stack::nativeStack);
        assertThrows(IllegalStateException.class, stack::catalog);
        assertThrows(IllegalStateException.class, () -> stack.layer(LayerTag.PA_96));
        assertThrows(IllegalStateException.class, () -> stack.associated(PaGraph.ROTATION_KEY));
    }

    @Test
    void canonicalStackKeepsEscapeHatches() {
        var stack =
                PaGraph.Stack.create(
                        new PaGraph.Catalog(4243),
                        ExactFixtures.policy(),
                        none(),
                        LayerTag.PA_96,
                        1);
        assertNotNull(stack.nativeStack());
        assertNotNull(stack.catalog());
        assertNotNull(stack.layer(LayerTag.PA_96));
    }

    @Test
    void sampleOnlyMatchesCanonicalWithLiveAdjustmentSource() {
        var inputs =
                new PaAdjustments.Inputs(
                        0.5f, 179, 179, 224, 224, 10, 10, false, false, false, false, false);
        PaGraph.Sources sources =
                id -> {
                    if (id == AdjustmentSource.HELD_WEAPON_POSE) return PaAdjustments.tacz(inputs);
                    if (id == AdjustmentSource.LR_BODY_POSE) return PaAdjustments.lr(inputs);
                    return part -> Optional.empty();
                };
        var graph = ExactFixtures.graph();
        var policy = ExactFixtures.policy();
        var canonical =
                PaGraph.Stack.materialize(graph, new PaGraph.Catalog(4244), policy, sources);
        var sampled = sampleOnly(sources);
        canonical.sample(0.5f);
        sampled.sample(0.5f);
        sampled.requireFinite();
        for (String part : PaGraph.RIG_PARTS)
            for (TransformType type : TransformType.values()) {
                float base = type == TransformType.SCALE ? 1 : 0;
                Vec3f a = canonical.transform(part, type, new Vec3f(base, base, base));
                Vec3f b = sampled.transform(part, type, new Vec3f(base, base, base));
                assertEquals(a.getX(), b.getX(), part + " " + type);
                assertEquals(a.getY(), b.getY(), part + " " + type);
                assertEquals(a.getZ(), b.getZ(), part + " " + type);
            }
    }
}
