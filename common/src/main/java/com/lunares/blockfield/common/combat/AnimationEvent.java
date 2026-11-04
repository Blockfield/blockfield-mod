package com.lunares.blockfield.common.combat;

import com.lunares.blockfield.common.combat.AnimationResources.*;
import com.lunares.blockfield.common.combat.AnimationSchema.Nullable;
import com.lunares.blockfield.common.combat.AnimationTypes.*;
import java.util.List;
import java.util.Objects;

public record AnimationEvent(
        AnimationState.Identity identity,
        EventId id,
        long predecessorEventSequence,
        NativeFamily family,
        EventKind kind,
        NativeStage broadcastStage,
        long acceptedNs,
        long broadcastNs,
        SeedRef seed,
        Patch patch,
        List<Definition> dependencies,
        NativePayload nativePayload) {
    public AnimationEvent {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(family, "family");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(broadcastStage, "broadcastStage");
        AnimationSchema.nonnegative(predecessorEventSequence, "predecessor event");
        if (acceptedNs < 0 || broadcastNs < 0)
            throw new IllegalArgumentException("Negative event timestamps");
        Objects.requireNonNull(seed, "seed");
        Objects.requireNonNull(patch, "patch");
        dependencies = List.copyOf(dependencies);
        AnimationSchema.count(dependencies.size(), AnimationSchema.MAX_NODES, "dependencies");
        Objects.requireNonNull(nativePayload, "nativePayload");
    }

    /**
     * Per-target-life sequences start at 1; the first event names predecessor 0 (genesis). Sequence
     * 0 is unencodable: a (0, 0) pair would name itself its own predecessor and deadlock causality
     * holds, so the packet codec requires sequence &gt; predecessor.
     */
    public record EventId(long serverTick, long eventSequence, NativeStage nativeStage) {
        public EventId {
            AnimationSchema.nonnegative(serverTick, "event tick");
            AnimationSchema.nonnegative(eventSequence, "event sequence");
            Objects.requireNonNull(nativeStage, "nativeStage");
        }
    }

    public record NativePayload(DefinitionRef payloadType, Bytes value) {
        public NativePayload {
            Objects.requireNonNull(payloadType, "payloadType");
            Objects.requireNonNull(value, "value");
        }
    }

    public record Patch(
            LayerTag layer,
            StartKind startKind,
            Outcome outcome,
            AnimationGraphState replacement,
            List<AnimationState.ActionState> actionInputs,
            @Nullable AnimationState.Equipment equipment,
            @Nullable AnimationState.Option options,
            @Nullable AnimationState.Tacz tacz,
            @Nullable AnimationState.Lr lr,
            @Nullable AnimationState.Create create,
            @Nullable AnimationState.Sbw sbw,
            @Nullable AnimationState.Vanilla vanilla,
            @Nullable AnimationState.Stance stance) {
        public Patch {
            Objects.requireNonNull(layer, "layer");
            Objects.requireNonNull(startKind, "startKind");
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(replacement, "replacement");
            actionInputs = List.copyOf(actionInputs);
            AnimationSchema.count(actionInputs.size(), AnimationSchema.MAX_NODES, "actionInputs");
        }
    }

    public record Decision(
            EventId id,
            LayerTag layer,
            StartKind startKind,
            Outcome outcome,
            long predecessorEventSequence,
            @Nullable AnimationGraphState.NodeRef predecessor,
            InputRef input,
            Patch patch,
            int transitionOrdinal) {
        public Decision {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(layer, "layer");
            Objects.requireNonNull(startKind, "startKind");
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(input, "input");
            Objects.requireNonNull(patch, "patch");
        }
    }

    public record ObserverSeed(
            AnimationState.Identity identity,
            SeedRef ref,
            @Nullable SeedRef predecessor,
            AnimationState checkpoint,
            AnimationGraphState graph,
            long inputPrefix,
            long eventPrefix,
            long decisionPrefix,
            List<AnimationStartReport.Start> starts,
            List<Definition> dependencies,
            Digest reducerDigest) {
        public ObserverSeed {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(ref, "ref");
            Objects.requireNonNull(checkpoint, "checkpoint");
            Objects.requireNonNull(graph, "graph");
            starts = List.copyOf(starts);
            AnimationSchema.count(starts.size(), AnimationSchema.MAX_NODES, "starts");
            dependencies = List.copyOf(dependencies);
            AnimationSchema.count(dependencies.size(), AnimationSchema.MAX_NODES, "dependencies");
            Objects.requireNonNull(reducerDigest, "reducerDigest");
        }
    }
}
