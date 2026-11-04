package com.lunares.blockfield.client.gui.tablet;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Итог боя из пакета {@code matchResult} (сервер: {@code MatchResults}), разобранный для вкладки
 * «Итог боя». Без классов Minecraft -- проверяется юнит-тестом без игры.
 *
 * @param me своя строка; null -- игрок не воевал (зритель, зашёл на последней секунде)
 */
public record MatchResult(
        String mode,
        String map,
        String winner,
        String reason,
        boolean frags,
        int red,
        int blue,
        long duration,
        List<Player> players,
        Player me) {

    public enum Outcome {
        VICTORY,
        DEFEAT,
        DRAW,
        OVER
    }

    public record Player(
            String name,
            String uuid,
            String team,
            long score,
            int kills,
            int deaths,
            int pointTime,
            int captures,
            int neutralizations,
            int streak,
            String cls,
            boolean deserter,
            int droneKills,
            long droneDamage) {}

    public static MatchResult parse(JsonObject json, String myUuid) {
        List<Player> players = new ArrayList<>();
        Player me = null;
        if (json.has("players") && json.get("players").isJsonArray()) {
            for (JsonElement element : json.getAsJsonArray("players")) {
                if (!element.isJsonObject()) continue;
                JsonObject p = element.getAsJsonObject();
                Player player =
                        new Player(
                                str(p, "name"),
                                str(p, "uuid"),
                                str(p, "team"),
                                num(p, "score"),
                                (int) num(p, "kills"),
                                (int) num(p, "deaths"),
                                (int) num(p, "pointTime"),
                                (int) num(p, "captures"),
                                (int) num(p, "neutralizations"),
                                (int) num(p, "streak"),
                                str(p, "cls"),
                                p.has("deserter") && p.get("deserter").getAsBoolean(),
                                (int) num(p, "droneKills"),
                                num(p, "droneDamage"));
                players.add(player);
                if (me == null && !player.deserter() && player.uuid().equalsIgnoreCase(myUuid))
                    me = player;
            }
        }
        return new MatchResult(
                str(json, "mode"),
                str(json, "map"),
                str(json, "winner"),
                str(json, "reason"),
                "frags".equals(str(json, "scoreKey")),
                (int) num(json, "red"),
                (int) num(json, "blue"),
                num(json, "duration"),
                List.copyOf(players),
                me);
    }

    /** Damage in a narrow cell: 950, 1.3k, 12k. */
    public static String compact(long value) {
        if (value < 1000) return String.valueOf(value);
        return value < 10_000
                ? String.format(Locale.ROOT, "%.1fk", value / 1000.0)
                : (value / 1000) + "k";
    }

    /** Командный бой: у участников есть стороны; в «каждый сам за себя» их нет. */
    public boolean teams() {
        return players.stream()
                .anyMatch(p -> "RedTeam".equals(p.team()) || "BlueTeam".equals(p.team()));
    }

    public Outcome outcome() {
        if ("DRAW".equals(winner)) return Outcome.DRAW;
        if (me == null) return Outcome.OVER;
        boolean won = teams() ? winner.equals(me.team()) : winner.equalsIgnoreCase(me.uuid());
        return won ? Outcome.VICTORY : Outcome.DEFEAT;
    }

    /** Место среди всех участников, с единицы; 0 -- своей строки нет. */
    public int rank() {
        return me == null ? 0 : players.indexOf(me) + 1;
    }

    /** Список планшета: своя команда в командном бою, все -- в «каждый сам за себя» и у зрителя. */
    public List<Player> roster() {
        if (me == null || !teams()) return players;
        return players.stream().filter(p -> p.team().equals(me.team())).toList();
    }

    /** Лучший по очкам в {@link #roster()}; null -- список пуст. */
    public Player best() {
        List<Player> roster = roster();
        return roster.isEmpty() ? null : roster.get(0);
    }

    /** Строки списка, влезающие в max: своя строка всегда видна, даже если она ниже отреза. */
    public List<Player> visibleRoster(int max) {
        List<Player> roster = roster();
        if (roster.size() <= max || max <= 0)
            return roster.subList(0, Math.min(roster.size(), Math.max(0, max)));
        List<Player> shown = new ArrayList<>(roster.subList(0, max));
        if (me != null && roster.indexOf(me) >= max) shown.set(max - 1, me);
        return shown;
    }

    public static String clock(long seconds) {
        return seconds / 60 + ":" + String.format(Locale.ROOT, "%02d", seconds % 60);
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private static long num(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsLong() : 0;
    }
}
