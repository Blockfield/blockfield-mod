package com.lunares.blockfield.client.combat.oracle;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.lunares.blockfield.client.combat.AnimationPolicy;
import com.lunares.blockfield.client.combat.ClientAnimationAssets;
import com.lunares.blockfield.client.combat.ClientAnimationResources;
import com.lunares.blockfield.common.combat.AnimationManifest;
import com.lunares.blockfield.common.combat.AnimationResources.Digest;
import com.lunares.blockfield.common.combat.AnimationState;
import com.lunares.blockfield.common.combat.AnimationTypes.PhaseDomain;
import com.lunares.blockfield.common.combat.TaczAssets;
import com.lunares.blockfield.network.packet.AnimationReadyC2SPacket;
import com.lunares.blockfield.oracle.AnimationManifestCases;
import com.lunares.blockfield.oracle.OracleTransformAudit;
import com.tacz.guns.client.resource.ClientAssetsManager;
import com.tacz.guns.client.resource.manager.DisplayManager;
import com.tacz.guns.client.resource.pojo.display.gun.GunDisplay;
import com.tacz.guns.compat.playeranimator.animation.PlayerAnimatorAssetManager;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.InactiveProfiler;
import org.junit.jupiter.api.Test;

/**
 * U9b READY on the transformed production client (ADR4 §6). The native TaCZ and LR loaders run
 * their own reload over a real multi-pack resource stack (the production gun packs, the LR JAR and
 * optional user packs on top); the production resolver then reproduces a manifest built with the
 * server's display reading ({@code TaczAssets.DisplayFile}/{@code LrAssets.DisplayFile}, the PA
 * GeckoLib parser, the same register calls as {@code HeldWeaponPose}/{@code LrBodyPose}).
 */
class AnimationResourcesOracleFixture {
    private static final long GENERATION = 7;
    private static final com.lunares.blockfield.common.combat.AnimationPolicy POLICY =
            new com.lunares.blockfield.common.combat.AnimationPolicy(
                    2,
                    PhaseDomain.A,
                    true,
                    true,
                    true,
                    true,
                    com.lunares.blockfield.common.combat.AnimationPolicy.registered());

    @org.junit.jupiter.api.BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void productionPacksReproduceTheServerManifest() {
        AnimSyncOracleRunner.run(
                "tacz",
                "u9b-production-packs-manifest",
                () -> {
                    OracleTransformAudit.requireConfig("tacz.mixins.json");
                    var packs = productionPacks();
                    var manifest = server(packs);
                    var result = new LinkedHashMap<String, Object>();
                    try (var ignored = new Native(packs)) {
                        var view = ClientAnimationResources.build(manifest);
                        assertEquals(List.of(), manifest.mismatches(view.values()));
                        result.put("entries", manifest.entries().size());
                        result.put("taczDisplays", count(manifest, AnimationManifest.TACZ_DISPLAY));
                        result.put("lrDisplays", count(manifest, AnimationManifest.LR_DISPLAY));
                        result.put("manifestDigest", hex(manifest.manifestDigest()));
                        result.put("compatibilityDigest", hex(manifest.compatibilityDigest()));
                        assertTrue(count(manifest, AnimationManifest.TACZ_DISPLAY) >= 90);
                        assertEquals(3, count(manifest, AnimationManifest.LR_DISPLAY));
                        // Both peers hash the family JARs they load; here they are the pinned ones.
                        var pins = new TreeMap<String, String>();
                        for (String mod : AnimationManifest.PINS.keySet()) {
                            String status =
                                    AnimationManifest.pinStatus(
                                            mod, AnimationManifest.nativeIdentity(mod));
                            pins.put(mod, status);
                            assertEquals("pinned", status, mod);
                        }
                        result.put("nativePins", pins);
                        // U8b: both peers hash the SBW read layout (resolved on the production bf24
                        // classes) and the Create adapter into the compared compatibility.
                        String sbw = com.lunares.blockfield.common.combat.SbwLayouts.identity();
                        assertFalse(sbw.contains("missing"), sbw);
                        assertTrue(
                                manifest.entries()
                                        .containsKey(AnimationManifest.COMPAT + "family.sbw"));
                        assertTrue(
                                manifest.entries()
                                        .containsKey(AnimationManifest.COMPAT + "family.create"));
                        result.put("sbwLayout", sbw);
                        result.put(
                                "createAdapter",
                                com.lunares.blockfield.common.combat.CreateHanging.identity());
                        // The installed views resolve every server-named display.
                        var generation = view.generation();
                        for (var display : serverViews(packs).tacz().displays())
                            assertEquals(
                                    display,
                                    generation.tacz().displays().stream()
                                            .filter(d -> d.id().equals(display.id()))
                                            .findFirst()
                                            .orElseThrow(),
                                    display.id());
                    }
                    return result;
                });
    }

