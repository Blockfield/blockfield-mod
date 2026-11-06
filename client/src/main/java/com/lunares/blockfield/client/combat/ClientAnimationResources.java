package com.lunares.blockfield.client.combat;

import com.lunares.blockfield.client.animation.RemoteLrAssets;
import com.lunares.blockfield.client.animation.RemoteTaczAssets;
import com.lunares.blockfield.common.combat.AnimationManifest;
import com.lunares.blockfield.common.combat.AnimationRefTable;
import com.lunares.blockfield.common.combat.AnimationResources.Digest;
import com.lunares.blockfield.common.combat.LrAssets;
import com.lunares.blockfield.common.combat.PaGraph;
import com.lunares.blockfield.common.combat.TaczAssets;
import com.lunares.blockfield.network.packet.AnimationManifestS2CPacket;
import com.lunares.blockfield.network.packet.AnimationReadyC2SPacket;
import com.tacz.guns.client.resource.ClientAssetsManager;
import com.tacz.guns.client.resource.pojo.display.gun.GunDisplay;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ADR4 §6 client side of READY (U9): the effective resource resolver and the reload fence.
 *
 * <p>For the server's manifest the client builds the generation's TaCZ/LR views from its own native
 * effective lookup (the resource manager with every user pack, as TaCZ and LR loaded it), answers
 * its value for every entry, and installs the views as {@link ClientAnimationAssets} and arms the
 * server's {@link AnimationPolicy} only when all of them match. Nothing drives a remote from a
 * generation before that. A later resource reload (F3+T, pack screen) never replaces an installed
 * view: the client revalidates its live resources against the same manifest and disconnects itself
 * when they no longer match. Main-thread owned.
 */
