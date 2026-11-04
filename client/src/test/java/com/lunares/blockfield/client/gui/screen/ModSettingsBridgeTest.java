package com.lunares.blockfield.client.gui.screen;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.lang.invoke.MethodHandleProxies;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.event.Event;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModSettingsBridgeTest {
    @TempDir Path directory;

    @Test
    void jsonPreservesNestedUnknownValuesAndRestoresFailedNativeSave() throws Exception {
        Path file = directory.resolve("pingwheel.json");
        String original =
                "{\"pingVolume\":100,\"unknown\":{\"list\":[1,true,\"x\"],\"null\":null},\"serverChannels\":{\"host\":\"squad\"}}";
        Files.writeString(file, original);
        ModSettingsBridge.saveJson(
                file,
                "pingVolume",
                25,
                () ->
                        Files.writeString(
                                file,
                                "{\"pingVolume\":25,\"serverChannels\":{\"host\":\"squad\"}}"));
        var saved = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        assertEquals(25, saved.get("pingVolume").getAsInt());
        assertEquals(
                JsonParser.parseString(original).getAsJsonObject().get("unknown"),
                saved.get("unknown"));
        ModSettingsBridge.saveJson(
                file,
                "correctionPeriod",
                0.1f,
                () -> Files.writeString(file, "{\"pingVolume\":25,\"correctionPeriod\":0.1}"));
        assertEquals(
                0.1f,
                JsonParser.parseString(Files.readString(file))
                        .getAsJsonObject()
                        .get("correctionPeriod")
                        .getAsFloat());
        String beforeFailure = Files.readString(file);
        assertThrows(
                IOException.class,
                () ->
                        ModSettingsBridge.saveJson(
                                file,
                                "pingVolume",
                                50,
                                () -> Files.writeString(file, "{\"pingVolume\":25}")));
        assertEquals(beforeFailure, Files.readString(file));
    }

    @Test
    void boundariesRejectNonFiniteFractionalAndLockedChangesBeforeMutation() throws Exception {
        Object[] live = {1.0};
        var ads =
                new ModSettingsBridge.Entry(
                        "blockfield",
                        "ads",
                        0,
                        2,
                        0.01,
                        () -> live[0],
                        value -> live[0] = value,
                        null);
        ads.set(0.0);
        assertEquals(0.0, ads.get());
        ads.set(2.0);
        assertEquals(2.0, ads.get());
        for (double invalid : new double[] {-0.01, 2.01, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> ads.set(invalid));
        }
        assertEquals(2.0, ads.get());
        var locked =
                new ModSettingsBridge.Entry(
                        "blockfield",
                        "locked",
                        0,
                        0,
                        0,
                        () -> true,
                        value -> fail("Locked option attempted a write"),
                        "locked");
        assertThrows(IllegalStateException.class, () -> locked.set(false));
        live[0] = 100;
        var control =
                new ModSettingsBridge.Entry(
                        "blockfield",
                        "control",
                        10,
                        200,
                        1,
                        () -> live[0],
                        value -> live[0] = value,
                        null);
        assertThrows(IllegalArgumentException.class, () -> control.set(10.5));
        control.set(200);
        assertInstanceOf(Integer.class, control.get());
    }

    @Test
    void nativeForgeConfigValueSavePreservesUnknownsAndStoredArmorPreference() throws Exception {
        Class<?> builderType = Class.forName("net.neoforged.neoforge.common.ModConfigSpec$Builder");
        Object builder = builderType.getConstructor().newInstance();
        Object ads =
                builderType
                        .getMethod(
                                "defineInRange",
                                String.class,
                                double.class,
                                double.class,
                                double.class)
                        .invoke(builder, "ads", 1.0, 0.0, 2.0);
        Object armor =
                builderType
                        .getMethod("define", String.class, boolean.class)
                        .invoke(builder, "display.armor", false);
        Object spec = builderType.getMethod("build").invoke(builder);
        Path file = directory.resolve("client.toml");
        Object config = parseToml("ads=1.0\n[display]\narmor=false\n");
        Class<?> loaded = Class.forName("net.neoforged.fml.config.IConfigSpec$ILoadedConfig");
        Object adapter =
                Proxy.newProxyInstance(
                        loaded.getClassLoader(),
                        new Class<?>[] {loaded},
                        (proxy, method, arguments) -> {
                            if (method.getName().equals("config")) return config;
                            if (method.getName().equals("save")) {
                                Files.writeString(file, writeToml(config));
                                return null;
                            }
                            return null;
                        });
        spec.getClass().getMethod("acceptConfig", loaded).invoke(spec, adapter);
        Files.writeString(
                file, "ads=1.0\nunknown=[1,2,3]\n[display]\narmor=false\ncustom=\"kept\"\n");
        armor.getClass().getMethod("set", Object.class).invoke(armor, true);
        ModSettingsBridge.saveToml(
                file,
                List.of("ads"),
                0.75,
                List.of("display", "armor"),
                () -> {
                    ads.getClass().getMethod("set", Object.class).invoke(ads, 0.75);
                    ads.getClass().getMethod("save").invoke(ads);
                });
        Object saved = parseToml(Files.readString(file));
        assertEquals(0.75, getToml(saved, List.of("ads")));
        assertEquals(false, getToml(saved, List.of("display", "armor")));
        assertEquals("kept", getToml(saved, List.of("display", "custom")));
        assertEquals(List.of(1, 2, 3), getToml(saved, List.of("unknown")));
        assertEquals(true, armor.getClass().getMethod("get").invoke(armor));
        spec.getClass()
                .getMethod("acceptConfig", loaded)
                .invoke(
                        spec,
                        Proxy.newProxyInstance(
                                loaded.getClassLoader(),
                                new Class<?>[] {loaded},
                                (proxy, method, arguments) ->
                                        method.getName().equals("config") ? saved : null));
        assertEquals(0.75, ads.getClass().getMethod("get").invoke(ads));
        assertEquals(false, armor.getClass().getMethod("get").invoke(armor));
        ModSettingsBridge.keepArmorHudEnabled(armor);
        assertEquals(true, armor.getClass().getMethod("get").invoke(armor));
        assertEquals(
                false, getToml(parseToml(Files.readString(file)), List.of("display", "armor")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void nativeForgeReloadingEventInvokesSingleArgumentCallback() throws Exception {
        String name = "fuzs.forgeconfigapiport.fabric.api.neoforge.v4.NeoForgeModConfigEvents";
        Class<?> api = Class.forName(name);
        Class<?> listener = Class.forName(name + "$Reloading");
        Event<Object> event =
                (Event<Object>)
                        api.getMethod("reloading", String.class)
                                .invoke(null, "blockfield-settings-check");
        AtomicInteger calls = new AtomicInteger();
        Consumer<Object> callback = config -> calls.incrementAndGet();
        event.register(
                MethodHandleProxies.asInterfaceInstance(
                        listener,
                        MethodHandles.lookup()
                                .findVirtual(
                                        Consumer.class,
                                        "accept",
                                        MethodType.methodType(void.class, Object.class))
                                .bindTo(callback)));
        listener.getMethod(
                        "onModConfigReloading", Class.forName("net.neoforged.fml.config.ModConfig"))
                .invoke(event.invoker(), new Object[] {null});
        assertEquals(1, calls.get());
    }

    @Test
    void nativeVoiceConfigEntrySetSaveSyncAndReload() throws Exception {
        Class<?> cfgType =
                Class.forName("de.maxhenkel.voicechat.configbuilder.CommentedPropertyConfig");
        Path file = directory.resolve("voicechat-client.properties");
        Files.writeString(file, "volume=1.0\nunknown=kept\n");
        Object builder = cfgType.getMethod("builder").invoke(null);
        builder.getClass().getMethod("path", Path.class).invoke(builder, file);
        Object config = builder.getClass().getMethod("build").invoke(builder);
        cfgType.getMethod("load").invoke(config);
        Object serializer =
                Class.forName(
                                "de.maxhenkel.voicechat.configbuilder.entry.serializer.DoubleSerializer")
                        .getField("INSTANCE")
                        .get(null);
        Class<?> serializerType =
                Class.forName(
                        "de.maxhenkel.voicechat.configbuilder.entry.serializer.ValueSerializer");
        Class<?> entryType =
                Class.forName("de.maxhenkel.voicechat.configbuilder.entry.DoubleConfigEntry");
        Object entry =
                entryType
                        .getConstructor(
                                cfgType,
                                serializerType,
                                String[].class,
                                String.class,
                                Double.class,
                                Double.class,
                                Double.class)
                        .newInstance(
                                config,
                                serializer,
                                new String[] {"Volume"},
                                "volume",
                                1.0,
                                0.0,
                                3.0);
        entryType.getMethod("reload").invoke(entry);
        Files.writeString(file, "volume=1.0\nunknown=kept\nexternal=42\n");
        ModSettingsBridge.seedVoiceUnknowns(entry, file);
        ModSettingsBridge.saveProperties(
                file,
                "volume",
                2.5,
                () -> {
                    entryType.getMethod("set", Object.class).invoke(entry, 2.5);
                    entryType.getMethod("saveSync").invoke(entry);
                });
        assertEquals(2.5, entryType.getMethod("get").invoke(entry));
        assertTrue(Files.readString(file).contains("unknown=kept"));
        assertTrue(Files.readString(file).contains("external=42"));
        cfgType.getMethod("reload").invoke(config);
        entryType.getMethod("reload").invoke(entry);
        assertEquals(2.5, entryType.getMethod("get").invoke(entry));
    }

    private static Object parseToml(String text) throws Exception {
        Object parser =
                Class.forName("com.electronwill.nightconfig.toml.TomlParser")
                        .getConstructor()
                        .newInstance();
        return parser.getClass().getMethod("parse", String.class).invoke(parser, text);
    }

    private static Object getToml(Object config, List<String> path) throws Exception {
        return Class.forName("com.electronwill.nightconfig.core.UnmodifiableConfig")
                .getMethod("get", List.class)
                .invoke(config, path);
    }

    private static String writeToml(Object config) throws Exception {
        Object writer =
                Class.forName("com.electronwill.nightconfig.toml.TomlWriter")
                        .getConstructor()
                        .newInstance();
        return (String)
                writer.getClass()
                        .getMethod(
                                "writeToString",
                                Class.forName(
                                        "com.electronwill.nightconfig.core.UnmodifiableConfig"))
                        .invoke(writer, config);
    }
}
