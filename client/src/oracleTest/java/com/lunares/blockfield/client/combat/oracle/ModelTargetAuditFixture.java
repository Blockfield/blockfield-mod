package com.lunares.blockfield.client.combat.oracle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Lists every production mixin that touches the four oracle model/render classes, read from the
 * transformed JVM's own mixin configs and {@code @Mixin} targets (not from documentation).
 *
 * <p>Family units extend the {@link #ORACLE_TARGETS} set only with a reviewed schema change; an
 * unlisted class is outside the exact cohort and cannot silently join it.
 */
public final class ModelTargetAuditFixture {
    /** Model/render classes whose mixins must be enumerated for the mixed renderer gate. */
    static final List<String> ORACLE_TARGETS =
            List.of(
                    net.minecraft.client.model.HumanoidModel.class.getName(),
                    net.minecraft.client.model.PlayerModel.class.getName(),
                    net.minecraft.client.renderer.entity.LivingEntityRenderer.class.getName(),
                    net.minecraft.client.renderer.entity.player.PlayerRenderer.class.getName());

    @Test
    void modelMixinsAreEnumerated() {
        var touched =
                AnimSyncOracleRunner.run(
                        "vanilla",
                        "model-target-audit",
                        () -> {
                            var result = new LinkedHashMap<String, Object>();
                            var scan = scan(ModelTargetAuditFixture.class.getClassLoader());
                            for (String config :
                                    List.of(
                                            "accessories-common.mixins.json",
                                            "accessories-fabric.mixins.json",
                                            "create.mixins.json",
                                            "vanillabackport.mixins.json",
                                            "mixins.iris.json",
                                            "sodium-common.mixins.json",
                                            "entityculling.mixins.json",
                                            "immediatelyfast-common.mixins.json"))
                                com.lunares.blockfield.oracle.OracleTransformAudit.requireConfig(
                                        config);
                            result.put("oracleTargetCount", scan.size());
                            var names = new ArrayList<String>();
                            for (var row : scan)
                                names.add(
                                        row.get("config")
                                                + " -> "
                                                + row.get("mixin")
                                                + " -> "
                                                + row.get("target"));
                            result.put("touchingMixins", names);
                            return result;
                        });
        @SuppressWarnings("unchecked")
        var names = (List<String>) touched.get("touchingMixins");
        if (names.isEmpty())
            throw new AssertionError(
                    "no production mixin touches the oracle model targets:"
                            + " the transformed mod list is not applied");
    }

    /** One row per (mixin config, mixin class, oracle target) touching an oracle target class. */
    static List<Map<String, Object>> scan(ClassLoader knot) {
        var rows = new ArrayList<Map<String, Object>>();
        for (var modEntry : TransformedOracleSession.modMixinConfigs(knot).entrySet()) {
            if (modEntry.getKey().startsWith("unreadable:")) continue;
            for (var config : modEntry.getValue()) {
                String pkg = mixinPackage(knot, config);
                for (var mixin : mixinEntries(knot, config)) {
                    String namespaced = mixin.startsWith(pkg) ? mixin : pkg + mixin;
                    for (var target :
                            TransformedOracleSession.mixinEntryTargets(knot, namespaced)) {
                        String dotted = target.replace('/', '.').replace('$', '.');
                        boolean oracle =
                                ORACLE_TARGETS.stream()
                                        .anyMatch(
                                                want ->
                                                        dotted.equals(want)
                                                                || dotted.endsWith(
                                                                        "."
                                                                                + want.substring(
                                                                                        want
                                                                                                        .lastIndexOf(
                                                                                                                '.')
                                                                                                + 1)));
                        if (!oracle) continue;
                        var row = new LinkedHashMap<String, Object>();
                        row.put("modId", modEntry.getKey());
                        row.put("config", config);
                        row.put("mixin", namespaced);
                        row.put("target", dotted);
                        rows.add(row);
                    }
                }
            }
        }
        rows.sort(
                (a, b) ->
                        (a.get("target") + "/" + a.get("mixin"))
                                .compareTo(b.get("target") + "/" + b.get("mixin")));
        return rows;
    }

    private static String mixinPackage(ClassLoader knot, String config) {
        try (var in = knot.getResourceAsStream(config)) {
            if (in == null) return "";
            var root =
                    com.google.gson.JsonParser.parseString(
                            new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            if (root.isJsonObject() && root.getAsJsonObject().has("package"))
                return root.getAsJsonObject().get("package").getAsString() + ".";
        } catch (Exception ignored) {
            // Best effort: entries may already be fully qualified.
        }
        return "";
    }

    private static List<String> mixinEntries(ClassLoader knot, String config) {
        var entries = new ArrayList<String>();
        try (var in = knot.getResourceAsStream(config)) {
            if (in == null) return entries;
            var root =
                    com.google.gson.JsonParser.parseString(
                            new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            if (!root.isJsonObject()) return entries;
            var json = root.getAsJsonObject();
            for (var section : List.of("client", "server", "mixins")) {
                if (!json.has(section)) continue;
                for (var entry : json.getAsJsonArray(section)) entries.add(entry.getAsString());
            }
        } catch (Exception ignored) {
            // Best effort only; required configs are asserted in checkEnvironment.
        }
        return entries;
    }
}
