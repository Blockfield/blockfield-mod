package com.lunares.blockfield.oracle;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.lunares.blockfield.common.combat.AnimationManifest;
import com.lunares.blockfield.common.combat.AnimationPolicy;
import com.lunares.blockfield.common.combat.LrAssets;
import com.lunares.blockfield.common.combat.PaGraph;
import com.lunares.blockfield.common.combat.TaczAssets;
import dev.kosmx.playerAnim.core.data.KeyframeAnimation;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * U9b: the production gun/melee packs and the server's per-file reading of them, executed
 * independently in both production Knot environments. The server oracle proves this reading equals
 * the actual {@code HeldWeaponPose.reload}/{@code LrBodyPose.reload} generation; the client oracle
 * proves LR's and TaCZ's native client loaders reproduce it through the production resolver.
 */
public final class AnimationManifestCases {
    private AnimationManifestCases() {}

    /** TaCZ default pack, Elite x Quality Guns and the LR JAR, lowest priority first. */
    public static List<PackResources> productionPacks() {
        var loader = FabricLoader.getInstance();
        return List.of(
                pack(
                        "tacz_default_gun",
                        loader.getModContainer("tacz")
                                .orElseThrow()
                                .findPath("assets/tacz/custom/tacz_default_gun")
                                .orElseThrow()),
                pack(
                        "elitex_quality_guns",
                        loader.getModContainer("elitex")
                                .orElseThrow()
                                .findPath("assets/elitex/custom/elitex_quality_guns")
                                .orElseThrow()),
                pack(
                        "lrtactical",
                        loader.getModContainer("lrtactical").orElseThrow().getRootPaths().get(0)));
    }

    public static PackResources pack(String id, Path root) {
        return new PathPackResources(
                new PackLocationInfo(
                        id, Component.literal(id), PackSource.BUILT_IN, Optional.empty()),
                root);
    }

    public record Views(TaczAssets tacz, LrAssets lr) {}

    /**
     * The server's per-file reading: {@code TaczAssets.DisplayFile} + the PA GeckoLib file for
     * TaCZ, {@code LrAssets.DisplayFile} + its file for LR, registered as the server registers
     * them. The server lists the CLIENT_RESOURCES of its own pack list, highest priority first.
     */
    public static Views serverViews(List<PackResources> packs, long generation) throws IOException {
        var manager = new MultiPackResourceManager(PackType.CLIENT_RESOURCES, packs);
        var catalog = new PaGraph.Catalog(generation);
        var tacz = new TaczAssets(catalog, Map.of());
        for (var entry : files(manager, "display/guns").entrySet()) {
            var read = TaczAssets.DisplayFile.parse(entry.getValue().getAsJsonObject());
            var keyed =
                    TaczAssets.keyed(animator(manager, ResourceLocation.parse(read.animation())));
            tacz.register(
                    entry.getKey().toString(),
                    read.animation(),
                    read.fixedHand(),
                    read.fallback(),
                    !keyed.isEmpty(),
                    keyed);
        }
        var lr = new LrAssets(catalog);
        for (var entry : files(manager, "display/melee").entrySet()) {
            var read = LrAssets.DisplayFile.parse(entry.getValue().getAsJsonObject());
            Map<String, KeyframeAnimation> clips = Map.of();
            if (read.config() && read.animation() != null)
                clips =
                        new TreeMap<>(
                                TaczAssets.keyed(
                                        animator(
                                                manager,
                                                ResourceLocation.parse(read.animation()))));
            lr.register(
                    entry.getKey().toString(),
                    read.config(),
                    read.animation(),
                    read.upper(),
                    read.lower(),
                    clips);
        }
        return new Views(tacz, lr);
    }

    /** Compatibility of this runtime plus the resource entries of {@link #serverViews}. */
    public static AnimationManifest manifest(
            List<PackResources> packs, long generation, AnimationPolicy policy) throws IOException {
        var views = serverViews(packs, generation);
        var entries =
                new TreeMap<>(AnimationManifest.compatibility(AnimationManifest::nativeIdentity));
        entries.putAll(
                AnimationManifest.resources(
                        views.tacz(),
                        views.lr(),
                        AnimationManifest.rotationWinner(AnimationManifest.loadedMods())));
        return new AnimationManifest(generation, policy, entries);
    }

    /** The PA GeckoLib file the server decoder reads; an unreadable file has no clips. */
    public static List<?> animator(ResourceManager manager, ResourceLocation animation) {
        var file =
                ResourceLocation.fromNamespaceAndPath(
                        animation.getNamespace(),
                        "player_animator/" + animation.getPath() + ".json");
        var resource = manager.getResource(file);
        if (resource.isEmpty()) return List.of();
        try (InputStream in = resource.get().open()) {
            return dev.kosmx.playerAnim.core.data.gson.GeckoLibSerializer.deserialize(
                    JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                            .getAsJsonObject());
        } catch (IOException | RuntimeException unreadable) {
            return List.of();
        }
    }

    /** Effective (top-priority) JSON files of a directory, keyed by the display-id rule. */
    public static TreeMap<ResourceLocation, JsonElement> files(ResourceManager manager, String dir)
            throws IOException {
        var result = new TreeMap<ResourceLocation, JsonElement>();
        for (var entry :
                manager.listResources(dir, id -> id.getPath().endsWith(".json")).entrySet()) {
            var file = entry.getKey();
            String path = file.getPath().substring(dir.length() + 1, file.getPath().length() - 5);
            try (InputStream in = entry.getValue().open()) {
                result.put(
                        ResourceLocation.fromNamespaceAndPath(file.getNamespace(), path),
                        JsonParser.parseString(
                                new String(in.readAllBytes(), StandardCharsets.UTF_8)));
            }
        }
        return result;
    }
}
