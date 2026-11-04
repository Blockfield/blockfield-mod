package com.lunares.blockfield.client.render;

import com.lunares.blockfield.client.gui.BfTheme;
import com.lunares.blockfield.client.gui.UiMotion;
import com.lunares.blockfield.common.grenade.GrenadeThrowSupport;
import com.lunares.blockfield.common.item.KitKnife;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The bottom of the screen: health and plates on the left, the hotbar under the stamina line in the
 * centre, the held item and its ammunition on the right, over a scrim and without boxes. Vanilla,
 * TaCZ, ParCool and SBW draw none of these (VanillaBottomHudMixin, GunHudOverlayMixin,
 * SbwBottomOverlayMixin, register()). Sizes are GUI pixels, laid out for GUI scale 3 at 1080p.
 */
public final class WeaponHud {
    private static final Logger LOGGER = LogManager.getLogger("BlockField-WeaponHud");
    private static final boolean TACZ = FabricLoader.getInstance().isModLoaded("tacz");
    private static final int MARGIN = 15, BOTTOM = 13, VITALS = 120, SCRIM = 65;
    private static final int SLOT = 29, SLOT_HEIGHT = 25, HOTBAR_BOTTOM = 10, STAMINA = 140;
    // Room the held-item cluster usually takes.
    private static final int HELD = 280;
    // TTF glyphs of every size sit on the line 7 GUI pixels below the draw position.
    private static final float BASELINE = 7;
    // Sizes against the faces' own (10 and 26 GUI pixels).
    private static final float CAPTION = 0.5f, KEY = 0.43f, STACK = 0.57f, RESERVE = 0.87f;
    private static final float HEALTH = 0.76f, PLATES = 1.16f, AMMO = 1.13f;
    private static final int[] AMMO_GROUPS = {5, 10, 25, 50, 100, 250, 500, 1000};
    private static final String[] CROSS = {
        "..###..", "..###..", "#######", "#######", "#######", "..###..", "..###.."
    };
    // SBW's server defaults (MiscConfig): plates a vest holds, 15 points each.
    // ponytail: defaults, read MiscConfig if the server ever changes them.
    private static final TagKey<Item> MILITARY_ARMOR = sbwTag("military_armor"),
            HEAVY_ARMOR = sbwTag("military_armor_heavy");
    private static final int PLATE_POINTS = 15;
    private static long staminaShownAt;
    private static final UiMotion HEALTH_FILL = new UiMotion(),
            PLATE_FILL = new UiMotion(),
            STAMINA_FILL = new UiMotion(),
            SELECTION = new UiMotion();
    private static Player motionPlayer;
    private static final RocketFill RPG_FILL = new RocketFill();

    private WeaponHud() {}

    /**
     * ParCool's stamina HUD is replaced; its own gates (infinite stamina, inactive) still apply.
     */
    public static void register() {
        com.alrex.parcool.fabric.ParCoolEvents.register(
                event -> {
                    if (event
                            instanceof
                            com.alrex.parcool.api.client.gui.ParCoolHUDEvent.RenderEvent render) {
                        staminaShownAt = Util.getMillis();
                        // The workshop has no WeaponHud, so ParCool's own bar stays for visitors.
                        if (!inWorkshop()) render.setCanceled(true);
                    }
                });
    }

    /**
     * The bottom HUD stays under the chat, as vanilla's hotbar does, and goes under other screens.
     */
    public static boolean visible() {
        Minecraft mc = Minecraft.getInstance();
        return !mc.options.hideGui
                && !mc.options.keyPlayerList.isDown()
                && (mc.screen == null || mc.screen instanceof ChatScreen)
                && mc.player != null
                && !mc.player.isSpectator()
                && !inWorkshop();
    }

    /** Workshop builders play creative and get vanilla's hotbar, health and item names back. */
    public static boolean inWorkshop() {
        var level = Minecraft.getInstance().level;
        // The workshop hub and all its maps live here (server Workshop/WorkshopMaps).
        return level != null
                && level.dimension().location().getNamespace().equals("blockfield_workshop");
    }

