package com.lunares.blockfield.client.combat.oracle;

import java.util.List;

/**
 * Boots the production client mod list through Fabric Loader's Knot without starting the game, then
 * runs the oracle fixtures with the transformed class loader.
 *
 * <p>Usage from the {@code oracleTest} task: the task classpath carries the production intermediary
 * 1.21.1 merged Minecraft, loader 0.19.3 (the version the production pack runs; LR bf2 and Create
 * bf4 refuse anything older), the production mod jars and the compiled oracle classes. This main
 * boots Knot in-process ({@code new Knot(CLIENT).init(args)} loads mods, registers production mixin
 * configs and runs {@code preLaunch} entrypoints, but never invokes the game main, so no GL
 * context, window or game start happens), sets the transformed marker and delegates to {@link
 * TransformedOracleSession} loaded from the transformed class loader.
 */
public final class TransformedOracleMain {
    private TransformedOracleMain() {}

    public static void main(String[] args) {
        try {
            execute(args);
        } catch (Throwable failure) {
            failure.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void execute(String[] args) throws Exception {
        var knot = new net.fabricmc.loader.impl.launch.knot.Knot(net.fabricmc.api.EnvType.CLIENT);
        ClassLoader transformed = knot.init(args);
        knot.addToClassPath(
                java.nio.file.Path.of(
                        TransformedOracleMain.class
                                .getProtectionDomain()
                                .getCodeSource()
                                .getLocation()
                                .toURI()));
        Thread.currentThread().setContextClassLoader(transformed);
        requireBlockfieldForks(transformed);
        Class<?> session =
                Class.forName(
                        "com.lunares.blockfield.client.combat.oracle.TransformedOracleSession",
                        true,
                        transformed);
        int failures = (int) session.getMethod("run").invoke(null);
        if (failures != 0) {
            System.err.println("transformed oracle: " + failures + " fixture(s) failed");
            System.exit(1);
        }
        System.out.println("transformed oracle: all fixtures passed");
        System.exit(0);
    }

    /** Mod ids that must be loaded in the client oracle JVM (fail loudly otherwise). */
    static List<String> requiredModIds() {
        return List.of(
                "minecraft",
                "fabricloader",
                "fabric-api",
                "blockfield",
                "blockfield_decor",
                "playeranimator",
                "tacz",
                "lrtactical",
                "parcool",
                "superbwarfare",
                "create",
                "accessories",
                "vanillabackport",
                "tacztweaks");
    }

    /** Production mixin configs that must be present in the transformed JVM. */
    static List<String> requiredMixinConfigs() {
        return List.of(
                "blockfield-client.mixins.json",
                "playerAnimator-common.mixins.json",
                "tacz.mixins.json",
                "tacz.fabric.mixins.json",
                "lrtactical.mixins.json",
                "parcool.mixins.json",
                "mixins.superbwarfare.json",
                "create.mixins.json",
                "accessories-fabric.mixins.json",
                "accessories-common.mixins.json",
                "vanillabackport.mixins.json",
                "tacztweaks.mixins.json",
                "tacztweaks-fabric.mixins.json");
    }

    /** Renderer mods: included when they boot headless, otherwise recorded unavailable. */
    static List<String> optionalModIds() {
        return List.of("sodium", "iris", "entityculling", "immediatelyfast");
    }

    /**
     * The bf8 TaCZ fork carries Blockfield-only packages that upstream lacks; requiring one of them
     * proves the oracle runs the production fork, not the same-versioned upstream artifact.
     */
    static void requireBlockfieldForks(ClassLoader transformed) throws Exception {
        for (var name :
                List.of(
                        "cn.sh1rocu.tacz.mixin.client.AbstractPlayerMixin",
                        "com.lunares.blockfield.client.mixin.CombatLivingInterpolationMixin")) {
            try {
                if (transformed.getResource(name.replace('.', '/') + ".class") == null)
                    throw new ClassNotFoundException(name);
            } catch (ClassNotFoundException missing) {
                throw new AssertionError(
                        "oracle is not running the production mod list: " + name + " is missing",
                        missing);
            }
        }
    }
}
