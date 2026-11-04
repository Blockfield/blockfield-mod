package com.lunares.blockfield.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GunResourcePackTest {
    private static final String ROOT = "resourcepacks/gun_overrides/";

    @Test
    void primaryDrawMotionEndsAtTheBalancedServerReadyTime() throws Exception {
        var times =
                Map.of(
                        "elitex/ak47x", .95,
                        "elitex/m4_sopmod", .95,
                        "elitex/benelli_super", .90,
                        "elitex/ro635v3", .80,
                        "tacz/hk416a5", .90,
                        "tacz/sks_tactical", .95,
                        "tacz/spr15hb", 1.00,
                        "tacz/m249", 1.35,
                        "tacz/ai_awp", 1.15,
                        "tacz/m700", 1.05);
        for (var entry : times.entrySet()) {
            String[] id = entry.getKey().split("/");
            var resource =
                    getClass()
                            .getClassLoader()
                            .getResource(
                                    ROOT
                                            + "assets/"
                                            + id[0]
                                            + "/animations/"
                                            + id[1]
                                            + ".animation.json");
            assertNotNull(resource, entry.getKey());
            var clips =
                    JsonParser.parseString(Files.readString(Path.of(resource.toURI())))
                            .getAsJsonObject()
                            .getAsJsonObject("animations");
            assertTrue(clips.has("draw"), entry.getKey() + ":draw");
            for (String name : List.of("draw", "draw_empty")) {
                if (!clips.has(name)) continue;
                double end = 0;
                for (var bone : clips.getAsJsonObject(name).getAsJsonObject("bones").entrySet()) {
                    for (var channel : bone.getValue().getAsJsonObject().entrySet()) {
                        if (!channel.getValue().isJsonObject()) continue;
                        for (String time : channel.getValue().getAsJsonObject().keySet()) {
                            if (time.matches("[0-9]+(?:\\.[0-9]*)?")) {
                                end = Math.max(end, Double.parseDouble(time));
                            }
                        }
                    }
                }
                assertEquals(entry.getValue(), end, .0001, entry.getKey() + ":" + name);
            }
        }
    }

    @Test
    void suppliesNativeResourcePathsWithoutGunpackExtractionPrefixes() throws Exception {
        var metadata = getClass().getClassLoader().getResource(ROOT + "pack.mcmeta");
        assertNotNull(metadata);
        Path root = Path.of(metadata.toURI()).getParent();
        var pack = JsonParser.parseString(Files.readString(root.resolve("pack.mcmeta")));
        assertEquals(
                34, pack.getAsJsonObject().getAsJsonObject("pack").get("pack_format").getAsInt());

        for (String asset :
                List.of(
                        "assets/elitex/animations/ak47x.animation.json",
                        "assets/elitex/animations/m4_sopmod.animation.json",
                        "assets/tacz/animations/glock_17.animation.json",
                        "assets/tacz/animations/m700.animation.json",
                        "assets/lrtactical/animations/throwable/m67_grenade.animation.json",
                        "assets/lrtactical/animations/throwable/smoke_grenade.animation.json",
                        "assets/elitex/tacz_sounds/assault_rifles/ak47x/ak47x_draw.ogg",
                        "assets/elitex/tacz_sounds/assault_rifles/m4_sopmod/m4_sopmod_draw.ogg",
                        "assets/tacz/tacz_sounds/glock_17/glock17_draw.ogg",
                        "assets/tacz/tacz_sounds/m700/m700_draw.ogg")) {
            assertNotNull(getClass().getClassLoader().getResource(ROOT + asset), asset);
        }

        try (var files = Files.walk(root.resolve("assets"))) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Path relative = root.relativize(file);
                assertEquals("assets", relative.getName(0).toString());
                assertTrue(
                        List.of("elitex", "tacz", "lrtactical")
                                .contains(relative.getName(1).toString()));
                String type = relative.getName(2).toString();
                if (type.equals("animations")) {
                    assertTrue(file.toString().endsWith(".animation.json"), file.toString());
                    var animation =
                            JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                    assertTrue(animation.getAsJsonObject("animations").size() > 0, file.toString());
                } else {
                    assertEquals("tacz_sounds", type);
                    assertTrue(file.toString().endsWith(".ogg"), file.toString());
                    try (var input = Files.newInputStream(file)) {
                        byte[] header = input.readNBytes(64);
                        assertTrue(header.length == 64, file.toString());
                        assertEquals("OggS", new String(header, 0, 4, StandardCharsets.US_ASCII));
                        assertTrue(
                                new String(header, StandardCharsets.ISO_8859_1)
                                        .contains("\1vorbis"));
                    }
                }
            }
        }
    }

    @Test
    void animationSoundEffectsResolveToGunpackNamespaces() throws Exception {
        // TaCZ resolves an animation effect without a namespace to
        // minecraft:tacz_sounds/<effect>.ogg, which no gunpack ships: the clip plays
        // silently and the client logs "[TACZ Sound] Missing gun sound resource".
        // Upstream default-gun animations are full of such stale refs (ar_akilo_*,
        // wfoly_*/p01_* leftovers); only explicitly namespaced effects survive here.
        // Ogg presence itself was verified offline against the pinned TaCZ bf8 /
        // EliteX bf10 jars; re-run that check when the pins change.
        var metadata = getClass().getClassLoader().getResource(ROOT + "pack.mcmeta");
        assertNotNull(metadata);
        Path root = Path.of(metadata.toURI()).getParent();
        try (var files = Files.walk(root.resolve("assets"))) {
            for (Path file :
                    files.filter(path -> path.toString().endsWith(".animation.json")).toList()) {
                var animation = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                for (var clip : animation.getAsJsonObject("animations").entrySet()) {
                    var effects = clip.getValue().getAsJsonObject().get("sound_effects");
                    if (effects == null) continue;
                    for (var entry : effects.getAsJsonObject().entrySet()) {
                        String effect =
                                entry.getValue().getAsJsonObject().get("effect").getAsString();
                        assertTrue(
                                effect.startsWith("tacz:")
                                        || effect.startsWith("elitex:")
                                        || effect.startsWith("lrtactical:"),
                                file.getFileName() + ":" + clip.getKey() + " -> " + effect);
                    }
                }
            }
        }
    }
}
