package com.lunares.blockfield.client.gui.tablet;

import com.lunares.blockfield.client.gui.BfTheme;
import com.lunares.blockfield.client.render.PointMarkerRenderer;
import com.lunares.blockfield.network.packet.MapPingS2CPacket;
import java.util.ArrayList;
import java.util.List;

/** Метки команды с карты планшета: живут отведённые сервером секунды и гаснут сами. */
public final class MapPings {

    private record Ping(String author, double x, double y, double z, long expiresAt) {}

    private static final List<Ping> PINGS = new ArrayList<>();

    /** A session accumulates pings, so the list and the lifetime are both bounded. */
    static final int MAX_PINGS = 64;

    static final int MIN_SECONDS = 1;
    static final int MAX_SECONDS = 60;

    private MapPings() {}

    public static void add(MapPingS2CPacket packet) {
        if (!Double.isFinite(packet.x())
                || !Double.isFinite(packet.y())
                || !Double.isFinite(packet.z())) return;
        long expiresAt =
                System.currentTimeMillis()
                        + Math.clamp(packet.seconds(), MIN_SECONDS, MAX_SECONDS) * 1000L;
        synchronized (PINGS) {
            while (PINGS.size() >= MAX_PINGS) PINGS.remove(0);
            PINGS.add(new Ping(packet.author(), packet.x(), packet.y(), packet.z(), expiresAt));
        }
    }

    public static void clear() {
        synchronized (PINGS) {
            PINGS.clear();
        }
    }

    /**
     * Метки как маркеры точек: тот же рисунок кольца с буквой, поэтому они видны и в мире, и на
     * миникарте, и на карте планшета без отдельного рендера.
     */
    public static List<PointMarkerRenderer.Marker> markers() {
        long now = System.currentTimeMillis();
        synchronized (PINGS) {
            PINGS.removeIf(ping -> ping.expiresAt() <= now);
            if (PINGS.isEmpty()) return List.of();
            List<PointMarkerRenderer.Marker> markers = new ArrayList<>(PINGS.size());
            for (Ping ping : PINGS) {
                markers.add(
                        new PointMarkerRenderer.Marker(
                                ping.x(),
                                ping.y(),
                                ping.z(),
                                ping.author(),
                                "!",
                                BfTheme.AMBER_BRIGHT,
                                0.0,
                                BfTheme.AMBER_BRIGHT,
                                null));
            }
            return markers;
        }
    }
}
