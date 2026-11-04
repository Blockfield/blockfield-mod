package com.lunares.blockfield.network.packet;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

class UiPacketsCodecTest {
    private static JsonObject ui() {
        JsonObject ui = new JsonObject();
        ui.addProperty("tabActive", true);
        ui.addProperty("showHud", true);
        ui.addProperty("battleCredits", 150);
        ui.add("pts", new com.google.gson.JsonArray());
        return ui;
    }

    private static <T> T roundTrip(
            net.minecraft.network.codec.StreamCodec<FriendlyByteBuf, T> codec, T packet) {
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        codec.encode(buf, packet);
        return codec.decode(buf);
    }

    @Test
    void updateUiRoundTrip() {
        var packet = new UpdateUIS2CPacket(ui());
        var decoded = roundTrip(UpdateUIS2CPacket.CODEC, packet);
        assertTrue(decoded.valid());
        assertEquals(packet.ui(), decoded.ui());
        assertEquals(packet.uiDataJson(), decoded.uiDataJson());
    }

    @Test
    void classMenuRoundTrip() {
        JsonObject classes = new JsonObject();
        JsonObject kit = new JsonObject();
        kit.addProperty("name", "Штурмовик");
        classes.add("assault", kit);
        var decoded =
                roundTrip(OpenClassMenuS2CPacket.CODEC, new OpenClassMenuS2CPacket(classes, true));
        assertTrue(decoded.valid());
        assertEquals(classes, decoded.classes());
        assertTrue(decoded.isUpdate());
        assertEquals(classes.toString(), decoded.classesJson());
    }

    @Test
    void spawnMenuRoundTrip() {
        JsonObject spawns = new JsonObject();
        spawns.addProperty("ffa", true);
        var decoded =
                roundTrip(
                        OpenSpawnMenuS2CPacket.CODEC, new OpenSpawnMenuS2CPacket(spawns, "arena"));
        assertTrue(decoded.valid());
        assertEquals(spawns, decoded.spawns());
        assertEquals("arena", decoded.mapId());
    }

    @Test
    void openMenuRoundTrip() {
        JsonObject menu = new JsonObject();
        menu.addProperty("id", "vote");
        menu.addProperty("close", true);
        var decoded = roundTrip(OpenMenuS2CPacket.CODEC, new OpenMenuS2CPacket(menu));
        assertTrue(decoded.valid());
        assertEquals("vote", decoded.id());
        assertTrue(decoded.close());
    }

    @Test
    void malformedJsonRejectedByCodec() {
        var badKind =
                UpdateUIS2CPacket.JSON_CODEC.parse(
                        JsonOps.INSTANCE, JsonParser.parseString("{\"tabActive\": \"yes\"}"));
        assertTrue(badKind.error().isPresent());
        assertTrue(badKind.error().get().message().contains("tabActive"));

        var notObject =
                UpdateUIS2CPacket.JSON_CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("[1]"));
        assertTrue(notObject.error().isPresent());
    }

    @Test
    void malformedWirePayloadIgnored() {
        assertFalse(UpdateUIS2CPacket.decode("not json").valid());
        assertFalse(OpenMenuS2CPacket.decode("{\"id\": 42}").valid());
        assertTrue(OpenMenuS2CPacket.decode("{\"id\": \"vote\"}").valid());
    }

    @Test
    void explicitNullOnTypedKeyIsInvalid() {
        var boundsNull =
                OpenSpawnMenuS2CPacket.JSON_CODEC.parse(
                        JsonOps.INSTANCE, JsonParser.parseString("{\"bounds\":null}"));
        assertTrue(boundsNull.error().isPresent());
        assertFalse(OpenSpawnMenuS2CPacket.decode("{\"bounds\":null}", "arena").valid());

        var mapNull =
                UpdateUIS2CPacket.JSON_CODEC.parse(
                        JsonOps.INSTANCE, JsonParser.parseString("{\"map\":null}"));
        assertTrue(mapNull.error().isPresent());
        assertFalse(UpdateUIS2CPacket.decode("{\"map\":null}").valid());

        assertFalse(OpenMenuS2CPacket.decode("{\"id\":null}").valid());
    }

    @Test
    void nonObjectClassEntriesStayValidButReadAsNull() {
        var scalar = OpenClassMenuS2CPacket.decode("{\"assault\":42}", false);
        assertTrue(scalar.valid());
        assertNull(UiJson.obj(scalar.classes(), "assault"));

        var explicitNull = OpenClassMenuS2CPacket.decode("{\"assault\":null}", false);
        assertTrue(explicitNull.valid());
        assertNull(UiJson.obj(explicitNull.classes(), "assault"));
    }

    @Test
    void typedReadersRejectWrongTypesAndNull() {
        JsonObject o = new JsonObject();
        o.addProperty("s", 42);
        o.addProperty("b", "true");
        o.addProperty("i", "oops");
        o.addProperty("d", true);
        o.add("n", com.google.gson.JsonNull.INSTANCE);

        assertEquals("text", UiJson.str(o, "missing", "text"));
        assertEquals("def", UiJson.str(o, "s", "def"));
        assertEquals("def", UiJson.str(o, "n", "def"));

        assertTrue(UiJson.bool(o, "missing", true));
        assertFalse(UiJson.bool(o, "b", false));
        assertFalse(UiJson.bool(o, "n", false));

        assertEquals(7, UiJson.integer(o, "missing", 7));
        assertEquals(7, UiJson.integer(o, "i", 7));
        assertEquals(7, UiJson.integer(o, "n", 7));
        assertEquals(42, UiJson.integer(o, "s", 3));

        assertEquals(1.5, UiJson.decimal(o, "missing", 1.5));
        assertEquals(1.5, UiJson.decimal(o, "i", 1.5));
        assertEquals(1.5, UiJson.decimal(o, "d", 1.5));
        assertEquals(1.5, UiJson.decimal(o, "n", 1.5));
        assertEquals(42.0, UiJson.decimal(o, "s", 0.0));
    }
}