    @Test
    void combatOverridePacksAreRefusedNoncombatPacksAccepted() {
        AnimSyncOracleRunner.run(
                "tacz",
                "u9b-user-pack-precedence",
                () -> {
                    var defaults = productionPacks();
                    var manifest = server(defaults);
                    var result = new LinkedHashMap<String, Object>();
                    Path root = Path.of("u9b-user-packs");
                    try {
                        var ak47 = defaultJson("tacz", "display/guns/ak47_display.json");
                        var noncombat = ak47.deepCopy();
                        noncombat.addProperty("hud", "tacz:gun/hud/m4a1");
                        noncombat.addProperty("texture", "tacz:gun/uv/m4a1");
                        var display = ak47.deepCopy();
                        display.addProperty("third_person_animation", "pistol");
                        var rifle =
                                defaultJson(
                                        "tacz",
                                        "player_animator/rifle_default.player_animation.json");
                        assertTrue(bumpFirstNumber(rifle.get("animations")), "rifle keyframe");
                        var bat = defaultJson("lrtactical", "display/melee/baseball_bat.json");
                        bat.getAsJsonObject("third_person_animation")
                                .getAsJsonObject("upper")
                                .addProperty("idle", "overridden");

                        var cases = new LinkedHashMap<String, List<String>>();
                        cases.put(
                                "noncombat",
                                mismatches(
                                        manifest,
                                        defaults,
                                        userPack(
                                                root,
                                                "noncombat",
                                                "tacz",
                                                "display/guns/ak47_display.json",
                                                noncombat)));
                        assertEquals(List.of(), cases.get("noncombat"), "noncombat pack accepted");
                        cases.put(
                                "display",
                                mismatches(
                                        manifest,
                                        defaults,
                                        userPack(
                                                root,
                                                "display",
                                                "tacz",
                                                "display/guns/ak47_display.json",
                                                display)));
                        // A changed combat display also changes the generation's ref table.
                        assertEquals(
                                List.of(
                                        AnimationManifest.TACZ_DISPLAY + "tacz:ak47_display",
                                        AnimationManifest.WIRE_REF_TABLE),
                                cases.get("display"));
                        cases.put(
                                "clip",
                                mismatches(
                                        manifest,
                                        defaults,
                                        userPack(
                                                root,
                                                "clip",
                                                "tacz",
                                                "player_animator/rifle_default.player_animation.json",
                                                rifle)));
                        var rifleDisplays = rifleDisplays(defaults);
                        assertFalse(rifleDisplays.isEmpty());
                        var clipMismatches = new ArrayList<>(rifleDisplays);
                        clipMismatches.add(AnimationManifest.WIRE_REF_TABLE);
                        assertEquals(
                                clipMismatches, cases.get("clip"), "every display of the file");
                        cases.put(
                                "lr",
                                mismatches(
                                        manifest,
                                        defaults,
                                        userPack(
                                                root,
                                                "lr",
                                                "lrtactical",
                                                "display/melee/baseball_bat.json",
                                                bat)));
                        assertEquals(
                                List.of(
                                        AnimationManifest.LR_ASSETS,
                                        AnimationManifest.LR_DISPLAY + "lrtactical:baseball_bat",
                                        AnimationManifest.WIRE_REF_TABLE),
                                cases.get("lr"));
                        result.put("mismatches", cases);
                        result.put("rifleDisplays", rifleDisplays.size());
                    } finally {
                        delete(root);
                    }
                    return result;
                });
    }

