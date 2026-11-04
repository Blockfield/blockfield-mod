# Statistics producer and durable outbox

Statistics producer for blockfield-releases#3 and #6. API, storage, rating rules, backup/restore and legacy import are documented in `blockfield-site/docs/statistics.md`. Runtime acceptance below supersedes the historical implementation-stage notes.

## Implemented
Real MatchTracker finalization snapshots validated v1 match results and separately accumulates a complete campaign summary. Every session owns a random UUID created once; the same finished session cannot be finalized again or retroactively changed by a late shutdown. Room.stop/close finalize a real unfinished match even after MATCH_END, before campaign/world cleanup. No fake Campaign/South result is fabricated when there was no actual session.

Archived player segments are merged by UUID for the export. Mixed-team segments use NONE rather than guessing a winning side; a segment whose side was never observed does not count as mixed (RED + unknown = RED, unknown only = NONE), and the tracker records an online campaign player's scoreboard side when their stats are created; maximum streak is a maximum, other counters sum. Classes use configuration classId and variant index (string) scoped by the explicitly configured ruleset. Changing/reordering variants requires a new ruleset version. Display labels remain separate. Archived class time stops on departure; playSeconds is the existing tracker class-selection time, **not** a newly proven alive-combat timer. Preparation/death/wait timing semantics need a later dedicated activity tracker.

Snapshots/serialization occur on the server thread. Worker tasks hold immutable bytes, IDs and transport state, not mutable Minecraft players/rooms. One disk worker (eight waiting records) writes the outbox. One HTTP worker sends at most four eligible records per pass with a five-second whole-response deadline. Non-gameplay profiles cannot create new result snapshots; they can still deliver already captured gameplay records.

The outbox uses an exclusive process/file lock, immutable UUID filenames, fsync plus atomic rename, 1 MiB/event and 10,000 records/256 MiB total including rejected records. Matching replay is a no-op; conflicting identity never overwrites. Accepted responses must be JSON 200/201 containing inserted and the exact eventId; generic proxy 200, wrong IDs, oversized/stalled bodies and redirects do not acknowledge delivery. Transient/auth/rate-limit errors back off up to fifteen minutes. 400/409/413/415/422 are retained under rejected/ for operator review. Failed first records do not block eligible later records.

## Configuration

Production export is enabled through the server deployment. Use a separate database and producer identity for local acceptance.

```dotenv
BLOCKFIELD_STATS_EXPORT_ENABLED=true
BLOCKFIELD_STATS_SERVER_ID=00000000-0000-0000-0000-000000000009
BLOCKFIELD_STATS_RULESET=review-v1
BLOCKFIELD_SESSION_PURPOSE=gameplay
BLOCKFIELD_STATS_INGEST_URL=https://stats.example.invalid/api/stats/results
# Inject a unique 32-256 printable-ASCII bearer token from your secret manager.
BLOCKFIELD_STATS_INGEST_TOKEN=<injected-secret>
```

Use a real stable server UUID, HTTPS endpoint and independently generated token. The web server must authorize that UUID/token via BLOCKFIELD_STATS_INGEST_KEYS and persist BLOCKFIELD_STATS_DB on its own volume. HTTP is rejected unless BLOCKFIELD_STATS_ALLOW_HTTP=true is explicitly set for a trusted isolated test network. Redirects are never followed; do not put credentials in the URI. Token rotation is read on server startup; retain previous/current keys on the API during rotation. Missing/invalid enabled settings fail startup rather than silently disabling export.

The producer volume is `<server directory>/blockfield/stats/outbox-v1`; use a local Linux filesystem supporting directory fsync/atomic rename. Mount it persistently in every sequential game/workshop/test profile. Retain the same server ID across restarts. Do not share the directory between simultaneously running processes. The independent web service and its DB must remain up while the Minecraft profile stops; place them outside the game host if host-failure availability is required.

