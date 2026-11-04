package com.lunares.blockfield.common.combat;

import static com.lunares.blockfield.common.combat.AnimationTypes.*;

import com.lunares.blockfield.common.combat.AnimationResources.*;
import java.util.*;

/** Shared two-time boundary, completeness and restoration rules; never queries a live entity. */
public final class AnimationResolver {
    private AnimationResolver() {}

    public enum Status {
        EXACT,
        STATE_UNAVAILABLE,
        GENERATION_UNAVAILABLE,
        RESOURCE_UNAVAILABLE,
        SEED_UNAVAILABLE,
        REPORT_UNAVAILABLE
    }

    public record Result(Status status, AnimationRecipe recipe, String reason) {
        public boolean exact() {
            return status == Status.EXACT;
        }

        private static Result unavailable(Status status, String reason) {
            return new Result(status, null, reason);
        }
    }

    public record Request(
            AnimationState.Identity identity,
            long high,
            Time r,
            Time a,
            AnimationEvent.ObserverSeed seed,
            AnimationStartReport.Victim starts,
            AnimationGraphState projectedGraph) {}

    public static Result resolve(
            Request request,
            List<AnimationState> history,
            Store resources,
            AnimationPolicy policy) {
        Objects.requireNonNull(request.identity());
        if (policy.generation() != request.identity().policyGeneration())
            return Result.unavailable(Status.GENERATION_UNAVAILABLE, "policy_identity");
        if (request.r().compareTo(request.a()) > 0
                || request.a().compareTo(Time.whole(request.high())) > 0)
            return Result.unavailable(Status.REPORT_UNAVAILABLE, "clock_order");
        var rEndpoint = AnimationTiming.endpoint(request.r());
        var aEndpoint = AnimationTiming.endpoint(request.a());
        AnimationState placement = null, animation = null;
        Set<Long> ticks = new HashSet<>();
        for (AnimationState state : history) {
            if (!ticks.add(state.serverTick()))
                return Result.unavailable(Status.STATE_UNAVAILABLE, "duplicate_tick");
            if (state.serverTick() >= request.r().tick()
                    && state.serverTick() <= aEndpoint.tick()
                    && !state.identity().sameGeneration(request.identity()))
                return Result.unavailable(Status.GENERATION_UNAVAILABLE, "boundary_between_R_A");
            if (state.serverTick() == rEndpoint.tick()) placement = state;
            if (state.serverTick() == aEndpoint.tick()) animation = state;
        }
        if (placement == null || animation == null)
            return Result.unavailable(Status.STATE_UNAVAILABLE, "missing_complete_endpoint");
        if (!request.identity().sameGeneration(placement.identity())
                || !request.identity().sameGeneration(animation.identity()))
            return Result.unavailable(Status.GENERATION_UNAVAILABLE, "endpoint_generation");
        var seed = request.seed();
        var starts = request.starts();
        if (seed == null
                || starts == null
                || !seed.identity().sameGeneration(request.identity())
                || seed.ref().contentId() != starts.seedRef())
            return Result.unavailable(Status.SEED_UNAVAILABLE, "seed_identity");
        if (starts.entityId() != request.identity().entityId()
                || starts.targetEpoch() != request.identity().targetEpoch()
                || starts.seatGeneration() != request.identity().seatGeneration()
                || starts.teleportGeneration() != request.identity().teleportGeneration()
                || starts.subscriptionGeneration() != request.identity().subscriptionGeneration())
            return Result.unavailable(Status.GENERATION_UNAVAILABLE, "victim_identity");
        if (!seed.checkpoint().identity().sameGeneration(seed.identity())
                || seed.ref().targetEpoch() != seed.identity().targetEpoch()
                || seed.ref().resourceGeneration() != seed.identity().resourceGeneration())
            return Result.unavailable(Status.SEED_UNAVAILABLE, "seed_checkpoint_scope");
        if (seed.eventPrefix() > starts.appliedEventSequence())
            return Result.unavailable(Status.REPORT_UNAVAILABLE, "seed_prefix");
        try {
            AnimationStartReport.validate(new AnimationStartReport(List.of(starts)));
            AnimationGraphValidator.validateComplete(placement.graph(), policy);
            AnimationGraphValidator.validateComplete(animation.graph(), policy);
            AnimationGraphValidator.validateComplete(seed.graph(), policy);
            for (var retained : seed.starts())
                AnimationStartReport.age(retained, request.r(), request.a(), policy);

            if (!presentationDigest(seed, request.projectedGraph(), starts)
                    .equals(starts.presentationDigest()))
                return Result.unavailable(Status.REPORT_UNAVAILABLE, "presentation_digest");
            Map<Integer, AnimationGraphState.Node> nodes =
                    AnimationGraphValidator.validateComplete(request.projectedGraph(), policy);
            Map<AnimationStartReport.StartKey, AnimationStartReport.Start> reported =
                    new HashMap<>();
            for (var start : starts.starts()) {
                AnimationStartReport.age(start, request.r(), request.a(), policy);
                reported.put(
                        new AnimationStartReport.StartKey(start.eventSequence(), start.nodeId()),
                        start);
            }
            for (var node : nodes.values())
                if (node.start() != null
                        && !reported.containsKey(node.start())
                        && seed.starts().stream()
                                .noneMatch(
                                        s ->
                                                s.eventSequence() == node.start().eventSequence()
                                                        && s.nodeId() == node.start().nodeId()))
                    return Result.unavailable(
                            Status.REPORT_UNAVAILABLE, "missing_contributing_start");
            AnimationSchema.validateFinite(placement);
            AnimationSchema.validateFinite(animation);
            AnimationSchema.validateFinite(seed);
            Set<ResourceRef> refs = new HashSet<>();
            refs.addAll(AnimationSchema.rememberedReferences(placement));
            refs.addAll(AnimationSchema.rememberedReferences(animation));
            refs.addAll(AnimationSchema.rememberedReferences(seed));
            refs.addAll(AnimationSchema.rememberedReferences(request.projectedGraph()));
            try (var ignored = resources.pin(List.copyOf(refs))) {
                Digest decoded =
                        Digest.hash(
                                AnimationSchema.encode(
                                        new RecipeIdentity(
                                                request.identity(),
                                                request.r(),
                                                request.a(),
                                                request.high(),
                                                rEndpoint.tick(),
                                                aEndpoint.tick(),
                                                AnimationSchema.digest(placement),
                                                AnimationSchema.digest(animation),
                                                AnimationSchema.digest(seed),
                                                AnimationSchema.digest(request.projectedGraph()),
                                                Digest.hash(AnimationSchema.encode(starts)))));
                return new Result(
                        Status.EXACT,
                        new AnimationRecipe(
                                request.identity(),
                                request.high(),
                                request.r(),
                                request.a(),
                                rEndpoint.tick(),
                                rEndpoint.partial(),
                                aEndpoint.tick(),
                                aEndpoint.partial(),
                                placement.placement(),
                                animation,
                                seed,
                                request.projectedGraph(),
                                starts,
                                decoded),
                        "exact");
            }
        } catch (IllegalArgumentException | ArithmeticException e) {
            return Result.unavailable(Status.RESOURCE_UNAVAILABLE, e.getMessage());
        }
    }