    @Test
    void readyInstallsArmsAndFencesOnlyAnExactReproduction() {
        AnimSyncOracleRunner.run(
                "blockfield",
                "u9b-ready-activation-fence",
                () -> {
                    var packs = productionPacks();
                    var manifest = server(packs);
                    var result = new LinkedHashMap<String, Object>();
                    AnimationPolicy.restore();
                    AnimationPolicy.register();
                    ClientAnimationAssets.clear();
                    ClientAnimationResources.forget();
                    try (var ignored = new Native(packs)) {
                        // A refused READY: one entry differs, so nothing installs and nothing arms.
                        var entries = new TreeMap<>(manifest.entries());
                        entries.put(
                                AnimationManifest.TACZ_DISPLAY + "tacz:ak47_display",
                                Digest.hash(new byte[] {1}));
                        var wrong = new AnimationManifest(GENERATION, POLICY, entries);
                        var wire = new com.lunares.blockfield.client.combat.AnimationWireReceiver();
                        var replies = new ArrayList<AnimationReadyC2SPacket>();
                        ClientAnimationResources.receive(wrong, replies::add, wire);
                        assertEquals(1, replies.size());
                        assertEquals(1, wrong.mismatches(replies.get(0).values()).size());
                        assertNull(ClientAnimationAssets.get(identity(wrong)));
                        assertNull(ClientAnimationResources.validated());
                        ClientPlayConnectionEvents.INIT.invoker().onPlayInit(null, null);
                        assertFalse(AnimationPolicy.forcing(), "refused server never forces");
                        ClientPlayConnectionEvents.DISCONNECT
                                .invoker()
                                .onPlayDisconnect(null, null);

                        // The exact reproduction installs before JOIN and arms the server policy.
                        replies.clear();
                        ClientAnimationResources.receive(
                                manifest,
                                ready -> {
                                    assertNotNull(
                                            wire.table(ready.resourceGeneration()),
                                            "table before READY");
                                    replies.add(ready);
                                },
                                wire);
                        assertEquals(List.of(), manifest.mismatches(replies.get(0).values()));
                        assertEquals(GENERATION, replies.get(0).resourceGeneration());
                        assertEquals(POLICY.generation(), replies.get(0).policyGeneration());
                        var installed = ClientAnimationAssets.get(identity(manifest));
                        assertNotNull(installed);
                        assertSame(POLICY, installed.policy());
                        assertFalse(AnimationPolicy.forcing(), "nothing forces before play INIT");
                        ClientPlayConnectionEvents.INIT.invoker().onPlayInit(null, null);
                        assertTrue(AnimationPolicy.forcing());
                        // Records stamped with another manifest or compatibility never use it.
                        assertNull(
                                ClientAnimationAssets.get(
                                        identity(
                                                manifest,
                                                manifest.compatibilityDigest(),
                                                Digest.hash(new byte[] {2}))));
                        assertNull(
                                ClientAnimationAssets.get(
                                        identity(
                                                manifest,
                                                Digest.hash(new byte[] {3}),
                                                manifest.manifestDigest())));

                        // Reload fence: the live resources must still reproduce the admission.
                        assertEquals(List.of(), ClientAnimationResources.mismatches(manifest));
                        Path root = Path.of("u9b-fence-pack");
                        try {
                            var display = defaultJson("tacz", "display/guns/ak47_display.json");
                            display.addProperty("third_person_animation", "pistol");
                            var stack = new ArrayList<>(packs);
                            stack.add(
                                    userPack(
                                            root,
                                            "fence",
                                            "tacz",
                                            "display/guns/ak47_display.json",
                                            display));
                            try (var reloaded = new Native(stack)) {
                                var fence = ClientAnimationResources.mismatches(manifest);
                                assertEquals(
                                        List.of(
                                                AnimationManifest.TACZ_DISPLAY
                                                        + "tacz:ak47_display",
                                                AnimationManifest.WIRE_REF_TABLE),
                                        fence);
                                result.put("reloadFence", fence);
                                // The installed private views keep presenting until disconnect.
                                assertNotNull(ClientAnimationAssets.get(identity(manifest)));
                            }
                        } finally {
                            delete(root);
                        }

                        // A newly activated generation installs beside the retained one.
                        var next =
                                new AnimationManifest(
                                        GENERATION + 1,
                                        POLICY,
                                        server(packs, GENERATION + 1).entries());
                        replies.clear();
                        ClientAnimationResources.receive(next, replies::add, wire);
                        assertEquals(List.of(), next.mismatches(replies.get(0).values()));
                        assertNotNull(ClientAnimationAssets.get(identity(manifest)), "retained");
                        assertNotNull(ClientAnimationAssets.get(identity(next)));
                        assertNotEquals(
                                ClientAnimationAssets.get(identity(manifest)).lr().catalog(),
                                ClientAnimationAssets.get(identity(next)).lr().catalog());

                        ClientPlayConnectionEvents.DISCONNECT
                                .invoker()
                                .onPlayDisconnect(null, null);
                        assertFalse(AnimationPolicy.forcing(), "disconnect restores");
                        ClientPlayConnectionEvents.INIT.invoker().onPlayInit(null, null);
                        assertFalse(AnimationPolicy.forcing(), "the next server must arm again");
                        result.put("installedGenerations", List.of(GENERATION, GENERATION + 1));
                        result.put("replyEntries", replies.get(0).values().size());
                    } finally {
                        AnimationPolicy.restore();
                        ClientAnimationAssets.clear();
                        ClientAnimationResources.forget();
                    }
                    return result;
                });
    }

