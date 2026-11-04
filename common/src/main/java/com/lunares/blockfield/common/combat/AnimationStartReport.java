package com.lunares.blockfield.common.combat;

import com.lunares.blockfield.common.combat.AnimationResources.*;
import com.lunares.blockfield.common.combat.AnimationTypes.*;
import java.util.List;
import java.util.Objects;

public record AnimationStartReport(List<Victim> victims) {
    public AnimationStartReport {
        victims = List.copyOf(victims);
        AnimationSchema.count(victims.size(), AnimationSchema.MAX_VICTIMS, "victims");
    }

    public record StartKey(long eventSequence, int nodeId) {}

    public record Victim(
            int entityId,
            long targetEpoch,
            long seatGeneration,
            long teleportGeneration,
            long subscriptionGeneration,
            long seedRef,
            long appliedEventSequence,
            Digest eventPrefixDigest,
            List<Start> starts,
            List<Application> applications,
            Digest presentationDigest) {
        public Victim {
            Objects.requireNonNull(eventPrefixDigest, "eventPrefixDigest");
            starts = List.copyOf(starts);
            AnimationSchema.count(starts.size(), AnimationSchema.MAX_NODES, "starts");
            applications = List.copyOf(applications);
            AnimationSchema.count(applications.size(), AnimationSchema.MAX_NODES, "applications");
            Objects.requireNonNull(presentationDigest, "presentationDigest");
        }
    }

    /**
     * One capsule the observer applied after its seed's event prefix, whether or not it created a
     * node that is still reachable (a missing clip, an idle-layer stop, a Create exit or an expired
     * actor still fixes the replay bucket). The replay template is derived from the delivered
     * capsule, never from this row ({@code ObserverReducer.template}).
     */
    public record Application(long eventSequence, ObserverApply proof) {
        public Application {
            Objects.requireNonNull(proof, "proof");
        }
    }

    public record Start(
            long eventSequence,
            int nodeId,
            LayerTag layerTag,
            StartKind startKind,
            AnchorClass anchorClass,
            PhaseDomain phaseDomain,
            long phaseAnchor,
            int u0,
            int predecessorNodeId,
            Proof proof) {
        public Start {
            Objects.requireNonNull(layerTag, "layerTag");
            Objects.requireNonNull(startKind, "startKind");
            Objects.requireNonNull(anchorClass, "anchorClass");
            Objects.requireNonNull(phaseDomain, "phaseDomain");
            Objects.requireNonNull(proof, "proof");
        }
    }

    public sealed interface Proof permits ObserverApply, CanonicalTick, ProjectedSuccessor {}

    public record ObserverApply(
            long applyFrameId,
            long applyRevision,
            long mappingId,
            Time sW,
            Time applyR,
            Time applyA,
            long applyH,
            long applyEventPrefix)
            implements Proof {
        public ObserverApply {
            Objects.requireNonNull(sW, "sW");
            Objects.requireNonNull(applyR, "applyR");
            Objects.requireNonNull(applyA, "applyA");
        }
    }

    public record CanonicalTick(long decisionTick, NativeStage decisionStage) implements Proof {
        public CanonicalTick {
            Objects.requireNonNull(decisionStage, "decisionStage");
        }
    }

    public record ProjectedSuccessor(
            long decisionTick, NativeStage decisionStage, long inputRef, int transitionOrdinal)
            implements Proof {
        public ProjectedSuccessor {
            Objects.requireNonNull(decisionStage, "decisionStage");
        }
    }

