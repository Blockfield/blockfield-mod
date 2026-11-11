package com.lunares.blockfield.client.render;

import com.lunares.blockfield.client.gui.BfTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;

/**
 * One shared killfeed entry: attacker and target names around the cause-resolved weapon silhouette.
 * The cause comes from the death event (killing weapon or damage), never from the killer's current
 * held item at render time; unknown and environmental deaths get an honest neutral mark instead of
 * a false firearm.
 */
public final class KillfeedEntry {
    private KillfeedEntry() {}

    /** What the death event says killed. */
    public enum Cause {
        GUN,
        KNIFE,
        EXPLOSIVE,
        ENVIRONMENT
    }

    /**
     * Cause priority, most specific first: a suicide is never a weapon kill, and knife/explosive
     * damage wins over whatever the killer holds.
     */
    public static Cause resolve(boolean suicide, boolean knife, boolean explosive, boolean gun) {
        if (suicide) return Cause.ENVIRONMENT;
        if (knife) return Cause.KNIFE;
        if (explosive) return Cause.EXPLOSIVE;
        if (gun) return Cause.GUN;
        return Cause.ENVIRONMENT;
    }

    public static final int ICON_W = 30;
    static final int GAP = 6;
    public static final int ROW_H = 12;

    public record Row(String attacker, String target, int width) {}

    /**
     * Right-alignable row: each name is capped at half the room so a long name cannot eat the other
     * side or the icon.
     */
    public static Row layout(Font font, String attacker, String target, int maxWidth) {
        int each = Math.max(20, (maxWidth - ICON_W - GAP * 2) / 2);
        String left = BfTheme.ellipsize(font, attacker, each);
        String right = BfTheme.ellipsize(font, target, each);
        return new Row(left, right, font.width(left) + GAP + ICON_W + GAP + font.width(right));
    }

    /**
     * Draws the row ending at {@code right}. Firearms use {@code gunDraw} (the killing weapon's
     * silhouette); knife and explosives use shared vector shapes; anything else gets the neutral
     * fallback. Returns the next row's y.
     */
    public static int render(
            GuiGraphics graphics,
            Font font,
            Row row,
            int right,
            int y,
            int attackerColor,
            int targetColor,
            GunDraw gunDraw,
            Cause cause) {
        int x = right - row.width();
        graphics.drawString(font, row.attacker(), x, y, attackerColor, false);
        int iconX = x + font.width(row.attacker()) + GAP;
        boolean drawn =
                switch (cause) {
                    case GUN -> gunDraw.draw(iconX, y - 1, ICON_W, ROW_H);
                    case KNIFE -> {
                        ItemSilhouette.draw(
                                graphics,
                                "knife",
                                ItemSilhouette.Style.FLAT,
                                iconX,
                                y - 1,
                                ICON_W,
                                ROW_H,
                                0.5f);
                        yield true;
                    }
                    case EXPLOSIVE -> {
                        ItemSilhouette.draw(
                                graphics,
                                "frag",
                                ItemSilhouette.Style.FLAT,
                                iconX,
                                y - 1,
                                ICON_W,
                                ROW_H,
                                0.5f);
                        yield true;
                    }
                    case ENVIRONMENT -> false;
                };
        if (!drawn) neutral(graphics, iconX + ICON_W / 2, y + ROW_H / 2 - 1);
        graphics.drawString(font, row.target(), iconX + ICON_W + GAP, y, targetColor, false);
        return y + ROW_H;
    }

    /** Neutral fallback: a small muted square, never a weapon silhouette. */
    static void neutral(GuiGraphics graphics, int centerX, int centerY) {
        graphics.fill(centerX - 2, centerY - 2, centerX + 3, centerY + 3, BfTheme.MUTED_DARK);
    }

    /** Draws the killing firearm fitted into the icon box; false when the stack has none. */
    public interface GunDraw {
        boolean draw(float x, float y, float width, float height);
    }

    /** GunDraw over a record stack; kept static so tests can stub it without graphics. */
    public static GunDraw ofStack(GuiGraphics graphics, ItemStack stack) {
        return (x, y, width, height) ->
                ItemSilhouette.draw(
                        graphics, stack, ItemSilhouette.Style.GRADIENT, x, y, width, height, 0.5f);
    }
}
