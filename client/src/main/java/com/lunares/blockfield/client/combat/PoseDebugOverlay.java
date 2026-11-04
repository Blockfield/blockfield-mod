package com.lunares.blockfield.client.combat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lunares.blockfield.client.render.BodyPartHitboxRenderer;
import com.lunares.blockfield.network.packet.PoseDebugPacket;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Pose overlay (server {@code bf bodypose overlay <player> on}, startup flag only). Draws, next to
 * each remote player's model, its latest server position (red) and the pose the server would test
 * for a shot fired now at the flight to that player (yellow; green is F3+B). A reply arrives a
 * round trip late, so yellow is drawn as its offset from the frame it answers. One log row per
 * rendered frame carries the same numbers, plus predicted feet and camera for the local player.
 */
public final class PoseDebugOverlay {
    private static final int MAX_ROWS = 40_000;
    private static final int MAX_QUERIES = 64;
    private static final long QUERY_NS = 50_000_000L;
    private static final long STALE_NS = 1_000_000_000L;

    private record Server(
            long tick,
            double[] live,
            double[] feet,
            long frameTick,
            long teleports,
            double[][] parts) {}

    private record Rewind(
            long seq,
            int flight,
            double distance,
            String label,
            String labelImpact,
            String subcause,
            double[] start,
            double[] impact,
            double[][] parts,
            double[] drawn) {}

    private static final Map<Integer, Server> SERVER = new HashMap<>();
    private static final Map<Integer, Rewind> REWIND = new HashMap<>();
    private static final LinkedHashMap<Long, Map<Integer, double[]>> SENT = new LinkedHashMap<>();
    private static final ArrayDeque<String> ROWS = new ArrayDeque<>();
    private static long lastPacketNs = Long.MIN_VALUE;
    private static long lastQueryNs;
    private static long lastFrameNs;
    private static long sequence;
    private static long dropped;
    private static String hud = "";

