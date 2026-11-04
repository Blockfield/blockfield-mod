package com.lunares.blockfield.client.music;

import java.util.Locale;

/**
 * Состояния саундтрека. Боевые (от {@link #AMBIENT}) упорядочены по приоритету: старшее перекрывает
 * младшее, {@link MusicBrain} сравнивает их через {@code compareTo}. Остальные задаёт сцена (меню,
 * хаб, итог боя).
 */
public enum MusicState {
    SILENT,
    MENU,
    LOBBY,
    WORKSHOP,
    LOADING,
    POST_MATCH,
    VICTORY,
    DEFEAT,
    AMBIENT,
    TACTICAL,
    DEPLOYMENT,
    COMBAT,
    VEHICLE,
    CONTESTED,
    CRITICAL,
    FINAL_PUSH;

    public boolean gameplay() {
        return compareTo(AMBIENT) >= 0;
    }

    /** Ключ секции в music.json. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static MusicState byId(String id) {
        for (MusicState state : values()) if (state.id().equals(id)) return state;
        return null;
    }

    static MusicState max(MusicState a, MusicState b) {
        return a.compareTo(b) >= 0 ? a : b;
    }
}
