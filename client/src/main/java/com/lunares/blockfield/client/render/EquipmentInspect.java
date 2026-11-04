package com.lunares.blockfield.client.render;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** First-person inspection for the RPG, which has no native inspection clip. */
public final class EquipmentInspect {
    private static final State STATE = new State();

    private EquipmentInspect() {}

    public static boolean start(LocalPlayer player) {
        ItemStack held = player.getMainHandItem();
        Sequence sequence = sequence(BuiltInRegistries.ITEM.getKey(held.getItem()).toString());
        return STATE.start(
                player,
                player.getInventory().selected,
                held,
                sequence,
                ready(player, held, sequence),
                Util.getMillis());
    }

    public static Pose pose(Player player, InteractionHand hand, ItemStack rendered) {
        if (hand != InteractionHand.MAIN_HAND || STATE.startedAt < 0) return Pose.IDENTITY;
        ItemStack held = player.getMainHandItem();
        Sequence sequence = sequence(BuiltInRegistries.ITEM.getKey(held.getItem()).toString());
        Pose pose =
                STATE.sample(
                        player,
                        player.getInventory().selected,
                        held,
                        ready(player, held, sequence),
                        Util.getMillis());
        return rendered == held ? pose : Pose.IDENTITY;
    }

    static boolean supports(String id) {
        return sequence(id) != null;
    }

    static Sequence sequence(String id) {
        return switch (id) {
            case "superbwarfare:rpg" -> Sequence.RPG;
            default -> null;
        };
    }

    private static boolean ready(Player player, ItemStack held, Sequence sequence) {
        Minecraft mc = Minecraft.getInstance();
        if (player != mc.player
                || mc.getCameraEntity() != player
                || mc.screen != null
                || mc.options.hideGui
                || !mc.options.getCameraType().isFirstPerson()
                || !player.isAlive()
                || player.isSpectator()
                || player.isUsingItem()
                || player.swinging
                || player.isSprinting()
                || mc.options.keyAttack.isDown()
                || mc.options.keyUse.isDown()) return false;
        return sequence != null && (sequence != Sequence.RPG || !Sbw.busy(held));
    }

    public record Pose(float x, float y, float z, float pitch, float yaw, float roll) {
        public static final Pose IDENTITY = new Pose(0, 0, 0, 0, 0, 0);

        private Pose between(
                Pose before,
                Pose target,
                Pose after,
                float previousSpan,
                float span,
                float nextSpan,
                float t) {
            return new Pose(
                    curve(before.x, x, target.x, after.x, previousSpan, span, nextSpan, t),
                    curve(before.y, y, target.y, after.y, previousSpan, span, nextSpan, t),
                    curve(before.z, z, target.z, after.z, previousSpan, span, nextSpan, t),
                    curve(
                            before.pitch,
                            pitch,
                            target.pitch,
                            after.pitch,
                            previousSpan,
                            span,
                            nextSpan,
                            t),
                    curve(before.yaw, yaw, target.yaw, after.yaw, previousSpan, span, nextSpan, t),
                    curve(
                            before.roll,
                            roll,
                            target.roll,
                            after.roll,
                            previousSpan,
                            span,
                            nextSpan,
                            t));
        }

        private static float curve(
                float before,
                float from,
                float to,
                float after,
                float previousSpan,
                float span,
                float nextSpan,
                float t) {
            float t2 = t * t;
            float t3 = t2 * t;
            return (2 * t3 - 3 * t2 + 1) * from
                    + (t3 - 2 * t2 + t) * span * tangent(before, from, to, previousSpan, span)
                    + (-2 * t3 + 3 * t2) * to
                    + (t3 - t2) * span * tangent(from, to, after, span, nextSpan);
        }

        private static float tangent(float before, float at, float after, float left, float right) {
            if (left == 0 || right == 0) return 0;
            float incoming = (at - before) / left;
            float outgoing = (after - at) / right;
            if (incoming * outgoing <= 0) return 0;
            // Time-weighted monotone tangents carry momentum without overshooting a grip target.
            float a = 2 * right + left;
            float b = right + 2 * left;
            return (a + b) / (a / incoming + b / outgoing);
        }
    }

    private record Frame(long time, Pose pose) {}

