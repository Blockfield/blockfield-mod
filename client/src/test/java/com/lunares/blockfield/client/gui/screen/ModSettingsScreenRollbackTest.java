package com.lunares.blockfield.client.gui.screen;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Fail-before cover for U12: YACL applies staged bindings before its save
// runnable, so ModSettingsScreen commits them with rollback (the pendingEdits
// pattern the vanilla list keeps for save failures).
class ModSettingsScreenRollbackTest {
    private static final class Fake {
        final List<Object> writes = new ArrayList<>();
        Object live;
        boolean failReads;
        boolean failWrites;
        boolean failRestore;

        Fake(Object initial) {
            live = initial;
        }

        ModSettingsBridge.Entry entry(String label) {
            return new ModSettingsBridge.Entry(
                    "blockfield",
                    label,
                    0,
                    0,
                    0,
                    () -> {
                        if (failReads) throw new IOException("read " + label);
                        return live;
                    },
                    value -> {
                        if (failWrites || (failRestore && !writes.isEmpty()))
                            throw new IOException("write " + label);
                        writes.add(value);
                        live = value;
                    },
                    null);
        }
    }

    @Test
    void appliesStagedEntriesInOrder() throws Exception {
        Fake first = new Fake("a");
        Fake second = new Fake("b");
        Map<ModSettingsBridge.Entry, Object> staged = new LinkedHashMap<>();
        staged.put(first.entry("test.first"), "a2");
        staged.put(second.entry("test.second"), "b2");
        ModSettingsScreen.commitStaged(staged);
        assertEquals("a2", first.live);
        assertEquals("b2", second.live);
        assertEquals(List.of("a2"), first.writes);
        assertEquals(List.of("b2"), second.writes);
    }

    @Test
    void rollsBackAppliedEntriesWhenALaterWriteFails() {
        Fake first = new Fake("a");
        Fake second = new Fake("b");
        Fake third = new Fake("c");
        second.failWrites = true;
        Map<ModSettingsBridge.Entry, Object> staged = new LinkedHashMap<>();
        ModSettingsBridge.Entry e1 = first.entry("test.first");
        ModSettingsBridge.Entry e2 = second.entry("test.second");
        ModSettingsBridge.Entry e3 = third.entry("test.third");
        staged.put(e1, "a2");
        staged.put(e2, "b2");
        staged.put(e3, "c2");
        ModSettingsScreen.StagedSaveException failure =
                assertThrows(
                        ModSettingsScreen.StagedSaveException.class,
                        () -> ModSettingsScreen.commitStaged(staged));
        assertEquals("test.second", failure.labelKey);
        assertInstanceOf(IOException.class, failure.getCause());
        // First entry restored to the value it held before the batch.
        assertEquals(List.of("a2", "a"), first.writes);
        assertEquals("a", first.live);
        // Failed entry never persisted; later entries never attempted.
        assertTrue(second.writes.isEmpty());
        assertTrue(third.writes.isEmpty());
        assertEquals("c", third.live);
    }

    @Test
    void restoreFailuresAreSuppressedOnTheOriginalError() {
        Fake first = new Fake("a");
        Fake second = new Fake("b");
        second.failWrites = true;
        first.failRestore = true;
        Map<ModSettingsBridge.Entry, Object> staged = new LinkedHashMap<>();
        staged.put(first.entry("test.first"), "a2");
        staged.put(second.entry("test.second"), "b2");
        ModSettingsScreen.StagedSaveException failure =
                assertThrows(
                        ModSettingsScreen.StagedSaveException.class,
                        () -> ModSettingsScreen.commitStaged(staged));
        assertEquals("test.second", failure.labelKey);
        assertEquals(1, failure.getSuppressed().length);
    }

    @Test
    void skipsEntriesWhoseValueDidNotChange() throws Exception {
        Fake same = new Fake("a");
        Fake changed = new Fake("b");
        Map<ModSettingsBridge.Entry, Object> staged = new LinkedHashMap<>();
        staged.put(same.entry("test.same"), "a");
        staged.put(changed.entry("test.changed"), "b2");
        ModSettingsScreen.commitStaged(staged);
        assertTrue(same.writes.isEmpty());
        assertEquals(List.of("b2"), changed.writes);
    }
}
