package com.lunares.blockfield.client.combat;

import com.lunares.blockfield.common.combat.AnimationResources;
import com.lunares.blockfield.common.combat.AnimationSchema;
import com.lunares.blockfield.common.combat.AnimationStartReport;
import com.lunares.blockfield.common.combat.AnimationTypes.Time;
import com.lunares.blockfield.common.combat.CombatView;
import com.lunares.blockfield.network.packet.AnimationAppliedC2SPacket;
import com.lunares.blockfield.network.packet.AnimationEventS2CPacket;
import com.lunares.blockfield.network.packet.AnimationProbeC2SPacket;
import com.lunares.blockfield.network.packet.AnimationProbeS2CPacket;
import com.lunares.blockfield.network.packet.PlayerSnapshotS2CPacket;
import com.mojang.logging.LogUtils;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.Boat;

/**
 * One main-thread scene and immutable last-rendered receipt per play connection. Tickets are also
 * drawn off-thread: TaCZ bf8 {@code LocalPlayerShoot.lambda$doShoot$2} sends from its scheduler.
 *
 * <p>On the ADR4 exact stream (U8) every main frame advances R and A once, applies the capsules
 * received since the previous frame in that same revision (zero client ticks in between), presents
 * each driven remote from its observer reduction and freezes the complete victim report; a capsule
 * applied while a frame renders makes a new revision of that frame with the same clocks.
 */
public final class ClientCombatScene {
    private static ClientSnapshotBuffer buffer = new ClientSnapshotBuffer();
    private static CombatSceneClock clock = new CombatSceneClock();
    private static long clockEpoch = -1;
    private static volatile CombatRenderReceipt receipt;
    private static volatile double sendDeadlineMs = Double.NEGATIVE_INFINITY;
    private static final Map<Integer, Entity> nativeEntities = new HashMap<>();
    private static final Map<Integer, String> seats = new HashMap<>();
    private static final java.util.Set<Class<?>> SIMPLE_VEHICLES =
            java.util.Set.of(
                    Boat.class,
                    net.minecraft.world.entity.vehicle.ChestBoat.class,
                    net.minecraft.world.entity.vehicle.Minecart.class,
                    net.minecraft.world.entity.vehicle.MinecartChest.class,
                    net.minecraft.world.entity.vehicle.MinecartFurnace.class,
                    net.minecraft.world.entity.vehicle.MinecartHopper.class,
                    net.minecraft.world.entity.vehicle.MinecartTNT.class,
                    net.minecraft.world.entity.vehicle.MinecartSpawner.class,
                    net.minecraft.world.entity.vehicle.MinecartCommandBlock.class);
    private static final AtomicLong sequence = new AtomicLong();
    private static volatile double lastAttackAge;

    /**
     * Rate/occupancy log for the §8 smoothness gates: one entry per client tick, 5 min at 20 tps.
     * Off until the first {@link #debugLog} call, so ordinary clients never hold it.
     */
    private static final int NET_LOG_CAP = 6000;

    private static final Deque<Map<String, Object>> netLog = new ArrayDeque<>();
    private static long clientTick;
    private static long sceneGeneration;
    private static boolean netLogArmed;

    /**
     * Retiring is asset GC: delaying it only holds memory, so run it rarely instead of every tick.
     */
    static final long RETIRE_INTERVAL_MS = 1000;

    private static double lastRetireMs = Double.NEGATIVE_INFINITY;

    /** Room for the receipt header inside one serverbound custom payload (32767 bytes). */
    static final int MAX_TICKET_REPORT_BYTES = 32767 - 256;

    // --- ADR4 presentation state (main thread) ---
    private static ClientAnimationJournal journal =
            new ClientAnimationJournal(ClientCombatScene::paired, NativePayloads::deliver);
    private static PlayerSnapshotS2CPacket exactBatch;
    private static long frameId, revision;
    private static long firstDecodedH = -1;
    private static volatile Map<String, Object> lastDecodedSnapshot = Map.of();

    static void decodedSnapshot(
            com.lunares.blockfield.common.combat.AnimationWire.Header header,
            PlayerSnapshotS2CPacket packet) {
        if (!com.lunares.blockfield.common.combat.ShotDebug.enabled()) return;
        lastDecodedSnapshot =
                Map.of(
                        "sequence",
                        header.logicalSequence(),
                        "H",
                        header.logicalTick(),
                        "stream",
                        header.observerStreamEpoch(),
                        "scene",
                        header.sceneGeneration(),
                        "connection",
                        header.connectionId(),
                        "decodeNs",
                        System.nanoTime(),
                        "rows",
                        packet.animation().records().stream()
                                .map(row -> row.identity().entityId())
                                .toList());
    }

    private static ClientAnimationJournal.Stamp stamp;
    private static boolean frameActive;
    private static final Map<Integer, String> unavailable = new LinkedHashMap<>();
    private static String reportUnavailable = "";

