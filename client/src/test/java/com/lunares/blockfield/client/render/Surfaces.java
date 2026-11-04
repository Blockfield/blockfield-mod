package com.lunares.blockfield.client.render;

import static com.lunares.blockfield.client.render.UniformAtlasBaker.clamp;
import static com.lunares.blockfield.client.render.UniformAtlasBaker.shade;
import static com.lunares.blockfield.client.render.UniformModel.*;

import com.lunares.blockfield.client.render.UniformAtlasBaker.Face;
import com.lunares.blockfield.client.render.UniformAtlasBaker.Texel;
import com.lunares.blockfield.common.item.UniformItem.Role;
import org.joml.Vector3f;

/**
 * Procedural materials and printed details for UniformAtlasBaker, in model units (PX texels each).
 */
final class Surfaces {
    private static final double PIXEL = 1.0 / UniformAtlasBaker.PX;

    private Surfaces() {}

    /** Soft studio key light from above, as in the concept renders. */
    static double light(Texel t) {
        return switch (t.face()) {
            case TOP -> 1.06;
            case BOTTOM -> .86;
            default -> 1.04 - .08 * clamp(t.t() / Math.max(1, t.fh()), 0, 1);
        };
    }

    static int paint(Texel t, Role role, int[] palette, Vector3f normal) {
        int base = palette[t.material()];
        double k = 1;
        int alpha = 255;
        double edge = t.edge();
        switch (t.material()) {
            case CLOTH -> {
                k *= camo(t);
                k *= 1 + weave(t) + folds(t);
                k *= stitch(t, .3, .1);
                if (edge < .1) k *= .9;
            }
            case MARK -> {
                k *= 1 + weave(t) + .5 * folds(t) + noise(t.tx(), t.ty(), 3) * .02;
                k *= stitch(t, .22, .12);
                if (edge < PIXEL * 1.5) k *= .82;
            }
            case ARMOR, POUCH -> {
                k *= 1 + cordura(t);
                if (edge < .16) k *= .84;
                else k *= stitch(t, .24, .1);
                k *= molle(t);
                if (edge < PIXEL) k *= 1.12;
            }
            case DARK -> {
                if (t.named("mask")) k *= 1.3 * knit(t);
                else k *= 1 + weave(t) * 1.5 + noise(t.tx(), t.ty(), 4) * .03;
                if (edge < PIXEL) k *= 1.25;
            }
            case LEATHER -> {
                k *= 1 + noise(t.tx(), t.ty(), 5) * .05 + blotch(t) * .05;
                if (edge < PIXEL * 1.5) k *= 1.18;
            }
            case METAL, HARDWARE, EDGE -> {
                k *= 1 + noise(t.ty(), t.cube(), 6) * .05 + noise(t.tx(), t.ty(), 7) * .02;
                if (edge < PIXEL * 1.5) k *= t.t() < t.fh() / 2 || t.s() < t.fw() / 2 ? 1.2 : .8;
            }
            case RUBBER -> k *= 1 + noise(t.tx(), t.ty(), 8) * .03 - (edge < PIXEL ? .1 : 0);
            case WEBBING -> {
                k *= 1 + ((t.ty() / 2) % 2 == 0 ? .06 : -.05) + noise(t.tx(), t.ty(), 9) * .02;
                if (edge < PIXEL * 1.5) k *= .82;
            }
            case GLASS -> {
                k *= .75 + .5 * (1 - clamp(t.t() / Math.max(.1, t.fh()), 0, 1));
                if (Math.abs(t.s() - t.t() * .7 - t.fw() * .22) < .18) k *= 1.6;
            }
            case SCRIM, SCRIM_DARK -> {
                k *=
                        1
                                + ((t.tx() % 3 == 0 || t.ty() % 3 == 0) ? -.09 : .04)
                                + (hash(t.cube(), 11) - .5) * .18;
                k *= 1 + noise(t.tx() / 2, t.ty() / 2, 12) * .06;
                boolean broad = t.fw() > .8 && t.fh() > .8;
                // Frayed burlap: the free end of each tab is ragged, cut out of the texture.
                if (broad
                        && t.t() > t.fh() - .3
                        && t.t() > t.fh() - .05 - .25 * hash((int) (t.s() * 6), t.cube())
                        && hash(t.cube(), 5) < .6) alpha = 0;
            }
            case JUTE -> k *= 1;
            case HAZARD -> {
                k *= 1 + noise(t.tx(), t.ty(), 18) * .03;
                if (edge < PIXEL * 1.5) k *= .8;
            }
            case SHEMAGH -> k *= shemagh(t);
            default -> {}
        }
        if (t.name().endsWith(":jute")) {
            // Twisted jute: per-column fibre streaks, a darker twist every unit, ragged tassel at
            // the free end.
            k =
                    (1 + noise(t.tx(), t.cube(), 16) * .1 + noise(t.tx(), t.ty() / 6, 17) * .05)
                            * ((int) (t.t() * 1.3) % 2 == 0 ? 1 : .94);
            if (t.face() != Face.TOP
                    && t.face() != Face.BOTTOM
                    && t.t() > t.fh() - .25 - .7 * hash(t.tx(), t.cube())) alpha = 0;
        }
        int color = shade(base, k);
        color = details(t, role, palette, color);
        return alpha << 24 | color;
    }

