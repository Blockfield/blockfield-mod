package com.lunares.blockfield.client.combat.oracle;

import static org.junit.jupiter.api.Assertions.*;

import com.alrex.parcool.common.info.ClientSetting;
import com.alrex.parcool.common.network.payload.ClientInformationPayload;
import com.alrex.parcool.config.ParCoolConfig;
import com.lunares.blockfield.client.combat.AnimationPolicy;
import com.lunares.blockfield.oracle.OracleTransformAudit;
import io.netty.buffer.Unpooled;
import java.util.Map;
import java.util.UUID;
import net.neoforged.fml.config.IConfigSpec;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.junit.jupiter.api.Test;

/** Proves the bf7 snapshot timing hazard and U9a getter/actual native wire path separately. */
class ParCoolPolicyOracleFixture {
    @Test
    void forcedGetterFlowsThroughFreshNativeClientInfoButBf7RetainsPreJoinSnapshot() {
        AnimSyncOracleRunner.run(
                "parcool",
                "policy-native-client-info-wire",
                () -> {
                    var spec = ParCoolConfig.Client.getConfigSpec();
                    var loaded = ModConfigSpec.class.getDeclaredField("loadedConfig");
                    loaded.setAccessible(true);
                    var previous = (IConfigSpec.ILoadedConfig) loaded.get(spec);
                    var previousPolicy = AnimationPolicy.forcing();
                    // NightConfig is a production JiJ dependency, deliberately not another compile
                    // pin.
                    var values =
                            Class.forName("com.electronwill.nightconfig.core.CommentedConfig")
                                    .getMethod("inMemory")
                                    .invoke(null);
                    ModConfigSpec.class
                            .getMethod(
                                    "correct",
                                    Class.forName(
                                            "com.electronwill.nightconfig.core.CommentedConfig"))
                            .invoke(spec, values);
                    var inMemory =
                            (IConfigSpec.ILoadedConfig)
                                    java.lang.reflect.Proxy.newProxyInstance(
                                            getClass().getClassLoader(),
                                            new Class<?>[] {IConfigSpec.ILoadedConfig.class},
                                            (proxy, method, args) ->
                                                    switch (method.getName()) {
                                                        case "config" -> values;
                                                        case "toString" -> "U5 in-memory config";
                                                        default ->
                                                                throw new AssertionError(
                                                                        "Unexpected config operation: "
                                                                                + method.getName());
                                                    });
                    var items =
                            new ParCoolConfig.Client.Booleans[] {
                                ParCoolConfig.Client.Booleans.EnableAnimation,
                                ParCoolConfig.Client.Booleans.EnableFallingAnimation,
                                ParCoolConfig.Client.Booleans.EnableLeanAnimationOfFastRun
                            };
                    try {
                        spec.acceptConfig(inMemory);
                        AnimationPolicy.restore();
                        for (var item : items) item.set(false);
                        ClientSetting beforeJoin = ClientSetting.readFromLocalConfig();
                        for (var item : items) assertFalse(beforeJoin.get(item));
                        {
                            AnimationPolicy.arm(
                                    com.lunares.blockfield.oracle.ParCoolProjectionCases.POLICY);
                            AnimationPolicy.install();
                        }
                        for (var item : items) {
                            assertTrue(item.get(), "production getter override must execute");
                            assertFalse(
                                    beforeJoin.get(item), "pinned bf7 retains the pre-JOIN value");
                        }
                        var fresh = ClientSetting.readFromLocalConfig();
                        var buffer = Unpooled.buffer();
                        try {
                            ClientInformationPayload.CODEC.encode(
                                    buffer,
                                    new ClientInformationPayload(new UUID(0, 1), true, fresh));
                            var decoded = ClientInformationPayload.CODEC.decode(buffer);
                            for (var item : items) assertTrue(decoded.information().get(item));
                            assertFalse(buffer.isReadable());
                        } finally {
                            buffer.release();
                        }
                        AnimationPolicy.restore();
                        for (var item : items)
                            assertFalse(item.get(), "stored false survives disconnect");
                        OracleTransformAudit.requireConfig("blockfield-client.mixins.json");
                        return Map.of(
                                "forcedGetterAndFreshWire", true,
                                "storedValuesRestored", true,
                                "bf7PreJoinSnapshotGap", true,
                                "exactPolicyCohortAcceptance", false,
                                "requiredPair",
                                        "U5 fork Local ClientSetting rereads three live getters; server lean is policy-forced; U9 must promote the verified fork pin");
                    } finally {
                        spec.acceptConfig(previous);
                        if (previousPolicy) {
                            AnimationPolicy.arm(
                                    com.lunares.blockfield.oracle.ParCoolProjectionCases.POLICY);
                            AnimationPolicy.install();
                        } else AnimationPolicy.restore();
                    }
                });
    }
}
