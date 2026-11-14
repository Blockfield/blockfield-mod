package com.lunares.blockfield.common.controlplane;

import java.util.concurrent.CompletableFuture;

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
 * room id in {@code playerId} and {@code "<OUTCOME>[ <reason>]"} in {@code reason}; list and
 * announce carry nothing; maintenance carries the op ({@code status|drain|resume|interrupt}) in
 * {@code playerId} and the interrupt reason in {@code reason}. Console lines go to {@link
 * #handleConsole}.
 */
public interface CpActionHandler {
    /**
     * Execute one validated action. A returned {@code applied=false} must leave game state
     * untouched; throwing means the effect is uncertain and needs operator reconciliation.
     */
    CpApplier.Outcome handleAction(String kind, String target, String playerId, String reason);

    /**
     * One owner console line ({@code console.run}). {@code readOnly} is the control plane's
     * allowlist verdict; the handler re-checks it against its own allowlist before running. The
     * outcome's output is the command's text. Without an override, consoles are not supported.
     */
    default CpApplier.Outcome handleConsole(String target, String command, boolean readOnly) {
        return CpApplier.Outcome.rejected(CpAck.Result.INVALID_SCHEMA, "console not supported");
    }

    /**
     * One completion request ({@code console.suggest}): the partial line as typed (leading slashes
     * stripped by the control plane, at most 1000 chars, no control characters). The outcome's
     * output is the suggest JSON ({@code {"from":N,"suggestions":[...]}}). Without an override,
     * completion answers are not supported.
     */
    default CpApplier.Outcome handleSuggest(String target, String input) {
        return CpApplier.Outcome.rejected(CpAck.Result.INVALID_SCHEMA, "suggest not supported");
    }

    /**
     * One Workshop game lifecycle action ({@code workshop.game}): {@code gameOp} is one of {@code
     * save-flush}, {@code save-off}, {@code save-on}, {@code stop}, or {@code export-limbo}; {@code
     * job} is the export job UUID for {@code export-limbo} and empty otherwise. {@code acked}
     * completes with the final {@link CpAck} once any delivery (initial attempt or retry, including
     * after SSE reconnect or resync replay) is accepted by the control plane and the cursor has
     * been saved, or completes exceptionally only on a terminal outcome: frame superseded or
     * discarded by a resnapshot, permanent HTTP 4xx rejection, or consumer shutdown. Handlers must
     * not block on {@code acked} synchronously inside this method (acks are posted only after it
     * returns); attach non-blocking continuations such as {@code whenComplete} (optionally with
     * {@code orTimeout}). Without an override, Workshop game actions are not supported.
     */
    default CpApplier.Outcome handleWorkshopGame(
            String target, String gameOp, String job, CompletableFuture<CpAck> acked) {
        return handleWorkshopGame(target, gameOp, job);
    }

    /** Convenience overload when the handler needs no acknowledgement-completion callback. */
    default CpApplier.Outcome handleWorkshopGame(String target, String gameOp, String job) {
        return CpApplier.Outcome.rejected(
                CpAck.Result.INVALID_SCHEMA, "workshop.game not supported");
    }
}
