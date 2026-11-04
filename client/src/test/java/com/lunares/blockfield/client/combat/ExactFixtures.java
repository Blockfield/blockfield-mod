package com.lunares.blockfield.client.combat;

import com.lunares.blockfield.common.combat.AnimationEvent;
import com.lunares.blockfield.common.combat.AnimationGraphState;
import com.lunares.blockfield.common.combat.AnimationPolicy;
import com.lunares.blockfield.common.combat.AnimationResources;
import com.lunares.blockfield.common.combat.AnimationSchema;
import com.lunares.blockfield.common.combat.AnimationStartReport;
import com.lunares.blockfield.common.combat.AnimationState;
import com.lunares.blockfield.common.combat.AnimationTypes.*;
import com.lunares.blockfield.common.combat.LrAssets;
import com.lunares.blockfield.common.combat.ObserverReducer;
import com.lunares.blockfield.common.combat.PaGraph;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Server-shaped ADR4 values built from the common schema only (no native object): one observer
 * connection, one target life and Create membership capsules, whose replay needs no clip asset.
 */
final class ExactFixtures {
    static final long CONNECTION = 3, STREAM = 17, SCENE = 1, SUBSCRIPTION = 5, TARGET = 9;
    static final int ENTITY = 42;
    static final AnimationResources.Digest ZERO = new AnimationResources.Digest(0, 0, 0, 0);

    private ExactFixtures() {}

    static AnimationPolicy policy() {
        return new AnimationPolicy(
                0, PhaseDomain.A, true, true, true, true, AnimationPolicy.registered());
    }

    static final PaGraph.Catalog CATALOG = new PaGraph.Catalog(0);
    private static AnimationGraphState GRAPH;
    private static final LrAssets LR = new LrAssets(CATALOG);

    /** The activated generation 0: policy plus an empty shared catalog (no TaCZ guns). */
    static ClientAnimationAssets.Generation assets() {
        return new ClientAnimationAssets.Generation(0, policy(), null, LR);
    }

    /** The freshly registered seven PA roots, frozen over the other families' neutral roots. */
    static AnimationGraphState graph() {
        PaGraph.Sources none = id -> part -> java.util.Optional.empty();
        return PaGraph.Stack.create(CATALOG, policy(), none, LayerTag.PA_96, 1)
                .freeze(AnimationGraphState.neutral());
    }

    static AnimationState.Identity identity() {
        return identity(ENTITY, TARGET, SUBSCRIPTION, 0, 0);
    }

    static AnimationState.Identity identity(
            int entity, long target, long subscription, long seat, long teleport) {
        return new AnimationState.Identity(
                CONNECTION,
                STREAM,
                SCENE,
                entity,
                0,
                subscription,
                target,
                seat,
                teleport,
                0,
                0,
                AnimationSchema.layoutDigest(),
                ZERO);
    }

