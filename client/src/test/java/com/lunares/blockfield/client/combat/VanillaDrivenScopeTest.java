package com.lunares.blockfield.client.combat;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

/**
 * U2 driven-velocity scope (ADR4 §4.2): the R pair at p_R feeds {@code getDeltaMovementLerped} for
 * driven remotes, never the ambient p_A. Also pins the override mixin's injection target so a
 * Mojang signature change fails here instead of silently detaching it.
 */
class VanillaDrivenScopeTest {
    @AfterEach
    void clear() {
        VanillaDrivenScope.clearAll();
    }

    @Test
    void undrivenEntitiesRunTheNativePath() {
        assertNull(VanillaDrivenScope.frame(7));
        assertNull(VanillaDrivenScope.lerpedVelocity(7));
    }

    @Test
    void drivenVelocityIgnoresTheAmbientPartial() {
        var frame = new VanillaDrivenScope.DrivenFrame(new Vec3(1, 2, 3), new Vec3(4, 6, 8), 0.5f);
        VanillaDrivenScope.install(7, frame);
        // (2.5, 4, 5.5): the stored p_R decides, whatever the renderer passes as p.
        assertEquals(new Vec3(2.5, 4, 5.5), VanillaDrivenScope.lerpedVelocity(7));
        assertEquals(new Vec3(2.5, 4, 5.5), VanillaDrivenScope.frame(7).lerped());
        VanillaDrivenScope.clear(7);
        assertNull(VanillaDrivenScope.lerpedVelocity(7));
    }

    @Test
    void frameValidationFailsClosed() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new VanillaDrivenScope.DrivenFrame(new Vec3(1, 2, 3), new Vec3(4, 6, 8), 2));
        assertThrows(
                IllegalArgumentException.class,
                () -> new VanillaDrivenScope.DrivenFrame(null, Vec3.ZERO, 0.5f));
    }

    @Test
    void velocityOverrideInjectionTargetIsPinned() throws Exception {
        ClassNode mixin;
        try (var in =
                VanillaDrivenScopeTest.class.getResourceAsStream(
                        "/com/lunares/blockfield/client/mixin/CombatPlayerVelocityMixin.class")) {
            assertNotNull(in, "mixin class");
            mixin = new ClassNode();
            new ClassReader(in).accept(mixin, 0);
        }
        var injects = new ArrayList<AnnotationNode>();
        mixin.methods.forEach(
                method -> {
                    if (method.visibleAnnotations != null)
                        method.visibleAnnotations.stream()
                                .filter(a -> a.desc.endsWith("/Inject;"))
                                .forEach(injects::add);
                    if (method.invisibleAnnotations != null)
                        method.invisibleAnnotations.stream()
                                .filter(a -> a.desc.endsWith("/Inject;"))
                                .forEach(injects::add);
                });
        assertEquals(1, injects.size(), "exactly one velocity override is reviewed");
        var values = injects.getFirst().values;
        Object method = null, cancellable = null, require = null;
        for (int i = 0; i < values.size(); i += 2)
            switch ((String) values.get(i)) {
                case "method" -> method = values.get(i + 1);
                case "cancellable" -> cancellable = values.get(i + 1);
                case "require" -> require = values.get(i + 1);
                default -> {}
            }
        assertEquals(java.util.List.of("getDeltaMovementLerped"), method);
        assertEquals(true, cancellable);
        assertEquals(1, require);
        // The native signature the override replaces; a Mojang change fails here first.
        var target =
                net.minecraft.client.player.AbstractClientPlayer.class.getMethod(
                        "getDeltaMovementLerped", float.class);
        assertEquals(Vec3.class, target.getReturnType());
    }
}