    /** Woven shemagh: sand ground with dark twin lines and small diamonds between them. */
    private static double shemagh(Texel t) {
        // Dense woven check: twin lines every .6 unit, a tiny cross between them, soft diagonal
        // folds.
        double a = t.s() / .6, b = t.t() / .6;
        double fa = a - Math.floor(a), fb = b - Math.floor(b);
        double px = PIXEL / .6;
        boolean line =
                fa < px || Math.abs(fa - .22) < px * .6 || fb < px || Math.abs(fb - .22) < px * .6;
        boolean cross =
                Math.abs(fa - .61) < px * .6 && Math.abs(fb - .61) < .12
                        || Math.abs(fb - .61) < px * .6 && Math.abs(fa - .61) < .12;
        double folds = (smooth(t.s() * .9 + t.t() * .5, t.t() * 1.6, 51) - .5) * .22;
        return (line ? .8 : cross ? .86 : 1)
                * (1 + folds)
                * (1 + ((t.tx() + t.ty()) % 2 == 0 ? .02 : -.02));
    }

    /** Printed and sewn details that replaced tiny cubes. */
    private static int details(Texel t, Role role, int[] palette, int color) {
        String n = t.name();
        double s = t.s(), y = t.t(), fw = t.fw(), fh = t.fh();
        boolean front = t.face() == Face.FRONT, back = t.face() == Face.BACK;
        if (n.equals("badge") && front) {
            double cover = emblem(role, s - fw / 2, y - fh / 2, Math.min(fw, fh) * .36);
            if (Math.abs(t.edge() - .2) < PIXEL * .7
                    && (int) ((s + y) * UniformAtlasBaker.PX) % 4 < 2) cover = Math.max(cover, .45);
            return mix(color, palette[METAL], cover);
        }
        if (n.startsWith("flap_") && front) {
            if (Math.abs(s - fw / 2) < .3 && y > .15)
                return shade(palette[WEBBING], 1 + (Math.abs(s - fw / 2) > .22 ? -.15 : 0));
        }
        if (n.startsWith("magazine_") && front) {
            if (y < .22) return shade(color, .55 + y);
            if (Math.abs(s - fw / 2) < .3 && y < .55) return shade(palette[WEBBING], .95);
            if (Math.hypot(s - fw / 2, y - .72) < .14) return shade(palette[HARDWARE], .8);
            if (Math.abs(s - fw / 2) < PIXEL && y > .9) return shade(color, .86);
        }
        if (n.equals("buckle") && front && s > .25 && s < fw - .25 && y > .2 && y < fh - .2)
            return shade(color, Math.abs(s - fw / 2) < PIXEL ? 1.1 : .35);
        if (n.startsWith("pack_clasp_")
                && back
                && s > .22
                && s < fw - .22
                && y > .22
                && y < fh - .22) return shade(palette[DARK], .7);
        if (n.startsWith("goggle_rim_") && front) {
            for (int i = 0; i < 4; i++)
                if (Math.hypot(s - (i % 2 == 0 ? .2 : fw - .2), y - (i < 2 ? .2 : fh - .2)) < .1)
                    return shade(palette[METAL], .9);
        }
        if (n.equals("radio_panel") && back && y > .2 && y < fh - .2) {
            double slot = (s - .45) / .72;
            if (slot > 0 && slot < 5.5 && slot % 1 < .5) return shade(palette[DARK], .6);
        }
        if (n.equals("controller") && front && y > fh - .55) {
            for (int i = 0; i < 3; i++)
                if (Math.hypot(s - (fw / 2 - .7 + i * .7), y - (fh - .3)) < .14)
                    return shade(palette[i == 1 ? MARK : HARDWARE], 1.1);
        }
        if (n.equals("wrench_handle")
                && front
                && Math.abs(s - fw / 2) < .09
                && y > .8
                && y < fh - .6) return shade(color, .6);
        if (n.equals("hammer_grip") && (int) (y / .5) % 2 == 1 && y % .5 < .16)
            return shade(palette[RUBBER], .8);
        if (n.startsWith("helmet_rail_") && (t.face() == Face.XNEG || t.face() == Face.XPOS)) {
            for (int i = 0; i < 2; i++)
                if (Math.hypot(s - (.35 + i * 2.8), y - fh / 2) < .13)
                    return shade(palette[HARDWARE], 1.1);
        }
        if (n.equals("mount") && front && s > .35 && s < fw - .35 && y > .6 && y < 1.45)
            return shade(palette[DARK], .8);
        if ((n.startsWith("harness_adjuster") || n.equals("chest_buckle"))
                && (front || back)
                && s > .22
                && s < fw - .22
                && y > .2
                && y < fh - .2) return shade(palette[DARK], .8);
        if (n.equals("top")
                && role != Role.SNIPER
                && role != Role.AIRBORNE
                && t.face() == Face.TOP) {
            double seam = Math.min(Math.abs(s - (fw / 2 - 2.7)), Math.abs(s - (fw / 2 + 2.7)));
            if (seam < PIXEL) return shade(color, .78);
            if (seam < PIXEL * 2.5 && (int) (y * UniformAtlasBaker.PX) % 5 < 3)
                return shade(color, 1.12);
        }
        if (n.equals("pack") && back && Math.abs(y - (fh - .4)) < PIXEL) return shade(color, .7);
        if (n.equals("pack_pocket") && t.face() == Face.TOP && Math.abs(y - t.fh() / 2) < .09)
            return shade(palette[DARK], (int) (s * UniformAtlasBaker.PX) % 2 == 0 ? .9 : 1.4);
        if (n.equals("boot") && front) return laces(s, y, fw, color, palette);
        if (n.equals("boot") || n.equals("boot_toe")) {
            if (y > fh - .25 && t.face() != Face.TOP && t.face() != Face.BOTTOM)
                return shade(color, .8);
        }
        if (n.equals("sole")
                && t.face() != Face.TOP
                && t.face() != Face.BOTTOM
                && (int) ((s + y) / .3) % 2 == 0) return shade(color, .75);
        if (n.equals("glove")
                && (front || back || t.face() == Face.XNEG || t.face() == Face.XPOS)) {
            if (y > fh - 1.3 && (front || back)) {
                for (int i = 1; i < 4; i++)
                    if (Math.abs(s - fw * i / 4) < PIXEL) return shade(color, .7);
            }
            if (Math.abs(y - (fh - 1.45)) < .12 && front) return shade(color, 1.18);
            if (y < .3) return shade(color, .82);
        }
        if ((n.equals("trousers") || n.equals("sleeve"))
                && (t.face() == Face.XNEG || t.face() == Face.XPOS)) {
            double seam = Math.abs(s - fw / 2);
            if (seam < PIXEL * .6) return shade(color, .8);
            if (seam < PIXEL * 2.2 && (int) (y * UniformAtlasBaker.PX) % 5 < 3)
                return shade(color, 1.1);
        }
        if (n.equals("mask") && front && Math.abs(y - 1.9) < .12) return shade(color, .75);
        if (n.equals("cartridges") && (front || back) && fw < 2) {
            // Elastic loops every .6 units, each holding a brass case with a copper bullet.
            double cell = y % .6;
            if (Math.abs(s - fw / 2) < .17 && cell < .3)
                return cell < .12
                        ? shade(0xb0643a, 1 - cell)
                        : shade(palette[BRASS], 1.05 - Math.abs(s - fw / 2));
            if (cell >= .3 && cell < .56) return shade(color, cell < .33 || cell > .53 ? .7 : 1.08);
        }
        if (n.equals("bag") && (front || back)) {
            if (Math.abs(s - fw * .72) < PIXEL * .8) return shade(palette[DARK], .9);
            if (Math.abs(s - fw * .72) < PIXEL * 2.2 && (int) (y * UniformAtlasBaker.PX) % 2 == 0)
                return shade(palette[METAL], .8);
        }
        if (n.equals("hazard_lid")
                && t.face() != Face.BOTTOM
                && ((int) Math.floor((s + y) / .55)) % 2 == 0) return shade(0x1c1c1a, 1.1);
        if (n.startsWith("tool_handle_") || n.equals("screwdriver")) {
            if ((int) (y / .25) % 2 == 1 && y < fh - .3) return shade(color, .78);
        }
        if (n.equals("flag_patch") && front) {
            if (t.edge() < PIXEL * 1.5) return shade(color, .6);
            if (Math.abs(t.edge() - .15) < PIXEL * .6
                    && (int) ((s + y) * UniformAtlasBaker.PX) % 4 < 2) return shade(color, 1.3);
        }
        if (n.startsWith("mag_") && t.face() != Face.TOP && t.face() != Face.BOTTOM) {
            if ((int) (y / .2) % 2 == 0 && y > .15) return shade(color, 1.18);
            if (y < .15) return shade(color, 1.35);
        }
        if (n.startsWith("magazine_") && front) {
            if (Math.abs(s - fw / 2) < PIXEL && y < 1.1) return shade(palette[DARK], .8);
            if (Math.hypot(s - fw / 2, y - 1.2) < .16) return shade(palette[DARK], .9);
        }
        if (n.equals("tq") && (front || back) && Math.abs(y - fh * .4) < .12)
            return shade(palette[METAL], .7);
        if (n.equals("radio") && t.face() == Face.TOP && Math.hypot(s - fw / 2, y - fh / 3) < .22)
            return shade(palette[HARDWARE], .9);
        if (n.equals("strobe") && t.face() == Face.TOP) return shade(0xc8d8c0, 1 - t.edge() * .5);
        if (n.startsWith("ear_cup_")
                && (t.face() == Face.XNEG || t.face() == Face.XPOS)
                && t.edge() > .35
                && (int) (y * 8) % 2 == 0) return shade(color, .7);
        if (n.equals("ifak") && back) {
            boolean cross =
                    Math.abs(s - fw / 2) < .16 && Math.abs(y - fh / 2) < .6
                            || Math.abs(y - fh / 2) < .16 && Math.abs(s - fw / 2) < .6;
            if (cross) return shade(palette[METAL], .95);
        }
        if ((n.equals("assault_panel") || n.equals("relay_pack") || n.equals("toolbox")) && back) {
            if (Math.abs(t.edge() - .45) < PIXEL * .8 && y > .3) return shade(palette[DARK], .9);
            if (Math.abs(t.edge() - .45) < PIXEL * 2
                    && y > .3
                    && (int) ((s + y) * UniformAtlasBaker.PX) % 2 == 0)
                return shade(palette[METAL], .75);
        }
        if (n.equals("reserve_flap") || n.equals("container_flap")) {
            if ((front || back) && Math.abs(s - fw / 2) < .5 && y > fh - .45)
                return shade(palette[WEBBING], .9);
        }
        if (n.equals("ripcord") && front) {
            double r = Math.hypot((s - fw / 2) / 1.6, (y - fh / 2) / .8);
            return r > .28 && r < .46 ? shade(palette[METAL], 1.1) : shade(palette[RUBBER], .8);
        }
        if (n.equals("altimeter_face")) {
            double cx = s - fw / 2, cy = y - fh / 2, r = Math.hypot(cx, cy);
            if (r > .66) return shade(palette[DARK], .8);
            double ang = Math.atan2(cy, cx);
            if (r > .5 && Math.abs((ang / (Math.PI / 6)) - Math.round(ang / (Math.PI / 6))) < .12)
                return 0xdcdad0;
            if (r < .45
                    && Math.abs(cx * Math.cos(.9) - cy * Math.sin(.9) * -1) < .05
                    && cx * Math.sin(.9) + cy * Math.cos(.9) < 0) return 0xe8a030;
            return shade(0x1a1c1a, 1);
        }
        if (n.equals("screen") && front) {
            // Drone video feed: sky, horizon, ground, crosshair and HUD ticks.
            double h = y / fh, cx = s - fw / 2, cy = y - fh * .45;
            if (Math.abs(cx) < .5 && Math.abs(cy) < PIXEL * .6
                    || Math.abs(cy) < .5 && Math.abs(cx) < PIXEL * .6) return 0xe8f0e0;
            if (t.edge() < .12 && (int) (s * 16 + y * UniformAtlasBaker.PX) % 3 == 0)
                return 0xb8e0b0;
            return h < .45 ? shade(0x7fa6c8, .8 + h) : shade(0x6f6446, 1.2 - h * .4) | 0;
        }
        if (n.equals("tablet_screen") && (t.face() == Face.XPOS || t.face() == Face.XNEG)) {
            // Rugged tablet map: terrain, grid, a road and two unit markers.
            if ((int) (s / .5) != (int) ((s - PIXEL) / .5)
                    || (int) (y / .5) != (int) ((y - PIXEL) / .5)) return 0x5f6f60;
            if (Math.abs(s - y * .8 - .3) < PIXEL * 1.2) return 0xc8b890;
            if (Math.hypot(s - fw * .3, y - fh * .35) < .13) return 0x3060d0;
            if (Math.hypot(s - fw * .7, y - fh * .7) < .13) return 0xc03830;
            return shade(0x8a9a78, 1 + (smooth(s * 1.5, y * 1.5, 61) - .5) * .3);
        }
        if (n.equals("battery_cells") && back) {
            double cell = s / (fw / 4);
            if (cell % 1 < .08) return shade(color, .6);
            if (y < .45 && Math.abs(cell % 1 - .5) < .12)
                return (int) cell % 2 == 0 ? 0x60d060 : 0xd8c040;
            if (y > .9 && y < 1.6) return shade(palette[METAL], .8);
        }
        if (n.equals("headlamp_lens") && front) return shade(0xfff4c8, 1 - t.edge() * .4);
        if (n.startsWith("leg_loop") && (int) (s * UniformAtlasBaker.PX) % 6 == 0)
            return shade(color, .82);
        if (n.startsWith("canopy_release_") && front && Math.abs(t.edge() - .2) < PIXEL)
            return shade(color, .7);
        if (n.equals("crawl_pad") && front && (int) ((s + y) / .5) % 2 == 0 && t.edge() > .2)
            return shade(color, .92);
        return color;
    }