    public static void render(GuiGraphics graphics) {
        if (!visible()) {
            motionPlayer = null;
            RPG_FILL.clear();
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        boolean snap = motionPlayer != player;
        motionPlayer = player;
        long now = System.nanoTime();
        // A GUI narrower than the layout (GUI scale 4 at 1080p, 3 at 720p) scales the whole bottom
        // HUD down, as the screen would at scale 3.
        float scale = hudScale(graphics.guiWidth());
        int height = graphics.guiHeight();
        scrim(graphics, graphics.guiWidth(), height, Math.round(SCRIM * scale));
        var pose = graphics.pose();
        pose.pushPose();
        pose.translate(0, height, 0);
        pose.scale(scale, scale, 1);
        try {
            int width = Math.round(graphics.guiWidth() / scale);
            vitals(graphics, player, MARGIN, -BOTTOM, now, snap);
            hotbar(graphics, player, width / 2, -HOTBAR_BOTTOM, now, snap);
            ItemStack held = player.getMainHandItem();
            Content content = content(held, player);
            if (content != null) held(graphics, held, content, width - MARGIN, -BOTTOM);
        } finally {
            pose.popPose();
        }
    }

    private static void scrim(GuiGraphics graphics, int width, int height, int size) {
        int top = height - size;
        graphics.fillGradient(0, top, width, top + size * 36 / 100, 0, shade(0.16f));
        graphics.fillGradient(
                0,
                top + size * 36 / 100,
                width,
                height - size * 28 / 100,
                shade(0.16f),
                shade(0.40f));
        graphics.fillGradient(
                0, height - size * 28 / 100, width, height, shade(0.40f), shade(0.58f));
    }

    private static int shade(float alpha) {
        return BfTheme.alpha(BfTheme.BG, Math.round(alpha * 255));
    }

    static float hudScale(int width) {
        return Math.min(1, width / (float) (MARGIN * 2 + VITALS + 9 * SLOT + HELD + 16));
    }

    public static int chatBottomInset(int width) {
        // Delayed-chat controls extend 9 pixels below the message anchor; leave a 4-pixel gap.
        return Math.max(40, (int) Math.ceil(SCRIM * hudScale(width)) + 13);
    }

    static float animate(UiMotion motion, float target, long now, boolean snap) {
        if (snap) motion.snap(target);
        else motion.to(target, now);
        return motion.value(now);
    }

    private static void vitals(
            GuiGraphics graphics, Player player, int x, int bottom, long now, boolean snap) {
        float health = Math.max(0, player.getHealth()), max = Math.max(1, player.getMaxHealth());
        ItemStack vest = player.getItemBySlot(EquipmentSlot.CHEST);
        var tag = vest.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        boolean plated = tag.contains("ArmorPlate");
        int barTop = bottom - (plated ? 4 : 2);
        segments(
                graphics,
                x,
                barTop,
                2,
                animate(HEALTH_FILL, Mth.clamp(health / max, 0, 1), now, snap),
                health / max <= 0.3f ? BfTheme.DANGER : BfTheme.OK);
        float baseline = barTop - 5;
        pixels(graphics, CROSS, x, (int) baseline - 9, 1, BfTheme.OK);
        float right =
                x
                        + 11
                        + text(
                                graphics,
                                BfTheme.hudFont("hud_big"),
                                Integer.toString(Mth.ceil(health)),
                                x + 11,
                                baseline,
                                HEALTH,
                                BfTheme.TEXT,
                                0);
        if (!plated) {
            PLATE_FILL.snap(0);
            return;
        }
        int level = vest.is(HEAVY_ARMOR) ? 3 : vest.is(MILITARY_ARMOR) ? 2 : 1;
        double plates = Math.max(0, tag.getDouble("ArmorPlate"));
        segments(
                graphics,
                x,
                bottom - 1,
                1,
                animate(
                        PLATE_FILL,
                        Mth.clamp((float) (plates / (level * PLATE_POINTS)), 0, 1),
                        now,
                        snap),
                BfTheme.TEXT_SECONDARY);
        int shield = (int) right + 7;
        tint(graphics, BfTheme.TEXT_SECONDARY);
        ItemSilhouette.draw(
                graphics, "shield", ItemSilhouette.Style.FLAT, shield, baseline - 10, 8, 9, 0);
        graphics.setColor(1, 1, 1, 1);
        text(
                graphics,
                BfTheme.hudFont("hud_figures"),
                Integer.toString((int) Math.ceil(plates)),
                shield + 11,
                baseline,
                PLATES,
                BfTheme.TEXT_SECONDARY,
                0);
    }

    /** Ten segments across the vitals, lit up to {@code fill}. */
    private static void segments(
            GuiGraphics graphics, int x, int y, int height, float fill, int color) {
        int track = BfTheme.alpha(BfTheme.TEXT, 0x24);
        for (int i = 0; i < 10; i++) {
            int left = x + i * 12;
            int lit = Math.round(11 * Mth.clamp(fill * 10 - i, 0, 1));
            if (lit < 11) graphics.fill(left + lit, y, left + 11, y + height, track);
            if (lit > 0) graphics.fill(left, y, left + lit, y + height, color);
        }
    }

    private static void hotbar(
            GuiGraphics graphics, Player player, int centre, int bottom, long now, boolean snap) {
        var inventory = player.getInventory();
        int left = centre - 9 * SLOT / 2;
        float selection = animate(SELECTION, inventory.selected, now, snap);
        float partial = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false);
        for (int i = 0; i < 9; i++) {
            int x = left + i * SLOT;
            boolean selected = i == inventory.selected;
            ItemStack stack = inventory.getItem(i);
            float cooldown =
                    stack.isEmpty()
                            ? 0
                            : player.getCooldowns().getCooldownPercent(stack.getItem(), partial);
            if (!stack.isEmpty()) {
                int box = selected ? 13 : 11;
                if (selected) graphics.setColor(1, 1, 1, cooldown > 0 ? 0.4f : 1);
                else graphics.setColor(0.953f, 0.906f, 0.816f, cooldown > 0 ? 0.25f : 0.55f);
                ItemSilhouette.draw(
                        graphics,
                        stack,
                        selected ? ItemSilhouette.Style.GRADIENT : ItemSilhouette.Style.FLAT,
                        x + (selected ? 3 : 4),
                        bottom - 8 - box,
                        selected ? 23 : 21,
                        box,
                        0.5f);
                graphics.setColor(1, 1, 1, 1);
                if (stack.getCount() > 1)
                    text(
                            graphics,
                            BfTheme.hudFont("hud"),
                            "×" + stack.getCount(),
                            x + SLOT - 2,
                            bottom - SLOT_HEIGHT + 5,
                            STACK,
                            BfTheme.TEXT,
                            1);
            }
            int lineLeft = x + 12, lineRight = x + SLOT - 12;
            graphics.fill(
                    lineLeft, bottom - 6, lineRight, bottom - 5, BfTheme.alpha(BfTheme.TEXT, 0x2E));
            if (cooldown > 0)
                graphics.fill(
                        lineLeft,
                        bottom - 6,
                        lineLeft + Math.round((lineRight - lineLeft) * cooldown),
                        bottom - 5,
                        BfTheme.TEXT_SECONDARY);
            text(
                    graphics,
                    BfTheme.hudFont("hud"),
                    Integer.toString(i + 1),
                    x + SLOT / 2f,
                    bottom - 1,
                    KEY,
                    selected ? BfTheme.AMBER : BfTheme.alpha(BfTheme.TEXT_SECONDARY, 0x99),
                    0.5f);
        }
        int selectedLeft = left + Math.round(selection * SLOT) + 3;
        graphics.fill(selectedLeft, bottom - 6, selectedLeft + SLOT - 6, bottom - 5, BfTheme.AMBER);
        ItemStack selectedStack = inventory.getSelected();
        float selectedCooldown =
                selectedStack.isEmpty()
                        ? 0
                        : player.getCooldowns()
                                .getCooldownPercent(selectedStack.getItem(), partial);
        if (selectedCooldown > 0)
            graphics.fill(
                    selectedLeft,
                    bottom - 6,
                    selectedLeft + Math.round((SLOT - 6) * selectedCooldown),
                    bottom - 5,
                    BfTheme.TEXT_SECONDARY);
        if (Util.getMillis() - staminaShownAt < 250)
            stamina(graphics, player, centre, bottom - SLOT_HEIGHT - 5, now, snap);
        else STAMINA_FILL.snap(1);
    }

