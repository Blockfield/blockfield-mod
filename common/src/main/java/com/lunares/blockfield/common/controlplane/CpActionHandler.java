package com.lunares.blockfield.common.controlplane;

/**
 * Server-side seam for typed control-plane actions ({@code moderation.kick}/{@code moderation.ban}
 * on the frozen v2 contract). Implemented in the server repo against the maintenance/player policy;
 * this package only routes validated frames to it. Owner-only kinds (raw console, OP commands) are
 * rejected by {@link CpConsumer} before this is ever called.
 */
public interface CpActionHandler {
    /**
     * Execute one validated action. A returned {@code applied=false} must leave game state
     * untouched; throwing means the effect is uncertain and needs operator reconciliation.
     */
    CpApplier.Outcome handleAction(String kind, String target, String playerId, String reason);
}
