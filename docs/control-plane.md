# Control Plane Consumer & Ack Terminal Contract (`cpv2`)

This document specifies the delivery, cursor advancement, post-ack hook, and terminal settlement contract between `Blockfield/blockfield-control-plane` (`cpv2`) and `CpConsumer` / `CpSseClient` / `CpStore` (`com.lunares.blockfield.common.controlplane`).

## 1. Unified Terminal Contract (`cpv2` + Consumer)

For each `control` SSE frame at `(epoch, sequence, operationId)`, `CpConsumer.onFrame` atomically persists the durable receipt and queued `CpAck` chain in ONE `CpStore` (`state.json`) write, and returns the chain to `POST /v2/agents/{agentId}/acks`:

- **Settings / Effective Actions**: `received` -> `validated` -> `applied` -> `effective` (`output` on `effective` only when applicable).
- **Non-executable / Expired / Malformed / Rejected Actions**: `received` -> `rejected`.
- **Uncertain Effect / Fallback Recovery**: `unknown` (`effect_uncertain`).

**Server & Client Cursor Invariants**:
1. **Terminal-only cursor (`cpv2`)**: `consumers.received_cursor` advances only across contiguous sequences with a **terminal** receipt (`effective`, `rejected`, or `unknown`), never on `received`/`validated`/`applied`.
2. **Operation timeout closure (`cpv2` PR #70)**: timed-out operations in `accepted`, `received`, `validated`, or `applied` expire lazily to terminal `unknown` (`effect_uncertain`), and fallback `unknown` receipts clamp consumer clock skew rather than failing `422 receipt_time`.
3. **Local cursor (`CpSseClient`)**: `store.saveCursorIfForward` advances past `sequence` only after CP returns `200` for a terminal state.

Unfinished `pendingAcks` chains are retried locally with backoff (and discarded on resnapshot if epoch, generation, or cursor moved past them); a redelivery already settled locally reposts without re-applying.

## 2. HTTP Status Classification (`POST /v2/agents/{agentId}/acks`)

| Status | Meaning on `cpv2` | Consumer / Client Action | Cursor (CP & Local) |
| :--- | :--- | :--- | :--- |
| `200` | Transition accepted | Continue chain; on final ack save cursor then call `ackAccepted(finalAck)` | Both advance to `sequence` |
| `409` | Stale cursor, generation, or transition | Resnapshot, discard a superseded chain, otherwise retry it | Snapshot baseline |
| `400` / `404` / `422` | Permanent validation rejection | Demote durable `EFFECTIVE` -> `UNKNOWN`; post fallback `unknown` if `received` was accepted, else call `ackFailed` and resync | Advance only once fallback `unknown` returns `200` |
| `408` / `429` / `5xx` / other `4xx` / I/O | Transient failure | Back off and retry the saved chain locally | Unchanged |

## 3. Post-Ack Hook Contract (`CpConsumer`)

The `CompletableFuture<CpAck> acked` passed to `CpActionHandler.handleWorkshopGame` is a best-effort, in-process **"ack delivery settled"** signal:

- It completes **normally** (never exceptionally) **exactly once** as soon as the action's final ack is accepted by CP (`complete(finalAck)`), OR after a bounded wait (`CpConsumer.HOOK_SETTLE_TIMEOUT`, 10 s), OR on permanent rejection (`ackFailed`), consumer `close()`, or supersede (`complete(null)`).
- Handler continuations (such as `workshop.game stop` -> halt) therefore always run and never hang or fail; if delivery fails or the process restarts before acceptance, the durable ack chain retry or `cpv2` timeout expiry guarantees a terminal CP state (`unknown` at worst).
- No hook state is persisted in `CpStore` (`state.json`), and no liveness inspection or restart demotion is performed.
