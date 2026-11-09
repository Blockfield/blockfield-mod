package com.lunares.blockfield.client.combat;

import com.lunares.blockfield.client.gui.tablet.Tablet;
import com.lunares.blockfield.client.render.MedkitAnimations;
import com.lunares.blockfield.common.combat.MedkitTarget;
import com.lunares.blockfield.network.packet.MedkitChannelC2SPacket;
import com.lunares.blockfield.network.packet.MedkitChannelS2CPacket;
import com.tacz.guns.client.input.InteractKey;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;

/**
 * Healing from a thrown medkit: hold the TACZ interact key on it. The client asks for the kit under
 * the crosshair and says stop when the key is let go or the crosshair leaves it; the server
 * (MedkitChannels) times and checks the rest. Keeping the key down can acquire a kit later or
 * continue with the next kit after healing.
 */
public final class MedkitPickup {
    private static final IntSet POSING = new IntOpenHashSet();
    private static int requested = MedkitChannelC2SPacket.STOP;

    private MedkitPickup() {}

    public static void register() {
        if (!FabricLoader.getInstance().isModLoaded("tacz")) return;
        ClientTickEvents.END_CLIENT_TICK.register(MedkitPickup::tick);
        ClientPlayNetworking.registerGlobalReceiver(
                MedkitChannelS2CPacket.TYPE, (packet, context) -> sync(context.client(), packet));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
    }

    /** The own heal is under way (the server may still cut it off). */
    public static boolean channeling() {
        return requested != MedkitChannelC2SPacket.STOP;
    }

    /** Draw this player (entity id) in the healing pose. */
    public static boolean posing(int player) {
        return POSING.contains(player);
    }

    /** Full body medkit priority; first-person hands keep MedkitAnimations instead. */
    public static boolean posingBody(LivingEntity entity) {
        Boolean driven = DrivenPresentation.medkit(entity);
        if (driven != null) return driven;
        Minecraft mc = Minecraft.getInstance();
        return posing(entity.getId())
                && !(entity == mc.player && mc.options.getCameraType().isFirstPerson());
    }

    private static void tick(Minecraft mc) {
        MedkitAnimations.advance(System.nanoTime());
        // A pose whose player left view or the world never gets its stop packet.
        if (!POSING.isEmpty())
            POSING.removeIf(id -> mc.level == null || mc.level.getEntity(id) == null);
        boolean down = InteractKey.INTERACT_KEY.isDown();
        int want = down ? target(mc) : MedkitChannelC2SPacket.STOP;
        if (want == requested || !ClientPlayNetworking.canSend(MedkitChannelC2SPacket.TYPE)) return;
        ClientPlayNetworking.send(new MedkitChannelC2SPacket(want));
        requested = want;
    }

    private static int target(Minecraft mc) {
        var player = mc.player;
        if (player == null
                || !player.isAlive()
                || player.isSpectator()
                || player.isPassenger()
                || player.getHealth() >= player.getMaxHealth()
                || mc.screen != null
                || Tablet.isOpen()) return MedkitChannelC2SPacket.STOP;
        var kit = MedkitTarget.find(player);
        return kit == null ? MedkitChannelC2SPacket.STOP : kit.getId();
    }

    private static void sync(Minecraft mc, MedkitChannelS2CPacket packet) {
        if (!packet.valid()) return;
        if (packet.active()) POSING.add(packet.player());
        else POSING.remove(packet.player());
        // Only the end of the current request: a stop for a kit given up earlier arrives late.
        if (!packet.active()
                && mc.player != null
                && packet.player() == mc.player.getId()
                && packet.kit() == requested) {
            // Healed, refused or cut off: the server has already forgotten the request.
            requested = MedkitChannelC2SPacket.STOP;
        }
    }

    private static void reset() {
        POSING.clear();
        requested = MedkitChannelC2SPacket.STOP;
    }
}
