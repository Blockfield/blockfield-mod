package com.lunares.blockfield.common.combat;

import static org.junit.jupiter.api.Assertions.*;

import com.lunares.blockfield.common.combat.AnimationResources.Digest;
import com.lunares.blockfield.common.combat.AnimationTypes.LayerTag;
import com.lunares.blockfield.common.combat.AnimationTypes.PhaseDomain;
import com.lunares.blockfield.network.packet.AnimationManifestS2CPacket;
import com.lunares.blockfield.network.packet.AnimationReadyC2SPacket;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

/** ADR4 §6 manifest wire, digests and the shared positional comparison (U9). */
class AnimationManifestTest {
    static final AnimationPolicy POLICY =
            new AnimationPolicy(
                    3, PhaseDomain.A, true, true, true, true, AnimationPolicy.registered());

    static Digest d(String text) {
        return Digest.hash(text.getBytes(StandardCharsets.UTF_8));
    }

    static AnimationManifest manifest() {
        var entries = new TreeMap<String, Digest>();
        entries.putAll(AnimationManifest.compatibility(mod -> "sha256:" + mod));
        entries.put(AnimationManifest.TACZ_DISPLAY + "tacz:ak47_display", d("ak"));
        entries.put(AnimationManifest.TACZ_DISPLAY + "tacz:m4_display", d("m4"));
        entries.put(AnimationManifest.LR_DISPLAY + "lrtactical:bat", d("bat"));
        entries.put(AnimationManifest.LR_ASSETS, d("lr"));
        return new AnimationManifest(9, POLICY, entries);
    }

    private static <T> T roundTrip(
            net.minecraft.network.codec.StreamCodec<FriendlyByteBuf, T> codec, T value) {
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        codec.encode(buf, value);
        return codec.decode(buf);
    }

    @Test
    void manifestAndAnswerRoundTripExactly() {
        var manifest = manifest();
        var decoded =
                roundTrip(
                                AnimationManifestS2CPacket.CODEC,
                                new AnimationManifestS2CPacket(manifest))
                        .manifest();
        assertEquals(manifest, decoded);
        assertEquals(manifest.manifestDigest(), decoded.manifestDigest());
        assertEquals(manifest.compatibilityDigest(), decoded.compatibilityDigest());
        var values = new ArrayList<Digest>(manifest.entries().values());
        values.set(1, null);
        var answer = new AnimationReadyC2SPacket(9, 3, values);
        assertEquals(answer, roundTrip(AnimationReadyC2SPacket.CODEC, answer));
    }

    @Test
    void wireRejectsTrailingUnorderedUnknownAndNoncanonicalValues() {
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        AnimationManifestS2CPacket.CODEC.encode(buf, new AnimationManifestS2CPacket(manifest()));
        buf.writeByte(0);
        assertThrows(
                IllegalArgumentException.class, () -> AnimationManifestS2CPacket.CODEC.decode(buf));

        var unordered = new FriendlyByteBuf(Unpooled.buffer());
        com.lunares.blockfield.common.combat.SnapshotWireMath.writeUvarLong(unordered, 1);
        AnimationManifest.writePolicy(unordered, POLICY);
        com.lunares.blockfield.common.combat.SnapshotWireMath.writeUvarInt(unordered, 2);
        unordered.writeUtf(AnimationManifest.LR_ASSETS);
        AnimationSchema.writeDigest(unordered, d("a"));
        unordered.writeUtf(AnimationManifest.COMPAT + "schema");
        AnimationSchema.writeDigest(unordered, d("b"));
        assertThrows(IllegalArgumentException.class, () -> AnimationManifest.read(unordered));

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new AnimationManifest(
                                1, POLICY, new TreeMap<>(Map.of("textures:gun", d("x")))));