    /** Role emblem coverage in [0, 1], 4×4 supersampled; x, y from the badge centre. */
    private static double emblem(Role role, double x, double y, double r) {
        double sum = 0;
        for (int i = 0; i < 16; i++) {
            double px = x + ((i % 4) - 1.5) * PIXEL / 4, py = y + ((i / 4) - 1.5) * PIXEL / 4;
            if (emblemAt(role, px / r, py / r)) sum++;
        }
        return sum / 16;
    }

    private static boolean emblemAt(Role role, double x, double y) {
        double d = Math.hypot(x, y);
        return switch (role) {
            case SNIPER ->
                    Math.abs(d - .68) < .1
                            || (Math.abs(x) < .07 && Math.abs(y) > .28 && Math.abs(y) < 1)
                            || (Math.abs(y) < .07 && Math.abs(x) > .28 && Math.abs(x) < 1)
                            || d < .1;
            case ENGINEER -> {
                // Open-end wrench: diagonal handle and a jawed ring at the top right.
                double along = (x - y) / Math.sqrt(2), across = (x + y) / Math.sqrt(2);
                boolean handle = Math.abs(across) < .15 && along > -.95 && along < .45;
                double jx = x - .52, jy = y + .52, jd = Math.hypot(jx, jy);
                boolean ring = jd < .44 && jd > .2 && !(jx - jy > 0 && Math.abs(jx + jy) < .26);
                yield handle || ring || Math.hypot(x + .67, y - .67) < .2;
            }
            case DRONE -> {
                boolean arms = (Math.abs(x - y) < .14 || Math.abs(x + y) < .14) && d < .85;
                boolean rotors = false;
                for (int i = 0; i < 4; i++) {
                    double rd = Math.hypot(x - (i % 2 == 0 ? -.62 : .62), y - (i < 2 ? -.62 : .62));
                    rotors |= rd < .36 && rd > .24;
                }
                yield arms || rotors || Math.max(Math.abs(x), Math.abs(y)) < .22;
            }
            case AIRBORNE -> {
                // Parachute: domed canopy with gores, suspension lines converging on the jumper.
                boolean canopy =
                        y < -.05
                                && d < .82
                                && d > .5 - .12 * Math.abs(Math.sin(Math.atan2(y, x) * 4));
                boolean lines =
                        y > -.1
                                && y < .62
                                && (Math.abs(x - (.72 - .72 * (y + .1) / .72)) < .06
                                        || Math.abs(x + (.72 - .72 * (y + .1) / .72)) < .06
                                        || Math.abs(x) < .05 && y < .5);
                yield canopy || lines || Math.hypot(x, y - .72) < .13;
            }
            case DM -> {
                double m = Math.max(Math.abs(x), Math.abs(y));
                yield m < .82 && m > .55;
            }
            default -> false;
        };
    }

