package com.lunares.blockfield.client.music;

import com.lunares.blockfield.client.music.MusicContext.Scene;
import java.util.Locale;

/**
 * Единственное место, где решается, какое состояние музыки звучит. Чистая логика без Minecraft:
 * вход -- {@link MusicContext} и время, выход -- {@link MusicState}. Тесты -- MusicBrainTest.
 *
 * <p>Боевые состояния идут с гистерезисом: подъём, если цель выше текущей держится {@code enter}
 * секунд, спуск -- если ниже держится {@code exit} секунд и текущее отзвучало не меньше {@code
 * hold}. Сцены (меню, хаб, подготовка, итог) переключаются сразу. Итог боя и высадка -- защёлки с
 * таймером {@code max}.
 */
public final class MusicBrain {
    private MusicState state = MusicState.SILENT;
    private MusicState target = MusicState.SILENT;
    private long enteredAt;
    private long upSince = -1;
    private long downSince = -1;
    private String reason = "";

    private Scene scene;
    private boolean deployed;
    private long deploymentUntil = -1;

    private MusicState ending;
    private long endingAt;
    private MusicState forced;

    public MusicState update(MusicContext c, MusicConfig config, long now) {
        if (c.scene() != scene) {
            // Новый раунд: только переход в подготовку или бой из другой сцены, а не устаревший
            // GAME сразу после итога.
            if (c.scene() == Scene.PREPARING || c.scene() == Scene.GAME || c.scene() == Scene.MENU)
                ending = null;
            if (c.scene() != Scene.GAME) {
                deployed = false;
                deploymentUntil = -1;
            }
            scene = c.scene();
        }
        target = forced != null ? forced : target(c, config, now);
        if (forced != null) reason = "принудительно (/music state)";
        if (target == state) {
            upSince = downSince = -1;
        } else if (!state.gameplay() || !target.gameplay() || forced != null) {
            switchTo(target, now);
        } else if (target.compareTo(state) > 0) {
            downSince = -1;
            if (upSince < 0) upSince = now;
            if (now - upSince >= ms(config.state(target).enter)) switchTo(target, now);
        } else {
            upSince = -1;
            if (downSince < 0) downSince = now;
            MusicConfig.StateConfig current = config.state(state);
            if (now - downSince >= ms(current.exit) && now - enteredAt >= ms(current.hold))
                switchTo(target, now);
        }
        return state;
    }

    /** Итог боя от сервера: "win", "loss" или "draw". */
    public void matchResult(String result, long now) {
        ending =
                switch (result) {
                    case "win" -> MusicState.VICTORY;
                    case "loss" -> MusicState.DEFEAT;
                    default -> MusicState.POST_MATCH;
                };
        endingAt = now;
    }

    /** Отладка: зафиксировать состояние; null -- снова автоматически. */
    public void force(MusicState state) {
        forced = state;
    }

    private MusicState target(MusicContext c, MusicConfig config, long now) {
        if (ending != null) {
            long since = now - endingAt;
            long result = ms(config.state(ending).max);
            if (ending != MusicState.POST_MATCH && since < result) {
                reason = "итог боя: " + ending.id();
                return ending;
            }
            if (since < result + ms(config.state(MusicState.POST_MATCH).max)) {
                reason = "после боя";
                return MusicState.POST_MATCH;
            }
            ending = null;
        }
        switch (c.scene()) {
            case MENU -> {
                reason = "главное меню";
                return MusicState.MENU;
            }
            case HUB -> {
                reason = "хаб";
                return MusicState.LOBBY;
            }
            case WORKSHOP -> {
                reason = "мастерская";
                return MusicState.WORKSHOP;
            }
            case RANGE -> {
                reason = "полигон: без музыки";
                return MusicState.SILENT;
            }
            case VOTING, PREPARING -> {
                reason = "подготовка к бою";
                return MusicState.LOADING;
            }
            case MATCH_END -> {
                reason = "бой окончен";
                return MusicState.POST_MATCH;
            }
            default -> {}
        }
        if (!deployed) {
            if (!c.alive()) {
                reason = "выбор высадки";
                return MusicState.LOADING;
            }
            deployed = true;
            deploymentUntil = now + ms(config.state(MusicState.DEPLOYMENT).max);
        }
        MusicConfig.Combat t = config.combat;
        MusicState s = MusicState.AMBIENT;
        reason = "тихо";
        if (c.combat() >= t.tactical || c.enemiesNear() > 0 || c.nearObjective()) {
            s = MusicState.TACTICAL;
            reason =
                    String.format(
                            Locale.ROOT,
                            "рядом: бой %.1f, враги %d, точка %b",
                            c.combat(),
                            c.enemiesNear(),
                            c.nearObjective());
        }
        if (now < deploymentUntil) {
            s = MusicState.max(s, MusicState.DEPLOYMENT);
            reason = "высадка";
        }
        if (c.combat() >= t.combat) {
            s = MusicState.max(s, c.inVehicle() ? MusicState.VEHICLE : MusicState.COMBAT);
            reason =
                    String.format(
                            Locale.ROOT,
                            "бой %.1f >= %.1f%s",
                            c.combat(),
                            t.combat,
                            c.inVehicle() ? " в технике" : "");
        }
        if (c.contested() && (c.combat() >= t.tactical || c.enemiesNear() > 0)) {
            s = MusicState.max(s, MusicState.CONTESTED);
            reason =
                    String.format(
                            Locale.ROOT,
                            "спорная точка: бой %.1f, враги %d",
                            c.combat(),
                            c.enemiesNear());
        }
        if (c.pressure().state().compareTo(s) > 0) {
            s = c.pressure().state();
            reason = c.pressure().reason();
        }
        // Мёртвый игрок не разгоняет музыку; спад идёт своим чередом.
        if (!c.alive() && s.compareTo(state) > 0 && state.gameplay()) {
            s = state;
            reason = "ждёт возрождения";
        }
        return s;
    }

    private void switchTo(MusicState next, long now) {
        // Высадку перебил бой: после боя она не возвращается, иначе снова заиграл бы трек высадки.
        if (next.compareTo(MusicState.DEPLOYMENT) > 0) deploymentUntil = -1;
        state = next;
        enteredAt = now;
        upSince = downSince = -1;
    }

    private static long ms(double seconds) {
        return (long) (seconds * 1000);
    }

    public MusicState state() {
        return state;
    }

    public MusicState target() {
        return target;
    }

    public String reason() {
        return reason;
    }

    public MusicState ending() {
        return ending;
    }

    public boolean forced() {
        return forced != null;
    }

    /** Таймер гистерезиса для отладки: "вверх 1.2 с", "вниз 4.0 с" или "". */
    public String timer(long now) {
        if (upSince >= 0)
            return String.format(Locale.ROOT, "вверх %.1f с", (now - upSince) / 1000.0);
        if (downSince >= 0)
            return String.format(Locale.ROOT, "вниз %.1f с", (now - downSince) / 1000.0);
        return "";
    }

    public double stateSeconds(long now) {
        return (now - enteredAt) / 1000.0;
    }
}