    private static void stamina(
            GuiGraphics graphics, Player player, int centre, int bottom, long now, boolean snap) {
        var stamina = com.alrex.parcool.api.Stamina.get(player);
        int color = stamina.isExhausted() ? BfTheme.DANGER : BfTheme.AMBER;
        int left = centre - (7 + 4 + STAMINA) / 2;
        tint(graphics, color);
        ItemSilhouette.draw(
                graphics, "runner", ItemSilhouette.Style.FLAT, left, bottom - 7.5f, 7, 8, 0);
        graphics.setColor(1, 1, 1, 1);
        int barLeft = left + 11, y = bottom - 5, h = 3;
        float fill =
                Mth.clamp(stamina.getValue() / (float) Math.max(1, stamina.getMaxValue()), 0, 1);
        int lit = Math.round(STAMINA * animate(STAMINA_FILL, fill, now, snap));
        graphics.fill(
                barLeft + lit, y, barLeft + STAMINA, y + h, BfTheme.alpha(BfTheme.TEXT, 0x50));
        graphics.fill(barLeft, y, barLeft + lit, y + h, color);
    }

    private static void held(
            GuiGraphics graphics, ItemStack held, Content content, int right, int bottom) {
        Font label = BfTheme.hudFont("hud"),
                figures = BfTheme.hudFont("hud_figures"),
                big = BfTheme.hudFont("hud_big");
        int iconRight = right;
        if (content.status() != null) {
            iconRight -=
                    10
                            + (int)
                                    text(
                                            graphics,
                                            figures,
                                            content.status().getString(),
                                            right,
                                            bottom,
                                            RESERVE,
                                            BfTheme.TEXT_SECONDARY,
                                            1);
        } else if (!content.count().isEmpty()) {
            boolean pips = content.magazine() > 0;
            String pipShape = content.pipShape();
            float baseline = pips ? bottom - pipHeight(pipShape, content.magazine()) - 3 : bottom;
            float count = big.width(content.count()) * AMMO;
            float column =
                    Math.max(
                            figures.width(content.reserve()) * RESERVE,
                            content.mode() == null
                                    ? 0
                                    : spaced(label, content.mode().getString(), KEY) + 4);
            float row = count + (column > 0 ? 3 + column : 0);
            int chamberOffset = chamberOffset(pipShape, content.magazine(), content.chamber());
            int pipsWidth = pips ? magazineWidth(pipShape, content.magazine()) + chamberOffset : 0;
            int left = right - (int) Math.ceil(Math.max(row, pipsWidth));
            text(
                    graphics,
                    big,
                    content.count(),
                    left,
                    baseline,
                    AMMO,
                    content.reloading()
                            ? BfTheme.AMBER
                            : content.low() ? BfTheme.DANGER : BfTheme.TEXT,
                    0);
            float columnLeft = left + count + 3;
            text(
                    graphics,
                    figures,
                    content.reserve(),
                    columnLeft,
                    baseline - 1,
                    RESERVE,
                    BfTheme.TEXT_SECONDARY,
                    0);
            if (content.mode() != null) {
                int chipTop = (int) baseline - 18, chipLeft = (int) columnLeft;
                int chipRight =
                        chipLeft
                                + (int) Math.ceil(spaced(label, content.mode().getString(), KEY))
                                + 4;
                outline(graphics, chipLeft, chipTop, chipRight, chipTop + 7, BfTheme.AMBER_DIM);
                spacedText(
                        graphics,
                        label,
                        content.mode().getString(),
                        chipLeft + 2,
                        chipTop + 5.5f,
                        KEY,
                        BfTheme.AMBER);
            }
            if (pips) {
                if (content.chamber() >= 0) {
                    graphics.setColor(1, 1, 1, content.chamber() > 0 ? 1 : 0x29 / 255f);
                    ItemSilhouette.draw(
                            graphics,
                            pipShape,
                            ItemSilhouette.Style.AMMUNITION,
                            left,
                            bottom - pipHeight(pipShape, content.magazine()),
                            pipWidth(pipShape, content.magazine()),
                            pipHeight(pipShape, content.magazine()),
                            0);
                    graphics.setColor(1, 1, 1, 1);
                }
                magazine(
                        graphics,
                        left + chamberOffset,
                        bottom,
                        magazineRounds(content.loaded(), content.chamber()),
                        content.magazine(),
                        pipShape);
            }
            iconRight = left - 10;
            graphics.fill(
                    iconRight,
                    (int) Math.floor(baseline - 23),
                    iconRight + 1,
                    bottom,
                    BfTheme.alpha(BfTheme.TEXT_SECONDARY, 0x4D));
            iconRight -= 10;
        }
        ItemSilhouette.draw(
                graphics,
                held,
                ItemSilhouette.Style.GRADIENT,
                iconRight - 63,
                bottom - 21,
                63,
                21,
                1);
        String name = held.getHoverName().getString().toUpperCase(Locale.ROOT);
        while (name.length() > 1 && spaced(label, name, CAPTION) > 85)
            name = name.substring(0, name.length() - 1);
        spacedText(
                graphics,
                label,
                name,
                iconRight - spaced(label, name, CAPTION),
                bottom - 25,
                CAPTION,
                BfTheme.TEXT_SECONDARY);
    }

