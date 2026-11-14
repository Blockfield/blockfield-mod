# Control Plane Consumer & Ack Terminal Contract (`cpv2`)

This document specifies the end-to-end delivery, cursor advancement, post-ack hook, and terminal settlement contract between `Blockfield/blockfield-control-plane` (`cpv2`) and `CpConsumer` / `CpSseClient` / `CpStore` (`com.lunares.blockfield.common.controlplane`).

## 1. Unified Terminal Contract (`cpv2` + Consumer)

For each `control` SSE frame at `(epoch, sequence, operationId)`, `CpConsumer.onFrame` atomically persists the durable receipt, the queued `CpAck` chain, any LKG/operator update, and the explicit post-ack hook flag in ONE `CpStore` (`state.json`) write, and returns the chain to `POST /v2/agents/{agentId}/acks`:

- **Settings / Effective Actions**: `received` -> `validated` -> `applied` -> `effective` (`output` on `effective` only when applicable).
- **Non-executable / Expired / Malformed / Rejected Actions**: `received` -> `rejected`.
- **Uncertain Effect / Fallback Recovery**: `unknown` (`effect_uncertain`).

**Server & Client Cursor Invariants**:
1. **Terminal-only cursor (`cpv2`)**: `consumers.received_cursor` (reported by `GET /snapshot` and enforced by `/events` + `Last-Event-ID`) advances only across contiguous sequences with a **terminal** receipt (`effective`, `rejected`, or `unknown`), never on `received`/`validated`/`applied`. Any resnapshot or SSE reconnect while an operation is non-terminal redelivers that operation. Older control planes can advance at `received`; the consumer therefore retries its locally persisted chain independently of redelivery.
2. **Operation timeout closure (`cpv2`)**: timed-out operations in `accepted`, `received`, `validated`, or `applied` expire lazily on read when outbox rows exist, and fallback `unknown` (`effect_uncertain`) receipts clamp consumer clock skew rather than failing `422 receipt_time`.
3. **Local cursor (`CpSseClient.dispatch`)**: `store.saveCursorIfForward` never advances past `sequence` until CP returns `200` for a terminal state (`effective`, `rejected`, or fallback `unknown`).

The client stores the exact ack bodies and progress before posting, and retries unfinished chains with bounded backoff and resnapshots before retrying after a conflict. After a restart it resumes from the stored progress; exact duplicate posts cover a lost HTTP response. A redelivery already settled locally does not apply the operation or complete its hook again.

## 2. HTTP Status Classification (`POST /v2/agents/{agentId}/acks`)

| Status | Meaning on `cpv2` | Consumer / Client Action | Hook Outcome | Cursor (CP & Local) |
| :--- | :--- | :--- | :--- | :--- |
| `200` | Transition accepted | Continue chain; on final ack save cursor then call `ackAccepted(finalAck)` | Succeeds **only** if `finalAck.state == EFFECTIVE`; `REJECTED`/`UNKNOWN` fails the hook | Both advance to `sequence` |
| `409` | Stale cursor, generation, or transition (`receipt_terminal`, `receipt_gap`, etc.) | Resnapshot, discard a superseded chain, otherwise retry it | Fails if superseded; otherwise stays pending | Snapshot baseline |
| `400` / `404` / `422` | Permanent validation rejection (`invalid_request`, `receipt_time`, field/route error) | Demote durable `EFFECTIVE` -> `UNKNOWN`; see Section 3 | Fails with `CpAckRejectedException` once terminal (or stays pending across transient fallback retries) | Advance only once fallback `unknown` returns `200` |
| `408` / `429` / `5xx` / other `4xx` / I/O | Transient timeout, rate limit, auth hiccup, or server error | Back off and retry the saved chain locally before reconnecting | Stays pending | Unchanged |

## 3. Terminal Rejection Recovery (`400` / `404` / `422`)

1. **Rejected AFTER `received` was accepted (`failedIndex > 0` and `failed.state != UNKNOWN`)**:
   - Before posting fallback, `consumer.markFallbackUnknown` demotes `CpStore` to `UNKNOWN`/`EFFECT_UNCERTAIN` (`needsOperator() = true`) and attaches the `CpAckRejectedException` to the still-pending hook so replay never emits `effective` or `received` (`409 receipt_conflict`).
   - Client posts fallback `state = unknown`, `result = effect_uncertain`, `output = null`.
   - **`200`**: `dispatch` saves the local cursor and `consumer.ackAccepted(fallback)` fails the pending hook with the original `CpAckRejectedException`.
   - **Transient failure (`5xx`, timeout, I/O, `401`/`403`/`408`/`429`, `409` resync)**: propagates out with the hook still pending and cursor unchanged (`< sequence`). The saved fallback is retried locally; once `200` arrives `ackAccepted(unknown)` fails the still-pending hook with the stored `CpAckRejectedException`.
   - **Terminal failure on fallback (`400`/`404`/`422`)**: attaches fallback rejection as suppressed, calls `consumer.ackFailed` (failing the hook), leaves cursor unchanged, and throws `CpResyncException`.

2. **Rejected BEFORE `received` (`failedIndex == 0`) or on `UNKNOWN`**:
   - CP has not recorded a terminal state (cursor stays behind `sequence`).
   - `consumer.ackFailed` demotes any durable `EFFECTIVE` state in `CpStore` to `UNKNOWN`/`EFFECT_UNCERTAIN` and fails the pending hook with `CpAckRejectedException`.
   - Client leaves local cursor unchanged and throws `CpResyncException` so `GET /snapshot` + `/events` redelivers the non-terminal operation as `[unknown]`—never re-sending `effective` without a hook.

## 4. Post-Ack Hook Identity, Persistence & Supersede Rules (`CpConsumer`)

Post-ack dependency is declared explicitly by `CpActionHandler` before persistence via `CpApplier.Outcome.okWithHook(...)` (`needsPostAckHook = true`, used by e.g. `workshop.game stop`), never inferred from `CompletableFuture` dependents—so a handler that stores `acked` and attaches continuations later from another thread still counts as hooked. The receipt (`hadHook`), pending ack chain (`hadHook`), and action state are written together in ONE atomic `state.json` replacement (with `started` persisted before execution, so a crash before or during atomic persist also settles as `unknown`).

Post-ack hooks (`CompletableFuture<CpAck>` for `workshop.game`) are keyed by full frame identity `(epoch, sequence, operationId)` so an ack for another operation at the same sequence can never settle a hook by accident. A hook succeeds only when CP returns `200` for `effective` on that exact `operationId`, stays pending across every transient failure (including fallback `unknown` retries), and fails immediately (outside the consumer lock) when:

- **Terminal non-effective settlement**: `ackAccepted` receives `UNKNOWN`/`REJECTED` (failing with the stored `CpAckRejectedException` when from fallback, or `IllegalStateException` otherwise).
- **Permanent rejection without retryable fallback**: `ackFailed` receives `CpAckRejectedException`.
- **Superseded or shutdown**: `onSnapshot` changes `epoch` or `generation` or moves `cursor >= sequence`; `onFrame` accepts a different `operationId` at `<= sequence` or sees cursor past it; `ackAccepted` settles at `>= sequence`; or `close()` shuts down the consumer (preferring any stored `CpAckRejectedException` over `CpResyncException`).

After restart or superseded generation in the same epoch without the live hook in memory, any stored chain or terminal flagged `hadHook = true` settles as `unknown` / `effect_uncertain` (never `effective`); unflagged actions (`Outcome.ok(...)`) replay `effective` unchanged.
