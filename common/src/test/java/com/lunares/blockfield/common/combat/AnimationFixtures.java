package com.lunares.blockfield.common.combat;

import static com.lunares.blockfield.common.combat.AnimationResources.*;
import static com.lunares.blockfield.common.combat.AnimationTypes.*;

import io.netty.buffer.Unpooled;
import java.lang.reflect.*;
import java.util.*;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Values deliberately contain no native object; family-native restoration is tested by its owner.
 */
final class AnimationFixtures {
    static final Digest DIGEST = new Digest(1, 2, 3, 4);

    private AnimationFixtures() {}

    static AnimationPolicy policy() {
        return new AnimationPolicy(
                0, PhaseDomain.A, true, true, true, true, AnimationPolicy.registered());
    }

    static FriendlyByteBuf buffer(byte[] bytes) {
        return new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
    }

    static byte[] bytes(FriendlyByteBuf buf) {
        byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        return bytes;
    }

    static <T extends Record> T roundTrip(T value) {
        FriendlyByteBuf buf = buffer(AnimationSchema.encode(value));
        try {
            return (T) AnimationSchema.readComplete(buf, value.getClass());
        } finally {
            buf.release();
        }
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
        if (clazz == boolean.class) return true;
        if (clazz == int.class) return 0;
        if (clazz == long.class) return 0L;
        if (clazz == float.class) return -0.0f;
        if (clazz == double.class) return -0.0d;
        if (clazz == String.class) return "fixture";
        if (clazz == Bytes.class) return new Bytes(new byte[] {0, 1, 2, (byte) 255});
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
            if (clazz == AnimationRecipe.SnapshotRecord.class
                    && field.getName().equals("checkpoint")) nullable = false;
            values[i] = nullable ? null : sample(field.getGenericType(), depth + 1);
        }
        return clazz.getConstructor(parameters).newInstance(values);
    }

    static AnimationStartReport.Start observer(
            long event, int node, LayerTag layer, PhaseDomain domain, long anchor, int age) {
        return new AnimationStartReport.Start(
                event,
                node,
                layer,
                StartKind.NEW,
                AnchorClass.OBSERVER_APPLY,
                domain,
                anchor,
                age,
                0,
                new AnimationStartReport.ObserverApply(
                        8,
                        2,
                        3,
                        new Time(103, 0),
                        new Time(98, 19660),
                        new Time(100, 19660),
                        103,
                        event));
    }

    static AnimationStartReport.Victim victim(int entity, List<AnimationStartReport.Start> starts) {
        return new AnimationStartReport.Victim(
                entity, 0, 0, 0, 0, 0, 100, DIGEST, starts, List.of(), DIGEST);
    }

    static CombatView receipt(List<AnimationStartReport.Victim> victims) {
        return new CombatView(
                7,
                4,
                103,
                98,
                19660,
                new CombatView.Receipt(
                        2,
                        3,
                        8,
                        2,
                        new Time(100, 19660),
                        0,
                        0,
                        DIGEST,
                        new AnimationStartReport(victims)));
    }

    static AnimationGraphState graph(List<AnimationGraphState.Node> nodes) {
        return new AnimationGraphState(
                List.of(
                        new AnimationGraphState.Root(
                                93, LayerTag.PA_93, new AnimationGraphState.NodeRef(1))),
                nodes);
    }

    static AnimationGraphState.Node node(int id, AnimationGraphState.NodeData data) {
        return new AnimationGraphState.Node(id, PhaseDomain.A, null, data);
    }

    static <T extends Record> T with(T value, String name, Object replacement) {
        try {
            var fields = value.getClass().getRecordComponents();
            Object[] args = new Object[fields.length];
            Class<?>[] types = new Class<?>[fields.length];
            for (int i = 0; i < args.length; i++) {
                types[i] = fields[i].getType();
                args[i] =
                        fields[i].getName().equals(name)
                                ? replacement
                                : fields[i].getAccessor().invoke(value);
            }
            return (T) value.getClass().getConstructor(types).newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
