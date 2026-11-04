package com.lunares.blockfield.client.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.lunares.blockfield.client.music.MusicContext.Scene;
import com.lunares.blockfield.client.render.HudRenderer;
import com.lunares.blockfield.client.voice.VoiceHudState;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.scores.Team;

/**
 * Сбор сигналов для музыки из того, что клиент и так знает: состояние комнаты (presence раз в
 * секунду), UI-пакет матча (билеты, точки, зона), сущности и звуки вокруг, здоровье и фраги своего
 * игрока. Серверу не нужно ничего знать о музыке, кроме итога боя ({@code matchResult}).
 */
final class MusicSignals {
    private static final long PRESENCE_TTL_MS = 5000;

    private final CombatMeter meter = new CombatMeter(4);
    private final Map<String, String> owners = new HashMap<>();
    private MusicConfig config = new MusicConfig();
    private JsonObject presence;
    private long presenceAt;
    private float lastHealth = -1;
    private int lastKills = -1;
    private long explosionDuckUntil;
    private Scene scene = Scene.MENU;

    void config(MusicConfig config) {
        this.config = config;
        meter.halfLife(config.combat.halfLife);
    }

    void presence(JsonObject value, long now) {
        presence = value;
        presenceAt = now;
    }

    /** Снимок на этот тик; попутно сообщает о захвате или потере точек своей командой. */
    MusicContext context(Minecraft mc, long now, Consumer<String> stinger) {
        scene = scene(mc, now);
        LocalPlayer player = mc.player;
        if (scene != Scene.GAME || player == null) {
            owners.clear();
            lastHealth = -1;
            return MusicContext.scene(scene);
        }
        JsonObject ui = HudRenderer.uiData();
        boolean alive = HudRenderer.isDeployed() && player.isAlive();
        String team = HudRenderer.playerTeam();
        String color = team.equals("RedTeam") ? "RED" : team.equals("BlueTeam") ? "BLUE" : "";
        boolean ffa = ui != null && ui.has("ffa") && ui.get("ffa").getAsBoolean();

        if (alive) {
            float health = player.getHealth();
            if (lastHealth >= 0 && health < lastHealth - 0.5f) meter.add(config.combat.hurt, now);
            lastHealth = health;
        } else {
            lastHealth = -1;
        }

        boolean contested = false, nearObjective = false;
        if (ui != null && ui.has("zone") && ui.get("zone").isJsonObject()) {
            JsonObject zone = ui.getAsJsonObject("zone");
            // Точка «спокойна», только если она наша, полностью и на ней перевес у нас; ничья на
            // точке = там враги.
            contested =
                    bool(zone, "active")
                            && !(color.equals(str(zone, "owner"))
                                    && color.equals(str(zone, "capturing"))
                                    && num(zone, "progress") >= 100);
        }
        JsonArray points =
                ui != null && ui.has("pts") && ui.get("pts").isJsonArray()
                        ? ui.getAsJsonArray("pts")
                        : new JsonArray();
        double radius = config.combat.objectiveRadius;
        for (int i = 0; i < points.size(); i++) {
            if (!points.get(i).isJsonObject()) continue;
            JsonObject point = points.get(i).getAsJsonObject();
            String owner = str(point, "o");
            if (!owner.equals(color)
                    && Mth.lengthSquared(
                                    player.getX() - num(point, "x"),
                                    player.getZ() - num(point, "z"))
                            <= radius * radius) {
                nearObjective = true;
            }
            // id точки не уникален (на Бердичеве у всех трёх id карты), ключ -- позиция и имя.
            String before = owners.put(i + ":" + str(point, "n"), owner);
            if (before != null && !before.equals(owner) && !color.isEmpty()) {
                if (owner.equals(color)) stinger.accept("captured");
                else if (before.equals(color)) stinger.accept("lost");
            }
        }

        int enemies = 0;
        double enemyRadius = config.combat.enemyRadius;
        Team own = player.getTeam();
        for (Player other : player.level().players()) {
            if (other == player
                    || other.isSpectator()
                    || !other.isAlive()
                    || other.distanceToSqr(player) > enemyRadius * enemyRadius) continue;
            Team theirs = other.getTeam();
            if (ffa || own != null && theirs != null && !theirs.getName().equals(own.getName()))
                enemies++;
        }

        MatchPressure.Result pressure = MatchPressure.Result.NONE;
        if (ui != null && !color.isEmpty() && ui.has("rT") && ui.has("bT")) {
            int red = ui.get("rT").getAsInt(), blue = ui.get("bT").getAsInt();
            int mine = color.equals("RED") ? red : blue, enemy = color.equals("RED") ? blue : red;
            if (ui.has("killLimit")) {
                pressure =
                        MatchPressure.frags(
                                mine,
                                enemy,
                                ui.get("killLimit").getAsInt(),
                                ui.has("timeLeft") ? ui.get("timeLeft").getAsInt() : -1,
                                config.pressure);
            } else if (ui.has("maxT")) {
                pressure =
                        MatchPressure.tickets(
                                mine, enemy, ui.get("maxT").getAsInt(), config.pressure);
            }
        }
        return new MusicContext(
                scene,
                alive,
                HudRenderer.hasCapturePoints(),
                player.getVehicle() != null,
                contested,
                nearObjective,
                enemies,
                meter.value(now),
                pressure);
    }

