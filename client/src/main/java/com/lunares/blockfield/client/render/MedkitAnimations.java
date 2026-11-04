package com.lunares.blockfield.client.render;

import com.lunares.blockfield.client.combat.MedkitPickup;
import com.mojang.blaze3d.vertex.PoseStack;
import com.tacz.guns.client.renderer.other.HandRenderer;
import com.tacz.guns.compat.iris.IrisCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * First person while healing from a thrown medkit (MedkitPickup): the held item drops out of view,
 * the hands reach for the kit on the ground and inject (virtual medkit, "ground_use" clip), then
 * the item comes back. Same phases as the virtual tablet (TabletAnimations).
 */
public final class MedkitAnimations {
    private static final long ITEM_OUT_NS = 150_000_000L;
    private static final long ITEM_BACK_NS = 220_000_000L;
    // Matches the "ground_end" clip in dev/assets/equipment-3d/anims.py.
    private static final long KIT_END_NS = 300_000_000L;

    private enum Phase {
        NONE,
        ITEM_OUT,
        KIT,
        KIT_DOWN,
        ITEM_BACK
    }

    private static ItemStack virtual;
    private static Phase phase = Phase.NONE;
    private static long phaseStart;

    private MedkitAnimations() {}

    private static BedrockItemRenderer renderer() {
        if (virtual == null) {
            var item =
                    BuiltInRegistries.ITEM.get(
                            ResourceLocation.fromNamespaceAndPath("superbwarfare", "medical_kit"));
            virtual = new ItemStack(item);
        }
        return EquipmentAnimations.renderer(virtual.getItem());
    }

    /** Replaces the hand pass while the virtual kit is up; false draws the held item, moved. */
    public static boolean renderHands(
            LocalPlayer player,
            PoseStack pose,
            MultiBufferSource.BufferSource buffers,
            int light,
            float partialTick) {
        long now = System.nanoTime();
        advance(now);
        switch (phase) {
            case KIT, KIT_DOWN -> {
                BedrockItemRenderer renderer = renderer();
                if (renderer == null) return false;
                IrisCompat.endBatch(buffers);
                Minecraft mc = Minecraft.getInstance();
                HandRenderer.INSTANCE.renderSolid(
                        own ->
                                renderer.renderVirtual(
                                        player,
                                        virtual,
                                        own != null ? own : pose,
                                        buffers,
                                        light,
                                        partialTick),
                        partialTick,
                        mc.gameRenderer.getMainCamera(),
                        mc.gameRenderer);
                return true;
            }
            case ITEM_OUT ->
                    TabletAnimations.drop(
                            pose,
                            TabletAnimations.smooth((now - phaseStart) / (float) ITEM_OUT_NS));
            case ITEM_BACK ->
                    TabletAnimations.drop(
                            pose,
                            1 - TabletAnimations.smooth((now - phaseStart) / (float) ITEM_BACK_NS));
            default -> {}
        }
        return false;
    }

    /** Moves through the phases by time and the heal state; runs every tick and frame. */
    public static void advance(long now) {
        boolean on = MedkitPickup.channeling();
        switch (phase) {
            case NONE -> {
                if (on) start(Phase.ITEM_OUT, now);
            }
            case ITEM_OUT -> {
                if (!on) reverse(Phase.ITEM_BACK, ITEM_OUT_NS, ITEM_BACK_NS, now);
                else if (now - phaseStart >= ITEM_OUT_NS) start(Phase.KIT, now);
            }
            case KIT -> {
                if (!on) start(Phase.KIT_DOWN, now);
            }
            case KIT_DOWN -> {
                if (on) {
                    end();
                    start(Phase.KIT, now);
                } else if (now - phaseStart >= KIT_END_NS) {
                    end();
                    start(Phase.ITEM_BACK, now);
                }
            }
            case ITEM_BACK -> {
                if (on) reverse(Phase.ITEM_OUT, ITEM_BACK_NS, ITEM_OUT_NS, now);
                else if (now - phaseStart >= ITEM_BACK_NS) phase = Phase.NONE;
            }
        }
    }

    private static void end() {
        BedrockItemRenderer renderer = renderer();
        if (renderer != null) renderer.endVirtual(virtual);
    }

    /** Turns a drop around from where the item is, so it never jumps. */
    private static void reverse(Phase next, long fromNs, long toNs, long now) {
        long done = Math.min(now - phaseStart, fromNs);
        phase = next;
        phaseStart = now - (long) ((1 - done / (double) fromNs) * toNs);
    }

    private static void start(Phase next, long now) {
        phase = next;
        phaseStart = now;
    }
}