    static float pipPitch(String shape, int size) {
        float pitch =
                switch (shape) {
                    case "round_heavy", "shell" -> 3.7f;
                    case "round_sniper" -> 3.5f;
                    case "rocket" -> 4.7f;
                    default -> 3;
                };
        if (size > 60) {
            float width = pipWidth(shape, size);
            return Math.min(
                    Math.max(pitch, width + 1),
                    (119 - groupCaptionWidth(size) - width) / (pipCount(size) - 1));
        }
        return Math.min(pitch, 119f / Math.max(1, size));
    }

    static float pipWidth(String shape, int size) {
        float width =
                switch (shape) {
                    case "round_heavy", "shell" -> 8 / 3f;
                    case "round_sniper" -> 7 / 3f;
                    case "rocket" -> 11 / 3f;
                    default -> 2;
                };
        if (size > 60)
            return Math.max(
                    width,
                    shape.equals("round_pistol") || shape.equals("shell")
                            ? 4
                            : shape.equals("bullet") ? 3.5f : 3);
        return Math.min(width, pipPitch(shape, size) - 2 / 3f);
    }

    static float pipHeight(String shape) {
        return switch (shape) {
            case "round_pistol" -> 5;
            case "round_heavy" -> 9;
            case "round_sniper", "rocket" -> 10;
            case "shell" -> 7;
            default -> 8;
        };
    }

    static float pipHeight(String shape, int size) {
        return size > 60 ? Math.max(10, pipHeight(shape)) : pipHeight(shape);
    }

    static int roundsPerPip(int size) {
        if (size <= 60) return 1;
        for (int group : AMMO_GROUPS) if (Math.ceilDiv(size, group) <= 20) return group;
        return Math.ceilDiv(size, 20);
    }

    static int pipCount(int size) {
        return size > 0 ? Math.ceilDiv(size, roundsPerPip(size)) : 0;
    }

    private static int groupCaptionWidth(int size) {
        return size > 60 ? 5 + (Integer.toString(roundsPerPip(size)).length() + 1) * 4 : 0;
    }

    static float pipFill(float loaded, int size, int index) {
        int group = roundsPerPip(size);
        int start = index * group, represented = Math.min(group, size - start);
        return Mth.clamp((loaded - start) / represented, 0, 1);
    }

    static int chamberRound(com.tacz.guns.resource.pojo.data.gun.Bolt bolt, boolean loaded) {
        return bolt == com.tacz.guns.resource.pojo.data.gun.Bolt.OPEN_BOLT ? -1 : loaded ? 1 : 0;
    }

    static float magazineRounds(float loaded, int chamber) {
        return Math.max(0, loaded - Math.max(0, chamber));
    }

