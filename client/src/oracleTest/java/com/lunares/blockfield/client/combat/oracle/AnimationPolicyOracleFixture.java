package com.lunares.blockfield.client.combat.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alrex.parcool.common.info.ClientSetting;
import com.alrex.parcool.common.network.payload.ClientInformationPayload;
import com.alrex.parcool.config.ParCoolConfig;
import com.lunares.blockfield.client.combat.AnimationPolicy;
import com.lunares.blockfield.oracle.OracleTransformAudit;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kotlin.jvm.functions.Function1;
import me.muksc.tacztweaks.config.Config;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

/**
 * U9p policy timing and widget lock on the transformed production classpath (ADR4 §6).
 *
 * <p>ParCool: pinned bf7 snapshots its ClientInfo inside {@code handleLogin}, after Fabric's play
 * INIT and before JOIN. The fixture fires the real Fabric events around the native snapshot/codec:
 * the encoding admitted by the server carries the forced values, the stored config keeps the user's
 * value, and disconnect restores it. TaCZ Tweaks: the native option builder disables the
 * smoother-crawl widget while forcing and leaves it editable after disconnect.
 */
class AnimationPolicyOracleFixture {
    static final List<ParCoolConfig.Client.Booleans> FORCED =
            List.of(
                    ParCoolConfig.Client.Booleans.EnableAnimation,
                    ParCoolConfig.Client.Booleans.EnableFallingAnimation,
                    ParCoolConfig.Client.Booleans.EnableLeanAnimationOfFastRun);
    static final ParCoolConfig.Client.Booleans PERSONAL =
            ParCoolConfig.Client.Booleans.EnableCameraAnimationOfRolling;
    static final com.lunares.blockfield.common.combat.AnimationPolicy SERVER_POLICY =
            new com.lunares.blockfield.common.combat.AnimationPolicy(
                    0,
                    com.lunares.blockfield.common.combat.AnimationTypes.PhaseDomain.A,
                    true,
                    true,
                    true,
                    true,
                    com.lunares.blockfield.common.combat.AnimationPolicy.registered());

    @Test
    void firstParCoolClientInfoCarriesForcedValues() {
        AnimSyncOracleRunner.run(
                "parcool",
                "policy-first-client-info",
                () -> {
                    requireMixin(
                            "com.alrex.parcool.config.ParCoolConfig$Client$Booleans",
                            "com.lunares.blockfield.client.mixin.AnimationPolicyParCoolMixin");
                    OracleTransformAudit.requireConfig("blockfield-client.mixins.json");
                    Object spec = ParCoolConfig.Client.getConfigSpec();
                    Method accept = loadInMemory(spec);
                    var result = new LinkedHashMap<String, Object>();
                    try {
                        for (var item : FORCED) item.set(false);
                        PERSONAL.set(false);
                        AnimationPolicy.restore();
                        AnimationPolicy.register();
                        // U9b scope: a server that sent no READY manifest never forces.
                        ClientPlayConnectionEvents.INIT.invoker().onPlayInit(null, null);
                        var unarmed = admitted();
                        result.put("unarmedInit", unarmed);
                        for (var item : FORCED)
                            assertEquals(false, unarmed.get(item.name()), "unarmed " + item);
                        ClientPlayConnectionEvents.DISCONNECT
                                .invoker()
                                .onPlayDisconnect(null, null);
                        // The READY manifest arms the server's policy during configuration.
                        AnimationPolicy.arm(SERVER_POLICY);
                        // Old order: before play INIT nothing forces, as JOIN-only installs saw it.
                        result.put("beforeInit", admitted());
                        ClientPlayConnectionEvents.INIT.invoker().onPlayInit(null, null);
                        var first = admitted();
                        result.put("firstAdmitted", first);
                        for (var item : FORCED) {
                            assertEquals(true, first.get(item.name()), item.name());
                            assertEquals(false, item.getInternalInstance().get(), "stored");
                        }
                        assertEquals(false, first.get(PERSONAL.name()), "personal stays personal");
                        ClientPlayConnectionEvents.DISCONNECT
                                .invoker()
                                .onPlayDisconnect(null, null);
                        var after = admitted();
                        result.put("afterDisconnect", after);
                        for (var item : FORCED)
                            assertEquals(false, after.get(item.name()), item.name());
                    } finally {
                        AnimationPolicy.restore();
                        accept.invoke(spec, (Object) null);
                    }
                    return result;
                });
    }