## Durability boundaries and recovery
A completed submit future means local record fsync completed, **not** merely that an executor accepted the task. Async snapshots can be lost if the process is killed before that completion. The exceptional full-memory-queue fallback writes synchronously rather than dropping a result; this can stall the server on disk IO. This is explicit bounded backpressure, not a promise of zero tick latency under disk saturation.

Orderly SERVER_STOPPING first finalizes interrupted rooms, stops HTTP and waits up to fifteen seconds for the disk worker. A failure/timeout latches unhealthy status and logs that durability is NOT confirmed. Disk full, corrupt queues, invalid captures, SIGKILL and lost disks are not represented as successful delivery. `persistenceHealthy()` is a failure latch, not proof that every pending task is already on disk. The maintenance controller reads pending persistence and health before switching. An ongoing campaign is not reconstructed after an uncommitted process crash.

Records already fsynced survive reopen and are retried with the same bytes/eventId. Never delete the queue to resolve an auth error. Rejected records are not silently retried or modified; review the reason/API first, then `ResultOutbox.retryRejected(id)` requeues the unchanged bytes. There is no exposed admin retry command/UI yet. Updating a finalized result under its old ID is prohibited.

A crash leaving `.tmp-*` stops reopening before more writes. Preserve and inspect that uncommitted file outside the queue, checking it against the corresponding legacy event log; do not assume it is complete JSON or promote it blindly. Startup failure releases the lock, and never deletes evidence. Invalid/symlink record files also fail closed and need operator review. The volume is trusted private server storage; ancestor-directory replacement by another local privileged user is outside this lock's security boundary.

Legacy CamelCase JSON is retained as a separate compatibility/recovery path. It now includes EventId and stable class keys plus ClassId/VariantId/Name metadata. Existing legacy log writing was not replaced by the durable outbox and is not itself a durability guarantee. Use `blockfield-site/scripts/import-legacy-stats.mjs` for explicit, idempotent historical import; never POST old files directly. Historical import does not invent MMR. Campaign MMR uses the web service’s documented Glicko-2 policy. Campaign summaries do not duplicate round combat totals in the companion web aggregate.

## Game-server diagnostics