    static final class Sequence {
        static final Sequence RPG =
                new Sequence(
                        rest(0),
                        frame(180, .012f, -.018f, -.012f, 2, -2, 2),
                        frame(470, -.055f, .085f, .025f, -6, 12, -7),
                        frame(780, -.105f, .140f, .045f, -11, 26, -14),
                        frame(980, -.095f, .132f, .040f, -10, 24, -12),
                        frame(1370, -.087f, .125f, .035f, -9, 22, -11),
                        frame(1610, -.110f, .108f, .008f, -4, 12, -5),
                        frame(1970, -.070f, .148f, .032f, -7, -19, 11),
                        frame(2170, -.063f, .142f, .028f, -6, -17, 9),
                        frame(2470, -.058f, .138f, .024f, -5, -15, 8),
                        frame(2750, -.090f, .164f, .044f, -15, -3, 1),
                        frame(2990, -.074f, .150f, .037f, -13, 1, -2),
                        frame(3260, -.025f, .050f, .006f, -4, 3, -3),
                        rest(3550));
        private final Frame[] frames;
        final long duration;

        private Sequence(Frame... frames) {
            this.frames = frames;
            duration = frames[frames.length - 1].time;
        }

        Pose sample(long elapsed) {
            if (elapsed <= 0 || elapsed >= duration) return Pose.IDENTITY;
            for (int i = 1; i < frames.length; i++) {
                Frame target = frames[i];
                if (elapsed > target.time) continue;
                Frame from = frames[i - 1];
                Frame before = frames[Math.max(0, i - 2)];
                Frame after = frames[Math.min(frames.length - 1, i + 1)];
                float t = (float) (elapsed - from.time) / (target.time - from.time);
                return from.pose.between(
                        before.pose,
                        target.pose,
                        after.pose,
                        from.time - before.time,
                        target.time - from.time,
                        after.time - target.time,
                        t);
            }
            return Pose.IDENTITY;
        }

        private static Frame rest(long time) {
            return new Frame(time, Pose.IDENTITY);
        }

        private static Frame frame(
                long time, float x, float y, float z, float pitch, float yaw, float roll) {
            return new Frame(time, new Pose(x, y, z, pitch, yaw, roll));
        }
    }

    static final class State {
        private Object owner;
        private Object item;
        private int slot;
        private long startedAt = -1;
        private Sequence sequence;

        boolean start(
                Object owner, int slot, Object item, Sequence sequence, boolean ready, long now) {
            if (!ready || sequence == null) return false;
            if (startedAt >= 0
                    && this.owner == owner
                    && this.slot == slot
                    && this.item == item
                    && now >= startedAt
                    && now - startedAt < this.sequence.duration) return false;
            this.owner = owner;
            this.slot = slot;
            this.item = item;
            this.sequence = sequence;
            startedAt = now;
            return true;
        }

        Pose sample(Object owner, int slot, Object item, boolean ready, long now) {
            if (startedAt < 0) return Pose.IDENTITY;
            if (!ready
                    || this.owner != owner
                    || this.slot != slot
                    || this.item != item
                    || now < startedAt
                    || now - startedAt >= sequence.duration) {
                startedAt = -1;
                return Pose.IDENTITY;
            }
            return sequence.sample(now - startedAt);
        }
    }

    private static final class Sbw {
        private static final Api API = api();

        private record Api(
                Method from,
                Method reloading,
                Field aiming,
                Field editing,
                Field drawTime,
                Field fireRecoilTime) {}

        private static Api api() {
            try {
                var data =
                        Class.forName(
                                "com.atsuishio.superbwarfare.data.gun.GunData",
                                false,
                                EquipmentInspect.class.getClassLoader());
                var events =
                        Class.forName(
                                "com.atsuishio.superbwarfare.event.ClientEventHandler",
                                false,
                                EquipmentInspect.class.getClassLoader());
                return new Api(
                        data.getMethod("from", ItemStack.class),
                        data.getMethod("reloading"),
                        events.getField("zoom"),
                        events.getField("isEditing"),
                        events.getField("drawTime"),
                        events.getField("fireRecoilTime"));
            } catch (ReflectiveOperationException | LinkageError error) {
                return null;
            }
        }

        static boolean busy(ItemStack held) {
            // An unavailable guard must not let inspection interfere with RPG actions.
            if (API == null) return true;
            try {
                return API.aiming().getBoolean(null)
                        || API.editing().getBoolean(null)
                        || API.drawTime().getDouble(null) >= .01
                        || API.fireRecoilTime().getDouble(null) > 0
                        || (boolean) API.reloading().invoke(API.from().invoke(null, held));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                return true;
            }
        }
    }
}