    private ClientCombatScene() {}

    public static double nowMs() {
        return System.nanoTime() / 1_000_000.0;
    }

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(
                PlayerSnapshotS2CPacket.TYPE, (packet, context) -> accept(packet, nowMs()));
        ClientPlayNetworking.registerGlobalReceiver(
                AnimationEventS2CPacket.TYPE,
                (packet, context) -> capsules(packet, System.nanoTime()));
        ClientPlayNetworking.registerGlobalReceiver(
                AnimationProbeS2CPacket.TYPE,
                (packet, context) -> {
                    probe(packet, System.nanoTime());
                    ClientPlayNetworking.send(new AnimationProbeC2SPacket(packet.id()));
                });
        ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) -> resetConnection(client, true));
        ClientPlayConnectionEvents.JOIN.register(
                (handler, sender, client) -> resetConnection(client));
        ClientTickEvents.END_CLIENT_TICK.register(client -> clientTick(nowMs()));
        CombatChannels.install();
    }

    public static void accept(PlayerSnapshotS2CPacket packet, double nowMs) {
        buffer.apply(
                packet,
                ClientCombatScene::nativePresent,
                nowMs,
                applied -> {
                    if (clockEpoch != buffer.epoch()) {
                        clockEpoch = buffer.epoch();
                        firstDecodedH = applied.serverTick();
                        // A restarted stream rewarms R; A keeps its connection high-water.
                        clock = clock.restarted();
                        receipt = null;
                    }
                    clock.arrival(applied, nowMs);
                    sendDeadlineMs = clock.deadlineMs();
                    exactBatch = applied;
                    long receivedNs = Math.round(nowMs * 1_000_000);
                    for (var seed : applied.animation().seeds()) journal.seed(seed, receivedNs);
                });
        recover(nowMs);
    }

    /** One native logical delivery of capsules, applied in this main-thread task. */
    public static void capsules(AnimationEventS2CPacket packet, long nowNs) {
        parcoolBatch(packet);
        boolean applied = false;
        for (var event : packet.events()) {
            buffer.installResources(event.dependencies());
            applied |= journal.capsule(event, nowNs);
        }
        if (applied && frameActive) revise(nowNs);
    }

    /**
     * ADR4 §2.2: a delivery may carry ParCool's native all-player batch (the server never also
     * sends it unwrapped to this client). Its native side effects run once, here, exactly as bf7's
     * {@code ActionStateBroadcastPayload.handleClient} task would: every entry of every non-local
     * player in order. Driven remotes take their geometry from the capsules; their native mirror
     * cannot select an animator ({@code ParCoolAnimationMixin}).
     */
    static void parcoolBatch(AnimationEventS2CPacket packet) {
        for (var event : packet.events()) {
            var type = ClientAnimationJournal.payloadType(event);
            var bytes = event.nativePayload().value().value();
            if (type == null
                    || bytes.length == 0
                    || !java.util.Arrays.equals(type, ClientAnimationJournal.PARCOOL_BATCH))
                continue;
            var buf = io.netty.buffer.Unpooled.wrappedBuffer(bytes);
            com.alrex.parcool.common.network.payload.ActionStateBroadcastPayload batch;
            try {
                batch =
                        com.alrex.parcool.common.network.payload.ActionStateBroadcastPayload.CODEC
                                .decode(buf);
            } catch (RuntimeException bad) {
                // Server bytes are untrusted: a truncated batch must not crash the client.
                wireDefect(event.identity().observerStreamEpoch(), "parcool_batch_decode");
                return;
            } finally {
                buf.release();
            }
            var level = Minecraft.getInstance().level;
            if (level == null) return;
            for (var state : batch.payloads()) {
                var player = level.getPlayerByUUID(state.playerID());
                if (player == null || player.isLocalPlayer()) continue;
                state.processPlayer(player);
            }
            return;
        }
    }

    /** One timing probe; the caller echoes its nonce on the ordered gameplay channel. */
    public static void probe(AnimationProbeS2CPacket packet, long nowNs) {
        journal.probe(
                packet.id(),
                packet.serverSendNs(),
                packet.boundaryTick(),
                packet.boundaryNs(),
                nowNs);
    }

    /** Ordered main-thread intake for a wire transaction that could not be decoded atomically. */
    public static void wireDefect(long stream, String detail) {
        buffer.wireDefect(stream, detail);
        recover(nowMs());
    }

    private static void recover(double nowMs) {
        buffer.expireHeld(nowMs);
        if (!buffer.broken()) return;
        receipt = null;
        DrivenPresentation.clearAll();
        sendDeadlineMs = Double.NEGATIVE_INFINITY;
        var request = buffer.restartRequest(nowMs);
        if (request != null) ClientPlayNetworking.send(request);
    }

    static void resetConnection(Minecraft client) {
        resetConnection(client, false);
    }

    /**
     * JOIN and DISCONNECT reset the scene; only DISCONNECT also drops the activated resource
     * generations, which belong to that connection (U9 may install them before JOIN).
     */
    static void resetConnection(Minecraft client, boolean disconnected) {
        Runnable reset =
                () -> {
                    disconnect();
                    if (disconnected) ClientAnimationAssets.clear();
                };
        // execute queues reentrant main-thread tasks; JOIN must reset before queued payloads.
        if (client.isSameThread()) reset.run();
        else client.execute(reset);
    }

    private static boolean paired(
            com.lunares.blockfield.common.combat.AnimationState.Identity identity) {
        var entity = nativeEntities.get(identity.entityId());
        var level = Minecraft.getInstance().level;
        return entity != null
                && level != null
                && level.getEntity(identity.entityId()) == entity
                && buffer.paired(identity);
    }

    private static boolean nativePresent(int id) {
        var level = Minecraft.getInstance().level;
        return level != null && level.getEntity(id) != null;
    }

    public static int kind(Entity entity) {
        if (entity instanceof RemotePlayer) return 0;
        if (entity instanceof Boat || entity instanceof AbstractMinecart) {
            // Only Mojang concrete classes were audited; mod overrides retain native visuals.
            return SIMPLE_VEHICLES.contains(entity.getClass()) ? 1 : -1;
        }
        if (entity instanceof SnapshotDrivenEntity
                && entity.getClass()
                        .getName()
                        .startsWith("com.atsuishio.superbwarfare.entity.vehicle."))
            return CombatVehicleAudit.supported(entity.getClass()) ? 2 : -1;
        return -1;
    }

    public static void add(Entity entity) {
        add(entity, false);
    }

    private static void add(Entity entity, boolean surviving) {
        int kind = kind(entity);
        if (kind < 0) return;
        nativeEntities.put(entity.getId(), entity);
        buffer.add(entity.getId(), kind, surviving);
        seats.put(entity.getId(), seat(entity));
    }

    /** Also reached from ClientLevel.addEntity, which first removes its id even when absent. */
    public static void remove(int id) {
        com.lunares.blockfield.client.render.BodyPartHitboxRenderer.forget(id);
        var removed = nativeEntities.remove(id);
        if (removed == null) return;
        DrivenPresentation.forget(removed);
        CombatVehicleScope.clear(id);
        if (!buffer.hasUnclaimedRun(id)) journal.forget(id);
        seats.remove(id);
        buffer.remove(id);
        // The already-rendered receipt still names its old prefix; sends remain victim-independent.
    }

    public static void linkageChanged() {
        nativeEntities.forEach(
                (id, entity) -> {
                    // Unseated remotes are the common case: their seat stays "" without
                    // per-packet UUID/index/concat work. Seating transitions still fall
                    // through to the exact compare below.
                    if (entity.getVehicle() == null && "".equals(seats.get(id))) return;
                    refreshSeat(id, entity);
                });
    }

    /**
     * Refresh after one passengers packet. Vanilla drops packets for unknown vehicles before
     * touching any seating (the post-respawn "Received passengers for unknown entity" bursts), so
     * an unknown vehicle means no tracked seat could have changed: skip the traversal.
     */
    public static void linkageChanged(int vehicleId) {
        var level = Minecraft.getInstance().level;
        if (level == null || level.getEntity(vehicleId) == null) return;
        linkageChanged();
    }

    private static void refreshSeat(int id, Entity entity) {
        String current = seat(entity);
        if (!current.equals(seats.put(id, current))) buffer.seatChanged(id);
    }

    private static String seat(Entity entity) {
        Entity vehicle = entity.getVehicle();
        return vehicle == null
                ? ""
                : vehicle.getUUID() + ":" + vehicle.getPassengers().indexOf(entity);
    }

    /**
     * Never the local player's own ride stack: positionRider places the local passenger and its
     * camera at tick time, so a frame-sampled vehicle or co-passenger would judder against it.
     * Other mounted remote players keep their own final write.
     */
    public static boolean eligible(Entity entity) {
        LocalPlayer local = Minecraft.getInstance().player;
        return !entity.isSpectator()
                && (local == null || entity.getRootVehicle() != local.getRootVehicle())
                && (entity instanceof RemotePlayer || !entity.isControlledByLocalInstance());
    }

    public static boolean driven(Entity entity) {
        return receipt != null
                && !buffer.broken()
                && !clock.timedOut(nowMs())
                && nativeEntities.get(entity.getId()) == entity
                && eligible(entity)
                && receipt.entities().containsKey(entity.getId())
                && buffer.hasLiveSamples(entity.getId());
    }

    public static void mainFrame(double nowMs) {
        frameEntry((long) (nowMs * 1_000_000));
    }

    /**
     * Main-frame entry, once per rendered frame: R and A advance and freeze, the placement of every
     * driven entity is written at R, and on the exact stream the journal and driven presentations
     * form this frame's first revision.
     */
    public static void frameEntry(long nowNs) {
        double nowMs = nowNs / 1_000_000.0;
        recover(nowMs);
        // Speed-capped traversal (remote-snap U2): the clock limits this frame's R advance by the
        // largest eligible non-teleport step any driven remote draws through. Rendered stays
        // pose(R), so hit tickets stay exact. Per-frame cache: the clock queries one tick per
        // crossed server tick, each fanning out over the driven remotes.
        java.util.Map<Long, Double> stepCache = new java.util.HashMap<>();
        clock.noteSteps(
                tick -> stepCache.computeIfAbsent(tick, ClientCombatScene::maxTraverseStep));
        if (buffer.broken() || !clock.frameNs(nowNs)) {
            receipt = null;
            stamp = null;
            DrivenPresentation.clearAll();
            return;
        }
        double time = clock.time();
        Time r = clock.r();
        frameId++;
        revision++;
        stamp =
                clock.animationReady()
                        ? new ClientAnimationJournal.Stamp(
                                frameId, revision, r, clock.a(), buffer.high(), nowNs)
                        : null;
        var samples = new LinkedHashMap<Integer, SnapshotResolver.Sample>();
        nativeEntities.forEach(
                (id, entity) -> {
                    var sample =
                            !eligible(entity) || !buffer.hasLiveSamples(id)
                                    ? null
                                    : SnapshotResolver.exact(buffer.frames(id), r);
                    if (sample != null) {
                        write(entity, sample);
                        samples.put(id, sample);
                        if (sample.before().kind() > 0)
                            CombatVehicleScope.install(id, vehicle(sample));
                        if (entity instanceof RemotePlayer player)
                            DrivenPresentation.placement(
                                    player, sample.before(), (float) sample.fraction());
                    } else {
                        DrivenPresentation.release(entity);
                        CombatVehicleScope.clear(id);
                    }
                });
        present(samples, nowNs);
        frameActive = true;
    }

    /** The frozen R frame of a driven vehicle (U6 scope) from its lossless record. */
    private static CombatVehicleScope.VehicleFrame vehicle(SnapshotResolver.Sample sample) {
        var placement = sample.before().placement();
        var a = placement.angles();
        var v = placement.vehicleAngles();
        var old = placement.previousOrigin();
        var now = placement.origin();
        return new CombatVehicleScope.VehicleFrame(
                new net.minecraft.world.phys.Vec3(old.x(), old.y(), old.z()),
                new net.minecraft.world.phys.Vec3(now.x(), now.y(), now.z()),
                a.yRotO(),
                a.yRot(),
                a.xRotO(),
                a.xRot(),
                v.prevRoll(),
                v.roll(),
                v.turretYRotO(),
                v.turretYRot(),
                v.turretXRotO(),
                v.turretXRot(),
                v.gunYRotO(),
                v.gunYRot(),
                v.gunXRotO(),
                v.gunXRot(),
                (float) sample.fraction(),
                revision);
    }

    /** End of the main render call: later applications wait for the next frame entry. */
    public static void frameEnd() {
        ClientShotDebug.frameEnd();
        frameActive = false;
    }

    /** A capsule applied during an active frame: same clocks, a new immutable revision. */
    private static void revise(long nowNs) {
        if (stamp == null || receipt == null) return;
        revision++;
        stamp =
                new ClientAnimationJournal.Stamp(
                        stamp.frameId(), revision, stamp.r(), stamp.a(), stamp.h(), nowNs);
        present(receipt.entities(), nowNs);
    }

    /**
     * One presentation revision on the exact stream: journal step, driven animation install and the
     * frozen receipt with its complete victim report.
     */
    private static void present(Map<Integer, SnapshotResolver.Sample> samples, long nowNs) {
        var r = clock.r();
        if (stamp != null) {
            journal.frame(stamp, buffer::frames);
            unavailable.clear();
            var a = stamp.a();
            samples.forEach(
                    (id, sample) -> {
                        if (!(nativeEntities.get(id) instanceof RemotePlayer player)) return;
                        String why = animate(player, id, sample, a, r);
                        if (why != null) unavailable.put(id, why);
                    });
        } else {
            // A is not live (warm-up/rewarm): animation goes native, locomotion stays at R.
            DrivenPresentation.clearAnimations();
        }
        var exact = exactReport(r, samples);
        // The applications go out before any ticket can name this revision.
        reportApplications(r, exact);
        receipt =
                new CombatRenderReceipt(
                        buffer.epoch(), buffer.high(), r.tick(), r.alpha(), samples, exact);
    }

    private static String animate(
            RemotePlayer player, int id, SnapshotResolver.Sample sample, Time a, Time r) {
        var reduced = journal.presentation(id);
        if (reduced == null) {
            DrivenPresentation.clear(player);
            return journal.unavailable(id);
        }
        var frames = buffer.frames(id);
        var current = frames.lastEntry();
        if (current == null) {
            DrivenPresentation.clear(player);
            return "state_unavailable";
        }
        var resolved = journal.resolve(current.getValue(), stamp, frames, buffer.resources());
        if (!resolved.exact()) {
            DrivenPresentation.clear(player);
            return resolved.reason();
        }
        var recipe = resolved.recipe();
        var placementRecord = frames.get(recipe.placementTick());
        var animationRecord = frames.get(recipe.animationTick());
        // Exact rendering uses the common resolver's endpoints and generation decision.
        var selected =
                SnapshotResolver.exact(placementRecord, recipe.placementPartial(), sample.mode());
        write(player, selected);
        var assets = ClientAnimationAssets.get(recipe.identity());
        return DrivenPresentation.install(
                player,
                placementRecord,
                recipe.placementPartial(),
                animationRecord,
                recipe.animationPartial(),
                reduced,
                assets,
                a,
                r.fraction());
    }

    /** The complete victim report of this revision: every player of the H batch, or none. */
    private static CombatRenderReceipt.Exact exactReport(
            Time r, Map<Integer, SnapshotResolver.Sample> samples) {
        var batch = exactBatch;
        if (stamp == null || batch == null || batch.serverTick() != buffer.high()) {
            reportUnavailable = stamp == null ? "clock_unavailable" : "batch_unavailable";
            return new CombatRenderReceipt.Exact(
                    buffer.connectionId(),
                    buffer.sceneGeneration(),
                    frameId,
                    revision,
                    stamp == null ? r : stamp.a(),
                    0,
                    0,
                    AnimationSchema.layoutDigest(),
                    null,
                    reportUnavailable);
        }
        var rows = new java.util.ArrayList<AnimationStartReport.Victim>();
        String why = null;
        long resource = 0, policy = 0;
        AnimationResources.Digest compatibility = AnimationSchema.layoutDigest();
        for (var record : batch.animation().records()) {
            var identity = record.identity();
            resource = identity.resourceGeneration();
            policy = identity.policyGeneration();
            compatibility = identity.compatibilityDigest();
            if (identity.kind() != 0) continue;
            var entity = nativeEntities.get(identity.entityId());
            String failed = unavailable.get(identity.entityId());
            if (failed != null
                    || entity == null
                    || !eligible(entity)
                    || !samples.containsKey(identity.entityId())
                    || !DrivenPresentation.driven(entity)) {
                why = identity.entityId() + ":" + (failed != null ? failed : "not_driven");
                break;
            }
            ClientAnimationJournal.Row row;
            try {
                row =
                        journal.row(
                                SnapshotTransform.exact(
                                        batch.serverTick(),
                                        identity,
                                        record.flags(),
                                        record.placement(),
                                        record.checkpoint()),
                                stamp);
            } catch (RuntimeException refused) {
                // A frame never fails on a report: the revision just has no exact ticket.
                row = new ClientAnimationJournal.Row(null, "row:" + refused.getMessage());
            }
            if (row.victim() == null) {
                why = identity.entityId() + ":" + row.unavailable();
                break;
            }
            rows.add(row.victim());
        }
        AnimationStartReport report = null;
        if (why == null)
            try {
                report = new AnimationStartReport(rows);
                var probe = report;
                int bytes =
                        AnimationSchema.encodeBounded(b -> AnimationStartReport.write(b, probe))
                                .length;
                // Serverbound custom payloads stop at 32767 bytes; never truncate victims.
                if (bytes > MAX_TICKET_REPORT_BYTES) {
                    why = "report_capacity:" + bytes;
                    report = null;
                }
            } catch (IllegalArgumentException capacity) {
                why = "report_capacity:" + capacity.getMessage();
                report = null;
            }
        reportUnavailable = why == null ? "" : why;
        return new CombatRenderReceipt.Exact(
                buffer.connectionId(),
                buffer.sceneGeneration(),
                frameId,
                revision,
                stamp.a(),
                resource,
                policy,
                compatibility,
                report,
                why);
    }

    /**
     * ADR4 §3.4 AnimationApplied: the applications this revision stamped, sent on the ordered
     * gameplay channel before any attack that uses the revision.
     */
    private static void reportApplications(Time r, CombatRenderReceipt.Exact report) {
        var drained = journal.drainUnreported();
        if (drained.isEmpty() || stamp == null) return;
        var batch = new LinkedHashMap<Integer, ClientAnimationJournal.Report>();
        for (var applied : drained) {
            int id = applied.identity().entityId();
            if (batch.containsKey(id)) {
                reportApplications(r, report, batch);
                batch.clear();
            }
            batch.put(id, applied);
        }
        reportApplications(r, report, batch);
    }

    private static void reportApplications(
            Time r,
            CombatRenderReceipt.Exact report,
            Map<Integer, ClientAnimationJournal.Report> drained) {
        var rows = new java.util.ArrayList<AnimationStartReport.Victim>();
        var zero = new AnimationResources.Digest(0, 0, 0, 0);
        drained.forEach(
                (id, applied) -> {
                    var identity = applied.identity();
                    long prefix = applied.activation() == null ? 0 : applied.seedRef();
                    for (var application : applied.applications())
                        prefix = Math.max(prefix, application.eventSequence());
                    rows.add(
                            new AnimationStartReport.Victim(
                                    id,
                                    identity.targetEpoch(),
                                    identity.seatGeneration(),
                                    identity.teleportGeneration(),
                                    identity.subscriptionGeneration(),
                                    applied.seedRef(),
                                    prefix,
                                    zero,
                                    applied.activation() == null
                                            ? List.of()
                                            : List.of(applied.activation()),
                                    applied.applications(),
                                    zero));
                });
        if (rows.isEmpty()) return;
        rows.sort(
                java.util.Comparator.comparingInt(AnimationStartReport.Victim::entityId)
                        .thenComparingLong(AnimationStartReport.Victim::targetEpoch));
        var view =
                new CombatView(
                        sequence.get(),
                        buffer.epoch(),
                        buffer.high(),
                        r.tick(),
                        r.alpha(),
                        new CombatView.Receipt(
                                buffer.connectionId(),
                                buffer.sceneGeneration(),
                                frameId,
                                revision,
                                stamp.a(),
                                report.resourceGeneration(),
                                report.policyGeneration(),
                                report.compatibilityDigest(),
                                new AnimationStartReport(rows)));
        try {
            var packet = new AnimationAppliedC2SPacket(view);
            var connection = Minecraft.getInstance().getConnection();
            if (connection != null)
                ((CombatViewSender) connection)
                        .blockfield$combatSender()
                        .sendApplied(packet, connection::send);
        } catch (RuntimeException refused) {
            // Unreportable applications only make later rows unavailable server-side.
            LogUtils.getLogger().debug("AnimationApplied report refused", refused);
        }
    }

    /**
     * Max eligible horizontal step (m) at one server tick over all driven remotes, NaN when none:
     * the clock's per-frame input for speed-capped traversal. Passengers ride their mount, so their
     * own records never cap; real teleports report NaN in the resolver and stay hard snaps. A run
     * of more than {@link CombatSceneClock#STEP_CAPPED_RUN} consecutive above-cap ticks is
     * sustained fast motion, not a stall step: that entity is exempt for the tick, so one fast
     * remote never slows the shared clock for everyone else.
     */
    static double maxTraverseStep(long tick) {
        double max = Double.NaN;
        for (var entry : nativeEntities.entrySet()) {
            var frames = buffer.frames(entry.getKey());
            if (frames.isEmpty()) continue;
            var entity = entry.getValue();
            if (entity.getVehicle() != null || !eligible(entity)) continue;
            double step = SnapshotResolver.traverseStep(frames, tick);
            if (Double.isNaN(step)) continue;
            if (step > CombatSceneClock.STEP_CAP_M_PER_TICK && sustainedRun(frames, tick)) continue;
            if (Double.isNaN(max) || step > max) max = step;
        }
        return max;
    }

    /** Above-cap steps on the two previous ticks of the same run: sustained motion, not a step. */
    static boolean sustainedRun(java.util.NavigableMap<Long, SnapshotTransform> frames, long tick) {
        return SnapshotResolver.traverseStep(frames, tick - 1)
                        > CombatSceneClock.STEP_CAP_M_PER_TICK
                && SnapshotResolver.traverseStep(frames, tick - 2)
                        > CombatSceneClock.STEP_CAP_M_PER_TICK;
    }

    public static void write(Entity entity, SnapshotResolver.Sample sample) {
        if (entity instanceof SnapshotDrivenEntity adapter) {
            adapter.blockfield$clearInterpolation();
            adapter.blockfield$clearMotionInterpolation();
        }
        entity.setPos(sample.x(), sample.y(), sample.z());
        entity.setYRot(sample.angles().get(0).floatValue());
        entity.setXRot(sample.angles().get(1).floatValue());
        entity.setOldPosAndRot();
        if (entity instanceof LivingEntity living && sample.before().kind() == 0) {
            living.yBodyRot = sample.angles().get(2).floatValue();
            living.yBodyRotO = living.yBodyRot;
            living.yHeadRot = sample.angles().get(3).floatValue();
            living.yHeadRotO = living.yHeadRot;
        }
        if (entity instanceof SnapshotDrivenEntity adapter) adapter.blockfield$sampleAngles(sample);
    }

    public static void sceneReset() {
        DrivenPresentation.clearAll();
        var level = Minecraft.getInstance().level;
        var survivors = new HashMap<Integer, Entity>();
        if (level != null)
            level.entitiesForRendering()
                    .forEach(
                            entity -> {
                                if (nativeEntities.get(entity.getId()) == entity)
                                    survivors.put(entity.getId(), entity);
                            });
        buffer.sceneReset(survivors::containsKey);
        clock.sceneReset();
        receipt = null;
        nativeEntities.clear();
        seats.clear();
        sendDeadlineMs = Double.NEGATIVE_INFINITY;
        sceneGeneration++;
        if (level != null)
            level.entitiesForRendering()
                    .forEach(entity -> add(entity, survivors.get(entity.getId()) == entity));
    }

    public static void disconnect() {
        DrivenPresentation.clearAll();
        NativePayloads.clear();
        CombatVehicleScope.clearAll();
        journal = new ClientAnimationJournal(ClientCombatScene::paired, NativePayloads::deliver);
        exactBatch = null;
        frameId = revision = 0;
        stamp = null;
        frameActive = false;
        unavailable.clear();
        reportUnavailable = "";
        buffer = new ClientSnapshotBuffer();
        clock = new CombatSceneClock();
        clockEpoch = -1;
        receipt = null;
        sendDeadlineMs = Double.NEGATIVE_INFINITY;
        nativeEntities.clear();
        seats.clear();
        sequence.set(0);
        lastAttackAge = 0;
        netLog.clear();
        clientTick = 0;
        sceneGeneration = 0;
        netLogArmed = false;
        lastRetireMs = Double.NEGATIVE_INFINITY;
    }

    public static CombatRenderReceipt receipt() {
        return receipt;
    }

    static CombatSceneClock clock() {
        return clock;
    }

    /** Sample the rendered frame wrote into a driven entity, or null when it is native. */
    public static SnapshotResolver.Sample drivenSample(Entity entity) {
        var rendered = receipt;
        return rendered != null && driven(entity) ? rendered.entities().get(entity.getId()) : null;
    }

    /**
     * Any thread; strict-greater timeout as in {@link CombatSceneClock#timedOut}. On the exact
     * stream a revision without its complete victim report yields no ticket (labelled fallback).
     */
    private static final ThreadLocal<String> ticketOmission = new ThreadLocal<>();

    public static String ticketOmission() {
        return ticketOmission.get();
    }

    static String ticketOmission(CombatRenderReceipt rendered, double deadlineMs, double nowMs) {
        if (rendered == null) return "no_receipt";
        if (nowMs > deadlineMs) return "deadline";
        if (rendered.exact().report() == null) return "report:" + rendered.exact().unavailable();
        return "";
    }

    public static CombatView nextTicket(double nowMs) {
        var rendered = receipt;
        String omission = ticketOmission(rendered, sendDeadlineMs, nowMs);
        ticketOmission.set(omission);
        // An incomplete report still dates the shot: the server rewinds by retained pose at R.
        if (!omission.isEmpty() && !omission.startsWith("report:")) return null;
        lastAttackAge = rendered.high() - rendered.time();
        return rendered.ticket(sequence.incrementAndGet());
    }

    /** Once per client tick: the last frame's rendered roots plus the servo counters. */
    public static void clientTick(double nowMs) {
        recover(nowMs);
        retireResources(nowMs);
        if (!netLogArmed) return;
        clientTick++;
        var rendered = receipt;
        var positions = new LinkedHashMap<Integer, Object>();
        if (rendered != null)
            rendered.entities()
                    .forEach(
                            (id, sample) -> {
                                var entity = nativeEntities.get(id);
                                if (entity != null)
                                    positions.put(
                                            id,
                                            List.of(entity.getX(), entity.getY(), entity.getZ()));
                            });
        var entry = new LinkedHashMap<String, Object>();
        entry.put("tick", clientTick);
        entry.put("sceneGeneration", sceneGeneration);
        entry.put("wallMs", nowMs);
        entry.put("R", rendered == null ? -1 : rendered.time());
        entry.put("H", rendered == null ? buffer.high() : rendered.high());
        entry.put("delayTicks", clock.delay());
        entry.put("jitterMs", clock.jitter());
        entry.put("occupancy", Double.isFinite(clock.occupancy()) ? clock.occupancy() : -1);
        entry.put("rate", clock.rate());
        entry.put("entitySnaps", buffer.entitySnaps());
        entry.put("recoverySnaps", clock.recoverySnaps());
        entry.put("delayChanges", clock.delayChanges());
        entry.put("positions", positions);
        netLog.addLast(entry);
        while (netLog.size() > NET_LOG_CAP) netLog.removeFirst();
    }

    /** All resource owners are main-thread confined; an off-thread shot reads only the receipt. */
    static void retireResources(double nowMs) {
        // GC cadence: keeping a generation one extra second is harmless, retiring one in use is
        // not.
        if (nowMs - lastRetireMs < RETIRE_INTERVAL_MS) return;
        lastRetireMs = nowMs;
        var refs = new java.util.HashSet<AnimationResources.ResourceRef>();
        var generations = new java.util.HashSet<Long>();
        buffer.references(refs, generations);
        journal.references(refs, generations);
        ClientAnimationResources.retainGenerations(generations);
        generations.addAll(
                com.lunares.blockfield.client.render.BodyPartHitboxRenderer.resourceGenerations());
        var rendered = receipt;
        if (rendered != null && nowMs <= sendDeadlineMs) {
            generations.add(rendered.exact().resourceGeneration());
            for (var sample : rendered.entities().values()) {
                ClientAnimationAssets.references(sample.before().checkpoint(), refs);
                ClientAnimationAssets.references(sample.after().checkpoint(), refs);
            }
        }
        for (var ref : refs) generations.add(ref.resourceGeneration());
        ClientAnimationAssets.retireExcept(generations);
        buffer.retireResources(refs);
    }

    /** Entries newer than sinceTick, oldest first, in the debugSnapshot map/list style. */
    public static List<Map<String, Object>> debugLog(long sinceTick) {
        netLogArmed = true;
        var out = new ArrayList<Map<String, Object>>();
        for (var entry : netLog) if ((long) entry.get("tick") > sinceTick) out.add(entry);
        return out;
    }

    /** Stable public oracle surface, also used by the F3 debug line. */
    public static Map<String, Object> debugSnapshot() {
        var out = new LinkedHashMap<String, Object>();
        out.put("driven", receipt != null);
        out.put("sceneGeneration", sceneGeneration);
        out.put("H", receipt == null ? buffer.high() : receipt.high());
        out.put("receivedHigh", buffer.high());
        out.put("firstDecodedH", firstDecodedH);
        out.put("lastDecodedSnapshot", lastDecodedSnapshot);
        out.put(
                "decodedRows",
                exactBatch == null
                        ? List.of()
                        : exactBatch.animation().records().stream()
                                .map(record -> record.identity().entityId())
                                .toList());
        out.put("R", receipt == null ? -1 : receipt.time());
        out.put("stream", buffer.epoch());
        out.put("sequence", sequence.get());
        out.put("delayTicks", clock.delay());
        out.put("delayMs", clock.delay() * 50);
        out.put("jitterMs", clock.jitter());
        out.put("occupancy", Double.isFinite(clock.occupancy()) ? clock.occupancy() : -1);
        out.put("rate", clock.rate());
        out.put("entitySnaps", buffer.entitySnaps());
        out.put("recoverySnaps", clock.recoverySnaps());
        out.put("protocolDefects", buffer.defects());
        out.put("protocolDefectReason", buffer.defectReason());
        out.put("kindMismatches", buffer.kindMismatches());
        out.put("expiredPending", buffer.expiredPending());
        out.put("lastAttackAge", lastAttackAge);
        out.put("delayChanges", clock.delayChanges());
        out.put("exactStream", true);
        out.put("A", receipt == null ? -1 : time(receipt.exact().a()));
        out.put("animationReason", clock.animationReason().name());
        out.put("animationClock", clock.animationCounts().toString());
        out.put("frameId", frameId);
        out.put("revision", revision);
        out.put("reportUnavailable", reportUnavailable);
        out.put("presentationUnavailable", new LinkedHashMap<>(unavailable));
        out.put("journal", journal.counters());
        out.put("pairing", buffer.debugPairing());
        out.put("journalLives", journal.debugLives(stamp));
        var entities = new LinkedHashMap<Integer, Object>();
        if (receipt != null)
            receipt.entities()
                    .forEach(
                            (id, s) ->
                                    entities.put(
                                            id,
                                            Map.of(
                                                    "mode",
                                                    s.mode(),
                                                    "beforeTick",
                                                    s.before().tick(),
                                                    "afterTick",
                                                    s.after().tick(),
                                                    "fraction",
                                                    s.fraction(),
                                                    "epoch",
                                                    s.before().epoch(),
                                                    "seatGeneration",
                                                    s.before().seat(),
                                                    "teleportGeneration",
                                                    s.before().teleport(),
                                                    "gap",
                                                    s.after().tick() - s.before().tick())));
        out.put("entities", entities);
        return out;
    }

    private static double time(Time value) {
        return value.tick() + value.alpha() / 65536.0;
    }

    public static String debugLine() {
        return String.format(
                java.util.Locale.ROOT,
                "BF net R %.3f H %d delay %dt jitter %.1fms occ %.2f rate %.3f hold/snap %d/%d",
                receipt == null ? -1 : receipt.time(),
                buffer.high(),
                clock.delay(),
                clock.jitter(),
                clock.occupancy(),
                clock.rate(),
                receipt == null
                        ? 0
                        : receipt.entities().values().stream()
                                .filter(s -> s.mode().equals("hold"))
                                .count(),
                clock.recoverySnaps());
    }

    /**
     * A definition the applied exact stream retains, or null (FX-POSE: the driven SBW seat check
     * resolves the checkpoint's root seat transform name here). Read-only.
     */
    public static AnimationResources.Definition retainedDefinition(
            AnimationResources.ResourceRef ref) {
        try {
            return buffer.resources().get(ref);
        } catch (IllegalArgumentException missing) {
            return null;
        }
    }
}