    // --- Native loaders over a real resource stack ---

    /**
     * Saves the native singletons, runs the native reloads over {@code packs}, restores on close.
     */
    private static final class Native implements AutoCloseable {
        private final Object savedGun, savedMelee;
        private final Map<Object, Object> savedClips;
        private final Field gunField, meleeField, clipsField;
        private final Object lr;

        @SuppressWarnings("unchecked")
        Native(List<PackResources> packs) throws Exception {
            var manager = new MultiPackResourceManager(PackType.CLIENT_RESOURCES, packs);
            gunField = ClientAssetsManager.class.getDeclaredField("gunDisplay");
            gunField.setAccessible(true);
            savedGun = gunField.get(ClientAssetsManager.INSTANCE);
            var displays =
                    new DisplayManager<>(
                            GunDisplay.class,
                            ClientAssetsManager.GSON,
                            "display/guns",
                            "GunDisplayLoader");
            reload(displays, manager);
            gunField.set(ClientAssetsManager.INSTANCE, displays);
            clipsField = PlayerAnimatorAssetManager.class.getDeclaredField("animations");
            clipsField.setAccessible(true);
            var clips = (Map<Object, Object>) clipsField.get(PlayerAnimatorAssetManager.get());
            savedClips = new HashMap<>(clips);
            reload(PlayerAnimatorAssetManager.get(), manager);
            var lrType = Class.forName("me.xjqsh.lrtactical.client.resource.LrClientAssetsManager");
            lr = lrType.getField("INSTANCE").get(null);
            meleeField = lrType.getDeclaredField("meleeDisplay");
            meleeField.setAccessible(true);
            savedMelee = meleeField.get(lr);
            meleeField.set(lr, melee(manager, (Gson) lrType.getField("GSON").get(null)));
        }

        @SuppressWarnings("unchecked")
        @Override
        public void close() throws Exception {
            gunField.set(ClientAssetsManager.INSTANCE, savedGun);
            meleeField.set(lr, savedMelee);
            var clips = (Map<Object, Object>) clipsField.get(PlayerAnimatorAssetManager.get());
            clips.clear();
            clips.putAll(savedClips);
        }
    }

    /**
     * LR's melee manager with the native GSON reading of each effective display. Native {@code
     * MeleeDisplayInstance.create} also binds models and Lua state machines, which this headless
     * runtime cannot load; it copies the record's third-person config unchanged, which is all the
     * resolver reads.
     */
    @SuppressWarnings("unchecked")
    private static Object melee(ResourceManager manager, Gson gson) throws Exception {
        String base = "me.xjqsh.lrtactical.client.resource.";
        var managerType = Class.forName(base + "manager.MeleeDisplayManager");
        Object displays = managerType.getConstructor(Gson.class).newInstance(gson);
        var dataField =
                Class.forName("com.tacz.guns.resource.manager.JsonDataManager")
                        .getDeclaredField("dataMap");
        dataField.setAccessible(true);
        var data = (Map<ResourceLocation, Object>) dataField.get(displays);
        var record = Class.forName(base + "display.MeleeDisplayInstance$MeleeDisplay");
        var instanceType = Class.forName(base + "display.MeleeDisplayInstance");
        var ctor = instanceType.getDeclaredConstructor();
        ctor.setAccessible(true);
        var third = instanceType.getDeclaredField("thirdPersonAnimation");
        third.setAccessible(true);
        var accessor = record.getMethod("thirdPersonAnimation");
        for (var entry : files(manager, "display/melee").entrySet()) {
            Object pojo;
            try {
                pojo = gson.fromJson(entry.getValue(), record);
            } catch (RuntimeException invalid) {
                continue; // Native apply logs and skips an unreadable display.
            }
            Object instance = ctor.newInstance();
            third.set(instance, accessor.invoke(pojo));
            data.put(entry.getKey(), instance);
        }
        return displays;
    }

    private static void reload(Object listener, ResourceManager manager) {
        ((PreparableReloadListener) listener)
                .reload(
                        new PreparableReloadListener.PreparationBarrier() {
                            @Override
                            public <T> CompletableFuture<T> wait(T value) {
                                return CompletableFuture.completedFuture(value);
                            }
                        },
                        manager,
                        InactiveProfiler.INSTANCE,
                        InactiveProfiler.INSTANCE,
                        Runnable::run,
                        Runnable::run)
                .join();
    }

