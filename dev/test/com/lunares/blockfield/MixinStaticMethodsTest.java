package com.lunares.blockfield;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

/** Reads compiled bytes without loading mixins or starting Minecraft. Runs on both sides. */
class MixinStaticMethodsTest {
    @Test
    void registeredClassMixinsHaveNoNonPrivateStaticMethods() throws IOException {
        var violations = new ArrayList<String>();
        for (var entry : json("fabric.mod.json").getAsJsonArray("mixins")) {
            String resource = entry.getAsString();
            JsonObject config = json(resource);
            String namespace = config.get("package").getAsString().replace('.', '/');
            for (String side : List.of("mixins", "client", "server")) {
                if (!config.has(side)) continue;
                for (var name : config.getAsJsonArray(side)) {
                    String path = namespace + "/" + name.getAsString().replace('.', '/') + ".class";
                    try (var input = getClass().getResourceAsStream("/" + path)) {
                        assertNotNull(input, path);
                        var node = new ClassNode();
                        new ClassReader(input).accept(node, ClassReader.SKIP_CODE);
                        // Accessor/Invoker interfaces support public static accessor methods.
                        if ((node.access & Opcodes.ACC_INTERFACE) != 0) continue;
                        for (var method : node.methods) {
                            if ((method.access & Opcodes.ACC_STATIC) != 0
                                    && (method.access
                                                    & (Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC))
                                            == 0
                                    && !method.name.equals("<clinit>")
                                    && !method.name.startsWith("lambda$")) {
                                violations.add(
                                        resource
                                                + ":"
                                                + node.name
                                                + "."
                                                + method.name
                                                + method.desc);
                            }
                        }
                    }
                }
            }
        }
        assertEquals(List.of(), violations, "Mixin rejects non-private static class methods");
    }

    private JsonObject json(String resource) throws IOException {
        try (var input = getClass().getResourceAsStream("/" + resource)) {
            assertNotNull(input, resource);
            return JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }
}
