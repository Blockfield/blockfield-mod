package com.lunares.blockfield.client.render;

import com.lunares.blockfield.common.grenade.GrenadeThrowSupport;
import com.lunares.blockfield.common.item.KitKnife;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.LinearGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

/** Offscreen vector contours retain identifying details at HUD size. */
final class WeaponHudSilhouettes {
    private WeaponHudSilhouettes() {}

    static String kind(ItemStack stack) {
        if (KitKnife.is(stack)) return "knife";
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        if (id.equals("lrtactical:throwable")) id = GrenadeThrowSupport.throwableId(stack);
        return switch (id) {
            case "superbwarfare:rpg" -> "rpg";
            case "superbwarfare:m18_smoke_grenade", "lrtactical:smoke_grenade" -> "smoke";
            case "superbwarfare:hand_grenade",
                    "superbwarfare:rgo_grenade",
                    "lrtactical:m67",
                    "lrtactical:rgn" ->
                    "frag";
            case "lrtactical:flash_grenade" -> "flash";
            case "lrtactical:molotov" -> "molotov";
            case "superbwarfare:medical_kit" -> "medkit";
            case "superbwarfare:armor_plate" -> "plate";
            case "superbwarfare:drone" -> "drone";
            case "superbwarfare:scout_drone" -> "scout_drone";
            case "superbwarfare:monitor" -> "monitor";
            case "blockfield:command_tablet" -> "tablet";
            default -> null;
        };
    }

    static float size(String kind) {
        if (kind == null) return 1;
        return switch (kind) {
            case "knife", "medkit", "monitor", "tablet" -> 0.72f;
            case "plate", "smoke", "flash", "frag", "molotov", "drone", "scout_drone" -> 0.78f;
            default -> 1;
        };
    }

    static String ammunition(String ammoId) {
        if (ammoId == null) return "bullet";
        String caliber = ammoId.substring(ammoId.lastIndexOf(':') + 1);
        return switch (caliber) {
            case "9mm",
                    "45acp",
                    "50ae",
                    "357mag",
                    "500mag",
                    "762x25",
                    "22wmr",
                    "57x28",
                    "46x30",
                    "five_seven_ammo" ->
                    "round_pistol";
            case "545x39", "556x45", "762x39", "58x42" -> "round_rifle";
            case "308",
                    "762x54",
                    "792x57",
                    "30_06",
                    "68x51fury",
                    "rifles_ammo",
                    "dmr_rifles_ammo",
                    "sig_fury_ammo",
                    "kar98k_ammo" ->
                    "round_heavy";
            case "338", "50bmg", "sp_ammo" -> "round_sniper";
            case "12g" -> "shell";
            case "rpg_rocket", "rpg_rocket_standard", "rpg_rocket_tbg" -> "rocket";
            default -> "bullet";
        };
    }

    /**
     * The shape fitted into {@code maxWidth} x {@code maxHeight} pixels, antialiased. Its lines are
     * one width for every shape at that size and sit on the pixel grid, so cut-outs, rings and
     * antennas look alike across the hotbar.
     */
    static BufferedImage raster(String kind, int maxWidth, int maxHeight) {
        var glyph = shape(kind);
        int line = Math.max(1, Math.round(maxHeight / 16f));
        var box = glyph.body().getBounds2D();
        int pad = 0;
        if (glyph.add().getCurrentPoint() != null) {
            box.add(glyph.add().getBounds2D());
            pad = line;
        }
        double scale =
                Math.min((maxWidth - pad) / box.getWidth(), (maxHeight - pad) / box.getHeight());
        var transform = AffineTransform.getTranslateInstance(pad / 2.0, pad / 2.0);
        transform.scale(scale, scale);
        transform.translate(-box.getX(), -box.getY());
        var shape = glyph.body().createTransformedArea(transform);
        shape.subtract(stroke(glyph.cut(), transform, line));
        shape.add(stroke(glyph.add(), transform, line));
        var bitmap =
                new BufferedImage(
                        Math.max(1, (int) Math.ceil(box.getWidth() * scale) + pad),
                        Math.max(1, (int) Math.ceil(box.getHeight() * scale) + pad),
                        BufferedImage.TYPE_INT_ARGB);
        var graphics = bitmap.createGraphics();
        graphics.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setColor(Color.WHITE);
        graphics.fill(shape);
        graphics.dispose();
        return bitmap;
    }