    private static int laces(double s, double y, double fw, int color, int[] palette) {
        double left = fw / 2 - .75, right = fw / 2 + .75;
        if (y < 1.95 && s > left - .12 && s < right + .12) {
            for (int row = 0; row < 4; row++) {
                double ey = .25 + row * .45;
                if (Math.hypot(s - left, y - ey) < .09 || Math.hypot(s - right, y - ey) < .09)
                    return shade(palette[HARDWARE], 1.1);
            }
            double phase = (y - .25) / .45;
            double along = left + (right - left) * (phase % 1);
            double mirrored = right - (right - left) * (phase % 1);
            if (phase > 0
                    && phase < 3.1
                    && (Math.abs(s - along) < .07 || Math.abs(s - mirrored) < .07))
                return shade(palette[WEBBING], .7);
            if (s > left && s < right) return shade(color, .72);
        }
        return color;
    }

    /** Pixel camouflage: one-unit cells grown into blobs, continuous across adjacent faces. */
    private static double camo(Texel t) {
        double a, b;
        int plane;
        switch (t.face()) {
            case XNEG, XPOS -> {
                a = t.z();
                b = t.y();
                plane = 1;
            }
            case TOP, BOTTOM -> {
                a = t.x();
                b = t.z();
                plane = 2;
            }
            default -> {
                a = t.x();
                b = t.y();
                plane = 0;
            }
        }
        int ca = (int) Math.floor(a), cb = (int) Math.floor(b);
        // Clustered blobs plus per-cell scatter: every one-unit cell reads as its own skin pixel.
        double field =
                smooth(ca / 2.4, cb / 2.4, 21 + plane) * .55 + hash(ca * 7 + plane, cb) * .45;
        return field < .3
                ? .76
                : field < .42
                        ? .86
                        : field < .56 ? .96 : field < .7 ? 1.05 : field < .82 ? 1.13 : 1.21;
    }