    /**
     * The digest includes decoded dependency identities, never original unencoded server objects.
     */
    public record ResolvedInputs(
            AnimationState placement,
            AnimationState animation,
            AnimationEvent.ObserverSeed seed,
            AnimationGraphState graph,
            AnimationStartReport.Victim starts) {}

    public record RecipeIdentity(
            AnimationState.Identity identity,
            Time r,
            Time a,
            long high,
            long placementTick,
            long animationTick,
            Digest placementState,
            Digest animationState,
            Digest seed,
            Digest graph,
            Digest report) {}

    public record PresentationClosure(
            SeedRef seed,
            long appliedSequence,
            Digest eventPrefixDigest,
            AnimationGraphState graph,
            List<AnimationStartReport.Start> starts) {
        public PresentationClosure {
            starts = List.copyOf(starts);
        }
    }

    public static Digest presentationDigest(
            AnimationEvent.ObserverSeed seed,
            AnimationGraphState graph,
            AnimationStartReport.Victim starts) {
        return Digest.hash(
                AnimationSchema.encode(
                        new PresentationClosure(
                                seed.ref(),
                                starts.appliedEventSequence(),
                                starts.eventPrefixDigest(),
                                graph,
                                starts.starts())));
    }

    public interface Reducer<S> {
        void restore(S state);

        void stage(long tick, NativeStage stage, List<AnimationEvent.Decision> decisions);

        S freeze(long tick);
    }

    /**
     * Importing a complete checkpoint replaces derivation. It never repeats that checkpoint's tick.
     */
    public static <S> S replay(
            long baseTick,
            S base,
            long targetTick,
            Map<Long, S> complete,
            Map<Long, List<AnimationEvent.Decision>> journal,
            Reducer<S> reducer) {
        if (baseTick < 0 || targetTick < baseTick)
            throw new IllegalArgumentException("Invalid replay interval");
        long restoredTick = baseTick;
        S restored = base;
        // The newest complete checkpoint in range wins regardless of map iteration order,
        // so server and client derivations with different insertion orders agree on one base.
        for (var entry : complete.entrySet())
            if (entry.getKey() >= baseTick
                    && entry.getKey() <= targetTick
                    && entry.getKey() >= restoredTick) {
                restoredTick = entry.getKey();
                restored = entry.getValue();
            }
        if (targetTick - restoredTick > 40)
            throw new IllegalArgumentException("Missing bounded continuation seed");
        reducer.restore(restored);
        S result = restored;
        for (long tick = restoredTick; tick < targetTick; ) {
            tick = Math.addExact(tick, 1);
            S checkpoint = complete.get(tick);
            if (checkpoint != null) {
                reducer.restore(checkpoint);
                result = checkpoint;
                continue;
            }
            List<AnimationEvent.Decision> decisions = journal.get(tick);
            if (decisions == null) throw new IllegalArgumentException("Missing reducer step");
            long sequence = -1;
            int stage = -1;
            for (var decision : decisions) {
                if (decision.id().serverTick() != tick
                        || decision.id().nativeStage().id() < stage
                        || decision.id().eventSequence() <= sequence)
                    throw new IllegalArgumentException("Unordered decision journal");
                stage = decision.id().nativeStage().id();
                sequence = decision.id().eventSequence();
            }
            for (NativeStage current : NativeStage.values()) {
                List<AnimationEvent.Decision> selected =
                        decisions.stream().filter(d -> d.id().nativeStage() == current).toList();
                reducer.stage(tick, current, selected);
            }
            result = reducer.freeze(tick);
        }
        return result;
    }
}