    /** A complete checkpoint S[tick] of the target with a neutral graph and no decisions. */
    static AnimationState checkpoint(AnimationState.Identity identity, long tick, double x) {
        if (GRAPH == null) GRAPH = graph();
        var sample = sample(AnimationState.class);
        var placement =
                new AnimationState.Placement(
                        new AnimationState.Vec3(x - .25, 64, 0),
                        new AnimationState.Vec3(x, 64, 0),
                        new AnimationState.Angles(10, 20, -5, 5, 30, 40, 31, 41),
                        new AnimationState.Walk(3, .2f, .4f),
                        new AnimationState.Vec3(0, 0, 0),
                        new AnimationState.Vec3(.25, 0, 0),
                        new AnimationState.VehicleAngles(0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                        List.of());
        var create = new AnimationState.Create(false, null, 0, 0, AnchorClass.CANONICAL_TICK);
        var input =
                new AnimationResources.InputRef(
                        identity.resourceGeneration(),
                        identity.targetEpoch(),
                        tick,
                        AnimationResources.Digest.hash(AnimationSchema.encode(placement)));
        // Every S[t] closes its journal with the ParCool boundary marker (neutral family here).
        return new AnimationState(
                identity,
                tick,
                0,
                tick * 256,
                placement,
                sample.vanilla(),
                sample.stance(),
                sample.equipment(),
                null,
                sample.lr(),
                GRAPH,
                com.lunares.blockfield.common.combat.ParCoolCheckpoints.neutral(
                        identity.resourceGeneration(), identity.targetEpoch()),
                create,
                sample.sbw(),
                List.of(
                        com.lunares.blockfield.common.combat.ParCoolCheckpoints.marker(
                                tick, tick * 256, input)));
    }

    /** A server seed of the life at S[tick] with no canonical starts. */
    static AnimationEvent.ObserverSeed seed(AnimationState checkpoint, long id) {
        var identity = checkpoint.identity();
        return new AnimationEvent.ObserverSeed(
                identity,
                new AnimationResources.SeedRef(
                        identity.resourceGeneration(),
                        identity.targetEpoch(),
                        id,
                        new AnimationResources.Digest(id, 1, 2, 3)),
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

    /** A Create membership capsule at {@code tick}: enter (hanging) or exit (no node, no start). */
    static AnimationEvent create(
            AnimationState.Identity identity,
            long tick,
            long sequence,
            long predecessor,
            boolean enter,
            long acceptedNs) {
        byte[] marker = "native.unwrapped".getBytes(StandardCharsets.UTF_8);
        var digest = AnimationResources.Digest.hash(marker);
        var payload =
                new AnimationResources.DefinitionRef(0, 0, digest.a() & Long.MAX_VALUE, digest);
        var membership =
                enter
                        ? new AnimationState.Create(
                                true,
                                new AnimationStartReport.StartKey(sequence, 1),
                                tick,
                                0,
                                AnchorClass.OBSERVER_APPLY)
                        : new AnimationState.Create(false, null, 0, 0, AnchorClass.CANONICAL_TICK);
        var patch =
                new AnimationEvent.Patch(
                        LayerTag.CREATE_HANGING,
                        enter ? StartKind.NEW : StartKind.STOP_FADE,
                        enter ? Outcome.SELECTED : Outcome.STOPPED,
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
        return new AnimationEvent(
                identity,
                new AnimationEvent.EventId(tick, sequence, NativeStage.INTER_TICK_ACCEPT),
                predecessor,
                NativeFamily.CREATE,
                enter ? EventKind.CREATE_ENTER : EventKind.CREATE_EXIT,
                NativeStage.END_COMMIT,
                acceptedNs,
                acceptedNs + 1_000_000,
                new AnimationResources.SeedRef(0, identity.targetEpoch(), tick - 1, ZERO),
                patch,
                List.of(
                        new AnimationResources.Definition(
                                payload, new AnimationResources.Bytes(marker), List.of())),
                new AnimationEvent.NativePayload(
                        payload, new AnimationResources.Bytes(new byte[0])));
    }

    static <T> T sample(Class<T> type) {
        try {
            return type.cast(sample((Type) type, 0));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(type.getName(), e);
        }
    }

    private static Object sample(Type type, int depth) throws ReflectiveOperationException {
        if (depth > 40) throw new AssertionError("Cyclic fixture schema");
        if (type instanceof ParameterizedType) return List.of();
        Class<?> clazz = (Class<?>) type;
        if (clazz == boolean.class) return false;
        if (clazz == int.class) return 0;
        if (clazz == long.class) return 0L;
        if (clazz == float.class) return 0f;
        if (clazz == double.class) return 0d;
        if (clazz == String.class) return "fixture";
        if (clazz == AnimationResources.Bytes.class)
            return new AnimationResources.Bytes(new byte[] {1});
        if (clazz.isEnum()) return clazz.getEnumConstants()[0];
        if (clazz == AnimationPolicy.class) return policy();
        if (clazz.isInterface()) return sample(clazz.getPermittedSubclasses()[0], depth + 1);
        var fields = clazz.getRecordComponents();
        Object[] values = new Object[fields.length];
        Class<?>[] parameters = new Class<?>[fields.length];
        for (int i = 0; i < fields.length; i++) {
            var field = fields[i];
            parameters[i] = field.getType();
            boolean nullable = field.isAnnotationPresent(AnimationSchema.Nullable.class);
            values[i] = nullable ? null : sample(field.getGenericType(), depth + 1);
        }
        return clazz.getConstructor(parameters).newInstance(values);
    }
}