    public static void validate(AnimationStartReport report) {
        AnimationSchema.count(report.victims().size(), AnimationSchema.MAX_VICTIMS, "victims");
        Victim previous = null;
        for (Victim victim : report.victims()) {
            if (previous != null
                    && (previous.entityId() > victim.entityId()
                            || previous.entityId() == victim.entityId()
                                    && previous.targetEpoch() >= victim.targetEpoch()))
                throw new IllegalArgumentException("Unsorted/duplicate victims");
            AnimationSchema.nonnegative(victim.entityId(), "entity id");
            AnimationSchema.nonnegative(victim.targetEpoch(), "target epoch");
            AnimationSchema.nonnegative(victim.seatGeneration(), "seat generation");
            AnimationSchema.nonnegative(victim.teleportGeneration(), "teleport generation");
            AnimationSchema.nonnegative(victim.subscriptionGeneration(), "subscription generation");
            AnimationSchema.nonnegative(victim.seedRef(), "seed ref");
            AnimationSchema.nonnegative(victim.appliedEventSequence(), "applied event sequence");
            AnimationSchema.count(victim.starts().size(), AnimationSchema.MAX_NODES, "starts");
            Start prior = null;
            java.util.Set<Integer> nodes = new java.util.HashSet<>();
            for (Start start : victim.starts()) {
                validateStart(start);
                if (start.proof() instanceof ObserverApply apply
                        && apply.applyEventPrefix() > victim.appliedEventSequence())
                    throw new IllegalArgumentException("Application prefix from the future");
                if (!nodes.add(start.nodeId())
                        || prior != null
                                && (prior.eventSequence() > start.eventSequence()
                                        || prior.eventSequence() == start.eventSequence()
                                                && prior.nodeId() >= start.nodeId()))
                    throw new IllegalArgumentException("Unsorted/duplicate starts");
                if (start.eventSequence() > victim.appliedEventSequence())
                    throw new IllegalArgumentException("Start outside applied prefix");
                prior = start;
            }
            long applied = 0;
            for (Application application : victim.applications()) {
                validateApplication(application);
                if (application.eventSequence() <= applied
                        || application.proof().applyEventPrefix() > victim.appliedEventSequence())
                    throw new IllegalArgumentException("Unsorted/out-of-prefix applications");
                applied = application.eventSequence();
            }
            previous = victim;
        }
    }

    public static void validateApplication(Application application) {
        if (application.eventSequence() <= 0)
            throw new IllegalArgumentException("Application needs an event sequence");
        validateObserverApply(application.proof(), application.eventSequence());
    }

    private static void validateObserverApply(ObserverApply proof, long eventSequence) {
        AnimationSchema.nonnegative(proof.applyFrameId(), "apply frame");
        AnimationSchema.nonnegative(proof.applyRevision(), "apply revision");
        AnimationSchema.nonnegative(proof.mappingId(), "mapping id");
        AnimationSchema.nonnegative(proof.applyH(), "apply H");
        AnimationSchema.nonnegative(proof.applyEventPrefix(), "apply prefix");
        if (proof.applyR().compareTo(proof.applyA()) > 0
                || proof.applyA().compareTo(Time.whole(proof.applyH())) > 0
                || proof.applyEventPrefix() < eventSequence)
            throw new IllegalArgumentException("Invalid application clocks/prefix");
    }

    public static void validateStart(Start start) {
        AnimationSchema.nonnegative(start.eventSequence(), "event sequence");
        if (start.nodeId() <= 0
                || start.predecessorNodeId() < 0
                || start.predecessorNodeId() == start.nodeId())
            throw new IllegalArgumentException("Invalid start node/predecessor");
        AnimationSchema.nonnegative(start.phaseAnchor(), "phase anchor");
        AnimationSchema.nonnegative(start.u0(), "initial age");
        switch (start.proof()) {
            case ObserverApply proof -> {
                if (start.anchorClass() != AnchorClass.OBSERVER_APPLY)
                    throw new IllegalArgumentException("Wrong start proof class");
                validateObserverApply(proof, start.eventSequence());
                Time phase = start.phaseDomain() == PhaseDomain.A ? proof.applyA() : proof.applyR();
                if (start.phaseAnchor() != phase.tick())
                    throw new IllegalArgumentException("Fractional/wrong application anchor");
            }
            case CanonicalTick proof -> {
                if (start.anchorClass() != AnchorClass.CANONICAL_TICK
                        || start.phaseAnchor() != proof.decisionTick())
                    throw new IllegalArgumentException("Invalid canonical anchor");
            }
            case ProjectedSuccessor proof -> {
                if (start.anchorClass() != AnchorClass.PROJECTED_SUCCESSOR
                        || start.phaseAnchor() != proof.decisionTick())
                    throw new IllegalArgumentException("Invalid projected anchor");
                AnimationSchema.nonnegative(proof.inputRef(), "input ref");
                AnimationSchema.nonnegative(proof.transitionOrdinal(), "transition ordinal");
                if (start.predecessorNodeId() == 0)
                    throw new IllegalArgumentException("Projected successor missing predecessor");
            }
        }
    }