    private static double weave(Texel t) {
        return ((t.tx() + t.ty()) % 4 < 2 ? .018 : -.018) + noise(t.tx(), t.ty(), 2) * .015;
    }

    private static double folds(Texel t) {
        return (smooth(t.x() / 3.5 + t.z() / 5, t.y() / 2.5, 41) - .5) * .1;
    }

    private static double cordura(Texel t) {
        return noise(t.tx(), t.ty(), 13) * .03
                + (t.tx() % 2 == 0 ? .008 : -.008)
                + (t.ty() % 2 == 0 ? .008 : 0);
    }

    private static double knit(Texel t) {
        return 1 + (t.tx() % 3 == 0 ? -.07 : .02) + noise(t.tx() / 3, t.ty(), 14) * .03;
    }

    private static double blotch(Texel t) {
        return smooth(t.x() * 1.7 + t.z(), t.y() * 1.7, 15) - .5;
    }

    /** Dashed thread line inset from the nearest edge; darker hollow next to it. */
    private static double stitch(Texel t, double inset, double strength) {
        double s = t.s(), y = t.t(), fw = t.fw(), fh = t.fh();
        if (fw < inset * 3 || fh < inset * 3) return 1;
        double dx = Math.min(s, fw - s), dy = Math.min(y, fh - y);
        double d = Math.min(dx, dy), along = dx < dy ? y : s;
        if (Math.abs(d - inset) < PIXEL * .6)
            return (int) (along * UniformAtlasBaker.PX) % 5 < 3 ? 1 + strength : 1;
        if (Math.abs(d - inset - PIXEL) < PIXEL * .6) return 1 - strength * .5;
        return 1;
    }

