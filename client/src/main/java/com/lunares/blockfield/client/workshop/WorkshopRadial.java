package com.lunares.blockfield.client.workshop;

/** Shared hit geometry and gesture state, independent of Minecraft input callbacks. */
public final class WorkshopRadial {
    public static final long TAP_MS = 180;

    /** Server groups (up to 8) plus the client-only «Клавиши» entry. */
    public static final int MAX_SECTORS = 9;

    /** Subpixel grid per GUI pixel for edge coverage. */
    static final int SUB = 4;

    /** Angular half-width of the clean gap between neighbouring sectors. */
    static final double GAP = 0.014;

    private WorkshopRadial() {}

    public static int sector(double x, double y, double deadZone, double radius, int count) {
        double distance = Math.hypot(x, y);
        if (count < 1 || count > MAX_SECTORS || distance <= deadZone || distance > radius)
            return -1;
        double step = Math.PI * 2 / count;
        double angle = Math.atan2(x, -y) + step / 2;
        if (angle < 0) angle += Math.PI * 2;
        return (int) (angle / step) % count;
    }

    /**
     * Draw-time sector: the hit sector minus the separator gap, so the baked wheel keeps clean
     * dividers while the cursor still hits a sector on them.
     */
    static int drawSector(double x, double y, double deadZone, double radius, int count) {
        int sector = sector(x, y, deadZone, radius, count);
        if (sector < 0) return -1;
        double step = Math.PI * 2 / count;
        double raw = Math.atan2(x, -y);
        double within = ((raw + step / 2) % (Math.PI * 2) + Math.PI * 2) % (Math.PI * 2) % step;
        return within < GAP || within > step - GAP ? -1 : sector;
    }

    /** Subpixel test: the point belongs to the sector (dead zone, rim and gaps excluded). */
    static boolean covers(
            double x, double y, double deadZone, double radius, int count, int sector) {
        return drawSector(x, y, deadZone, radius, count) == sector;
    }

    /**
     * Anti-aliased coverage of the GUI pixel: the fraction of its {@code SUB x SUB} subpixels
     * inside the sector. Interior pixels read exactly 1, exterior 0, rim and separator pixels a
     * partial value the wheel bakes into a translucent edge fill.
     */
    static double coverage(int px, int py, double deadZone, double radius, int count, int sector) {
        int hits = 0;
        for (int sy = 0; sy < SUB; sy++)
            for (int sx = 0; sx < SUB; sx++)
                if (covers(
                        px + (sx + 0.5) / SUB,
                        py + (sy + 0.5) / SUB,
                        deadZone,
                        radius,
                        count,
                        sector)) hits++;
        return hits / (double) (SUB * SUB);
    }

    public enum Release {
        CANCEL,
        ACTIVATE
    }

    /**
     * Hold-to-release: a quick tap always cancels, so it can never leave the menu latched open. A
     * held key selects the sector under the cursor, or cancels over the dead zone.
     */
    public static Release release(long heldMs, int sector) {
        if (heldMs < TAP_MS) return Release.CANCEL;
        return sector < 0 ? Release.CANCEL : Release.ACTIVATE;
    }

    /** Label centre offset from the wheel centre, {x, y}, on the same circle as drawing. */
    public static int[] labelOffset(int index, int count, int radius) {
        double angle = index * Math.PI * 2 / count;
        return new int[] {
            (int) (Math.sin(angle) * radius * .68), -(int) (Math.cos(angle) * radius * .68)
        };
    }

    /**
     * Widest label that cannot touch a neighbour sharing its text row: such a pair (the bottom two,
     * for an odd count) splits the gap between their centres.
     */
    public static int labelWidth(int index, int count, int radius, int lineHeight) {
        int width = Math.max(48, radius * 2 / 3);
        int[] at = labelOffset(index, count, radius);
        for (int step : new int[] {1, count - 1}) {
            int[] other = labelOffset((index + step) % count, count, radius);
            if (count > 1 && Math.abs(other[1] - at[1]) <= lineHeight)
                width = Math.min(width, Math.abs(other[0] - at[0]) - 4);
        }
        return width;
    }

    /**
     * Extra horizontal shift keeping one row-sharing caption pair {@code >= 8} GUI px apart (ink to
     * ink): each label moves half the missing gap away from the pair. Clamped, so a narrow wheel
     * cannot throw a label out of its sector; single-word captions cannot wrap.
     */
    public static int pairShift(int centres, int inkA, int inkB) {
        return Math.clamp((int) Math.ceil(((inkA + inkB) / 2.0 + 8 - centres) / 2), 0, 6);
    }