    public static Time age(Start start, Time r, Time a, AnimationPolicy policy) {
        validateStart(start);
        if (start.phaseDomain() != policy.domain(start.layerTag()))
            throw new IllegalArgumentException("Client-selected phase domain");
        Time clock = start.phaseDomain() == PhaseDomain.A ? a : r;
        long age = Math.addExact(start.u0(), Math.subtractExact(clock.tick(), start.phaseAnchor()));
        if (age < 0) throw new IllegalArgumentException("Negative consuming age");
        return new Time(age, clock.alpha());
    }

    public static void write(
            net.minecraft.network.FriendlyByteBuf buf, AnimationStartReport report) {
        validate(report);
        SnapshotWireMath.writeUvarInt(buf, report.victims().size());
        for (Victim victim : report.victims()) writeVictim(buf, victim);
    }

    private static void writeVictim(net.minecraft.network.FriendlyByteBuf buf, Victim v) {
        SnapshotWireMath.writeUvarInt(buf, v.entityId());
        for (long value :
                new long[] {
                    v.targetEpoch(),
                    v.seatGeneration(),
                    v.teleportGeneration(),
                    v.subscriptionGeneration(),
                    v.seedRef(),
                    v.appliedEventSequence()
                }) SnapshotWireMath.writeUvarLong(buf, value);
        AnimationSchema.writeDigest(buf, v.eventPrefixDigest());
        SnapshotWireMath.writeUvarInt(buf, v.starts().size());
        for (Start start : v.starts()) writeStart(buf, start);
        SnapshotWireMath.writeUvarInt(buf, v.applications().size());
        for (Application application : v.applications()) {
            SnapshotWireMath.writeUvarLong(buf, application.eventSequence());
            writeObserverApply(buf, application.proof());
        }
        AnimationSchema.writeDigest(buf, v.presentationDigest());
    }

    public static void writeStart(net.minecraft.network.FriendlyByteBuf b, Start s) {
        validateStart(s);
        SnapshotWireMath.writeUvarLong(b, s.eventSequence());
        SnapshotWireMath.writeUvarInt(b, s.nodeId());
        b.writeByte(s.layerTag().id());
        b.writeByte(s.startKind().id());
        b.writeByte(s.anchorClass().id());
        b.writeByte(s.phaseDomain().id());
        SnapshotWireMath.writeUvarLong(b, s.phaseAnchor());
        SnapshotWireMath.writeUvarInt(b, s.u0());
        SnapshotWireMath.writeUvarInt(b, s.predecessorNodeId());
        switch (s.proof()) {
            case ObserverApply p -> writeObserverApply(b, p);
            case CanonicalTick p -> {
                SnapshotWireMath.writeUvarLong(b, p.decisionTick());
                b.writeByte(p.decisionStage().id());
            }
            case ProjectedSuccessor p -> {
                SnapshotWireMath.writeUvarLong(b, p.decisionTick());
                b.writeByte(p.decisionStage().id());
                SnapshotWireMath.writeUvarLong(b, p.inputRef());
                SnapshotWireMath.writeUvarInt(b, p.transitionOrdinal());
            }
        }
    }

    private static void writeObserverApply(
            net.minecraft.network.FriendlyByteBuf b, ObserverApply p) {
        for (long v : new long[] {p.applyFrameId(), p.applyRevision(), p.mappingId()})
            SnapshotWireMath.writeUvarLong(b, v);
        AnimationSchema.writeTime(b, p.sW());
        AnimationSchema.writeTime(b, p.applyR());
        AnimationSchema.writeTime(b, p.applyA());
        SnapshotWireMath.writeUvarLong(b, p.applyH());
        SnapshotWireMath.writeUvarLong(b, p.applyEventPrefix());
    }