public final class ClientAnimationResources {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClientAnimationResources.class);

    /** The server-built manifest a staged view must reproduce, and the reply sink. */
    private record Request(
            AnimationManifest manifest,
            Consumer<AnimationReadyC2SPacket> reply,
            AnimationWireReceiver wire) {}

    /**
     * A staged generation: its views, the answer for every manifest entry and the ref table those
     * views derive (ADR4.2 §5; the wire stage registers it before READY is sent).
     */
    public record View(
            ClientAnimationAssets.Generation generation,
            List<Digest> values,
            AnimationRefTable table) {}

    private static Request deferred;
    private static AnimationWireReceiver validatedWire;
    private static AnimationManifest validated;
    private static volatile boolean reloaded;
    private static SortedMap<String, Digest> compatibility;

    private ClientAnimationResources() {}

    public static void register() {
        com.lunares.blockfield.network.GameNetworkHandler.registerConfiguration();
        AnimationWireReceiver.register();
        ClientConfigurationNetworking.registerGlobalReceiver(
                AnimationManifestS2CPacket.TYPE,
                (payload, context) ->
                        receive(
                                payload.manifest(),
                                p -> context.responseSender().sendPacket(p),
                                AnimationWireReceiver.of(
                                        ((AnimationWireReceiver.ListenerAccess)
                                                        context.networkHandler())
                                                .blockfield$wireConnection())));
        ClientPlayNetworking.registerGlobalReceiver(
                AnimationManifestS2CPacket.TYPE,
                (payload, context) ->
                        receive(
                                payload.manifest(),
                                p -> context.responseSender().sendPacket(p),
                                AnimationWireReceiver.of(
                                        context.player().connection.getConnection())));
        // Every configuration phase (first join or a proxy backend switch) starts with nothing
        // armed or installed: only this backend's READY can arm it.
        ClientConfigurationConnectionEvents.INIT.register(
                (handler, client) -> {
                    forget();
                    ClientAnimationAssets.clear();
                    AnimationWireReceiver.of(
                                    ((AnimationWireReceiver.ListenerAccess) handler)
                                            .blockfield$wireConnection())
                            .configuration();
                });
        ClientConfigurationConnectionEvents.DISCONNECT.register((handler, client) -> forget());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> forget());
        ClientTickEvents.END_CLIENT_TICK.register(ClientAnimationResources::tick);
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES)
                .registerReloadListener(
                        new SimpleSynchronousResourceReloadListener() {
                            @Override
                            public ResourceLocation getFabricId() {
                                return ResourceLocation.fromNamespaceAndPath(
                                        "blockfield", "animation_reload_fence");
                            }

                            @Override
                            public void onResourceManagerReload(ResourceManager manager) {
                                reloaded = true;
                            }
                        });
    }

    /**
     * Answers a READY request once no resource reload is running (TaCZ/LR fill their managers in
     * the reload); the server's configuration task waits for the answer.
     */
    public static void receive(
            AnimationManifest manifest,
            Consumer<AnimationReadyC2SPacket> reply,
            AnimationWireReceiver wire) {
        var request = new Request(manifest, reply, java.util.Objects.requireNonNull(wire));
        var client = Minecraft.getInstance();
        if (client != null && client.getOverlay() != null) deferred = request;
        else answer(request);
    }

    private static void answer(Request request) {
        var manifest = request.manifest();
        View view;
        try {
            view = build(manifest);
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.warn("Combat animation resources unreadable", failure);
            view = null;
        }
        var values = view == null ? unreadable(manifest) : view.values();
        var mismatches = manifest.mismatches(values);
        if (mismatches.isEmpty()) {
            request.wire().register(view.table());
            ClientAnimationAssets.install(
                    view.generation(), manifest.compatibilityDigest(), manifest.manifestDigest());
            request.wire().retain(ClientAnimationAssets.generations());
            AnimationPolicy.arm(manifest.policy());
            validated = manifest;
            validatedWire = request.wire();
            reloaded = false;
            LOGGER.info(
                    "Combat animation generation {} installed ({} entries)",
                    manifest.resourceGeneration(),
                    values.size());
        } else
            LOGGER.warn(
                    "Combat animation generation {} differs from the server: {}",
                    manifest.resourceGeneration(),
                    mismatches);
        // The server decides on the same values and refuses JOIN on any mismatch.
        request.reply()
                .accept(
                        new AnimationReadyC2SPacket(
                                manifest.resourceGeneration(),
                                manifest.policy().generation(),
                                values));
    }

    private static List<Digest> unreadable(AnimationManifest manifest) {
        var values = new ArrayList<Digest>();
        for (int i = 0; i < manifest.entries().size(); i++) values.add(null);
        return values;
    }

    /**
     * Stages the generation's views from the native effective managers: every display the manifest
     * names, under the server's generation number so equal content yields equal refs.
     */
    public static View build(AnimationManifest manifest) {
        var catalog = new PaGraph.Catalog(manifest.resourceGeneration());
        // The client never resolves a gun to a display: checkpoints name the server's choice.
        var tacz = new TaczAssets(catalog, Map.of());
        var lr = new LrAssets(catalog);
        Map<ResourceLocation, GunDisplay> guns = new HashMap<>();
        for (var entry : ClientAssetsManager.INSTANCE.getGunDisplays())
            guns.put(entry.getKey(), entry.getValue());
        Map<?, ?> melee = meleeDisplays();
        var compat = compatibility();
        var values = new HashMap<String, Digest>();
        for (String key : manifest.entries().keySet()) {
            try {
                if (key.startsWith(AnimationManifest.COMPAT)) values.put(key, compat.get(key));
                else if (key.startsWith(AnimationManifest.TACZ_DISPLAY)) {
                    String id = key.substring(AnimationManifest.TACZ_DISPLAY.length());
                    var pojo = guns.get(ResourceLocation.parse(id));
                    if (pojo != null)
                        values.put(
                                key,
                                RemoteTaczAssets.retain(tacz, id, pojo).ref().dependencyDigest());
                } else if (key.startsWith(AnimationManifest.TACZ_GUN)) {
                    // Server-only gun index (see AnimationManifest): the list enters the table.
                    values.put(key, manifest.entries().get(key));
                } else if (key.startsWith(AnimationManifest.LR_DISPLAY)) {
                    String id = key.substring(AnimationManifest.LR_DISPLAY.length());
                    Object display = melee.get(ResourceLocation.parse(id));
                    if (display != null)
                        values.put(
                                key,
                                RemoteLrAssets.retain(lr, id, display).ref().dependencyDigest());
                }
            } catch (RuntimeException refused) {
                // One unusable display is a named mismatch, never a guessed value.
                LOGGER.warn("Combat animation entry {} unavailable: {}", key, refused.toString());
            }
        }
        if (manifest.entries().containsKey(AnimationManifest.LR_ASSETS))
            values.put(
                    AnimationManifest.LR_ASSETS,
                    lr.manifest(AnimationManifest.rotationWinner(AnimationManifest.loadedMods()))
                            .dependencyDigest());
        AnimationRefTable table = null;
        if (manifest.entries().containsKey(AnimationManifest.WIRE_REF_TABLE)) {
            table =
                    AnimationRefTable.derive(
                            manifest.resourceGeneration(), manifest.entries().keySet(), tacz, lr);
            values.put(AnimationManifest.WIRE_REF_TABLE, table.digest());
        }
        var ordered = new ArrayList<Digest>();
        for (String key : manifest.entries().keySet()) ordered.add(values.get(key));
        return new View(
                new ClientAnimationAssets.Generation(
                        manifest.resourceGeneration(), manifest.policy(), tacz, lr),
                ordered,
                table);
    }

    /**
     * Native LR {@code LrClientAssetsManager.meleeDisplay.getAllData()} (LR is not compiled in).
     */
    private static Map<?, ?> meleeDisplays() {
        try {
            var type = Class.forName("me.xjqsh.lrtactical.client.resource.LrClientAssetsManager");
            Object instance = type.getField("INSTANCE").get(null);
            var field = type.getDeclaredField("meleeDisplay");
            field.setAccessible(true);
            Object manager = field.get(instance);
            return manager == null
                    ? Map.of()
                    : (Map<?, ?>) manager.getClass().getMethod("getAllData").invoke(manager);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Pinned LR display manager changed", failure);
        }
    }

    private static SortedMap<String, Digest> compatibility() {
        if (compatibility == null)
            compatibility =
                    AnimationManifest.compatibility(
                            mod -> {
                                String identity = AnimationManifest.nativeIdentity(mod);
                                LOGGER.info(
                                        "Animation compatibility native {}: {}",
                                        mod,
                                        AnimationManifest.pinStatus(mod, identity));
                                return identity;
                            });
        return compatibility;
    }

    private static void tick(Minecraft client) {
        if (client.getOverlay() != null) return;
        var request = deferred;
        if (request != null) {
            deferred = null;
            answer(request);
        }
        var manifest = validated;
        if (!reloaded || manifest == null) return;
        reloaded = false;
        revalidate(client, manifest);
    }

    /**
     * Reload fence: the installed views are private copies and keep presenting; the live resources
     * must still reproduce the manifest this connection was admitted with.
     */
    private static void revalidate(Minecraft client, AnimationManifest manifest) {
        var mismatches = mismatches(manifest);
        if (mismatches.isEmpty()) return;
        LOGGER.warn("Resource reload changed combat animations: {}", mismatches);
        var connection = client.getConnection();
        if (connection != null)
            connection
                    .getConnection()
                    .disconnect(
                            Component.literal(
                                    "Ресурспак изменил боевые анимации: на сервере так играть"
                                            + " нельзя.\nОтключи его и подключись снова.\nРазличия: "
                                            + String.join(
                                                    ", ",
                                                    mismatches.subList(
                                                            0, Math.min(5, mismatches.size())))));
    }

    /** The entries the live native resources no longer reproduce (the reload fence's test). */
    public static List<String> mismatches(AnimationManifest manifest) {
        try {
            return manifest.mismatches(build(manifest).values());
        } catch (RuntimeException | LinkageError failure) {
            return List.of("unreadable: " + failure);
        }
    }

    static void retainGenerations(java.util.Set<Long> generations) {
        if (validated != null) generations.add(validated.resourceGeneration());
        if (deferred != null) generations.add(deferred.manifest().resourceGeneration());
    }

    static void retainTables(java.util.Set<Long> generations) {
        if (validatedWire != null) validatedWire.retain(generations);
    }

    /** The manifest this connection was admitted with, or null. */
    public static AnimationManifest validated() {
        return validated;
    }

    public static void forget() {
        deferred = null;
        validated = null;
        validatedWire = null;
        AnimationPolicy.disarm();
    }
}