    /**
     * Множитель громкости музыки: смерть (только в бою, не на брифинге до высадки), чужой голос,
     * близкий взрыв.
     */
    float duck(Minecraft mc, MusicState state, long now) {
        MusicConfig.Ducking d = config.ducking;
        float duck = 1f;
        if (state.gameplay() && scene == Scene.GAME && !HudRenderer.isDeployed()) duck *= d.dead;
        if (mc.player != null && VoiceHudState.anyoneSpeaking(now)) duck *= d.voice;
        if (now < explosionDuckUntil) duck *= d.explosion;
        return duck;
    }

    /** Новая сущность в мире: снаряд (пуля TACZ, ракета, граната) -- выстрел рядом. */
    void entity(Entity entity, Minecraft mc, long now) {
        if (!(entity instanceof Projectile projectile) || mc.player == null) return;
        double weight = config.combat.shot * falloff(Math.sqrt(entity.distanceToSqr(mc.player)));
        if (weight <= 0) return;
        Entity owner = projectile.getOwner();
        // Без владельца стрелка узнаём по месту вылета: иначе все пули слились бы в одного
        // «стрелка» с лимитом.
        int shooter =
                owner != null
                        ? owner.getId()
                        : Objects.hash(
                                Mth.floor(entity.getX() / 4),
                                Mth.floor(entity.getY() / 4),
                                Mth.floor(entity.getZ() / 4));
        meter.shot(shooter, weight, config.combat.shotCap, now);
    }

    /**
     * Любой сыгранный звук: взрывы опознаются по имени события (ваниль, Superb Warfare, гранаты).
     */
    void sound(SoundInstance sound, Minecraft mc, long now) {
        ResourceLocation id = sound.getLocation();
        if (mc.player == null || sound.isRelative() || !id.getPath().contains("explo")) return;
        double distance =
                Math.sqrt(mc.player.distanceToSqr(sound.getX(), sound.getY(), sound.getZ()));
        meter.add(config.combat.explosion * falloff(distance), now);
        if (distance <= config.ducking.explosionRadius)
            explosionDuckUntil = now + (long) (config.ducking.explosionHold * 1000);
    }

    /** Свой счёт из таблицы: фраг -- тоже признак боя. */
    void kills(int kills, long now) {
        if (lastKills >= 0 && kills > lastKills) meter.add(config.combat.kill, now);
        lastKills = kills;
    }

    double combat(long now) {
        return meter.value(now);
    }

    private double falloff(double distance) {
        MusicConfig.Combat c = config.combat;
        if (distance <= c.near) return 1;
        if (distance >= c.far) return 0;
        return 1 - (distance - c.near) / (c.far - c.near);
    }

    private Scene scene(Minecraft mc, long now) {
        if (mc.level == null || mc.player == null) return Scene.MENU;
        ResourceLocation dimension = mc.level.dimension().location();
        if (dimension.getNamespace().equals("blockfield_shared")
                && dimension.getPath().equals("firing_range")) return Scene.RANGE;
        // The workshop hub and every map there live in this namespace (blockfield-mod
        // Workshop/WorkshopMaps).
        if (dimension.getNamespace().equals("blockfield_workshop")) return Scene.WORKSHOP;
        JsonObject room = now - presenceAt < PRESENCE_TTL_MS ? presence : null;
        // Без presence (лимбо прокси, мастерская) -- по HUD: бой или хаб.
        if (room == null) return HudRenderer.isDeployed() ? Scene.GAME : Scene.HUB;
        if ("lobby".equals(str(room, "id"))) return Scene.HUB;
        return switch (str(room, "phase")) {
            case "VOTING" -> Scene.VOTING;
            case "PREPARING" -> Scene.PREPARING;
            case "GAME" -> Scene.GAME;
            case "MATCH_END" -> Scene.MATCH_END;
            default -> Scene.HUB;
        };
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }

    private static double num(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsDouble() : 0;
    }

    private static boolean bool(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() && o.get(key).getAsBoolean();
    }

    /** Сырые данные счёта из UI-пакета для отладки: команда, билеты/фраги и их предел. */
    String score() {
        JsonObject ui = HudRenderer.uiData();
        if (ui == null) return "нет UI";
        return String.format(
                java.util.Locale.ROOT,
                "%s %s:%s, maxT %s, killLimit %s, timeLeft %s",
                HudRenderer.playerTeam(),
                str(ui, "rT"),
                str(ui, "bT"),
                str(ui, "maxT"),
                str(ui, "killLimit"),
                str(ui, "timeLeft"));
    }

    List<String> debug(long now) {
        List<String> lines = new ArrayList<>();
        JsonObject room = now - presenceAt < PRESENCE_TTL_MS ? presence : null;
        lines.add(
                "сцена "
                        + scene
                        + (room == null
                                ? " (нет presence)"
                                : " · комната " + str(room, "id") + " " + str(room, "phase")));
        return lines;
    }
}
