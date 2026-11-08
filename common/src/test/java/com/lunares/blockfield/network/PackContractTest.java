package com.lunares.blockfield.network;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class PackContractTest {
    private static final String CONTRACT = "c".repeat(64);

    private static String v1(int protocol) {
        return CONTRACT + ":" + protocol;
    }

    private static String v2(int protocol) {
        return CONTRACT + ":" + protocol + ":" + String.join(",", PackContract.CAPABILITIES);
    }

    @Test
    void replyIsV2WithSortedCaps() {
        assertEquals(
                CONTRACT
                        + ":"
                        + PackContract.PROTOCOL
                        + ":balance-v1,map.players-v1,ui.envelope-v1",
                PackContract.reply(CONTRACT));
        assertEquals(
                new ArrayList<>(new TreeSet<>(PackContract.CAPABILITIES)),
                PackContract.CAPABILITIES);
    }

    @Test
    void currentAndPreviousProtocolAreOkInBothShapes() {
        assertEquals(
                PackContract.Verdict.OK, PackContract.check(CONTRACT, v1(PackContract.PROTOCOL)));
        assertEquals(
                PackContract.Verdict.OK, PackContract.check(CONTRACT, v2(PackContract.PROTOCOL)));
        assertEquals(
                PackContract.Verdict.OK,
                PackContract.check(CONTRACT, v1(PackContract.PREV_PROTOCOL)));
        assertEquals(
                PackContract.Verdict.OK,
                PackContract.check(CONTRACT, v2(PackContract.PREV_PROTOCOL)));
    }

    @Test
    void olderAndNewerProtocolsAreIncompatible() {
        assertEquals(
                PackContract.Verdict.INCOMPATIBLE,
                PackContract.check(CONTRACT, v1(PackContract.PREV_PROTOCOL - 1)));
        assertEquals(
                PackContract.Verdict.INCOMPATIBLE,
                PackContract.check(CONTRACT, v2(PackContract.PREV_PROTOCOL - 1)));
        assertEquals(
                PackContract.Verdict.INCOMPATIBLE,
                PackContract.check(CONTRACT, v1(PackContract.PROTOCOL + 1)));
        assertEquals(
                PackContract.Verdict.INCOMPATIBLE,
                PackContract.check(CONTRACT, v2(PackContract.PROTOCOL + 1)));
    }

    @Test
    void contractMismatchIsOutdated() {
        String other = "d".repeat(64);
        assertEquals(
                PackContract.Verdict.OUTDATED,
                PackContract.check(other, v1(PackContract.PROTOCOL)));
        assertEquals(
                PackContract.Verdict.OUTDATED,
                PackContract.check(other, v2(PackContract.PROTOCOL)));
        assertEquals(
                PackContract.Verdict.OUTDATED, PackContract.check("", v2(PackContract.PROTOCOL)));
    }

    @Test
    void malformedRepliesAreOutdated() {
        for (String reply :
                new String[] {
                    "",
                    CONTRACT,
                    ":" + PackContract.PROTOCOL,
                    CONTRACT + ":" + PackContract.PROTOCOL + ":caps:extra",
                }) {
            assertEquals(PackContract.Verdict.OUTDATED, PackContract.check(CONTRACT, reply), reply);
        }
    }

    @Test
    void unparsableProtocolIsIncompatible() {
        assertEquals(
                PackContract.Verdict.INCOMPATIBLE, PackContract.check(CONTRACT, CONTRACT + ":ten"));
        assertEquals(
                PackContract.Verdict.INCOMPATIBLE, PackContract.check(CONTRACT, CONTRACT + ":"));
    }

    @Test
    void capsOfReadsV2AndIgnoresV1AndMalformed() {
        assertEquals(Set.of(), PackContract.capsOf(v1(PackContract.PROTOCOL)));
        assertEquals(
                new LinkedHashSet<>(PackContract.CAPABILITIES),
                PackContract.capsOf(v2(PackContract.PROTOCOL)));
        assertEquals(Set.of(), PackContract.capsOf(CONTRACT + ":" + PackContract.PROTOCOL + ":"));
        assertEquals(
                Set.of("balance-v1"),
                PackContract.capsOf(
                        CONTRACT + ":" + PackContract.PROTOCOL + ":balance-v1,,balance-v1"));
    }
}
