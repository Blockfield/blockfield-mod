package com.lunares.blockfield.client.music;

/**
 * Снимок того, что музыке нужно знать о мире на этом тике: сцена, игрок (жив, в технике), поле боя
 * вокруг (очки боя, враги, точка) и матч в целом (давление по счёту). Собирает {@code
 * MusicSignals}, решает {@link MusicBrain}.
 */
public record MusicContext(
        Scene scene,
        boolean alive,
        boolean objectives,
        boolean inVehicle,
        boolean contested,
        boolean nearObjective,
        int enemiesNear,
        double combat,
        MatchPressure.Result pressure) {

    public enum Scene {
        MENU,
        HUB,
        RANGE,
        WORKSHOP,
        VOTING,
        PREPARING,
        GAME,
        MATCH_END
    }

    public static MusicContext scene(Scene scene) {
        return new MusicContext(
                scene, false, true, false, false, false, 0, 0, MatchPressure.Result.NONE);
    }
}
