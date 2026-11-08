package com.lunares.blockfield.network;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;

/**
 * Login-time compatibility check that replaces the exact client-pack hash ({@link PackVersion}) for
 * clients that understand it: client-only changes keep the contract and need no paired server.
 *
 * <p>Phase-2 six-box pair is protocol 5 and refuses older clients; the Workshop AI speed mode/ETA
 * pair is protocol 6. Phase 3 animation sync is protocol 7: the snapshot S2C + CombatView C2S pair
 * changes the wire again, so it refuses everything released before (mod 1.52.0 released 6).
 * Protocol 8 adds the optional TaCZ fire-frame eye and aim to the CombatView ticket; protocol 9
 * lets a ticket carry R without a complete victim report. Any later change to the shared geometry
 * (a new {@code BodyGeometry.DIGEST}) or to the wire bumps {@code PROTOCOL} and ships as a
 * client+server pair: the client JAR is {@code side = "client"}, so a client-only art change would
 * otherwise silently desync the hit volume. Server-only selection or shape-assembly changes do not
 * bump it.
 */
public final class PackContract {
    public static final ResourceLocation CHANNEL =
            ResourceLocation.fromNamespaceAndPath("blockfield", "pack_contract");
    // Bump on any client<->server packet or payload change: the mod ships as separate client and
    // server JARs, so the pack contract (side="both" content) does not cover it.
    // 6: speed mode in Workshop AI generate requests and the ETA in its progress payload.
    // 7: phase 3 animation sync wire (snapshot S2C + CombatView C2S).
    // 8: the CombatView ticket's optional TaCZ fire-frame eye and aim.
    // 9: a ticket without a complete victim report (presence byte), rewound by retained pose at R.
    // 10: synced data component blockfield:rig_slots; 1.59.x clients fail registry sync at login.
    public static final int PROTOCOL = 10;
    // Oldest client protocol this build still accepts. Bump PROTOCOL on any wire change;
    // raise MIN_COMPATIBLE only when the change is not additive (registry/codec break,
    // like 9->10): 9 stays rejected until an additive change moves the floor again.
    public static final int MIN_COMPATIBLE_PROTOCOL = 10;
    // Additive capabilities this build understands, sorted: reply() sends them in this
    // order. The server sends a new packet shape only when the negotiated set has its
    // token, else the old shape. Old servers read a v2 reply as OUTDATED (safe kick).
    public static final List<String> CAPABILITIES =
            List.of("balance-v1", "map.players-v1", "ui.envelope-v1");
    // Max wire length of the login reply, shared by the client write and every
    // read of it: a v2 reply is 108 chars, so the old 80-char limit throws
    // during the handshake. Keep the server gate in sync (it kicks on overflow).
    public static final int REPLY_MAX_LEN = 256;
    // Frozen at startup like PackVersion.CURRENT; build-client.py writes it, same digest as the
    // server image's build.json "compatibility".
    public static final String CURRENT = PackVersion.read("blockfield-pack-contract.txt");

    public enum Verdict {
        OK,
        OUTDATED,
        INCOMPATIBLE
    }

    private PackContract() {}

    public static String reply(String contract) {
        return contract + ":" + PROTOCOL + ":" + String.join(",", CAPABILITIES);
    }

    public static Verdict check(String expected, String reply) {
        return check(expected, reply, MIN_COMPATIBLE_PROTOCOL, PROTOCOL);
    }

    static Verdict check(String expected, String reply, int minCompatible, int current) {
        String[] parts = reply.split(":", -1);
        if ((parts.length != 2 && parts.length != 3) || !PackVersion.accepts(expected, parts[0]))
            return Verdict.OUTDATED;
        int protocol;
        try {
            protocol = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return Verdict.INCOMPATIBLE;
        }
        return (protocol >= minCompatible && protocol <= current)
                ? Verdict.OK
                : Verdict.INCOMPATIBLE;
    }

    /** Capability tokens the client advertised; empty for v1 replies and malformed input. */
    public static Set<String> capsOf(String reply) {
        String[] parts = reply.split(":", -1);
        if (parts.length != 3) return Set.of();
        Set<String> caps = new LinkedHashSet<>();
        for (String cap : parts[2].split(",", -1)) {
            if (!cap.isEmpty()) caps.add(cap);
        }
        return Collections.unmodifiableSet(caps);
    }
}