    static int loadedCapacity(int magazine, int chamber) {
        return magazine + (chamber >= 0 ? 1 : 0);
    }

    static int chamberOffset(String shape, int size, int chamber) {
        return chamber < 0 ? 0 : (int) Math.ceil(pipWidth(shape, size)) + 4;
    }

    static int magazineWidth(String shape, int size) {
        if (size <= 0) return 0;
        return Math.min(
                119,
                (int)
                                Math.ceil(
                                        (pipCount(size) - 1) * pipPitch(shape, size)
                                                + pipWidth(shape, size))
                        + groupCaptionWidth(size));
    }

    /** Large magazines use at most twenty linked cartridges, each representing a marked group. */
    private static void magazine(
            GuiGraphics graphics, int left, int bottom, float loaded, int size, String shape) {
        int count = pipCount(size);
        float pitch = pipPitch(shape, size),
                width = pipWidth(shape, size),
                height = pipHeight(shape, size);
        if (size > 60) {
            int y = Math.round(bottom - height * 0.35f);
            for (int i = 1; i < count; i++)
                graphics.fill(
                        Math.round(left + (i - 1) * pitch + width * 0.8f),
                        y,
                        Math.round(left + i * pitch + width * 0.2f),
                        y + 1,
                        BfTheme.alpha(BfTheme.TEXT_SECONDARY, 0x3D));
            graphics.flush();
        }
        for (int i = 0; i < count; i++) {
            graphics.setColor(1, 1, 1, Mth.lerp(pipFill(loaded, size, i), 0x29 / 255f, 1));
            ItemSilhouette.draw(
                    graphics,
                    shape,
                    ItemSilhouette.Style.AMMUNITION,
                    left + i * pitch,
                    bottom - height,
                    width,
                    height,
                    0);
        }
        graphics.setColor(1, 1, 1, 1);
        if (size > 60)
            text(
                    graphics,
                    BfTheme.hudFont("hud"),
                    "×" + roundsPerPip(size),
                    left + (count - 1) * pitch + width + 5,
                    bottom - height / 2 + 3,
                    STACK,
                    BfTheme.TEXT_SECONDARY,
                    0);
    }

    /** Tints what is blitted next; reset to white after. */
    private static void tint(GuiGraphics graphics, int color) {
        graphics.setColor(
                (color >> 16 & 255) / 255f,
                (color >> 8 & 255) / 255f,
                (color & 255) / 255f,
                (color >>> 24) / 255f);
    }

    private static void outline(
            GuiGraphics graphics, int left, int top, int right, int bottom, int color) {
        graphics.fill(left, top, right, top + 1, color);
        graphics.fill(left, bottom - 1, right, bottom, color);
        graphics.fill(left, top + 1, left + 1, bottom - 1, color);
        graphics.fill(right - 1, top + 1, right, bottom - 1, color);
    }

    private static void pixels(
            GuiGraphics graphics, String[] rows, int x, int y, int cell, int color) {
        for (int row = 0; row < rows.length; row++)
            for (int column = 0; column < rows[row].length(); column++)
                if (rows[row].charAt(column) == '#')
                    graphics.fill(
                            x + column * cell,
                            y + row * cell,
                            x + (column + 1) * cell,
                            y + (row + 1) * cell,
                            color);
    }

    /**
     * Draws {@code value} with its baseline at {@code baseline}, anchored by {@code align} (0 left,
     * 0.5 centre, 1 right), and returns its width.
     */
    private static float text(
            GuiGraphics graphics,
            Font font,
            String value,
            float x,
            float baseline,
            float scale,
            int color,
            float align) {
        float width = font.width(value) * scale;
        var pose = graphics.pose();
        pose.pushPose();
        pose.translate(x - width * align, baseline - BASELINE * scale, 0);
        pose.scale(scale, scale, 1);
        graphics.drawString(font, value, 0, 0, color, false);
        pose.popPose();
        return width;
    }

    /** Capitals tracked out by 0.14 em, as the mockup's captions. */
    private static float spaced(Font font, String value, float scale) {
        return (font.width(value) + 1.4f * Math.max(0, value.length() - 1)) * scale;
    }

    private static void spacedText(
            GuiGraphics graphics,
            Font font,
            String value,
            float x,
            float baseline,
            float scale,
            int color) {
        for (int i = 0; i < value.length(); ) {
            int end = value.offsetByCodePoints(i, 1);
            String glyph = value.substring(i, end);
            x += text(graphics, font, glyph, x, baseline, scale, color, 0) + 1.4f * scale;
            i = end;
        }
    }

    public static boolean replacesSbwAmmoHud() {
        if (!visible()) return false;
        var player = Minecraft.getInstance().player;
        return "superbwarfare:rpg".equals(itemId(player.getMainHandItem()))
                && SbwRpg.content(player.getMainHandItem(), player) != null;
    }

