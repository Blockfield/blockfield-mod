# Control Plane Consumer & Ack Terminal Contract (`cpv2`)

This document specifies the ack delivery, cursor advancement, post-ack hook, and terminal failure-recovery contract implemented by `CpConsumer`, `CpSseClient`, and `CpStore` (`com.lunares.blockfield.common.controlplane`) against `Blockfield/blockfield-control-plane` (`cpv2`).

## 1. Ack Chain and Cursor Invariant

For each `control` SSE frame at `(epoch, sequence, operationId)`, `CpConsumer.onFrame` records durable execution state in `CpStore` (`state.json`) and returns an ordered list of `CpAck` bodies to `POST /v2/agents/{agentId}/acks`:

- **Settings / Effective Actions**: `received` -> `validated` -> `applied` -> `effective` (`output` carried on the final `effective` ack only when applicable).
- **Non-executable / Expired / Malformed / Rejected Actions**: `received` -> `rejected`.
- **Crash-started Non-idempotent Action / Effect Uncertain**: `unknown` (`effect_uncertain`).

**Cursor Invariant (`CpSseClient.dispatch`)**:
The local cursor (`store.saveCursorIfForward`) MUST NEVER advance past `sequence` unless the control plane has returned `HTTP 200` for a terminal state (`effective`, `rejected`, or fallback `unknown`).

## 2. HTTP Status Classification (`POST /v2/agents/{agentId}/acks`)

| Status | Meaning on `cpv2` | Consumer / Client Action | Hook Outcome | Local Cursor |
| :--- | :--- | :--- | :--- | :--- |
| `200` | Transition accepted | Continue chain; on final ack call `ackAccepted(last)` | Completes normally with final `CpAck` | Saved (`sequence`) |
| `409` | Stale cursor, generation mismatch, or invalid state transition (e.g. `rejected` after `applied`, or `receipt_terminal`) | Drop remaining chain, throw `CpResyncException` -> backoff + `GET /snapshot` | Stays pending (retried on replay; failed if snapshot moves past `sequence` or changes epoch) | Unchanged |
| `400` / `404` / `422` | Validation rejection (`invalid_request`, `receipt_time`, missing/malformed field, route mismatch) | See Section 3 (`handleRejectedAck`) | Fails with `CpAckRejectedException` | Only advances if fallback `unknown` returns `200` |
| `408` / `429` / `5xx` / other `4xx` / I/O | Transient timeout, rate limit, auth hiccup, or server error | Throw `IOException` -> backoff + reconnect (`/events` replays from unchanged local cursor) | Stays pending | Unchanged |

## 3. Terminal Rejection Recovery Path (`400` / `404` / `422`)

Because `cpv2` (`cpv2/delivery.py`) advances `consumers.received_cursor = sequence` immediately when `received` returns `200`, rejecting a later ack in the same chain leaves the server cursor ahead of the unsettled row. `CpSseClient.handleRejectedAck` handles `CpAckRejectedException` in two cases:

1. **Rejected AFTER `received` was accepted (`failedIndex > 0` and `failed.state != UNKNOWN`)**:
   - Example: `effective` or `rejected` fails schema/size/time validation (such as `output` rejected by an older CP schema or unexpected field) after `received`/`validated`/`applied` already returned `200`.
   - The client posts one fallback terminal ack: `state = unknown`, `result = effect_uncertain`, `output = null`, fresh `actualAt = OffsetDateTime.now(clock)`.
   - If CP returns `200`: `consumer.ackSettledUnknown` overwrites the durable terminal record in `CpStore` to `UNKNOWN`/`EFFECT_UNCERTAIN` (`needsOperator()` becomes `true` and crash-replay will repost `unknown`), fails the post-ack hook with the `CpAckRejectedException`, and only then advances `store.saveCursorIfForward(epoch, sequence)`.
   - If the fallback `unknown` also returns `400`/`404`/`422`: the fallback exception is attached as suppressed on the primary `CpAckRejectedException`, the hook fails via `ackFailed`, the local cursor is **not** advanced, and `CpResyncException` is thrown to backoff + `GET /snapshot`.
   - If the fallback `unknown` throws `409` or `IOException`: the hook fails via `ackFailed`, the local cursor is **not** advanced, and the exception propagates (`resync` on `409`, reconnect backoff on `IOException`).

2. **Rejected BEFORE `received` (`failedIndex == 0`) or on an `UNKNOWN` ack itself**:
   - CP has not recorded any state transition (`received_cursor` did not move).
   - Wait/advance is unsafe: `consumer.ackFailed` fails the post-ack hook with `CpAckRejectedException`, the local cursor is **not** saved, and `CpSseClient` throws `CpResyncException` so `runLoop` backs off and runs `GET /snapshot` before resuming `/events`.

## 4. Post-Ack Hook Identity & Supersede Rules (`CpConsumer`)

Post-ack hooks (`CompletableFuture<CpAck>` for `workshop.game`) are keyed by full frame identity `(epoch, sequence, operationId)` so an ack for another operation at the same sequence can never complete a hook by accident.

A pending hook fails immediately (outside the consumer lock) with `CpResyncException` whenever CP or local state moves past it or replaces its slot:

- **Resnapshot**: `onSnapshot` moves to a new `epoch` or sets `cursor >= entry.sequence()`.
- **Frame dispatch**: `onFrame` accepts a valid non-gap frame with a different `operationId` at the same `(epoch, sequence)`, or sees the stored cursor has already passed/reached an earlier pending entry (`entry.sequence() < frame.sequence`, or `entry.sequence() <= cursor.sequence()` excluding a replay of the same operation).
- **Accepted / settled ack**: `ackAccepted` or `ackSettledUnknown` completes for `(epoch, sequence, operationId)` and discards every other pending entry in `epoch` with `entry.sequence() < sequence` or `(entry.sequence() == sequence && !Objects.equals(entry.operationId(), operationId))`.
- **Consumer shutdown**: `close()` fails all remaining hooks with `IllegalStateException`.

## 5. Known Control-Plane (`cpv2`) Server Gaps Observed

When auditing `Blockfield/blockfield-control-plane` (`next`, `cpv2`), three server-side gaps affect `400`/`404`/`422` recovery and should be tracked on CP:

1. **`received_cursor` advances on `received` before a terminal ack**: `cpv2/delivery.py` sets `consumers.received_cursor = sequence` upon `state == 'received'`. If the consumer subsequently fails to deliver `unknown` (e.g. process crash or second `422`), `GET /snapshot` already reports `cursor = sequence` and `/events` will not redeliver that operation.
2. **`applied` operations never expire on timeout**: `cpv2/core.py:read_operation` expires timed-out operations only when `state` is in `('accepted', 'received', 'validated')`; an operation stranded in `'applied'` remains non-terminal until reconciled or settled via `unknown`.
3. **Clock-skew `422 receipt_time` blocks fallback `unknown`**: `cpv2/delivery.py` returns `422 receipt_time` when `actualAt > now() + 5s` or `actualAt < created_at`, which can also reject a fallback `unknown` ack built from a forward-skewed game host clock.
