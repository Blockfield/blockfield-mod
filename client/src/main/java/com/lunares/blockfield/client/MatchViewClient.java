package com.lunares.blockfield.client;

/**
 * The server announces the chunk radius its view needs (a match map: the whole map), but the client
 * draws only up to its own render distance. While the server announces more than the player chose,
 * render at least the announced radius; when it drops back (leaving the match), restore the
 * player's value. A manual slider move always wins over the remembered value.
 */
public final class MatchViewClient {
    private static int saved = -1;
    private static int raised = -1;

    private MatchViewClient() {}

    /** Render distance to use after the server announced `announced`, from `current`. */
    public static int onRadius(int announced, int current) {
        if (saved >= 0 && current != raised) saved = current;
        if (announced > current) {
            if (saved < 0) saved = current;
            raised = Math.min(announced, 32);
            return raised;
        }
        if (saved >= 0 && announced <= saved) {
            int restore = saved;
            saved = -1;
            return restore;
        }
        return current;
    }

    /** Leaving the level or server: give back the value the announcements borrowed. */
    public static int onLeave(int current) {
        if (saved < 0) return current;
        int restore = saved;
        saved = -1;
        return restore;
    }
}