    /**
     * The native join-handler body ({@code readFromLocalConfig} then the payload) encoded and
     * decoded by the native codec, as the server admits it.
     */
    private static Map<String, Object> admitted() {
        var buffer = Unpooled.buffer();
        ClientInformationPayload.CODEC.encode(
                buffer,
                new ClientInformationPayload(
                        new UUID(1, 2), true, ClientSetting.readFromLocalConfig()));
        var view = new LinkedHashMap<String, Object>();
        view.put("bytes", ByteBufUtil.hexDump(buffer));
        var decoded = ClientInformationPayload.CODEC.decode(buffer).information();
        for (var item : FORCED) view.put(item.name(), decoded.get(item));
        view.put(PERSONAL.name(), decoded.get(PERSONAL));
        return view;
    }

    /**
     * Loads ParCool's real client spec over an in-memory config, so stored values exist without a
     * file. Returns {@code acceptConfig} for unloading. NightConfig is runtime-only here.
     */
    private static Method loadInMemory(Object spec) throws Exception {
        var loader = spec.getClass().getClassLoader();
        Object stored =
                Class.forName("com.electronwill.nightconfig.core.CommentedConfig", true, loader)
                        .getMethod("inMemory")
                        .invoke(null);
        var loadedType = Class.forName("net.neoforged.fml.config.IConfigSpec$ILoadedConfig");
        Object loaded =
                Proxy.newProxyInstance(
                        loadedType.getClassLoader(),
                        new Class<?>[] {loadedType},
                        (proxy, method, args) ->
                                switch (method.getName()) {
                                    case "config" -> stored;
                                    case "hashCode" -> System.identityHashCode(proxy);
                                    case "equals" -> proxy == args[0];
                                    case "toString" -> "oracle-in-memory";
                                    default -> null;
                                });
        Method accept = spec.getClass().getMethod("acceptConfig", loadedType);
        accept.invoke(spec, loaded);
        return accept;
    }

    @Test
    void tweaksSmootherCrawlWidgetLocksWhileForced() {
        AnimSyncOracleRunner.run(
                "tacz",
                "policy-tweaks-widget-lock",
                () -> {
                    Object entry = VISUALS.getMethod("getSmootherCrawlAnimation").invoke(visuals());
                    yacl("config.v3.ConfigEntry", "set", Object.class).invoke(entry, false);
                    var result = new LinkedHashMap<String, Object>();
                    try {
                        AnimationPolicy.arm(SERVER_POLICY);
                        AnimationPolicy.install();
                        var locked = smootherWidget();
                        result.put("forcing", locked);
                        assertEquals(false, locked.get("available"), "widget locked");
                        assertEquals(false, locked.get("pendingValue"), "shows the stored value");
                        assertEquals(true, smootherGetter(), "getter forced");
                    } finally {
                        AnimationPolicy.restore();
                    }
                    // Config transforms on first load, so the audit is checked after building.
                    requireMixin(
                            "me.muksc.tacztweaks.config.Config",
                            "com.lunares.blockfield.client.mixin.tweaks.AnimationPolicyTweaksScreenMixin");
                    OracleTransformAudit.requireConfig("blockfield-client-tweaks.mixins.json");
                    var open = smootherWidget();
                    result.put("disconnected", open);
                    assertEquals(true, open.get("available"), "widget editable again");
                    assertEquals(false, smootherGetter(), "stored value governs again");
                    return result;
                });
    }

