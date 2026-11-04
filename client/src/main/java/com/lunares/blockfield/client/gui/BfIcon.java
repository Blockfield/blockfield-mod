package com.lunares.blockfield.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/** Lucide icons via Iconify; source SVGs and license are in dev/ui/icons. */
public enum BfIcon {
    MENU("menu"),
    MAP("map"),
    RANK("medal"),
    SQUAD("users"),
    TARGET("target"),
    FLAG("flag"),
    KIT("briefcase-medical"),
    ASSAULT("swords"),
    MARKSMAN("focus"),
    ENGINEER("wrench"),
    SNIPER("crosshair"),
    DRONE("drone"),
    CHART("chart-no-axes-column-increasing"),
    BACK("arrow-left"),
    ARROW("arrow-right"),
    CHECK("check"),
    SUN("sun"),
    SUNSET("sunset"),
    MOON("moon"),
    RANDOM("shuffle"),
    SLOW("chevron-right"),
    FAST("chevrons-right"),
    SHIELD("shield-check"),
    CROSS_FIRE("swords"),
    FIELD("sprout"),
    CITY("building-2"),
    RAIL("train-track"),
    LOCK("lock-keyhole");

    private final ResourceLocation texture;

    BfIcon(String name) {
        texture =
                ResourceLocation.fromNamespaceAndPath(
                        "blockfield", "textures/gui/icons/" + name + ".png");
    }

    public static BfIcon forClass(String classId) {
        return switch (classId) {
            case "assault" -> ASSAULT;
            case "mark" -> MARKSMAN;
            case "tech" -> ENGINEER;
            case "sniper" -> SNIPER;
            case "drone" -> DRONE;
            default -> KIT;
        };
    }

    public static BfIcon forAction(String action) {
        if (action.endsWith(".random")) return RANDOM;
        if (action.equals("vote:map.fields")) return FIELD;
        if (action.equals("vote:map.berdichev")) return CITY;
        if (action.equals("vote:map.zaliznichne")) return RAIL;
        if ((action.equals("vote:time.morning") || action.equals("vote:time.day"))) return SUN;
        if (action.equals("vote:time.evening")) return SUNSET;
        if (action.equals("vote:time.night")) return MOON;
        if (action.equals("vote:speed.slow")) return SLOW;
        if (action.equals("vote:speed.normal")) return ARROW;
        if (action.equals("vote:speed.fast")) return FAST;
        if (action.equals("vote:fire.off")) return SHIELD;
        if (action.equals("vote:fire.on")
                || action.startsWith("pvp:")
                || action.equals("find:deathmatch")) return CROSS_FIRE;
        if (action.contains("campaign")) return RANK;
        if (action.contains("sniper") || action.equals("range:enter")) return TARGET;
        if (action.contains("class") || action.equals("range:refill")) return KIT;
        if (action.contains("spawn") || action.startsWith("vote:")) return MAP;
        if (action.startsWith("top:")) return CHART;
        if (action.startsWith("ready:")) return CHECK;
        if (action.equals("menu") || action.equals("leave") || action.equals("range:exit"))
            return BACK;
        if (action.equals("find:room")) return FLAG;
        if (action.equals("find:awp")) return SNIPER;
        if (action.equals("casual") || action.startsWith("find:")) return SQUAD;
        return ARROW;
    }

    public void draw(GuiGraphics g, float x, float y, float size, int color) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(size / 96f, size / 96f, 1f);
        g.setColor(
                ((color >> 16) & 255) / 255f,
                ((color >> 8) & 255) / 255f,
                (color & 255) / 255f,
                ((color >>> 24) & 255) / 255f);
        g.blit(texture, 0, 0, 0, 0, 96, 96, 96, 96);
        g.setColor(1f, 1f, 1f, 1f);
        g.pose().popPose();
    }
}