    /** PALS webbing rows with bar tacks on plate-carrier faces. */
    private static double molle(Texel t) {
        String n = t.name();
        boolean carrier =
                n.equals("vest_front")
                        || n.equals("back_plate")
                        || n.equals("chest_plate")
                        || n.equals("pack");
        if (!carrier || (t.face() != Face.FRONT && t.face() != Face.BACK)) return 1;
        if (t.s() < .35 || t.s() > t.fw() - .35) return 1;
        double row = (t.t() - .45) / 1.2;
        if (row < 0 || row > (t.fh() - .8) / 1.2) return 1;
        double inRow = (row % 1) * 1.2;
        if (inRow > .7) return 1;
        if (inRow < PIXEL) return 1.14;
        if (inRow > .7 - PIXEL) return .7;
        if (Math.abs((t.s() - .35) % 1.5) < PIXEL * 1.2) return .72;
        return .95 + ((t.tx() / 2) % 2 == 0 ? .02 : 0);
    }

    private static int mix(int a, int b, double k) {
        int r = (int) Math.round((a >> 16 & 255) * (1 - k) + (b >> 16 & 255) * k);
        int g = (int) Math.round((a >> 8 & 255) * (1 - k) + (b >> 8 & 255) * k);
        int bl = (int) Math.round((a & 255) * (1 - k) + (b & 255) * k);
        return r << 16 | g << 8 | bl;
    }

    static double hash(int a, int b) {
        long h = a * 0x9E3779B97F4A7C15L ^ (b + 0x632BE59BD9B4E019L) * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return (h >>> 11) * 0x1.0p-53;
    }

    /** Value noise in [-1, 1] per texel. */
    private static double noise(int x, int y, int seed) {
        return hash((x >> 1) * 73856093 ^ seed, (y >> 1) * 19349663 ^ seed * 83492791) * 2 - 1;
    }

    /** Smooth value noise in [0, 1]. */
    private static double smooth(double x, double y, int seed) {
        int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y);
        double fx = x - x0, fy = y - y0;
        fx = fx * fx * (3 - 2 * fx);
        fy = fy * fy * (3 - 2 * fy);
        double a = hash(x0 * 31 + seed, y0),
                b = hash((x0 + 1) * 31 + seed, y0),
                c = hash(x0 * 31 + seed, y0 + 1),
                d = hash((x0 + 1) * 31 + seed, y0 + 1);
        return (a + (b - a) * fx) * (1 - fy) + (c + (d - c) * fx) * fy;
    }
}
