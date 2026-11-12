package com.lunares.blockfield.common.controlplane;

/**
 * Server-side seam for typed control-plane actions ({@code moderation.kick}/{@code moderation.ban}
 * on the frozen v2 contract, plus the site-sent moderation commands {@code moderation.say}, {@code
 * moderation.pardon}, {@code moderation.team-kick}, {@code moderation.class-lock}, {@code
 * moderation.class-unlock}, {@code moderation.range} and {@code moderation.stop-room}). Implemented
 * in the server repo against the maintenance/player policy; this package only routes validated
 * frames to it. Owner-only kinds (raw console, OP commands) are rejected by {@link CpConsumer}
 * before this is ever called.
 *
 * <p>Kind-to-argument mapping (set by {@link CpConsumer}, mirrored from the control-plane {@code
 * _moderation_payload} shapes): kick/ban carry the player UUID in {@code playerId} and the free
 * text in {@code reason}; say carries the broadcast text in {@code reason} ({@code playerId}
 * empty); pardon/team-kick carry the nickname in {@code playerId}; class-lock/unlock carry the
 * class id in {@code playerId}; range carries the op in {@code playerId}; stop-room carries the
 * room id in {@code playerId} and {@code "<OUTCOME>[ <reason>]"} in {@code reason}.
 */
public interface CpActionHandler {
    /**
     * Execute one validated action. A returned {@code applied=false} must leave game state
     * untouched; throwing means the effect is uncertain and needs operator reconciliation.
     */
    CpApplier.Outcome handleAction(String kind, String target, String playerId, String reason);
}