    private PoseDebugOverlay() {}

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(
                PoseDebugPacket.TYPE, (packet, context) -> onPacket(packet));
        WorldRenderEvents.AFTER_ENTITIES.register(PoseDebugOverlay::frame);
        HudRenderCallback.EVENT.register(
                (graphics, tick) -> {
                    if (active() && !hud.isEmpty())
                        graphics.drawString(Minecraft.getInstance().font, hud, 4, 4, 0xFFFF00);
                });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
    }

    private static boolean active() {
        return lastPacketNs != Long.MIN_VALUE && System.nanoTime() - lastPacketNs < STALE_NS;
    }

    static void reset() {
        SERVER.clear();
        REWIND.clear();
        SENT.clear();
        lastPacketNs = Long.MIN_VALUE;
        lastQueryNs = 0;
        hud = "";
    }

    static void onPacket(PoseDebugPacket packet) {
        var in = JsonParser.parseString(packet.json()).getAsJsonObject();
        lastPacketNs = System.nanoTime();
        BodyPartHitboxRenderer.evidence(true);
        if (in.get("kind").getAsString().equals("tick")) {
            long tick = in.get("tick").getAsLong();
            SERVER.clear();
            for (JsonElement element : in.getAsJsonArray("rows")) {
                var row = element.getAsJsonObject();
                SERVER.put(
                        row.get("id").getAsInt(),
                        new Server(
                                tick,
                                doubles(row.getAsJsonArray("live")),
                                doubles(row.getAsJsonArray("feet")),
                                row.get("frameTick").getAsLong(),
                                row.get("teleports").getAsLong(),
                                parts(row.getAsJsonArray("parts"))));
            }
            return;
        }
        long seq = in.get("seq").getAsLong();
        var drawn = SENT.remove(seq);
        if (drawn == null) return;
        for (JsonElement element : in.getAsJsonArray("rows")) {
            var row = element.getAsJsonObject();
            int id = row.get("id").getAsInt();
            var at = drawn.get(id);
            if (at == null) continue;
            REWIND.put(
                    id,
                    new Rewind(
                            seq,
                            row.get("flight").getAsInt(),
                            row.get("distance").getAsDouble(),
                            row.get("label").getAsString(),
                            row.get("labelImpact").getAsString(),
                            row.has("subcause") && !row.get("subcause").isJsonNull()
                                    ? row.get("subcause").getAsString()
                                    : null,
                            doubles(row.getAsJsonArray("start")),
                            doubles(row.getAsJsonArray("impact")),
                            parts(row.getAsJsonArray("parts")),
                            at));
        }
    }

    private static void frame(WorldRenderContext context) {
        if (!active() || context.consumers() == null || context.matrixStack() == null) return;
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        float partial = context.tickCounter().getGameTimeDeltaPartialTick(true);
        long now = System.nanoTime();
        Vec3 camera = context.camera().getPosition();
        var receipt = ClientCombatScene.receipt();
        var pose = context.matrixStack();
        var lines = context.consumers().getBuffer(RenderType.lines());
        var drawn = new HashMap<Integer, double[]>();
        var targets = new JsonArray();
        String text = "";
        for (Player player : mc.level.players()) {
            if (player == mc.player) continue;
            double[] at = drawnFeet(player, partial);
            drawn.put(player.getId(), at);
            var server = SERVER.get(player.getId());
            var rewind = REWIND.get(player.getId());
            if (server != null) draw(pose, lines, camera, server.parts, 0, 0, 0, 1, 0, 0);
            if (rewind != null)
                draw(
                        pose,
                        lines,
                        camera,
                        rewind.parts,
                        at[0] - rewind.drawn[0],
                        at[1] - rewind.drawn[1],
                        at[2] - rewind.drawn[2],
                        1,
                        1,
                        0);
            targets.add(targetRow(player.getId(), at));
            if (server != null && rewind != null)
                text =
                        String.format(
                                java.util.Locale.ROOT,
                                "server live %.2fm | rewind start %.2fm | impact %.2fm (flight %d,"
                                        + " %.0fm)",
                                horizontal(server.live, at),
                                horizontal(rewind.start, rewind.drawn),
                                horizontal(rewind.impact, rewind.drawn),
                                rewind.flight,
                                rewind.distance);
        }
        hud = text;
        var row = new JsonObject();
        row.addProperty("ns", now);
        row.addProperty("dtMs", lastFrameNs == 0 ? 0 : (now - lastFrameNs) / 1e6);
        lastFrameNs = now;
        if (receipt != null) {
            row.addProperty("r", receipt.time());
            row.addProperty("h", receipt.high());
            row.addProperty("rate", ClientCombatScene.clock().rate());
            row.addProperty("delay", ClientCombatScene.clock().delay());
            if (receipt.exact() != null && receipt.exact().unavailable() != null)
                row.addProperty("unavailable", receipt.exact().unavailable());
        }
        var self = new JsonObject();
        self.add("pred", json(mc.player.getPosition(partial)));
        self.add("tickPos", json(mc.player.position()));
        self.add("cam", json(camera));
        var mine = SERVER.get(mc.player.getId());
        if (mine != null) {
            self.add("live", json(mine.live));
            self.add("feet", json(mine.feet));
            self.addProperty("srvTick", mine.tick);
            self.addProperty("teleports", mine.teleports);
        }
        row.add("self", self);
        row.add("targets", targets);
        record(row.toString());
        if (receipt != null && !drawn.isEmpty() && queryDue(now)) {
            var query = new JsonObject();
            query.addProperty("seq", noteQuery(drawn));
            query.addProperty("rTick", receipt.tick());
            query.addProperty("rAlpha", receipt.alpha());
            ClientPlayNetworking.send(new PoseDebugPacket(query.toString()));
        }
    }

    static boolean queryDue(long now) {
        if (lastQueryNs != 0 && now - lastQueryNs < QUERY_NS) return false;
        lastQueryNs = now;
        return true;
    }

    /** Remembers the frame a query asks about: its reply is measured from these positions. */
    static long noteQuery(Map<Integer, double[]> drawn) {
        long seq = ++sequence;
        SENT.put(seq, drawn);
        while (SENT.size() > MAX_QUERIES) SENT.remove(SENT.keySet().iterator().next());
        return seq;
    }

    static void record(String row) {
        if (ROWS.size() >= MAX_ROWS) {
            ROWS.removeFirst();
            dropped++;
        }
        ROWS.addLast(row);
    }

    static JsonObject targetRow(int id, double[] at) {
        var server = SERVER.get(id);
        var rewind = REWIND.get(id);
        var row = new JsonObject();
        row.addProperty("id", id);
        row.add("drawn", json(at));
        if (server != null) {
            row.add("live", json(server.live));
            row.add("feet", json(server.feet));
            row.addProperty("srvTick", server.tick);
            row.addProperty("frameTick", server.frameTick);
            row.addProperty("teleports", server.teleports);
        }
        if (rewind != null) {
            row.addProperty("seq", rewind.seq);
            row.addProperty("flight", rewind.flight);
            row.addProperty("distance", rewind.distance);
            row.addProperty("label", rewind.label);
            row.addProperty("labelImpact", rewind.labelImpact);
            if (rewind.subcause != null) row.addProperty("subcause", rewind.subcause);
            row.add("offStart", json(minus(rewind.start, rewind.drawn)));
            row.add("offImpact", json(minus(rewind.impact, rewind.drawn)));
        }
        return row;
    }

    /** The position the renderer really drew this frame; the tick lerp when it was culled. */
    private static double[] drawnFeet(Player player, float partial) {
        var capture = BodyPartHitboxRenderer.mainCapture(player.getId());
        if (capture != null) return capture.origin();
        Vec3 at = player.getPosition(partial);
        return new double[] {at.x, at.y, at.z};
    }

    private static void draw(
            PoseStack pose,
            VertexConsumer lines,
            Vec3 camera,
            double[][] parts,
            double dx,
            double dy,
            double dz,
            float r,
            float g,
            float b) {
        for (double[] p : parts)
            LevelRenderer.renderLineBox(
                    pose,
                    lines,
                    p[0] + dx - camera.x,
                    p[1] + dy - camera.y,
                    p[2] + dz - camera.z,
                    p[3] + dx - camera.x,
                    p[4] + dy - camera.y,
                    p[5] + dz - camera.z,
                    r,
                    g,
                    b,
                    1);
    }

    /** Writes the frame log as NDJSON and empties it; the row count, or -1 if rows were lost. */
    public static long dump(String path) throws IOException {
        var rows = new ArrayList<>(ROWS);
        long lost = dropped;
        ROWS.clear();
        dropped = 0;
        var file = Path.of(path);
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Files.write(file, rows, StandardCharsets.UTF_8);
        return lost > 0 ? -1 : rows.size();
    }

    private static double horizontal(double[] a, double[] b) {
        return Math.hypot(a[0] - b[0], a[2] - b[2]);
    }

    private static double[] minus(double[] a, double[] b) {
        return new double[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static double[][] parts(JsonArray in) {
        var out = new double[in.size()][];
        for (int i = 0; i < out.length; i++) out[i] = doubles(in.get(i).getAsJsonArray());
        return out;
    }

    private static double[] doubles(JsonArray in) {
        var out = new double[in.size()];
        for (int i = 0; i < out.length; i++) out[i] = in.get(i).getAsDouble();
        return out;
    }

    private static JsonArray json(double[] v) {
        var out = new JsonArray();
        for (double d : v) out.add(Math.round(d * 10000) / 10000.0);
        return out;
    }

    private static JsonArray json(Vec3 v) {
        return json(new double[] {v.x, v.y, v.z});
    }
}