    // --- The server's reading of the same packs (shared with the server oracle) ---

    private static AnimationManifest server(List<PackResources> packs) throws IOException {
        return server(packs, GENERATION);
    }

    private static AnimationManifest server(List<PackResources> packs, long generation)
            throws IOException {
        return AnimationManifestCases.manifest(packs, generation, POLICY);
    }

    private static AnimationManifestCases.Views serverViews(List<PackResources> packs)
            throws IOException {
        return AnimationManifestCases.serverViews(packs, GENERATION);
    }

    private static List<PackResources> productionPacks() {
        return AnimationManifestCases.productionPacks();
    }

    private static PackResources pack(String id, Path root) {
        return AnimationManifestCases.pack(id, root);
    }

    private static TreeMap<ResourceLocation, JsonElement> files(ResourceManager manager, String dir)
            throws IOException {
        return AnimationManifestCases.files(manager, dir);
    }

    private static PackResources userPack(
            Path root, String name, String namespace, String path, JsonObject json)
            throws IOException {
        var file = root.resolve(name).resolve("assets").resolve(namespace).resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, json.toString());
        return pack("user_" + name, root.resolve(name));
    }

    private static List<String> mismatches(
            AnimationManifest manifest, List<PackResources> defaults, PackResources user)
            throws Exception {
        var stack = new ArrayList<>(defaults);
        stack.add(user);
        try (var ignored = new Native(stack)) {
            return manifest.mismatches(ClientAnimationResources.build(manifest).values());
        }
    }

    private static List<String> rifleDisplays(List<PackResources> packs) throws IOException {
        var manager = new MultiPackResourceManager(PackType.CLIENT_RESOURCES, packs);
        var result = new ArrayList<String>();
        for (var entry : files(manager, "display/guns").entrySet())
            if (TaczAssets.DisplayFile.parse(entry.getValue().getAsJsonObject())
                    .animation()
                    .equals("tacz:rifle_default.player_animation"))
                result.add(AnimationManifest.TACZ_DISPLAY + entry.getKey());
        result.sort(null);
        return result;
    }

    private static JsonObject defaultJson(String mod, String path) throws IOException {
        var loader = FabricLoader.getInstance().getModContainer(mod).orElseThrow();
        var file =
                mod.equals("tacz")
                        ? loader.findPath("assets/tacz/custom/tacz_default_gun/assets/tacz/" + path)
                        : loader.findPath("assets/" + mod + "/" + path);
        return JsonParser.parseString(Files.readString(file.orElseThrow())).getAsJsonObject();
    }

    /** Adds 1 to the first number in a keyframe tree (a combat clip override). */
    private static boolean bumpFirstNumber(JsonElement element) {
        if (element instanceof JsonObject object) {
            for (var entry : object.entrySet()) {
                if (entry.getValue() instanceof JsonPrimitive p && p.isNumber()) {
                    object.addProperty(entry.getKey(), p.getAsDouble() + 1);
                    return true;
                }
                if (bumpFirstNumber(entry.getValue())) return true;
            }
        } else if (element instanceof JsonArray array) {
            for (int i = 0; i < array.size(); i++) {
                if (array.get(i) instanceof JsonPrimitive p && p.isNumber()) {
                    array.set(i, new JsonPrimitive(p.getAsDouble() + 1));
                    return true;
                }
                if (bumpFirstNumber(array.get(i))) return true;
            }
        }
        return false;
    }

    private static AnimationState.Identity identity(AnimationManifest manifest) {
        return identity(manifest, manifest.compatibilityDigest(), manifest.manifestDigest());
    }

    private static AnimationState.Identity identity(
            AnimationManifest manifest, Digest compatibility, Digest digest) {
        return new AnimationState.Identity(
                1,
                1,
                1,
                7,
                0,
                1,
                1,
                0,
                0,
                manifest.resourceGeneration(),
                manifest.policy().generation(),
                compatibility,
                digest);
    }

    private static long count(AnimationManifest manifest, String prefix) {
        return manifest.entries().keySet().stream().filter(k -> k.startsWith(prefix)).count();
    }

    private static String hex(Digest digest) {
        return java.util.HexFormat.of().formatHex(digest.bytes());
    }

    private static void delete(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList())
                Files.delete(path);
        }
    }
}
