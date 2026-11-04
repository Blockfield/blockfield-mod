package com.lunares.blockfield.client.combat;

import com.lunares.blockfield.common.combat.AnimationPolicy;
import com.lunares.blockfield.common.combat.AnimationResources;
import com.lunares.blockfield.common.combat.AnimationResources.Digest;
import com.lunares.blockfield.common.combat.AnimationState;
import com.lunares.blockfield.common.combat.LrAssets;
import com.lunares.blockfield.common.combat.TaczAssets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Activated resource generations the observer presents with: the server-owned policy and the
 * retained TaCZ/LR effective views that share one PA catalog. U9's READY/activation protocol
 * installs them ({@link ClientAnimationResources}); until a generation is installed, its remotes
 * keep native animation and their victim rows are {@code resource_unavailable}. Main-thread owned;
 * cleared with the connection.
 */
public final class ClientAnimationAssets {
    public record Generation(
            long resourceGeneration, AnimationPolicy policy, TaczAssets tacz, LrAssets lr) {
        public Generation {
            Objects.requireNonNull(policy, "policy");
            Objects.requireNonNull(lr, "lr");
            if (lr.catalog().generation() != resourceGeneration
                    || tacz != null && tacz.catalog() != lr.catalog())
                throw new IllegalArgumentException("One generation catalog per resource view");
        }
    }

    /** The digests a READY-validated generation was admitted with (U9). */
    private record Admitted(Digest compatibility, Digest manifest) {}

    private static final Map<Long, Generation> GENERATIONS = new HashMap<>();
    private static final Map<Long, Admitted> ADMITTED = new HashMap<>();
    private static long latest = -1;

    private ClientAnimationAssets() {}

    public static void install(Generation generation) {
        latest = generation.resourceGeneration();
        GENERATIONS.put(generation.resourceGeneration(), generation);
        ADMITTED.remove(generation.resourceGeneration());
    }

    /**
     * U9 READY install: the generation reproduced the server manifest; only records stamped with
     * the same compatibility and manifest digests may use it.
     */
    public static void install(Generation generation, Digest compatibility, Digest manifest) {
        install(generation);
        ADMITTED.put(generation.resourceGeneration(), new Admitted(compatibility, manifest));
    }

    /** The activated generation a stamped identity names, or null when not (yet) installed. */
    public static Generation get(AnimationState.Identity identity) {
        var generation = GENERATIONS.get(identity.resourceGeneration());
        if (generation == null || generation.policy().generation() != identity.policyGeneration())
            return null;
        var admitted = ADMITTED.get(identity.resourceGeneration());
        if (admitted != null
                && (!admitted.compatibility().equals(identity.compatibilityDigest())
                        || !admitted.manifest().equals(identity.manifestDigest()))) return null;
        return generation;
    }

    /** Retire only after the scene has enumerated every live reference, including old assets. */
    static void retireExcept(java.util.Set<Long> referenced) {
        var retained = new java.util.HashSet<>(referenced);
        retained.add(latest);
        boolean changed;
        do {
            changed = false;
            for (var generation : GENERATIONS.values()) {
                if (!retained.contains(generation.resourceGeneration())) continue;
                for (var definition : generation.lr().catalog().definitions())
                    for (var dependency : definition.dependencies())
                        changed |= retained.add(dependency.resourceGeneration());
            }
        } while (changed);
        GENERATIONS.keySet().retainAll(retained);
        ADMITTED.keySet().retainAll(GENERATIONS.keySet());
        ClientAnimationResources.retainTables(generations());
    }

    static void references(Record value, java.util.Set<AnimationResources.ResourceRef> refs) {
        if (value != null)
            refs.addAll(com.lunares.blockfield.common.combat.AnimationSchema.references(value));
    }

    static java.util.Set<Long> generations() {
        return java.util.Set.copyOf(GENERATIONS.keySet());
    }

    public static void clear() {
        GENERATIONS.clear();
        ADMITTED.clear();
        latest = -1;
    }
}
