package com.lunares.blockfield.client.gui;

/** Frame-time interpolation with continuous reversals, independent of the game tick rate. */
public final class UiMotion {
    public static final long DURATION_NS = 170_000_000L;
    private float from;
    private float target;
    private long started;

    public void snap(float value) {
        from = target = value;
    }

    public void to(float value, long now) {
        if (value == target) return;
        from = value(now);
        target = value;
        started = now;
    }

    public float value(long now) {
        float t = Math.max(0f, Math.min(1f, (now - started) / (float) DURATION_NS));
        return from + (target - from) * (1f - (float) Math.pow(1f - t, 3));
    }
}
