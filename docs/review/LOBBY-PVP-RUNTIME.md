# Experimental lobby PvP runtime

Refs blockfield-releases#7 and the shared consent requirement of #5. This branch preserves
PR #2's pure policy, PR #6's room/death isolation and PR #12's isolated rig histories.
It is not the complete weapon/side-effect/inventory epic. No new duel engine exists.

## Enable only on a disposable review server

`BLOCKFIELD_LOBBY_PVP_ENABLED` defaults to `true`; setting it to `false` leaves the existing combat decisions alone.
Explicit `true` enables a first runtime slice for **vanilla direct melee only**.
`BLOCKFIELD_PVP_COOLDOWN_SECONDS` is 10 by default, configurable from 1 to 60.
Both are fallbacks: `lobby.pvpEnabled` / `lobby.pvpCooldownSeconds` in the website settings file override them live
(see `docs/admin-settings.md`).
The portable rig uses a fresh flat world and fixed test-only profile; no production data.

Players use `/bf pvp`, `/bf pvp on`, `/bf pvp off` or the server-issued tablet buttons.
`/pvp` is an alias if no previously registered command occupies it. Permission decisions
and state live on the server, not in the menu or caller-supplied UUID. Repeated `on`/`off`
requests do not toggle unpredictably. `DISABLING` remains combat-enabled until the quiet
interval expires. Queries do not create attacks or extend combat activity. Menu snapshots
list up to 32 current hub participants and their actual consent state; these are not
continuous above-head indicators. The owner's action-bar countdown refreshes every second.

Real physical hub context is checked separately from room membership. Changing the player
handle or room identity resets consent. Active battle dimensions, respawn room, spectators,
spawn protection and the legacy overworld battle fallback are not voluntary PvP contexts.
Server stop, disconnect and respawn invalidate old consent/source generations.

## Damage and defeat

The existing `ConsentPvp` policy is called from a server-side vanilla melee frame, created
before attack fire/knockback/sweep. Passive targets reject the primary attack before its
side effects. Sweep knockback also has a separate gate, including a mob as the primary
target. Accepted melee and applied damage extend the relevant quiet interval. A blocked
hit does not tag the passive target or postpone its hub regeneration.

Melee requires the original direct attack frame. Projectile damage requires the launch token;
OFF → ON, disconnect and context changes invalidate that token. LR molotov clouds inherit
the grenade's launch authority rather than minting a fresh token at detonation. Ignition
records its consent generation for later source-less burn ticks. A disconnected owner
cannot turn a recorded projectile or fire into environmental damage. Harmful cloud/splash
effects and explosion push use the same boundary. SBW mine/vehicle explosions and impacts
without a live player owner are denied in voluntary areas, including abandoned devices.
Vehicle crush/OBB movement and rider collision are gated on both server and client: the
client otherwise predicts a push even after the server rejects it. Vanilla environmental
damage and player-attributed self damage retain their existing policies.

Fatal permitted melee is a soft lobby defeat: restore HP immediately, reset consent OFF,
then clear effects/fire/velocity and teleport to the configured hub spawn at END_SERVER_TICK
(after vanilla's post-hurt side effects). Use the same player, inventory and accessories;
never call `Hub.send`, which would clear them. No battle death/tickets/results are created.
A disconnected/replaced player handle is never teleported by a pending cleanup callback.
No replenishment of spent ammunition/durability, item copy or standard kit is introduced.

## Verification

```sh
bash dev/test-consent-pvp.sh
python3 -m unittest discover -s dev/rig/isolated -p 'test_*.py' -v
python3 -m unittest discover -s dev/rig/tests -v
python3 dev/rig/check-consent-machinery.py # two full-pack rig clients already in the hub
./gradlew --no-daemon build
./gradlew --no-daemon -p dev/rig/testbot build
python3 dev/rig/isolated/launch.py build --output /tmp/bf-pvp-build
python3 dev/rig/isolated/launch.py run \
  --image "$(cat /tmp/bf-pvp-build/image-id.txt)" \
  --output /tmp/bf-pvp-result --mode pvp --accept-eula
```

Output directories must be new. Run the game profile on a local Linux Docker engine only.
Two actual Fabric clients have separate names, UUIDs, working directories and loopback bot
ports. The one container runs one full Minecraft backend, has no external network or port
mappings, 6 GiB memory / 2 CPUs, read-only root and one fresh evidence mount. Builds finish
before game processes start. No home caches, SSH, LAN host or external process manager.

The new test-only `melee <visible-player>` command goes through the client's normal attack
packet and server vanilla damage pipeline; it is not RCON damage pretending to be melee.
RCON arranges positions, low HP and inventory fixtures, and reads server-side evidence.
The separate negative ranged-source test uses an explicitly untracked arrow DamageSource,
not a real in-world projectile. Reports and screenshots distinguish these boundaries.

Actual test results and current-head CI links are recorded in the PR; the presence of this
script is not proof that a game scenario passed. Unit tests mock handles and are distinct
from real game acceptance. JUnit adapters overlap the standalone policy assertions.

## Acceptance tracking

The current full-pack checklist and evidence are tracked in
[releases#7](https://github.com/Blockfield/blockfield-releases/issues/7).
The isolated scenario above covers its original melee scope; it does not replace the
three-client, weapon, container, room and transition checks recorded there. Reconnect
resets consent and keeps the existing login inventory reset policy.
