package com.lunares.blockfield.client.render;

import com.atsuishio.superbwarfare.event.KillMessageHandler;
import com.atsuishio.superbwarfare.tools.DamageTypeTool;
import com.atsuishio.superbwarfare.tools.LivingKillRecord;
import com.lunares.blockfield.client.gui.BfTheme;
import com.lunares.blockfield.common.item.KitKnife;
import com.tacz.guns.api.item.IGun;
import java.util.Set;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Blockfield killfeed: one shared entry per death, its weapon silhouette resolved from the death
 * event's weapon (see KillMessageWeaponMixin), with knife/explosion/environment fallbacks. Room
 * isolation and reset stay on the shared record queue (server RoomKillFeed, KillFeedReset); this
 * renderer only reads it.
 */
public final class KillfeedHud {
    private KillfeedHud() {}

    static final int MARGIN_X = 10;
    private static final Set<String> EXPLOSIONS =
            Set.of(
                    "minecraft:explosion",
                    "minecraft:player_explosion",
                    "minecraft:fireworks",
                    "superbwarfare:projectile_explosion",
                    "superbwarfare:custom_explosion");
    private static final Set<String> FIRE =
            Set.of(
                    "minecraft:in_fire",
                    "minecraft:on_fire",
                    "minecraft:lava",
                    "superbwarfare:burn");
    private static final boolean TACZ = FabricLoader.getInstance().isModLoaded("tacz");

    public static void init() {
        HudRenderCallback.EVENT.register(KillfeedHud::render);
    }

    private static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        if (!FabricLoader.getInstance().isModLoaded("superbwarfare")) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.options.keyPlayerList.isDown() || mc.screen != null) return;
        if (!HudRenderer.isHudActive()) return;
        var queue = KillMessageHandler.INSTANCE.getQUEUE();
        if (queue.isEmpty()) return;
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int right = screenWidth - MARGIN_X;
        // Below the top stack (bar, protection chip, zone banner), never over the center HUD.
        int y = HudRenderer.topStackBottom() + 4;
        int maxWidth = Math.max(120, Math.min(320, screenWidth - MARGIN_X - 10));
        float partial = deltaTracker.getGameTimeDeltaPartialTick(true);
        for (LivingKillRecord record : queue.toArray(new LivingKillRecord[0])) {
            if (record == null) continue;
            y = renderEntry(graphics, mc.font, record, right, y, maxWidth, partial);
        }
    }

    static int renderEntry(
            GuiGraphics graphics,
            Font font,
            LivingKillRecord record,
            int right,
            int y,
            int maxWidth,
            float partial) {
        String attacker = record.getAttacker().getDisplayName().getString();
        String target = record.getTarget().getDisplayName().getString();
        var row = KillfeedEntry.layout(font, attacker, target, maxWidth);
        float alpha = fade(record, partial);
        if (alpha < 1f) graphics.setColor(1f, 1f, 1f, alpha);
        KillfeedEntry.render(
                graphics,
                font,
                row,
                right,
                y,
                teamColor(record.getAttacker()),
                teamColor(record.getTarget()),
                KillfeedEntry.ofStack(graphics, record.getStack()),
                cause(record));
        if (alpha < 1f) graphics.setColor(1f, 1f, 1f, 1f);
        return y + KillfeedEntry.ROW_H;
    }

    /** Expiry fade mirrors the queue's own timing so entries vanish exactly when polled. */
    static float fade(LivingKillRecord record, float partial) {
        if (record.getTick() < KillMessageHandler.HOLD_TICKS) return 1f;
        int span = record.getFastRemove() ? 2 : 20;
        float t = (record.getTick() + partial - KillMessageHandler.HOLD_TICKS) / span;
        return Math.max(0f, 1f - (float) Math.pow(Math.min(1f, t), 5));
    }

    static KillfeedEntry.Cause cause(LivingKillRecord record) {
        boolean suicide = record.getAttacker().getId() == record.getTarget().getId();
        ItemStack stack = record.getStack();
        boolean knife =
                KitKnife.is(stack) || DamageTypeTool.INSTANCE.isKnifeDamage(record.getDamageType());
        String damage = record.getDamageType().location().toString();
        boolean explosive =
                EXPLOSIONS.contains(damage)
                        || (FIRE.contains(damage) && record.getAttacker() instanceof Player);
        boolean gun = false;
        if (!knife
                && !explosive
                && !stack.isEmpty()
                && WeaponHudSilhouettes.kind(stack) == null
                && DamageTypeTool.isCompatGunDamage(
                        record.getDamageType(), record.getTarget().level().registryAccess()))
            gun = isFirearm(stack);
        return KillfeedEntry.resolve(suicide, knife, explosive, gun);
    }

    static boolean isFirearm(ItemStack stack) {
        if (TACZ && IGun.getIGunOrNull(stack) != null) return true;
        return stack.getItem() instanceof com.atsuishio.superbwarfare.item.gun.GunItem;
    }

    static int teamColor(Entity entity) {
        var team = entity.getTeam();
        if (team == null) return BfTheme.TEXT;
        ChatFormatting color = team.getColor();
        if (color == null) return BfTheme.TEXT;
        Integer value = color.getColor();
        return value == null ? BfTheme.TEXT : value;
    }
}
