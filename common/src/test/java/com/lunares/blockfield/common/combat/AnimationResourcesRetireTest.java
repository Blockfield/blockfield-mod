package com.lunares.blockfield.common.combat;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Retire cost (server#323 sub-item): one store scan, not one per generation. */
class AnimationResourcesRetireTest {
    private static final AnimationResources.Digest ZERO = new AnimationResources.Digest(0, 0, 0, 0);

    static List<AnimationResources.Definition> catalog(int generations, int perGen) {
        var defs = new ArrayList<AnimationResources.Definition>();
        for (long g = 0; g < generations; g++) {
            var root = new AnimationResources.DefinitionRef(g, 0, 0, ZERO);
            defs.add(
                    new AnimationResources.Definition(
                            root, new AnimationResources.Bytes(new byte[8]), List.of()));
            for (int i = 1; i < perGen; i++)
                defs.add(
                        new AnimationResources.Definition(
                                new AnimationResources.DefinitionRef(g, 0, i, ZERO),
                                new AnimationResources.Bytes(new byte[8]),
                                List.of(root)));
        }
        return defs;
    }

    static List<AnimationResources.ResourceRef> liveRefs(int generations, int perGen) {
        long g = generations - 1;
        return List.of(
                new AnimationResources.DefinitionRef(g, 0, 0, ZERO),
                new AnimationResources.DefinitionRef(g, 0, perGen - 1, ZERO));
    }

    static AnimationResources.Store filled(List<AnimationResources.Definition> defs) {
        var store = new AnimationResources.Store();
        store.installAll(defs);
        return store;
    }

    static java.util.Set<AnimationResources.ResourceRef> remaining(
            AnimationResources.Store store, List<AnimationResources.Definition> defs) {
        var out = new HashSet<AnimationResources.ResourceRef>();
        for (var d : defs) {
            try {
                store.get(d.ref());
                out.add(d.ref());
            } catch (IllegalArgumentException gone) {
                // retired
            }
        }
        return out;
    }

    @Test
    void singlePassMatchesPerGenerationLoop() {
        var defs = catalog(3, 4);
        var refs = liveRefs(3, 4);

        var looped = filled(defs);
        var generations = new HashSet<Long>();
        for (var d : defs) generations.add(d.ref().resourceGeneration());
        try (var lease = looped.pin(refs)) {
            for (long generation : generations) looped.retireUnpinned(generation);
        }

        var single = filled(defs);
        try (var lease = single.pin(refs)) {
            single.retireUnpinned();
        }

        assertEquals(remaining(looped, defs), remaining(single, defs));
        assertEquals(new HashSet<>(refs), remaining(single, defs));
    }

    @Test
    void retiresUnreferencedDefinitionsOfLiveGeneration() {
        var defs = catalog(2, 4);
        var refs = List.of(liveRefs(2, 4).get(0));
        var store = filled(defs);
        try (var lease = store.pin(refs)) {
            store.retireUnpinned();
        }
        // Only the pinned root survives: dead generations and unreferenced siblings go.
        assertEquals(new HashSet<>(refs), remaining(store, defs));
    }

    @Test
    void retiresOncePinnedDefinitionsAfterUnpin() {
        var defs = catalog(2, 2);
        var dead = new AnimationResources.DefinitionRef(0, 0, 0, ZERO);
        var store = filled(defs);
        var outer = store.pin(List.of(dead));
        try (var lease = store.pin(liveRefs(2, 2))) {
            store.retireUnpinned();
        }
        assertTrue(remaining(store, defs).contains(dead));
        outer.close();
        try (var lease = store.pin(liveRefs(2, 2))) {
            store.retireUnpinned();
        }
        assertFalse(remaining(store, defs).contains(dead));
    }
}