    static BufferedImage ammunitionRaster(String kind, int maxWidth, int maxHeight) {
        var body = shape(kind).body();
        var box = body.getBounds2D();
        double scale = Math.min(maxWidth / box.getWidth(), maxHeight / box.getHeight());
        var image =
                new BufferedImage(
                        Math.max(1, Math.min(maxWidth, (int) Math.ceil(box.getWidth() * scale))),
                        Math.max(1, Math.min(maxHeight, (int) Math.ceil(box.getHeight() * scale))),
                        BufferedImage.TYPE_INT_ARGB);
        int[] profile =
                switch (kind) {
                    case "round_pistol" -> new int[] {10, 27};
                    case "round_rifle" -> new int[] {14, 44};
                    case "round_heavy" -> new int[] {17, 49};
                    case "round_sniper" -> new int[] {21, 55};
                    case "shell" -> new int[] {34, 34};
                    case "rocket" -> new int[] {43, 87};
                    default -> new int[] {14, 27};
                };
        var graphics = image.createGraphics();
        graphics.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.scale(scale, scale);
        graphics.translate(-box.getX(), -box.getY());
        Color[] casing =
                kind.equals("rocket")
                        ? new Color[] {
                            new Color(0x817A60),
                            new Color(0xD5CDBB),
                            new Color(0xABA38F),
                            new Color(0x77705E)
                        }
                        : new Color[] {
                            new Color(0x866335),
                            new Color(0xE1C78B),
                            new Color(0xCCAA68),
                            new Color(0x866335)
                        };
        graphics.setPaint(
                new LinearGradientPaint(
                        (float) box.getX(),
                        0,
                        (float) box.getMaxX(),
                        0,
                        new float[] {0, 0.28f, 0.6f, 1},
                        casing));
        graphics.fill(body);
        graphics.clip(body);
        Color[] projectile =
                switch (kind) {
                    case "shell" ->
                            new Color[] {
                                new Color(0x854A3A),
                                new Color(0xB8866D),
                                new Color(0xA96B54),
                                new Color(0x704235)
                            };
                    case "rocket" ->
                            new Color[] {
                                new Color(0x63754C),
                                new Color(0xBDCCA0),
                                new Color(0x92A776),
                                new Color(0x5D6E46)
                            };
                    default ->
                            new Color[] {
                                new Color(0x998E7A),
                                new Color(0xF3E7D0),
                                new Color(0xD5CBB6),
                                new Color(0x998E7A)
                            };
                };
        graphics.setPaint(
                new LinearGradientPaint(
                        (float) box.getX(),
                        0,
                        (float) box.getMaxX(),
                        0,
                        new float[] {0, 0.28f, 0.6f, 1},
                        projectile));
        graphics.fill(new Rectangle2D.Double(box.getX(), box.getY(), box.getWidth(), profile[0]));
        if (kind.equals("rocket")) {
            graphics.setColor(new Color(0xD5CDBB));
            graphics.fill(new Rectangle2D.Double(14, 0, 2, 6));
        }
        graphics.setColor(new Color(0x71552B));
        graphics.setStroke(new BasicStroke((float) (0.65 / scale)));
        var seams = new Path2D.Double();
        line(seams, box.getX(), profile[0], box.getMaxX(), profile[0]);
        line(seams, box.getX(), profile[1] - 1.5 / scale, box.getMaxX(), profile[1] - 1.5 / scale);
        graphics.draw(seams);
        if (kind.equals("shell")) {
            var crimp = new Path2D.Double();
            line(crimp, 3, 2, 8, 4, 13, 2);
            graphics.draw(crimp);
        }
        graphics.dispose();
        return image;
    }

