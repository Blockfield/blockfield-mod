package com.lunares.blockfield.client.render;

import java.util.SplittableRandom;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.EntityHitResult;

/**
 * Into and out of SuperbWarfare's drone view with a linked monitor. The use click is held back
 * while the station comes up to the eyes ("to_face"), the picture breaks into signal static and
 * only then SBW switches the camera to the drone; leaving, the static clears over the station in
 * front of the eyes, which then comes back down ("control_end").
 */
public final class DroneLinkTransition {
    // Matches the "to_face" clip in dev/assets/equipment-3d/anims.py.
    private static final long TO_FACE_NS = 350_000_000L;
    private static final long STATIC_IN_NS = 140_000_000L;
    private static final long STATIC_OUT_NS = 320_000_000L;

    private static long raisingSince;
    private static boolean bypass;
    private static boolean started;
    private static boolean ended;
    private static boolean controlling;
    private static boolean shownControlling;
    private static long clearingSince = Long.MIN_VALUE / 2;
    private static long hudAt = Long.MIN_VALUE / 2;
    // A held or repeated right click must not flip the view straight back.
    private static long quietUntil;
    private static final long QUIET_NS = 450_000_000L;

    private DroneLinkTransition() {}

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(DroneLinkTransition::tick);
        HudRenderCallback.EVENT.register((g, delta) -> render(g));
    }

    /** From startUseItem: true swallows the click while the station comes up to the eyes. */
    public static boolean interceptUse(Minecraft mc) {
        if (bypass || mc.player == null || mc.gameMode == null || mc.screen != null) return false;
        // Right-clicking a drone links or unlinks it; that stays SBW's own interaction.
        if (mc.hitResult instanceof EntityHitResult) return false;
        CompoundTag tag = monitorTag(mc.player.getMainHandItem());
        if (tag == null || !tag.getBoolean("Linked")) return false;
        if (System.nanoTime() < quietUntil) return true;
        if (tag.getBoolean("Using")) return false;
        if (raisingSince == 0) {
            raisingSince = System.nanoTime();
            started = true;
        }
        return true;
    }

    /** The station should start coming up to the eyes (once). */
    public static boolean takeStarted() {
        boolean value = started;
        started = false;
        return value;
    }

    /** The drone view ended or never began; the station goes back down (once). */
    public static boolean takeEnded() {
        boolean value = ended;
        ended = false;
        return value;
    }

    private static void tick(Minecraft mc) {
        var player = mc.player;
        // SBW hides the hands while flying, so the renderer never sees the session end.
        boolean now = player != null && isControlling(player.getMainHandItem());
        if (controlling && !now) {
            ended = true;
            quietUntil = System.nanoTime() + QUIET_NS;
        }
        controlling = now;
        // With the HUD hidden (F1) a switch shows no static; do not flash it later.
        if (System.nanoTime() - hudAt > 200_000_000L) shownControlling = now;
        if (raisingSince == 0 || System.nanoTime() - raisingSince < TO_FACE_NS) return;
        raisingSince = 0;
        CompoundTag tag = player == null ? null : monitorTag(player.getMainHandItem());
        if (tag == null || !tag.getBoolean("Linked") || mc.gameMode == null) {
            ended = true;
            return;
        }
        bypass = true;
        try {
            mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
        } finally {
            bypass = false;
        }
        // Out of range or the drone is gone: SBW refuses and the station comes back down.
        if (isControlling(player.getMainHandItem())) controlling = true;
        else ended = true;
        quietUntil = System.nanoTime() + QUIET_NS;
    }

    private static void render(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        long now = System.nanoTime();
        hudAt = now;
        boolean flying = mc.player != null && isControlling(mc.player.getMainHandItem());
        if (flying != shownControlling) clearingSince = now;
        shownControlling = flying;
        float alpha = 1f - clamp01((now - clearingSince) / (float) STATIC_OUT_NS);
        if (raisingSince != 0) {
            float in = (now - raisingSince - (TO_FACE_NS - STATIC_IN_NS)) / (float) STATIC_IN_NS;
            alpha = Math.max(alpha, clamp01(in));
        }
        if (alpha > 0.01f) drawStatic(g, alpha);
    }

    /** Lost-signal snow with scanlines, tinted like the station's screen. */
    private static void drawStatic(GuiGraphics g, float alpha) {
        int w = g.guiWidth(), h = g.guiHeight();
        g.fill(0, 0, w, h, argb(alpha * 0.9f, 6, 10, 7));
        var random = new SplittableRandom(System.nanoTime() / 16_000_000L);
        int cell = 6;
        for (int y = 0; y < h; y += cell) {
            for (int x = 0; x < w; x += cell) {
                int v = random.nextInt(256);
                if (v > 150) {
                    g.fill(x, y, x + cell, y + cell, argb(alpha * (v - 150) / 150f, 160, 200, 170));
                }
            }
        }
        for (int y = 0; y < h; y += 3) g.fill(0, y, w, y + 1, argb(alpha * 0.3f, 0, 0, 0));
    }

    static boolean isControlling(ItemStack stack) {
        CompoundTag tag = monitorTag(stack);
        return tag != null && tag.getBoolean("Using") && tag.getBoolean("Linked");
    }

    private static CompoundTag monitorTag(ItemStack stack) {
        var key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (!key.getNamespace().equals("superbwarfare") || !key.getPath().equals("monitor")) {
            return null;
        }
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).getUnsafe();
    }

    private static int argb(float alpha, int r, int g, int b) {
        return Math.round(clamp01(alpha) * 255) << 24 | r << 16 | g << 8 | b;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : Math.min(v, 1f);
    }
}