    /**
     * Builds the smoother-crawl option exactly as the native screen does: the screen's property
     * reference and its own option lambda through the private {@code option(KProperty0)} builder.
     */
    private static Map<String, Object> smootherWidget() throws Exception {
        var groupBuilder = yaclClass("api.OptionGroup").getMethod("createBuilder").invoke(null);
        yacl("api.OptionGroup$Builder", "name", Component.class)
                .invoke(groupBuilder, Component.literal("visuals"));
        var group =
                Config.OptionGroupBuilder.class.getConstructors()[0].newInstance(
                        "audioAndVisuals.visuals", groupBuilder);
        var propertyType = tweaksClass("Config$generateConfigScreen$1$4$3$7");
        var ctor = propertyType.getDeclaredConstructor(Object.class);
        ctor.setAccessible(true);
        var property = ctor.newInstance(visuals());
        Method nativeSetup =
                Config.class.getDeclaredMethod(
                        "generateConfigScreen$lambda$0$3$2$3", Config.OptionBuilder.class);
        nativeSetup.setAccessible(true);
        Function1<Object, Object> setup = builder -> invoke(nativeSetup, builder);
        Method option =
                Config.class.getDeclaredMethod(
                        "option",
                        Config.OptionGroupBuilder.class,
                        kotlin.reflect.KProperty0.class,
                        boolean.class,
                        Function1.class);
        option.setAccessible(true);
        option.invoke(Config.INSTANCE, group, property, false, setup);
        Object built = yacl("api.OptionGroup$Builder", "build").invoke(groupBuilder);
        var options = (List<?>) yacl("api.OptionGroup", "options").invoke(built);
        assertEquals(1, options.size());
        Object widget = options.getFirst();
        var view = new LinkedHashMap<String, Object>();
        view.put("available", yacl("api.Option", "available").invoke(widget));
        view.put("pendingValue", yacl("api.Option", "pendingValue").invoke(widget));
        String name = yacl("api.Option", "name").invoke(widget).toString();
        view.put("name", name);
        assertTrue(name.contains("smoother"), "built the smoother-crawl option: " + name);
        return view;
    }

    // Visuals extends YACL's CodecConfig, which only the runtime classpath has.
    static final Class<?> VISUALS = tweaksClass("Config$AudioAndVisuals$Visuals");

    private static Object visuals() throws ReflectiveOperationException {
        return VISUALS.getField("INSTANCE").get(null);
    }

    /** The hooked static getter every native renderer read goes through. */
    private static Object smootherGetter() throws ReflectiveOperationException {
        return VISUALS.getMethod("smootherCrawlAnimation").invoke(null);
    }

    private static Class<?> tweaksClass(String name) {
        try {
            return Class.forName(
                    "me.muksc.tacztweaks.config." + name,
                    false,
                    AnimationPolicyOracleFixture.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object invoke(Method method, Object arg) {
        try {
            return method.invoke(null, arg);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    // YACL is runtime-only on the oracle classpath; its public API interfaces are stable names.
    private static Class<?> yaclClass(String name) throws ClassNotFoundException {
        return Class.forName("dev.isxander.yacl3." + name, true, Config.class.getClassLoader());
    }

    private static Method yacl(String iface, String name, Class<?>... types)
            throws ReflectiveOperationException {
        return yaclClass(iface).getMethod(name, types);
    }

    private static void requireMixin(String target, String mixin) {
        boolean applied =
                OracleTransformAudit.applied().stream()
                        .filter(row -> target.equals(row.get("target")))
                        .flatMap(row -> ((List<?>) row.get("mixins")).stream())
                        .anyMatch(m -> mixin.equals(((Map<?, ?>) m).get("mixin")));
        assertTrue(applied, mixin + " did not apply to " + target);
    }
}