        var answer = new FriendlyByteBuf(Unpooled.buffer());
        AnimationReadyC2SPacket.CODEC.encode(
                answer, new AnimationReadyC2SPacket(1, 3, List.of(d("x"))));
        byte[] bytes = new byte[answer.readableBytes()];
        answer.readBytes(bytes);
        bytes[3] = 2; // presence byte of the only value
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        AnimationReadyC2SPacket.CODEC.decode(
                                new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes))));
    }

    @Test
    void mismatchesNameEveryDifferingOrMissingEntryInServerOrder() {
        var manifest = manifest();
        var values = new ArrayList<Digest>(manifest.entries().values());
        assertEquals(List.of(), manifest.mismatches(values));
        var keys = new ArrayList<>(manifest.entries().keySet());
        int ak = keys.indexOf(AnimationManifest.TACZ_DISPLAY + "tacz:ak47_display");
        int bat = keys.indexOf(AnimationManifest.LR_DISPLAY + "lrtactical:bat");
        values.set(ak, d("user pack"));
        values.set(bat, null);
        assertEquals(
                List.of(
                        AnimationManifest.LR_DISPLAY + "lrtactical:bat (missing)",
                        AnimationManifest.TACZ_DISPLAY + "tacz:ak47_display"),
                manifest.mismatches(values));
        assertThrows(
                IllegalArgumentException.class, () -> manifest.mismatches(values.subList(1, 3)));
    }

    @Test
    void digestsSeparateCodeIdentityFromGenerationResources() {
        var manifest = manifest();
        var entries = new TreeMap<>(manifest.entries());
        entries.put(AnimationManifest.TACZ_DISPLAY + "tacz:ak47_display", d("changed"));
        var resources = new AnimationManifest(9, POLICY, entries);
        assertEquals(manifest.compatibilityDigest(), resources.compatibilityDigest());
        assertNotEquals(manifest.manifestDigest(), resources.manifestDigest());
        var compat = new TreeMap<>(manifest.entries());
        compat.put(AnimationManifest.COMPAT + "native.tacz", d("another jar"));
        var code = new AnimationManifest(9, POLICY, compat);
        assertNotEquals(manifest.compatibilityDigest(), code.compatibilityDigest());
        assertEquals(manifest.manifestDigest(), code.manifestDigest());
        // Generation and policy are part of the resource identity even with equal content.
        assertNotEquals(
                manifest.manifestDigest(),
                new AnimationManifest(10, POLICY, manifest.entries()).manifestDigest());
        var lowerR =
                new AnimationPolicy(
                        3, PhaseDomain.R, true, true, true, true, AnimationPolicy.registered());
        assertNotEquals(
                manifest.manifestDigest(),
                new AnimationManifest(9, lowerR, manifest.entries()).manifestDigest());
    }

    @Test
    void compatibilityNamesEveryPinnedFamilyAndTheParCoolFamilyIdentity() {
        var compat = AnimationManifest.compatibility(mod -> "absent");
        for (String mod : AnimationManifest.PINS.keySet())
            assertTrue(compat.containsKey(AnimationManifest.COMPAT + "native." + mod), mod);
        var family = com.lunares.blockfield.common.combat.ParCoolCheckpoints.familyIdentity();
        assertTrue(family.contains("duration.ROLL=9;duration.TAP=8;duration.VAULT=11"), family);
        assertEquals(
                Digest.hash(family.getBytes(StandardCharsets.UTF_8)),
                compat.get(AnimationManifest.COMPAT + "family.parcool"));
        assertEquals(
                AnimationSchema.layoutDigest(), compat.get(AnimationManifest.COMPAT + "schema"));
        assertEquals(ObserverReducer.DIGEST, compat.get(AnimationManifest.COMPAT + "reducer"));
        assertNotEquals(
                AnimationManifest.compatibilityDigest(compat),
                AnimationManifest.compatibilityDigest(
                        AnimationManifest.compatibility(
                                mod -> mod.equals("tacz") ? "x" : "absent")));
    }

    @Test
    void compatibilityNamesTheSbwLayoutAndTheCreateAdapter() {
        var compat = AnimationManifest.compatibility(mod -> "absent");
        String sbw = SbwLayouts.identity();
        assertEquals(
                Digest.hash(sbw.getBytes(StandardCharsets.UTF_8)),
                compat.get(AnimationManifest.COMPAT + "family.sbw"));
        // Every pinned bf24 slot resolves with its descriptor on the loaded classes.
        for (var slot : SbwLayouts.baseSlots())
            assertTrue(sbw.contains(slot.member() + slot.descriptor() + ":CONSTANT:ok"), sbw);
        assertFalse(sbw.contains("missing"), sbw);
        String create = CreateHanging.identity();
        assertEquals(
                Digest.hash(create.getBytes(StandardCharsets.UTF_8)),
                compat.get(AnimationManifest.COMPAT + "family.create"));
        assertTrue(create.contains("phase=i2f(u0+(n-sA))+frac(A)"), create);
    }

    @Test
    void createPhaseKeepsTheNativeClockFloatOrder() {
        // Native setHangingPose: (float) getTicks(true) + getPartialTicks().
        for (int ticks : new int[] {0, 1, 7, 16_777_217, 1 << 30})
            for (float frac : new float[] {0f, 0x1p-16f, 0.3f, 0.99998474f}) {
                float expected = (float) ticks + frac;
                assertEquals(
                        Float.floatToRawIntBits(expected),
                        Float.floatToRawIntBits(CreateHanging.phase(ticks, 5, 5, frac)));
                assertEquals(ticks, CreateHanging.ticks(ticks, 5, 5));
            }
        assertEquals(3, CreateHanging.ticks(1, 10, 12), "u0 + (n - sA)");
        assertThrows(ArithmeticException.class, () -> CreateHanging.ticks(1, 0, 1L << 31));
    }

    @Test
    void resourceEntriesCoverEveryDisplayAndChangeWithItsClipContent() {
        var manifest = resources(0f);
        var keys = manifest.keySet();
        assertTrue(keys.contains(AnimationManifest.TACZ_DISPLAY + "tacz:ak47_display"));
        assertTrue(keys.contains(AnimationManifest.LR_DISPLAY + "lrtactical:bat"));
        assertTrue(keys.contains(AnimationManifest.LR_ASSETS));
        var moved = resources(0.25f);
        assertNotEquals(
                manifest.get(AnimationManifest.TACZ_DISPLAY + "tacz:ak47_display"),
                moved.get(AnimationManifest.TACZ_DISPLAY + "tacz:ak47_display"));
        assertNotEquals(
                manifest.get(AnimationManifest.LR_ASSETS), moved.get(AnimationManifest.LR_ASSETS));
        // The rotation alias winner is part of the LR algorithm identity.
        var catalog = new PaGraph.Catalog(4);
        var lr = new LrAssets(catalog);
        assertNotEquals(
                AnimationManifest.resources(null, lr, LayerTag.PA_42)
                        .get(AnimationManifest.LR_ASSETS),
                AnimationManifest.resources(null, lr, LayerTag.PA_96)
                        .get(AnimationManifest.LR_ASSETS));
    }

    private static Map<String, Digest> resources(float pitch) {
        var catalog = new PaGraph.Catalog(4);
        var tacz = new TaczAssets(catalog, Map.of("tacz:ak47", "tacz:ak47_display"));
        var clip = clip("idle", pitch);
        tacz.register(
                "tacz:ak47_display",
                "tacz:rifle_default.player_animation",
                false,
                "empty",
                true,
                Map.of("idle", clip));
        var lr = new LrAssets(catalog);
        lr.register(
                "lrtactical:bat",
                true,
                "lrtactical:bat",
                Map.of("idle", List.of("idle")),
                Map.of(),
                Map.of("idle", clip));
        return AnimationManifest.resources(tacz, lr, LayerTag.PA_42);
    }

    private static dev.kosmx.playerAnim.core.data.KeyframeAnimation clip(String name, float pitch) {
        var builder =
                new dev.kosmx.playerAnim.core.data.KeyframeAnimation.AnimationBuilder(
                        dev.kosmx.playerAnim.core.data.AnimationFormat.UNKNOWN);
        builder.endTick = 10;
        builder.stopTick = 10;
        builder.setName(name);
        var tracks = builder.getOrCreatePart("head");
        tracks.pitch.setEnabled(true);
        tracks.pitch.addKeyFrame(0, pitch, dev.kosmx.playerAnim.core.util.Ease.LINEAR);
        return builder.build();
    }

    @Test
    void rotationWinnerFollowsTheLastRegisteredFamily() {
        assertEquals(
                LayerTag.PA_96,
                AnimationManifest.rotationWinner(Arrays.asList("lrtactical", "tacz")));
        assertEquals(
                LayerTag.PA_42,
                AnimationManifest.rotationWinner(Arrays.asList("tacz", "lrtactical")));
        assertEquals(LayerTag.PA_42, AnimationManifest.rotationWinner(List.of()));
    }
}