    /**
     * Horizontal nudge per sector, so every row-sharing pair (the bottom pair of an odd count)
     * keeps its {@link #pairShift} gap. The shift moves the whole label assembly (icon and caption)
     * within its sector.
     */
    public static int[] captionShifts(int radius, int count, int[] ink, int lineHeight) {
        int[] shift = new int[count];
        for (int i = 0; i < count; i++) {
            int j = (i + 1) % count;
            int[] a = labelOffset(i, count, radius), b = labelOffset(j, count, radius);
            if (i == j || Math.abs(a[1] - b[1]) > lineHeight) continue;
            int s = pairShift(Math.abs(a[0] - b[0]), ink[i], ink[j]);
            if (a[0] >= b[0]) {
                shift[i] += s;
                shift[j] -= s;
            } else {
                shift[i] -= s;
                shift[j] += s;
            }
        }
        return shift;
    }

    /**
     * Top of the nested-group slot below the dead zone, in pixels from the wheel centre. The group
     * name sits between the 23 px hole (and the crosshair over it) and the captions.
     */
    public static final int GROUP_TOP = 27;

    /**
     * Widest centred group-name slot below the hole (top {@link #GROUP_TOP}, one 10 px row) whose
     * rectangle clears the hole and every sector icon and caption row. Falls back to {@code
     * wordWidth} — the whole word over a graze — when the wheel is too small for even that (radius
     * 72 with a bottom pair); the real nested groups fit at radius 98 and up.
     */
    public static int groupWidth(
            int radius, int count, int[] inkHalf, int[] inkLines, int wordWidth) {
        int cap = (int) (radius * 1.4);
        int half = cap / 2;
        for (int i = 0; i < count; i++) {
            int[] at = labelOffset(i, count, radius);
            if (at[1] - 13 < GROUP_TOP + 10 && at[1] - 1 > GROUP_TOP)
                half = Math.min(half, Math.abs(at[0]) - 6 - 1);
            if (at[1] + 3 < GROUP_TOP + 10 && at[1] + 3 + 10 * inkLines[i] > GROUP_TOP)
                half = Math.min(half, Math.abs(at[0]) - inkHalf[i] - 1);
        }
        return Math.max(Math.min(cap, half * 2), wordWidth);
    }

    public static boolean nested(boolean alreadyNested, int children, boolean panel) {
        return !alreadyNested && children > 0 && children <= 8 && !panel;
    }

    /**
     * Smallest readable label shrink: below it a caption wraps to two lines instead of shrinking
     * further, and the fit below reports the floor as best effort.
     */
    public static final float MIN_SCALE = 0.7f;

    /** Free pixels kept between a caption's ink and its sector chord, on each side. */
    static final float FIT_MARGIN = 2;

    /**
     * Uniform shrink so the widest caption line stays inside its sector chord with a margin: at 8+
     * sectors even a two-line wrap is wider than the chord. Never enlarges; floors at {@link
     * #MIN_SCALE} to stay readable.
     */
    public static float labelScale(int count, int radius, int ink) {
        return Math.clamp(rawFit(count, radius, ink), MIN_SCALE, 1);
    }

    /** Unfloored fit of one caption: the chord minus the margin over the ink, at most 1. */
    static float rawFit(int count, int radius, int ink) {
        if (count < 2 || radius <= 0 || ink <= 0) return 1;
        double chord = 2 * (radius * .68) * Math.tan(Math.PI / count) - 4;
        if (chord <= 0) return 1;
        return (float) Math.min(1, (chord - FIT_MARGIN) / ink);
    }

    /**
     * True when every single-line caption fits its chord without dropping below {@link #MIN_SCALE}:
     * the wheel renders all labels on one line at the common scale. Otherwise the render wraps long
     * captions to two lines and fits the wrapped block instead.
     */
    public static boolean fitsSingleLine(int count, int radius, int[] ink) {
        for (int width : ink) if (rawFit(count, radius, width) < MIN_SCALE) return false;
        return true;
    }

    /**
     * One scale for the whole wheel: the smallest {@link #labelScale} of its labels, so every
     * caption renders at the same size instead of long labels shrinking alone.
     */
    public static float commonScale(int count, int radius, int[] ink) {
        float scale = 1;
        for (int width : ink) scale = Math.min(scale, labelScale(count, radius, width));
        return scale;
    }
}
