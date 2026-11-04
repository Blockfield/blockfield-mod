package com.lunares.blockfield.client.combat;

import com.lunares.blockfield.common.combat.CombatView;
import com.lunares.blockfield.common.grenade.GrenadeThrowSupport;
import com.lunares.blockfield.network.packet.AnimationAppliedC2SPacket;
import com.lunares.blockfield.network.packet.CombatViewC2SPacket;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** Exactly the p3 r8 §3.2 C2S allowlist, after unwrapping Fabric's actual packet. */
public final class CombatAttackSender {
    public static final Set<String> CUSTOM_IDS =
            Set.of(
                    "tacz:client_player_shoot",
                    "tacz:client_player_melee",
                    "superbwarfare:shoot",
                    "superbwarfare:vehicle_fire",
                    "superbwarfare:melee_attack",
                    "superbwarfare:fire_key",
                    "superbwarfare:drone_fire",
                    "superbwarfare:artillery_indicator_fire",
                    "superbwarfare:lunge_mine_attack",
                    "lrtactical:melee_attack_request",
                    "lrtactical:prepare_melee_attack",
                    "blockfield:knife_aim",
                    "blockfield:grenade_throw",
                    "superbwarfare:interact");

    /** Eye at the last TaCZ fire frame; the scheduler thread sends that shot later. */
    private static final java.util.concurrent.atomic.AtomicReference<Vec3> FIRE_EYE =
            new java.util.concurrent.atomic.AtomicReference<>();

    /** Rotation the shot's own Rot packet sent; the server's deferred shot may miss it. */
    private static final java.util.concurrent.atomic.AtomicReference<CombatView.Aim> FIRE_AIM =
            new java.util.concurrent.atomic.AtomicReference<>();

    private boolean sending;
    private boolean cooking;
    private InteractionHand cookHand;
    private ItemStack cookItem;
    private LocalPlayer cookPlayer;

    public boolean matches(Packet<?> packet, LocalPlayer player) {
        if (packet instanceof ServerboundCustomPayloadPacket custom)
            return CUSTOM_IDS.contains(custom.payload().type().id().toString());
        if (packet instanceof ServerboundInteractPacket interact) {
            boolean[] attack = {false};
            interact.dispatch(
                    new ServerboundInteractPacket.Handler() {
                        @Override
                        public void onInteraction(InteractionHand hand) {}

                        @Override
                        public void onInteraction(InteractionHand hand, Vec3 location) {}

                        @Override
                        public void onAttack() {
                            attack[0] = true;
                        }
                    });
            return attack[0];
        }
        InteractionHand hand =
                packet instanceof ServerboundUseItemPacket use
                        ? use.getHand()
                        : packet instanceof ServerboundUseItemOnPacket use ? use.getHand() : null;
        if (hand != null) {
            boolean throwable = player != null && throwable(player.getItemInHand(hand));
            if (throwable) {
                cooking = true;
                cookPlayer = player;
                cookHand = hand;
                cookItem = player.getItemInHand(hand).copy();
            } else cooking = false;
            return throwable;
        }
        if (packet instanceof ServerboundPlayerActionPacket action
                && action.getAction() == ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM) {
            boolean active =
                    cooking
                            && player != null
                            && player.isUsingItem()
                            && player == cookPlayer
                            && ItemStack.isSameItemSameComponents(
                                    cookItem, player.getItemInHand(cookHand));
            cooking = false;
            return active;
        }
        return false;
    }

    private static boolean throwable(ItemStack stack) {
        return GrenadeThrowSupport.kind(stack) != null
                || (!stack.isEmpty()
                        && BuiltInRegistries.ITEM
                                .getKey(stack.getItem())
                                .toString()
                                .equals("lrtactical:throwable"));
    }

    /** Synchronized: ticket and attack stay adjacent when TaCZ sends from its own thread. */
    public synchronized void send(
            Packet<?> packet,
            LocalPlayer player,
            Supplier<CombatView> ticket,
            Consumer<Packet<?>> original) {
        if (sending) {
            original.accept(packet);
            return;
        }
        sending = true;
        try {
            if (matches(packet, player)) {
                CombatView view = ticket.get();
                if (view != null && isShot(packet)) {
                    Vec3 eye = FIRE_EYE.getAndSet(null);
                    CombatView.Aim aim = FIRE_AIM.getAndSet(null);
                    if (eye != null) view = view.withFire(eye, aim);
                }
                if (com.lunares.blockfield.common.combat.ShotDebug.enabled()
                        && packet instanceof ServerboundCustomPayloadPacket custom
                        && custom.payload()
                                instanceof
                                com.tacz.guns.network.message.ClientMessagePlayerShoot shot)
                    ClientShotDebug.sent(player, shot.timestamp(), view);
                if (view != null) {
                    var report = new CombatViewC2SPacket(view);
                    for (var fragment : report.fragments())
                        original.accept(new ServerboundCustomPayloadPacket(fragment));
                }
            }
            original.accept(packet);
        } finally {
            sending = false;
        }
    }

    /** Main thread, in TaCZ {@code doShoot}: the frame the player aimed in. */
    public static void fired(Vec3 renderEye) {
        FIRE_EYE.set(renderEye);
    }

    /** TaCZ scheduler thread, just before the shot: the rotation its Rot packet carries. */
    public static void aimed(float yaw, float pitch) {
        FIRE_AIM.set(new CombatView.Aim(yaw, pitch));
    }

    private static boolean isShot(Packet<?> packet) {
        return packet instanceof ServerboundCustomPayloadPacket custom
                && custom.payload().type().id().toString().equals("tacz:client_player_shoot");
    }

    /** The same monitor covers main-thread reports and TaCZ's ticket/attack thread. */
    public synchronized void sendApplied(
            AnimationAppliedC2SPacket report, Consumer<Packet<?>> original) {
        boolean outer = sending;
        sending = true;
        try {
            for (var fragment : report.fragments())
                original.accept(new ServerboundCustomPayloadPacket(fragment));
        } finally {
            sending = outer;
        }
    }

    public void resetUse() {
        cooking = false;
        cookItem = null;
        cookHand = null;
        cookPlayer = null;
    }
}