The enabled GAME-profile producer also sends schema-v1 diagnostics to the sibling
`POST /api/stats/diagnostics` route, using the same server UUID, bearer key and HTTP worker.
The site's admin/owner «Производительность» view is documented in
[site admin](https://github.com/Blockfield/blockfield-site/blob/main/docs/admin.md).
No additional setting or credential is needed; result-export enablement and configuration
remain required. Workshop-profile servers do not initialize this producer.

Each minute reports observed TPS, average/P95/worst tick duration, player count, heap usage
and GC pause time. The complete interval is measured, so a spike cannot disappear merely
because the most recent 100 ticks were fast. Heap MiB, timestamps, GC and event duration
are integers for the site's PostgreSQL contract. P95 is omitted if more than 65,536 ticks
occur in one interval; average, maximum and TPS still include every tick.

Sub-second tick spikes remain in the minute's maximum/P95 instead of flooding the journal.
Ticks of one second or more produce freezes; adjacent freezes within a minute coalesce by
source, with a repeat count and the maximum observed duration, regardless of stack/duration text.
The existing HTTP worker also attempts one stack capture per still-running long tick.
This capture is best effort: result delivery or a slow API can occupy the worker, and a
short freeze can finish before it is observed. A watchdog observation and completion of the
same tick count once. An event observed while still running has a lower-bound duration; the
minute sample later records the complete tick duration. WARN/ERROR/FATAL game logs become a bounded
journal, with adjacent repeated messages coalesced. Logger names containing `auth`, `session`,
`drasl`, `http`, `sun.net`, `java.net`, `webhook`, `config` or the producer's own names are
excluded by name. This does not exclude every login/network message: vanilla intermediary
loggers can forward them. URLs, IPs, e-mails and common credential patterns are removed before
queueing. Unusual secret formats cannot be identified reliably, so this is game logging,
not an export of arbitrary server logs.

Diagnostics are a memory buffer, separate from the durable match-result outbox: at most
120 samples and 200 events, including any immutable in-flight batch. Failed requests retry
the exact bytes/IDs with backoff from five seconds to fifteen minutes. The API acknowledges
JSON sample/event counts and stores replay idempotently. Permanent 400/409/413/415/422
responses drop only the rejected diagnostics batch, log a fixed local warning and count its
samples/events as lost; transient/auth failures retain the batch. This does not change the
durable match-result outbox's rejected-record policy. Queue pressure preserves crashes/errors
before freezes/warnings; loss counts are reported when there is room for their warning.
Process death, shutdown or a long outage can lose unsent diagnostics; the site's
stale-data state and restart timestamps must not be interpreted as proof of a healthy server
or a complete crash journal. Existing local logs remain the recovery evidence.

## Verification

```sh
just test
just gradle --no-daemon --max-workers=2 build
```

The outbox check covers immutable records, replay/conflict, reopen, real loopback HTTP,
response deadlines, bounded payloads and persistence fencing. Gradle includes DTO, recorder,
UI and room/policy regression tests. The web repository supplies aggregation, authorization,
SQLite/PostgreSQL, Glicko-2, season, legacy import and read API tests.

## Full-pack runtime acceptance — 2026-09-24

Three local Minecraft clients used independent offline test identities. All results went to
an isolated Astro/SQLite service; no test result was sent to production. Native launcher
verification used its real Rust HTTP/IPC path with a local UI identity fixture, not Drasl login.

- Two complete front campaigns finalized. Rounds produced no rating operations; their
  completed campaigns produced respectively three and two player rating changes.
- Reconnect alive/dead, reserve expiry, late admission, two simultaneous rooms and training
  were exercised. Reconnecting preserved the remaining respawn timer and selected class.
- Real delivered payloads were replayed in reverse order twice after restart: nine stored
  results and five rating changes stayed unchanged. Later administrative interruption added
  two factual results (winner NONE), leaving the same five rating changes.
- After Minecraft stopped, the API and website still returned the saved profile. During
  WORKSHOP → TEST → WORKSHOP → GAME the database remained independent.
- A two-player candidate playtest produced a local SessionPurpose=test log with class/time/
  score data, but no API result or MMR change (11 results, five rating changes). It was ended
  administratively; this check does not claim a natural test campaign victory. Its block
  destruction did not change the draft hash; the original Create block and two entities
  were present on returning to the same workshop session.
- Same filter (all modes, all time, score), same UUID `67278904-96a9-3613-a334-63780ae01220`:
  website, native launcher and in-game tablet all showed rank 2, MMR 1433 (provisional),
  kills/deaths 0/1, matches/wins 5/2. Website/launcher also showed score 150 and captures 2.
  Campaign filtering was checked separately against the API. Desktop/mobile website layouts
  and screenshots of all three clients were inspected.
- The runtime check exposed and fixed Java HTTP/2 upgrade timeouts against the stats service:
  StatsBoard uses HTTP/1.1 like StatsHttpTransport (PR #136, released in v1.25.2).

Evidence is retained in `/var/tmp/bf-jobs/closure-20260924/evidence`: `redelivery-after-restart*`,
`emergency-interrupt.json`, `stats-backend-off.json`, `playtest-isolation.json`,
`playtest-workshop-return.json`, `game-stats-all.json`, `native-launcher-stats.jpg`,
`stats-leaderboard-all.png`, and client screenshots. These local fixtures do not establish
real-account proxy authentication or host power-loss tolerance.

## Operational limits

The API may use independent SQLite or PostgreSQL storage. Different containers on the same
host survive a Minecraft process failure, not a whole-host outage; use a separate host if
that failure boundary is required. Keep database backups independent of disposable worlds.

`playSeconds` measures tracked class participation, not exclusively alive combat time.
Class IDs are stable configuration IDs; variant IDs are indices scoped by ruleset version.
Preparation/death/wait time semantics are therefore part of the documented metric.

A pending write is durable only after its persistence future completes. Shutdown waits a
bounded time; disk failure or a kill before fsync cannot recover a result that was never
committed. Never delete rejected records or change their IDs to bypass a conflict.
