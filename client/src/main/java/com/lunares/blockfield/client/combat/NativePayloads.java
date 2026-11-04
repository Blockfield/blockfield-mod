package com.lunares.blockfield.client.combat;

import com.lunares.blockfield.common.combat.AnimationEvent;
import com.lunares.blockfield.common.combat.AnimationTypes.NativeFamily;
import io.netty.buffer.Unpooled;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ADR4 §2.2 (U8b): the native TaCZ/LR geometry broadcast a capsule carries. Its client handler runs
 * exactly once, in the task that applies the capsule (sound, particles, the native listener and
 * LR's local-native compatibility view), as the native packet's own task would; driven geometry
 * still comes from the capsule. Duplicate deliveries of one event id never run it again.
 */
final class NativePayloads {
    private static final Logger LOGGER = LoggerFactory.getLogger(NativePayloads.class);

    static final String CARRIER = "native.payload:";
    static final byte[] BATCHED = "native.batched".getBytes(StandardCharsets.UTF_8);

    /** One wrappable native payload: its stream codec and the native client body. */
    private record Native(StreamCodec<RegistryFriendlyByteBuf, ?> codec, Method body) {}

    private static Map<String, Native> natives;

    // Event identities already dispatched (bounded; a delivery is at most once per observer).
    private static final Map<String, Boolean> DISPATCHED =
            new LinkedHashMap<>(256, .75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > 1024;
                }
            };

    private NativePayloads() {}

    /** The carrier type of {@code event}'s native payload, or null when it carries none. */
    static String carried(AnimationEvent event, byte[] type) {
        if (type == null
                || (event.family() != NativeFamily.TACZ && event.family() != NativeFamily.LR))
            return null;
        String text = new String(type, StandardCharsets.UTF_8);
        return text.startsWith(CARRIER) ? text.substring(CARRIER.length()) : null;
    }

    /** Whether the journal may treat {@code event}'s payload as delivered (carrier or batched). */
    static boolean known(AnimationEvent event, byte[] type) {
        if (type == null) return false;
        boolean family = event.family() == NativeFamily.TACZ || event.family() == NativeFamily.LR;
        if (!family) return false;
        if (java.util.Arrays.equals(type, BATCHED))
            return event.nativePayload().value().size() == 0;
        String id = carried(event, type);
        return id != null && event.nativePayload().value().size() > 0 && registry().containsKey(id);
    }

    /** Non-carrier capsules have no per-target body; carriers must really deliver before apply. */
    static boolean deliver(AnimationEvent event) {
        var type = ClientAnimationJournal.payloadType(event);
        return carried(event, type) == null || dispatch(event, type);
    }

    /** Runs the carried native handler once per event identity; false when delivery failed. */
    static boolean dispatch(AnimationEvent event, byte[] type) {
        String id = carried(event, type);
        byte[] bytes = event.nativePayload().value().value();
        if (id == null || bytes.length == 0) return false;
        var identity = event.identity();
        String key =
                identity.connectionId()
                        + ":"
                        + identity.targetEpoch()
                        + ":"
                        + identity.entityId()
                        + ":"
                        + event.id().eventSequence();
        if (DISPATCHED.containsKey(key)) return true;
        var target = registry().get(id);
        var connection = Minecraft.getInstance().getConnection();
        if (target == null || connection == null) return false;
        var buf =
                new RegistryFriendlyByteBuf(
                        Unpooled.wrappedBuffer(bytes), connection.registryAccess());
        try {
            Object payload = target.codec().decode(buf);
            target.body().invoke(null, payload);
            DISPATCHED.put(key, Boolean.TRUE);
            return true;
        } catch (ReflectiveOperationException | RuntimeException failed) {
            LOGGER.warn("Native {} payload of a capsule failed: {}", id, failed.toString());
            return false;
        } finally {
            buf.release();
        }
    }

    static void clear() {
        DISPATCHED.clear();
    }

    /**
     * Pinned bf8/bf2 payloads and the private static client bodies their handlers schedule ({@code
     * handle -> client.execute(...)}); the LR jar is reached reflectively like elsewhere.
     */
    private static synchronized Map<String, Native> registry() {
        if (natives != null) return natives;
        var result = new LinkedHashMap<String, Native>();
        for (String[] entry :
                new String[][] {
                    {"com.tacz.guns.network.message.event.ServerMessageGunShoot", "doClientEvent"},
                    {"com.tacz.guns.network.message.event.ServerMessageGunReload", "doClientEvent"},
                    {"com.tacz.guns.network.message.event.ServerMessageGunMelee", "doClientEvent"},
                    {"com.tacz.guns.network.message.event.ServerMessageGunDraw", "doClientEvent"},
                    {"me.xjqsh.lrtactical.network.message.SMeleeAnimationSync", "lambda$handle$2"}
                }) {
            try {
                Class<?> type = Class.forName(entry[0]);
                var id = ((CustomPacketPayload.Type<?>) type.getField("TYPE").get(null)).id();
                @SuppressWarnings("unchecked")
                var codec =
                        (StreamCodec<RegistryFriendlyByteBuf, ?>)
                                type.getField("STREAM_CODEC").get(null);
                Method body = type.getDeclaredMethod(entry[1], type);
                body.setAccessible(true);
                result.put(id.toString(), new Native(codec, body));
            } catch (ReflectiveOperationException | LinkageError absent) {
                LOGGER.warn(
                        "Native capsule payload {} unavailable: {}", entry[0], absent.toString());
            }
        }
        return natives = Map.copyOf(result);
    }
}
