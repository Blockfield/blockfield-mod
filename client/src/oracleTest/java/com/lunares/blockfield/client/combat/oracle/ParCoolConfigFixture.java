package com.lunares.blockfield.client.combat.oracle;

import com.alrex.parcool.config.ParCoolConfig;
import net.neoforged.fml.config.IConfigSpec;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * A corrected in-memory ParCool client config for native callbacks that read unforced settings;
 * sounds are off so a mocked remote player needs no audio. Restores the previous config.
 */
final class ParCoolConfigFixture implements AutoCloseable {
    private final ModConfigSpec spec;
    private final IConfigSpec.ILoadedConfig previous;

    private ParCoolConfigFixture(ModConfigSpec spec, IConfigSpec.ILoadedConfig previous) {
        this.spec = spec;
        this.previous = previous;
    }

    static ParCoolConfigFixture inMemory() throws ReflectiveOperationException {
        var spec = ParCoolConfig.Client.getConfigSpec();
        var loaded = ModConfigSpec.class.getDeclaredField("loadedConfig");
        loaded.setAccessible(true);
        var previous = (IConfigSpec.ILoadedConfig) loaded.get(spec);
        // NightConfig is a production JiJ dependency, deliberately not another compile pin.
        var values =
                Class.forName("com.electronwill.nightconfig.core.CommentedConfig")
                        .getMethod("inMemory")
                        .invoke(null);
        ModConfigSpec.class
                .getMethod(
                        "correct",
                        Class.forName("com.electronwill.nightconfig.core.CommentedConfig"))
                .invoke(spec, values);
        spec.acceptConfig(
                (IConfigSpec.ILoadedConfig)
                        java.lang.reflect.Proxy.newProxyInstance(
                                ParCoolConfigFixture.class.getClassLoader(),
                                new Class<?>[] {IConfigSpec.ILoadedConfig.class},
                                (proxy, method, args) ->
                                        switch (method.getName()) {
                                            case "config" -> values;
                                            case "toString" -> "U5 in-memory config";
                                            default ->
                                                    throw new AssertionError(
                                                            "Unexpected config operation: "
                                                                    + method.getName());
                                        }));
        ParCoolConfig.Client.Booleans.EnableActionSounds.set(false);
        return new ParCoolConfigFixture(spec, previous);
    }

    @Override
    public void close() {
        spec.acceptConfig(previous);
    }
}
