package com.lunares.blockfield.common.combat;

import static com.lunares.blockfield.common.combat.AnimationTypes.*;

import java.util.List;

/** Geometry policy is server-owned; R may scope only the two complete lower-layer subtrees. */
public record AnimationPolicy(
        long generation,
        PhaseDomain lowerBodyDomain,
        boolean enableAnimation,
        boolean enableFallingAnimation,
        boolean enableFastRunLean,
        boolean smootherCrawlAnimation,
        List<AnimatorTag> registeredAnimators) {
    public AnimationPolicy {
        AnimationSchema.nonnegative(generation, "policy generation");
        java.util.Objects.requireNonNull(lowerBodyDomain);
        registeredAnimators = List.copyOf(registeredAnimators);
        if (!enableAnimation
                || !enableFallingAnimation
                || !enableFastRunLean
                || !smootherCrawlAnimation)
            throw new IllegalArgumentException("Combat animation policy must be enabled");
        if (!registeredAnimators.equals(registered()))
            throw new IllegalArgumentException("Animator policy registry mismatch");
    }

    public static List<AnimatorTag> registered() {
        return List.of(AnimatorTag.values()).subList(0, 26);
    }

    public PhaseDomain domain(LayerTag root) {
        return root == LayerTag.PA_41 || root == LayerTag.PA_93 ? lowerBodyDomain : PhaseDomain.A;
    }
}