    /** {@code path} in pixels, its points on the grid so a line covers whole pixels. */
    private static Area stroke(Path2D path, AffineTransform transform, int width) {
        var snapped = new Path2D.Double();
        double[] point = new double[6];
        for (var it = path.getPathIterator(transform); !it.isDone(); it.next()) {
            int type = it.currentSegment(point);
            for (int i = 0; i < 6; i++)
                point[i] = width % 2 == 0 ? Math.round(point[i]) : Math.floor(point[i]) + 0.5;
            switch (type) {
                case PathIterator.SEG_MOVETO -> snapped.moveTo(point[0], point[1]);
                case PathIterator.SEG_LINETO -> snapped.lineTo(point[0], point[1]);
                case PathIterator.SEG_QUADTO ->
                        snapped.quadTo(point[0], point[1], point[2], point[3]);
                case PathIterator.SEG_CUBICTO ->
                        snapped.curveTo(point[0], point[1], point[2], point[3], point[4], point[5]);
                default -> snapped.closePath();
            }
        }
        return new Area(
                new BasicStroke(width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER)
                        .createStrokedShape(snapped));
    }

    /** A filled body, the lines cut out of it, then the lines added to it. */
    private record Glyph(Area body, Path2D cut, Path2D add) {}

    private static Glyph shape(String kind) {
        var shape = new Area();
        var cut = new Path2D.Double();
        var add = new Path2D.Double();
        switch (kind) {
            case "knife" -> {
                shape.add(polygon(6, 55, 30, 33, 99, 33, 99, 46, 28, 54));
                shape.add(polygon(97, 25, 105, 25, 108, 56, 98, 56));
                shape.add(polygon(104, 33, 145, 33, 151, 39, 148, 48, 106, 47));
                line(cut, 37, 41.5, 91, 40.5);
                shape.subtract(ellipse(140, 38, 5, 5));
            }
            case "smoke", "flash" -> {
                shape.add(rounded(61, 30, 35, 58, 6));
                shape.add(polygon(67, 30, 67, 24, 74, 20, 85, 20, 89, 25, 89, 30));
                shape.add(polygon(76, 19, 96, 22, 106, 60, 101, 62, 92, 28, 76, 25));
                add.append(new Ellipse2D.Double(55.5, 13.5, 17, 15), false);
                if (kind.equals("flash")) {
                    for (int y = 41; y < 80; y += 12) line(cut, 66, y, 91, y);
                } else {
                    line(cut, 63, 38, 94, 38);
                    line(cut, 63, 80, 94, 80);
                }
            }
            case "frag" -> {
                shape.add(ellipse(53, 35, 48, 53));
                shape.add(rounded(64, 26, 25, 17, 4));
                shape.add(polygon(73, 23, 98, 27, 110, 56, 105, 59, 94, 33, 73, 29));
                add.append(new Ellipse2D.Double(52.5, 16.5, 19, 17), false);
                line(cut, 60, 50.5, 94, 50.5);
            }
            case "molotov" -> {
                shape.add(
                        polygon(
                                71, 27, 84, 27, 84, 43, 99, 55, 99, 85, 96, 89, 57, 89, 54, 85, 54,
                                55, 71, 43));
                shape.add(
                        polygon(
                                73, 25, 70, 17, 76, 10, 86, 14, 99, 9, 108, 14, 93, 23, 84, 20, 81,
                                26));
                line(cut, 63.5, 59, 63.5, 80);
            }
            case "medkit" -> {
                shape.add(rounded(28, 31, 103, 54, 10));
                shape.add(rounded(60, 19, 39, 22, 7));
                shape.subtract(rounded(66, 24, 27, 10, 3));
                shape.subtract(
                        polygon(
                                73, 44, 85, 44, 85, 54, 95, 54, 95, 66, 85, 66, 85, 76, 73, 76, 73,
                                66, 63, 66, 63, 54, 73, 54));
                line(cut, 36, 39.5, 54, 39.5);
                line(cut, 105, 39.5, 123, 39.5);
            }
            // The SBW item is a featureless slab; a plate carrier with the SAPI insert reads as
            // armour.
            case "plate" -> {
                shape.add(rounded(40, 6, 80, 82, 10));
                shape.subtract(ellipse(62, -6, 36, 40));
                shape.subtract(ellipse(24, 0, 30, 40));
                shape.subtract(ellipse(106, 0, 30, 40));
                line(cut, 63, 44, 97, 44, 104, 51, 104, 80, 56, 80, 56, 51);
                cut.closePath();
            }
            // Side view with a hanging charge, so it never reads as the top-down scout drone.
            case "drone" -> {
                shape.add(rounded(56, 34, 48, 18, 10));
                shape.add(polygon(76, 50, 84, 50, 84, 58, 76, 58));
                shape.add(ellipse(68, 56, 24, 30));
                line(add, 26, 40.5, 134, 40.5);
                for (int x : new int[] {30, 130}) {
                    line(add, x, 28, x, 43);
                    line(add, x - 22, 26, x + 22, 26);
                }
                line(add, 64.5, 50, 53.5, 66);
                line(add, 95.5, 50, 106.5, 66);
                line(cut, 68, 69.5, 92, 69.5);
            }
            case "scout_drone" -> {
                line(add, 45.5, 17, 114.5, 77);
                line(add, 114.5, 17, 45.5, 77);
                for (int x : new int[] {45, 115})
                    for (int y : new int[] {17, 77}) {
                        shape.add(ellipse(x - 7, y - 7, 14, 14));
                        line(add, x - 24, y, x + 24, y);
                    }
                shape.add(rounded(62, 26, 36, 44, 12));
                cut.append(new Ellipse2D.Double(73, 34, 14, 14), false);
            }
            // Handheld drone controller: screen between two sticks, twin antennas.
            case "monitor" -> {
                shape.add(rounded(22, 32, 116, 50, 18));
                shape.subtract(rounded(50, 38, 60, 36, 4));
                cut.append(new Ellipse2D.Double(31, 49, 12, 12), false);
                cut.append(new Ellipse2D.Double(117, 49, 12, 12), false);
                line(add, 36, 34, 30, 10);
                line(add, 124, 34, 130, 10);
            }
            // The menu item: a tablet showing a list.
            case "tablet" -> {
                shape.add(rounded(0, 0, 100, 64, 10));
                shape.subtract(rounded(8, 7, 74, 50, 4));
                shape.subtract(ellipse(87, 28, 7, 8));
                for (int y = 20; y <= 44; y += 12) line(add, 22, y, 68, y);
            }
            // Plate counter beside health: a faceted shield.
            case "shield" -> {
                shape.add(polygon(3, 2, 12, 0, 21, 2, 21, 12, 17, 19, 12, 23, 7, 19, 3, 12));
                line(
                        cut, 6.5, 4.5, 12, 3.2, 17.5, 4.5, 17.5, 11.5, 14.5, 16.8, 12, 19, 9.5,
                        16.8, 6.5, 11.5);
                cut.closePath();
                line(cut, 12, 3.2, 12, 19);
            }
            case "bullet" -> {
                var round = new Path2D.Double();
                round.moveTo(0, 12);
                round.quadTo(0, 3, 5, 0);
                round.quadTo(10, 3, 10, 12);
                round.lineTo(10, 30);
                round.lineTo(0, 30);
                round.closePath();
                shape.add(new Area(round));
                line(cut, 0, 14, 10, 14);
            }
            case "round_pistol" -> {
                shape.add(svg("M1 9Q1 0 6 0Q11 0 11 9V27H12V30H0V27H1Z"));
            }
            case "round_rifle" -> {
                shape.add(svg("M6 0Q9 7 9 12V19L11 24V44H12V48H0V44H1V24L3 19V12Q3 7 6 0Z"));
            }
            case "round_heavy" -> {
                shape.add(svg("M8 0Q11 9 11 15V22L15 28V49H16V54H0V49H1V28L5 22V15Q5 9 8 0Z"));
            }
            case "round_sniper" -> {
                shape.add(svg("M7 0Q9 9 10 19V25L13 32V55H14V60H0V55H1V32L4 25V19Q5 9 7 0Z"));
            }
            case "shell" -> {
                shape.add(rounded(1, 0, 14, 35, 2));
                shape.add(polygon(0, 34, 16, 34, 16, 42, 0, 42));
            }
            case "rocket" -> {
                shape.add(
                        svg(
                                "M14 0H16V6L20 10Q30 20 30 26Q30 30 24 36L18 43V90H12V43"
                                        + "L6 36Q0 30 0 26Q0 20 10 10L14 6Z"));
                shape.add(polygon(12, 68, 3, 73, 1, 86, 12, 81));
                shape.add(polygon(18, 68, 27, 73, 29, 86, 18, 81));
            }
            // Material Symbols "directions_run", sharp, filled (Apache-2.0):
            // google/material-design-icons@737e3324305806514d7909874fa1818ae1808232
            // symbols/web/directions_run/materialsymbolssharp/directions_run_fill1_24px.svg
            case "runner" ->
                    shape.add(
                            svg(
                                    "M520-40v-240l-84-80-40 176-276-56 16-80 192 40 64-324-72"
                                            + " 28v136h-80v-188l158-68q35-15 51.5-19.5T480-720q21 0 39"
                                            + " 11t29 29l40 64q26 42 70.5 69T760-520v80q-66"
                                            + " 0-123.5-27.5T540-540l-24 120 84 80v300h-80Zm20-700q-33"
                                            + " 0-56.5-23.5T460-820q0-33 23.5-56.5T540-900q33 0 56.5"
                                            + " 23.5T620-820q0 33-23.5 56.5T540-740Z"));
            default -> throw new IllegalArgumentException("unknown HUD silhouette " + kind);
        }
        return new Glyph(shape, cut, add);
    }