    private static Content content(ItemStack held, Player player) {
        String id = itemId(held);
        if (!id.equals("superbwarfare:rpg")) RPG_FILL.clear();
        if (TACZ) {
            if (Gun.is(held)) return Gun.content(held, player);
            Gun.clear();
        }
        if (held.isEmpty()) return null;
        if (id.equals("superbwarfare:rpg")) return SbwRpg.content(held, player);
        if (KitKnife.is(held)) return Content.count("", "");
        if (id.equals("superbwarfare:monitor")) {
            var tag = held.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
            String linked = tag.getString("LinkedDrone");
            boolean hasLink = monitorLinked(tag.getBoolean("Linked"), linked);
            return new Content(
                    "",
                    "",
                    null,
                    0,
                    0,
                    -1,
                    "bullet",
                    false,
                    false,
                    Component.translatable(
                            hasLink
                                    ? "hud.blockfield.monitor.linked"
                                    : "hud.blockfield.monitor.unlinked"));
        }
        if (GrenadeThrowSupport.kind(held) == null
                && !id.equals("superbwarfare:drone")
                && !id.equals("superbwarfare:scout_drone")
                && !id.equals("superbwarfare:medical_kit")
                && !id.equals("superbwarfare:armor_plate")) return null;
        boolean infinite =
                id.equals("superbwarfare:armor_plate")
                        && held.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY)
                                .copyTag()
                                .getBoolean("Infinite");
        int count = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack candidate = player.getInventory().getItem(slot);
            if (candidate.getItem() == held.getItem()
                    && sameSupply(
                            id,
                            GrenadeThrowSupport.throwableId(held),
                            itemId(candidate),
                            GrenadeThrowSupport.throwableId(candidate)))
                count += candidate.getCount();
        }
        return Content.count(amount(count, infinite), "");
    }

    private static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static TagKey<Item> sbwTag(String path) {
        return TagKey.create(
                Registries.ITEM, ResourceLocation.fromNamespaceAndPath("superbwarfare", path));
    }

    static boolean sameSupply(String item, String subtype, String otherItem, String otherSubtype) {
        return item.equals(otherItem)
                && (!item.equals("lrtactical:throwable") || subtype.equals(otherSubtype));
    }

    static String amount(int value, boolean infinite) {
        return infinite ? "∞" : Integer.toString(Math.clamp(value, 0, 9999));
    }

    static boolean monitorLinked(boolean linked, String droneId) {
        if (!linked) return false;
        try {
            return UUID.fromString(droneId).toString().equalsIgnoreCase(droneId);
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    /**
     * What the right cluster shows: the count, reserve and fire mode, rounds loaded of a magazine
     * (0 without pips), a chamber round (-1 for guns without a chamber), or a status line instead
     * of numbers.
     */
    private record Content(
            String count,
            String reserve,
            Component mode,
            float loaded,
            int magazine,
            int chamber,
            String pipShape,
            boolean low,
            boolean reloading,
            Component status) {
        static Content count(String count, String reserve) {
            return new Content(count, reserve, null, 0, 0, -1, "bullet", false, false, null);
        }
    }

    static final class AmmoDisplay {
        private String weapon;
        private int loaded;
        private int reserve;
        private boolean reloading;
        private float progress;
        private final UiMotion fill = new UiMotion();

        void update(String weapon, int loaded, int reserve, boolean reloading) {
            if (!weapon.equals(this.weapon) || !reloading) {
                this.loaded = loaded;
                this.reserve = reserve;
                progress = 0;
                fill.snap(loaded);
            }
            this.weapon = weapon;
            this.reloading = reloading;
        }

        int loaded() {
            return loaded;
        }

        int reserve() {
            return reserve;
        }

        float pips(int magazine, int target, float clipProgress, long now) {
            int base = Math.clamp(loaded, 0, magazine);
            if (!reloading) return base;
            if (clipProgress >= 0) {
                progress = Math.max(progress, Mth.clamp(clipProgress, 0, 1));
                float value = Math.clamp(target, 0, magazine) * progress;
                fill.snap(value);
            } else fill.to(Math.clamp(target, base, magazine), now);
            return Mth.clamp(fill.value(now), 0, magazine);
        }

        void clear() {
            weapon = null;
        }
    }

    static float reloadTimerProgress(int remaining, int duration) {
        // SBW counts down from duration + 2 and feeds at 1.
        return duration > 0 ? Mth.clamp((duration - remaining + 1) / (float) duration, 0, 1) : 0;
    }

    static final class RocketFill {
        private final UiMotion fill = new UiMotion();
        private String weapon;
        private boolean active;
        private float progress;

        float value(String weapon, int loaded, boolean reloading, float progress, long now) {
            if (!weapon.equals(this.weapon) || !reloading || !active) {
                this.progress = 0;
                fill.snap(reloading ? 0 : Math.clamp(loaded, 0, 1));
            }
            this.weapon = weapon;
            active = reloading;
            if (reloading) {
                this.progress = Math.max(this.progress, Mth.clamp(progress, 0, 1));
                fill.to(this.progress, now);
            }
            return fill.value(now);
        }

        void clear() {
            weapon = null;
        }
    }

    static boolean activeReloadClip(
            com.tacz.guns.api.client.animation.ObjectAnimationRunner runner) {
        return reloadRunner(runner) != null;
    }

    private static com.tacz.guns.api.client.animation.ObjectAnimationRunner reloadRunner(
            com.tacz.guns.api.client.animation.ObjectAnimationRunner runner) {
        if (runner == null) return null;
        if (runner.isTransitioning()) {
            var next = reloadRunner(runner.getTransitionTo());
            if (next != null) return next;
        }
        boolean active =
                runner.getAnimation().name.contains("reload")
                        && !runner.isHolding()
                        && !runner.isStopped()
                        && (runner.isRunning() || runner.isPausing() || runner.isTransitioning());
        return active ? runner : null;
    }

    static float reloadProgress(com.tacz.guns.api.client.animation.ObjectAnimationRunner runner) {
        runner = reloadRunner(runner);
        if (runner == null) return -1;
        String name = runner.getAnimation().name;
        // Shell reloads repeat short clips; follow rounds fed instead of a magazine ratio.
        if (runner.getAnimation().playType
                        == com.tacz.guns.api.client.animation.ObjectAnimation.PlayType.LOOP
                || name.contains("_intro")
                || name.contains("_start")
                || name.contains("_loop")
                || name.contains("_end")
                || name.contains("_finish")) return -1;
        float duration = runner.getAnimation().getMaxEndTimeS();
        return duration > 0 ? Mth.clamp(runner.getProgressNs() / (duration * 1e9f), 0, 1) : 0;
    }

    /** TaCZ guns, counted as TaCZ's own overlay counts them (GunHudCache). */
    private static final class Gun {
        private static final AmmoDisplay DISPLAY = new AmmoDisplay();
        private static Player owner;

        static void clear() {
            DISPLAY.clear();
        }

        static boolean is(ItemStack held) {
            return com.tacz.guns.api.item.IGun.getIGunOrNull(held) != null;
        }

        static Content content(ItemStack held, Player player) {
            if (!com.tacz.guns.config.client.RenderConfig.GUN_HUD_ENABLE.get()) {
                DISPLAY.clear();
                return null;
            }
            if (owner != player) {
                DISPLAY.clear();
                owner = player;
            }
            var gun = com.tacz.guns.api.item.IGun.getIGunOrNull(held);
            var data =
                    com.tacz.guns.api.TimelessAPI.getClientGunIndex(gun.getGunId(held))
                            .map(com.tacz.guns.client.resource.index.ClientGunIndex::getGunData)
                            .orElse(null);
            if (data == null) return null;
            boolean inventory = gun.useInventoryAmmo(held);
            int reserve = com.lunares.blockfield.client.tacz.GunHudCache.reserve();
            int chamber = chamberRound(data.getBolt(), gun.hasBulletInBarrel(held));
            int loaded =
                    (inventory ? reserve : gun.getCurrentAmmoCount(held)) + Math.max(0, chamber);
            int magazine = com.lunares.blockfield.client.tacz.GunHudCache.magazine();
            int capacity = loadedCapacity(magazine, chamber);
            var reload = reloadRunner(held);
            float progress = reloadProgress(reload);
            boolean reloading =
                    reload != null
                            || com.tacz.guns.api.entity.IGunOperator.fromLivingEntity(player)
                                    .getSynReloadState()
                                    .getStateType()
                                    .isReloading();
            // Hold TaCZ's early-fed numbers until its finishing clip ends.
            DISPLAY.update(
                    player.getInventory().selected + ":" + ItemSilhouette.key(held),
                    loaded,
                    reserve,
                    reloading);
            boolean infinite = !gun.useDummyAmmo(held) && data.getReloadData().isInfinite();
            int target =
                    progress < 0
                            ? loaded
                            : infinite ? capacity : DISPLAY.loaded() + DISPLAY.reserve();
            float pips = DISPLAY.pips(capacity, target, progress, System.nanoTime());
            loaded = DISPLAY.loaded();
            reserve = DISPLAY.reserve();
            boolean low =
                    loaded < magazine * 0.25 && loaded < 10
                            || data.hasHeatData() && gun.isOverheatLocked(held);
            var mode = gun.getFireMode(held);
            return new Content(
                    amount(loaded, false),
                    inventory ? "" : amount(reserve, infinite),
                    mode == com.tacz.guns.api.item.gun.FireMode.UNKNOWN
                            ? null
                            : Component.translatable(
                                    "hud.blockfield.fire_mode."
                                            + mode.name().toLowerCase(Locale.ROOT)),
                    pips,
                    magazine,
                    chamber,
                    WeaponHudSilhouettes.ammunition(
                            data.getAmmoId() == null ? null : data.getAmmoId().toString()),
                    low,
                    reloading,
                    null);
        }

        private static com.tacz.guns.api.client.animation.ObjectAnimationRunner reloadRunner(
                ItemStack held) {
            var machine =
                    com.tacz.guns.api.TimelessAPI.getGunDisplay(held)
                            .map(
                                    com.tacz.guns.client.resource.GunDisplayInstance
                                            ::getAnimationStateMachine)
                            .orElse(null);
            if (machine == null || !machine.isInitialized()) return null;
            var controller = machine.getAnimationController();
            Iterable<Integer> tracks = controller.getUpdatingTrackArray();
            if (tracks == null) {
                int count =
                        ((com.lunares.blockfield.client.tacz.AnimationControllerAccessor)
                                        controller)
                                .blockfield$getCurrentRunners()
                                .size();
                tracks = () -> java.util.stream.IntStream.range(0, count).iterator();
            }
            for (int track : tracks) {
                var runner = WeaponHud.reloadRunner(controller.getAnimation(track));
                if (runner != null) return runner;
            }
            return null;
        }
    }

    /** SBW is optional and has no compile dependency; missing API leaves its own HUD enabled. */
    private static final class SbwRpg {
        private static final Api API = api();
        private static boolean warned;
        private static Player owner;

        private record Api(
                Method from,
                Field ammo,
                Field virtualAmmo,
                Method value,
                Method backup,
                Method infinite,
                Method backpack,
                Method reloading,
                Field reload,
                Method time,
                Method empty,
                Method property,
                Object emptyReloadTime,
                Object normalReloadTime,
                Class<?> vehicle,
                Method banHand,
                Object ammoHud,
                Method ammoHudValue) {}

        private static Api api() {
            try {
                ClassLoader loader = WeaponHud.class.getClassLoader();
                Class<?> data =
                        Class.forName(
                                "com.atsuishio.superbwarfare.data.gun.GunData", false, loader);
                Class<?> vehicle =
                        Class.forName(
                                "com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity",
                                false,
                                loader);
                Field ammo = data.getField("ammo");
                Field reload = data.getField("reload");
                Class<?> property =
                        Class.forName(
                                "com.atsuishio.superbwarfare.data.gun.GunProp", false, loader);
                Object ammoHud =
                        Class.forName(
                                        "com.atsuishio.superbwarfare.config.client.DisplayConfig",
                                        true,
                                        loader)
                                .getField("AMMO_HUD")
                                .get(null);
                return new Api(
                        data.getMethod("from", ItemStack.class),
                        ammo,
                        data.getField("virtualAmmo"),
                        ammo.getType().getMethod("get"),
                        data.getMethod("countBackupAmmo", Entity.class),
                        data.getMethod("hasInfiniteBackupAmmo", Entity.class),
                        data.getMethod("useBackpackAmmo"),
                        data.getMethod("reloading"),
                        reload,
                        reload.getType().getMethod("time"),
                        reload.getType().getMethod("empty"),
                        data.getMethod("get", property),
                        property.getField("EMPTY_RELOAD_TIME").get(null),
                        property.getField("NORMAL_RELOAD_TIME").get(null),
                        vehicle,
                        vehicle.getMethod("banHand", LivingEntity.class),
                        ammoHud,
                        ammoHud.getClass().getMethod("get"));
            } catch (ReflectiveOperationException | LinkageError error) {
                LOGGER.warn("RPG HUD API unavailable; retaining SBW HUD", error);
                return null;
            }
        }

        private static Content content(ItemStack held, Player player) {
            if (API == null) return null;
            if (owner != player) {
                RPG_FILL.clear();
                owner = player;
            }
            try {
                if (!(Boolean) API.ammoHudValue().invoke(API.ammoHud())) {
                    RPG_FILL.clear();
                    return null;
                }
                Entity vehicle = player.getVehicle();
                if (API.vehicle().isInstance(vehicle)
                        && (boolean) API.banHand().invoke(vehicle, player)) {
                    RPG_FILL.clear();
                    return null;
                }
                Object data = API.from().invoke(null, held);
                int ammo = ((Number) API.value().invoke(API.ammo().get(data))).intValue();
                int backup = ((Number) API.backup().invoke(data, player)).intValue();
                int virtual = ((Number) API.value().invoke(API.virtualAmmo().get(data))).intValue();
                boolean infinite = (boolean) API.infinite().invoke(data, player);
                boolean backpack = (boolean) API.backpack().invoke(data);
                boolean reloading = (boolean) API.reloading().invoke(data);
                int loaded = backpack ? backup - virtual : ammo;
                float progress = 0;
                if (reloading) {
                    Object reload = API.reload().get(data);
                    int remaining = ((Number) API.time().invoke(reload)).intValue();
                    int duration =
                            ((Number)
                                            API.property()
                                                    .invoke(
                                                            data,
                                                            (boolean) API.empty().invoke(reload)
                                                                    ? API.emptyReloadTime()
                                                                    : API.normalReloadTime()))
                                    .intValue();
                    progress = reloadTimerProgress(remaining, duration);
                }
                float pips =
                        RPG_FILL.value(
                                player.getInventory().selected + ":" + ItemSilhouette.key(held),
                                loaded,
                                reloading,
                                progress,
                                System.nanoTime());
                return new Content(
                        amount(loaded, backpack && infinite),
                        backpack ? "" : amount(backup - virtual, infinite),
                        null,
                        pips,
                        1,
                        -1,
                        "rocket",
                        false,
                        reloading,
                        null);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                if (!warned) LOGGER.warn("RPG HUD data unavailable; retaining SBW HUD", error);
                warned = true;
                RPG_FILL.clear();
                return null;
            }
        }
    }
}
