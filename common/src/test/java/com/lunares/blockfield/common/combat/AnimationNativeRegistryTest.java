package com.lunares.blockfield.common.combat;

import static org.junit.jupiter.api.Assertions.*;

import com.lunares.blockfield.common.combat.AnimationTypes.*;
import java.util.*;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

class AnimationNativeRegistryTest {
    @Test
    void actualPinnedEaseFirstPersonAndOptionEnumsMatchSchema() {
        assertEquals(names(Ease.values()), names(dev.kosmx.playerAnim.core.util.Ease.values()));
        assertEquals(
                names(FirstPersonMode.values()),
                names(dev.kosmx.playerAnim.api.firstPerson.FirstPersonMode.values()));
        assertEquals(
                names(AnimationPart.values()),
                names(com.alrex.parcool.api.unstable.animation.AnimationPart.values()));
    }

    private static Set<String> names(Enum<?>[] values) {
        Set<String> names = new TreeSet<>();
        for (var value : values) names.add(value.name());
        return names;
    }

    @Test
    void pinnedEaseIdsMatchNativeGetIdByteValues() {
        // Native PA Ease ids are explicit bytes (0, 1, 6..37), not ordinals: any pin change that
        // moves an id must fail here before U3 assumes ordinal() == id on the wire.
        var nativeByName = new TreeMap<String, Byte>();
        for (var constant : dev.kosmx.playerAnim.core.util.Ease.values())
            nativeByName.put(constant.name(), constant.getId());
        assertEquals(34, nativeByName.size());
        var schemaByName = new TreeMap<String, Integer>();
        for (var value : Ease.values()) schemaByName.put(value.name(), value.id());
        assertEquals(nativeByName.keySet(), schemaByName.keySet());
        for (var name : nativeByName.keySet())
            assertEquals(nativeByName.get(name).intValue(), schemaByName.get(name), "Ease." + name);
        assertEquals(0, (int) schemaByName.get("LINEAR"));
        assertEquals(1, (int) schemaByName.get("CONSTANT"));
        assertEquals(6, (int) schemaByName.get("INSINE"));
        assertEquals(37, (int) schemaByName.get("STEP"));
        var ids = new HashSet<>(schemaByName.values());
        assertEquals(34, ids.size());
        for (int missing : new int[] {2, 3, 4, 5}) assertFalse(ids.contains(missing));
    }

    @Test
    void pinnedAnimatorAndActionRegistriesMatchClosedTagsInNativeOrder() throws Exception {
        try (var jar = new ZipFile(System.getProperty("blockfield.test.parcoolJar"))) {
            var animators = classes(jar, "com/alrex/parcool/client/animation/AnimatorList.class");
            assertEquals(26, animators.size());
            var actual =
                    animators.stream()
                            .map(n -> n.substring(n.lastIndexOf('/') + 1).replace("Animator", ""))
                            .map(n -> n.equals("DiveAnimationHost") ? "DIVE_HOST" : snake(n))
                            .toList();
            assertEquals(AnimationPolicy.registered().stream().map(Enum::name).toList(), actual);
            var actions =
                    classes(jar, "com/alrex/parcool/common/action/Actions.class").stream()
                            .map(n -> snake(n.substring(n.lastIndexOf('/') + 1)))
                            .toList();
            assertEquals(Arrays.stream(ActionTag.values()).map(Enum::name).toList(), actions);
            String dead = "com/alrex/parcool/client/animation/impl/ExaggeratedSideDodgeAnimator";
            for (var entry : Collections.list(jar.entries()))
                if (entry.getName().endsWith(".class")) {
                    var node = read(jar, entry.getName());
                    for (var method : node.methods)
                        for (var instruction : method.instructions)
                            if (instruction instanceof TypeInsnNode type
                                    && type.getOpcode() == org.objectweb.asm.Opcodes.NEW)
                                assertNotEquals(
                                        dead,
                                        type.desc,
                                        entry.getName() + " constructs declared-only animator");
                }
        }
    }

    private static String snake(String value) {
        return value.replaceAll("(?<=[a-z])(?=[A-Z])", "_").toUpperCase(Locale.ROOT);
    }

    private static List<String> classes(ZipFile jar, String path) throws Exception {
        List<String> types = new ArrayList<>();
        for (var method : read(jar, path).methods)
            if (method.name.equals("<clinit>"))
                for (var instruction : method.instructions)
                    if (instruction instanceof LdcInsnNode ldc && ldc.cst instanceof Type type)
                        types.add(type.getInternalName());
        return List.copyOf(types);
    }

    private static ClassNode read(ZipFile jar, String path) throws Exception {
        var node = new ClassNode();
        try (var stream = jar.getInputStream(Objects.requireNonNull(jar.getEntry(path)))) {
            new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return node;
    }
}