    private static ObserverApply readObserverApply(net.minecraft.network.FriendlyByteBuf b) {
        return new ObserverApply(
                SnapshotWireMath.readUvarLong(b),
                SnapshotWireMath.readUvarLong(b),
                SnapshotWireMath.readUvarLong(b),
                AnimationSchema.readTime(b),
                AnimationSchema.readTime(b),
                AnimationSchema.readTime(b),
                SnapshotWireMath.readUvarLong(b),
                SnapshotWireMath.readUvarLong(b));
    }

    public static AnimationStartReport read(net.minecraft.network.FriendlyByteBuf b) {
        int count = AnimationSchema.readCount(b, AnimationSchema.MAX_VICTIMS, 72);
        List<Victim> victims = new java.util.ArrayList<>(Math.min(count, 64));
        for (int i = 0; i < count; i++) {
            int entity = SnapshotWireMath.readUvarInt(b);
            long target = SnapshotWireMath.readUvarLong(b),
                    seat = SnapshotWireMath.readUvarLong(b),
                    teleport = SnapshotWireMath.readUvarLong(b),
                    subscription = SnapshotWireMath.readUvarLong(b),
                    seed = SnapshotWireMath.readUvarLong(b),
                    prefix = SnapshotWireMath.readUvarLong(b);
            Digest event = AnimationSchema.readDigest(b);
            int starts = AnimationSchema.readCount(b, AnimationSchema.MAX_NODES, 11);
            List<Start> entries = new java.util.ArrayList<>(Math.min(starts, 64));
            for (int j = 0; j < starts; j++) entries.add(readStart(b));
            int applied = AnimationSchema.readCount(b, AnimationSchema.MAX_NODES, 15);
            List<Application> applications = new java.util.ArrayList<>(Math.min(applied, 64));
            for (int j = 0; j < applied; j++)
                applications.add(
                        new Application(SnapshotWireMath.readUvarLong(b), readObserverApply(b)));
            victims.add(
                    new Victim(
                            entity,
                            target,
                            seat,
                            teleport,
                            subscription,
                            seed,
                            prefix,
                            event,
                            entries,
                            applications,
                            AnimationSchema.readDigest(b)));
        }
        var report = new AnimationStartReport(victims);
        validate(report);
        return report;
    }

    public static Start readStart(net.minecraft.network.FriendlyByteBuf b) {
        long event = SnapshotWireMath.readUvarLong(b);
        int node = SnapshotWireMath.readUvarInt(b);
        AnimationSchema.require(b, 4);
        LayerTag layer = AnimationSchema.tag(LayerTag.class, b.readUnsignedByte());
        StartKind kind = AnimationSchema.tag(StartKind.class, b.readUnsignedByte());
        AnchorClass anchor = AnimationSchema.tag(AnchorClass.class, b.readUnsignedByte());
        PhaseDomain domain = AnimationSchema.tag(PhaseDomain.class, b.readUnsignedByte());
        long phase = SnapshotWireMath.readUvarLong(b);
        int age = SnapshotWireMath.readUvarInt(b), predecessor = SnapshotWireMath.readUvarInt(b);
        Proof proof =
                switch (anchor) {
                    case OBSERVER_APPLY -> readObserverApply(b);
                    case CANONICAL_TICK ->
                            new CanonicalTick(SnapshotWireMath.readUvarLong(b), readStage(b));
                    case PROJECTED_SUCCESSOR ->
                            new ProjectedSuccessor(
                                    SnapshotWireMath.readUvarLong(b),
                                    readStage(b),
                                    SnapshotWireMath.readUvarLong(b),
                                    SnapshotWireMath.readUvarInt(b));
                };
        Start start =
                new Start(event, node, layer, kind, anchor, domain, phase, age, predecessor, proof);
        validateStart(start);
        return start;
    }

    private static NativeStage readStage(net.minecraft.network.FriendlyByteBuf b) {
        AnimationSchema.require(b, 1);
        return AnimationSchema.tag(NativeStage.class, b.readUnsignedByte());
    }
}