    private static void line(Path2D path, double... points) {
        path.moveTo(points[0], points[1]);
        for (int i = 2; i < points.length; i += 2) path.lineTo(points[i], points[i + 1]);
    }

    /** An SVG path of the commands the icons above use: M L H V Q T Z, absolute or relative. */
    static Area svg(String data) {
        var path = new Path2D.Double();
        var tokens = Pattern.compile("[A-Za-z]|-?(?:\\d+\\.?\\d*|\\.\\d+)").matcher(data);
        var items = new ArrayList<String>();
        while (tokens.find()) items.add(tokens.group());
        char command = 0;
        double x = 0, y = 0, controlX = 0, controlY = 0;
        for (int i = 0; i < items.size(); ) {
            if (Character.isLetter(items.get(i).charAt(0))) {
                command = items.get(i++).charAt(0);
                if (Character.toUpperCase(command) == 'Z') {
                    path.closePath();
                    var start = path.getCurrentPoint();
                    x = controlX = start.getX();
                    y = controlY = start.getY();
                }
                continue;
            }
            char upper = Character.toUpperCase(command);
            double baseX = upper == command ? 0 : x, baseY = upper == command ? 0 : y;
            if (upper == 'Q' || upper == 'T') {
                controlX = upper == 'T' ? 2 * x - controlX : baseX + number(items, i++);
                controlY = upper == 'T' ? 2 * y - controlY : baseY + number(items, i++);
                x = baseX + number(items, i++);
                y = baseY + number(items, i++);
                path.quadTo(controlX, controlY, x, y);
                continue;
            }
            if (upper != 'V') x = baseX + number(items, i++);
            if (upper != 'H') y = baseY + number(items, i++);
            controlX = x;
            controlY = y;
            switch (upper) {
                case 'M' -> {
                    path.moveTo(x, y);
                    // Further pairs after a move are lines.
                    command = command == 'M' ? 'L' : 'l';
                }
                case 'L', 'H', 'V' -> path.lineTo(x, y);
                default -> throw new IllegalArgumentException("SVG command " + command);
            }
        }
        return new Area(path);
    }

    private static double number(List<String> items, int index) {
        return Double.parseDouble(items.get(index));
    }

    private static Area polygon(double... points) {
        var path = new Path2D.Double();
        path.moveTo(points[0], points[1]);
        for (int i = 2; i < points.length; i += 2) path.lineTo(points[i], points[i + 1]);
        path.closePath();
        return new Area(path);
    }

    private static Area ellipse(double x, double y, double width, double height) {
        return new Area(new Ellipse2D.Double(x, y, width, height));
    }

    private static Area rounded(double x, double y, double width, double height, double radius) {
        return new Area(new RoundRectangle2D.Double(x, y, width, height, radius, radius));
    }
}
