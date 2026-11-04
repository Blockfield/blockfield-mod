package com.lunares.blockfield.client.workshop;

/** Shares the installed voice-chat microphone; no second device or capture thread is opened. */
public final class WorkshopVoiceCapture {
    private static volatile boolean screenOpen;
    private static volatile boolean recording;
    private static WorkshopRecording audio;
    private static long started;
    private static volatile long suppressUntil;

    private WorkshopVoiceCapture() {}

    public static synchronized void openScreen() {
        discard();
        screenOpen = true;
    }

    public static boolean screenOpen() {
        return screenOpen;
    }

    public static boolean privateAudio() {
        return screenOpen || System.nanoTime() < suppressUntil;
    }

    public static boolean recording() {
        return recording;
    }

    public static synchronized void start() {
        if (!screenOpen) return;
        discard();
        audio = new WorkshopRecording();
        started = System.nanoTime();
        recording = true;
    }

    public static synchronized void accept(short[] samples) {
        if (screenOpen && recording && audio != null) audio.append(samples);
    }

    /** Whole seconds of the running recording, 0 when idle. */
    public static synchronized long seconds() {
        return recording ? (System.nanoTime() - started) / 1_000_000_000L : 0;
    }

    public static synchronized boolean timedOut() {
        return recording
                && (audio.full()
                        || System.nanoTime() - started
                                >= WorkshopRecording.MAX_SECONDS * 1_000_000_000L);
    }

    public static synchronized byte[] finish() {
        recording = false;
        byte[] result = audio == null || audio.samples() < 1600 ? null : audio.wav();
        discard();
        return result;
    }

    public static synchronized void discard() {
        recording = false;
        if (audio != null) audio.clear();
        audio = null;
    }

    public static synchronized void closeScreen() {
        discard();
        if (screenOpen) suppressUntil = System.nanoTime() + 250_000_000L;
        screenOpen = false;
    }
}
