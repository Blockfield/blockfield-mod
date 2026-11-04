package com.lunares.blockfield.oracle;

import java.lang.reflect.Field;
import net.fabricmc.loader.api.FabricLoader;

/** Reflection strings are not rewritten when Loom remaps the fixture bytecode. */
public final class OracleReflection {
    private OracleReflection() {}

    public static Field field(Class<?> owner, String namedField, String namedDescriptor)
            throws NoSuchFieldException {
        var mappings = FabricLoader.getInstance().getMappingResolver();
        String namedOwner = mappings.unmapClassName("named", owner.getName());
        return owner.getDeclaredField(
                mappings.mapFieldName("named", namedOwner, namedField, namedDescriptor));
    }

    /** A declared method by its named (mojmap) name and descriptor, accessible. */
    public static java.lang.reflect.Method method(
            Class<?> owner, String namedMethod, String namedDescriptor, Class<?>... parameters)
            throws NoSuchMethodException {
        var mappings = FabricLoader.getInstance().getMappingResolver();
        String namedOwner = mappings.unmapClassName("named", owner.getName());
        var method =
                owner.getDeclaredMethod(
                        mappings.mapMethodName("named", namedOwner, namedMethod, namedDescriptor),
                        parameters);
        method.setAccessible(true);
        return method;
    }
}
