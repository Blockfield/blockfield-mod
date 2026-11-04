package com.lunares.blockfield.client.gui.screen;

public final class DestructiveConfirm {
    private static final long WINDOW_MS = 3000;
    private static String pendingAction;
    private static long pendingAt;

    private DestructiveConfirm() {}

    public static boolean armed(String action, long now) {
        return action.equals(pendingAction) && now - pendingAt < WINDOW_MS;
    }

    public static boolean confirm(String action, long now) {
        if (armed(action, now)) {
            pendingAction = null;
            return true;
        }
        pendingAction = action;
        pendingAt = now;
        return false;
    }
}
