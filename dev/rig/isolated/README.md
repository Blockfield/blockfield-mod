# Isolated Blockfield rig

An additive Docker profile for `dev/rig`, not a replacement for its existing LAN/hotswap workflow.
It reuses the existing `bftestbot` and PortableMC client-installation API. No `homeserver`, SSH,
rsync, local modpack checkout, shared player cache or external `agent-process` is required.

## Scope

The initial smoke environment contains Minecraft/Fabric, Fabric API, Accessories/owo, the actual side-specific
Blockfield JARs and the client-only testbot. It is **not the full TaCZ/SBW modpack**.
It tests boot, server build identity, a fresh flat world, client login, server-generated menu,
screenshot and reconnect. The separate `--mode pvp` scenario in this branch also tests two actual
clients and the consent adapter with real bow shots (melee is knife-only) (see below). Neither mode certifies
Ranked, Deathmatch, firearms, armor, workshop switching or production maps. Failure is not silently
downgraded to a skip.

## Run

Linux x86-64, Docker Engine, JDK 21, Python >=3.12, Just 1.57.0, and the project's Gradle dependencies are required.
Run from a clean checkout; output parents must exist and output directories must be new:

```sh
just build
dev/rig/testbot/fetch-testbot.sh # canonical sources from blockfield-workspace, pinned SHA+sha256
just gradle --no-daemon --max-workers=2 -p dev/rig/testbot/upstream build
just isolated build --output /tmp/bf-rig-build
just isolated run \
  --image "$(cat /tmp/bf-rig-build/image-id.txt)" \
  --output /tmp/bf-rig-result --mode client --accept-eula
```

`--accept-eula` explicitly accepts Minecraft's EULA for this temporary server. Omitting it refuses
execution. `--mode server` performs only the server check; it does not imply client acceptance.
Use a different output directory for every attempt. Reports and logs are retained on failure.

## Isolation and resource policy

Network downloads happen only while preparing the image, using exact Minecraft/loader/Fabric API
versions from `gradle.properties` and PortableMC 4.4.1 with a pinned wheel hash. Upstream metadata
and the base OS image are not fully locked: a clean image rebuild is **not** claimed bit-reproducible.
The result is addressed by its immutable local image ID. The image contains a SHA-256 inventory
of runtime files and input JARs; every session verifies it before launching Java.

The actual session uses Docker `--network none` (loopback only), no published ports, no Docker
socket, no host networking/PID namespace, no devices, dropped capabilities, a read-only image,
a numeric unprivileged host user, and one fresh report-directory mount. Worlds and client state
are new in container-local tmpfs. No existing world can be passed to this profile. Server/client
communicate on namespace-local 127.0.0.1. Existing hardcoded application update calls cannot reach
LAN/Internet in this namespace; they are not evidence of successfully contacted services.

The container has a 5 GiB limit for `server`/`client`, or 6 GiB for `pvp`, with no extra swap
allocation, 2 CPUs and 512 PIDs; server and each client heap are 1.5 GiB. Compilation finishes before game processes start. Only one full Minecraft
backend is launched. Xvfb uses software rendering at 800x450; this is not a GPU performance test.

Cleanup targets only the exact newly-created container ID after its random ownership label is
verified, and only child process handles created by the supervisor. There is no port/name-based
kill or global prune. Normal completion asks the server to stop and waits for children; a forced
shutdown is a failed check. Containers are removed; evidence and the built image are retained.
Do not publish the runtime image or Minecraft assets as a release.

## Evidence and checks

`build-report.json`, immutable image ID, `runtime-manifest.json` (embedded in the session report),
`report.json`, `launch-report.json`, server/client/display logs and `isolated-menu.png` identify
what actually ran. No generated world or random RCON credential is copied into evidence.
The build identity check compares `/bf version` with the source commit captured at image build.

```text
just test
```

The isolated workflow builds and runs the real smoke scenario, without release/deployment secrets.
Unit tests use mocks for Docker/process error cases; they are not a substitute for that workflow.
Inspect the current-head CI report before claiming Minecraft acceptance. Full-pack provisioning,
additional gameplay scenarios and interactive bot sessions remain follow-up work. The `pvp` mode
now supplies two concurrent minimal clients; this is not full-pack multiplayer coverage.

## Runtime follow-up

The first real CI launch exposed an entrypoint linkage dependency on Accessories and Docker tmpfs
`noexec` preventing JNA shared-library loading. The profile now provisions Accessories and owo
from exact modpack artifact IDs with SHA-512 verification; it does not patch game classes to hide
those requirements. Only the temporary `/tmp` mount is executable for native extraction.

The host launcher resolves the selected Docker context locally, refuses SSH/TCP endpoints, and
pins a local Unix socket for all subsequent commands. Run as a non-root host account.
Test-session spawn coordinates, playtest purpose and disabled statistics export are fixed inside
the isolated supervisor, not taken from host environment. Menu acceptance requires the actual
`MenuScreen`, and premature process exit cannot be accepted as a successful smoke run.

The next real run confirmed server startup/build identity and a real client connection. It also
exposed two test assumptions: `LocalPlayer` exists while terrain synchronization is still pending,
and the old rig documentation mentions `/bf menu` although that command is not registered.
The smoke now waits for the synchronized test spawn and an unobstructed in-game screen, selects
the server-issued tablet, and opens the menu through its normal use action. No fake menu or extra
command is added to game code. Failures preserve the last client state/messages and a screenshot
when the client remains responsive. Client JNA extraction uses `/tmp`, not the read-only library cache.

The custom Blockfield `MenuScreen` draws its controls without vanilla `AbstractWidget` children.
The testbot's read-only `bfmenu` command snapshots this known screen's actual server-supplied JSON
on the client thread. Smoke acceptance checks `id=hub`, the title and an enabled, labelled
Casual action (`casual` or `find:room`), then saves the rendered screenshot. A merely open screen or empty vanilla
widget list is not treated as evidence of valid menu contents. The probe is confined to testbot;
no new production command or fake menu is introduced.

## Two-client consent scenario (bow shots)

After building the image from this runtime-PvP branch, select a new evidence directory:

```sh
just isolated run \
  --image "$(cat /tmp/bf-rig-build/image-id.txt)" \
  --output /tmp/bf-pvp-result --mode pvp --accept-eula
```

This starts one full backend and two actual Fabric clients, `RigPlayer` and `RigOpponent`, with
separate game directories, identities and fixed loopback bot ports. The container's external
network remains disabled. The supervisor explicitly enables the experimental adapter only for
this test profile; production defaults remain OFF.

The scenario uses the real client `gameMode.attack` packet path, commands and tablet actions.
RCON arranges fixtures and reads server evidence; it does not replace the accepted melee attack.
Assertions cover the OFF/OFF, ON/OFF, OFF/ON and ON/ON matrix, delayed disable, ordinary-inventory
hub defeat, reconnect reset and both rendered menus. The ranged-source negative check uses an
untracked arrow damage source supplied by the test console, not a physically fired projectile.
See `docs/review/LOBBY-PVP-RUNTIME.md` for the exact feature boundary and latest PR evidence.

This does not certify TaCZ/SBW, autonomous projectile ownership, mixed-mod harmful effects,
open-container/cursor/accessory transactions, match waiting-room interactions or two-room gameplay.
The latter routing also has adapter-level regression coverage, not a live two-room acceptance run.
Do not extrapolate passing melee checks into production readiness or a complete issue #7.
