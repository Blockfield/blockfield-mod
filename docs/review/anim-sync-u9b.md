# U9b resources/policy activation API and handoff

Contract: ADR4.1 §6, §10 resource/policy gate, §11 row U9 (remainder after U9p) and both errata.
Branch/worktree `fulla/as-u9b`, `WS/.dev/worktrees/as-u9b`, base phase23 `245c3a64`. The U1 schema
(manifest 4) is unchanged: the new payloads are U9-owned and outside `AnimationSchema`.

## Manifest (`common.combat.AnimationManifest`)

One immutable value per resource generation: `(resourceGeneration, AnimationPolicy, sorted entries)`.

| Key | Value |
| --- | --- |
| `compat.schema`, `compat.reducer`, `compat.geometry` | `AnimationSchema.layoutDigest()`, `ObserverReducer.DIGEST`, `BodyGeometry.DIGEST` |
| `compat.animators`, `compat.pa-roots`, `compat.joml` | registered animator order, PA root priorities, the effective JOML `FASTMATH`/`USE_MATH_FMA`/`SIN_LOOKUP` |
| `compat.native.<mod>` | SHA-256 of the JAR each family mod was loaded from (`tacz`, `lrtactical`, `parcool`, `playeranimator`, `superbwarfare`, `create`, `tacztweaks`); fork builds keep upstream versions in metadata, so only bytes identify a pin. `PINS` holds the ADR4 §13 values for logging only. |
| `compat.family.parcool` | `ParCoolCheckpoints.familyIdentity()`: ParCool reducer versions, action registration, duration/removal constants, forced settings (U7c, [handoff](anim-sync-u7c-parcool.md)) |
| `tacz.display:<id>` | dependency digest of every effective TaCZ display of the generation catalog (its bytes name every clip ref it can select) |
| `lr.display:<id>`, `lr.assets` | every effective LR melee display; `LrAssets.manifest(winner)` with the actual rotation alias winner |

`compatibilityDigest()` hashes the `compat.*` entries (stamped `compatibilityDigest` of every
record); `manifestDigest()` hashes generation, policy and the resource entries (stamped
`manifestDigest`). `mismatches(values)` is the one comparison both peers run on positional values.
`TaczAssets.displays()` and `LrAssets.displays()` are new read-only accessors for the collector.
`LrAssets.DisplayFile` is the server's LR display reading (`LrBodyPose.parseDisplay` +
`generation`), shared so the client oracle can compare it with LR's native loader;
`LrDisplayFileParityTest` pins it to `LrBodyPose`.

## READY protocol

1. `HeldWeaponPose.reload` → `LrBodyPose.reload` → `AnimSyncResources.activate(server, G)`:
   builds the manifest, installs `AnimSync.manifest(digest)` only when both TaCZ and LR views
   exist (otherwise `manifest_unavailable`, logged), and announces G to every connection that
   answered an earlier READY. `AnimSync.compatibilityDigest()` is now the full compat digest.
2. Before JOIN: `AnimationResourceGate` (called from `PackVersionGate.init`, also on dev stands)
   adds a Fabric **configuration task** sending `AnimationManifestS2CPacket`. The client
   (`ClientAnimationResources`) answers once no resource reload is running: it builds the
   generation's views from the native effective managers (TaCZ `ClientAssetsManager` display
   POJOs + `PlayerAnimatorAssetManager`, LR `meleeDisplay`), only for keys the server named, under
   the server's generation number, and sends `AnimationReadyC2SPacket` (its value per entry, `null`
   when it cannot produce one).
3. Any differing entry refuses JOIN with up to five resource ids ("Ресурсы боевых анимаций…" or,
   for `compat.*` only, "Моды анимаций…"); full list in the server log. Stale generation/policy or
   shape, no answer within 30 s, or a client without the channel (production) also refuse; a dev
   stand admits a no-mod client without combat resources. No other admission path exists.
4. On an exact match the client installs `ClientAnimationAssets.install(generation, compat, manifest)`
   (only records stamped with both admitted digests use it) and arms the server's policy.
5. Mid-session generation (`/reload`, gun asset reload): a play `AnimationManifestS2CPacket`; the
   client answers the same way (mismatch → disconnect). Until it answers, records of G+1 find no
   view (`resource_unavailable`, native presentation, no ticket). The first checkpoint of every life
   in G+1 is FULL|RESET (`AnimSyncCapture.generationBoundary`), so R and A never splice
   generations and the observer seed of G+1 activates once the view is installed.
6. Reload fence: a client resource reload never replaces an installed view (private copies keep
   presenting); the live resources are revalidated against the admitted manifest and the client
   disconnects itself on any difference.

Retention: superseded generations stay resolvable (`HeldWeaponPose.assets`, `LrBodyPose.assets`,
`AnimSyncResources.manifest`) while any store lease pins one of their definitions
(`AnimSyncLedger.pinned`): S[t] rings, observer seeds, send logs, pending records. Then they retire
together with the capture definition index. This replaces U3f's two-generation `ponytail`.
Validation still accepts only receipts of the active generation, so a ticket issued before an
activation is `unavailable:generation` (explicit labelled reset of in-flight tickets; retained
assets keep advancing/fused shots and folds resolvable).

## Policy scope and ownership

`client.combat.AnimationPolicy`: `arm(policy)` only from a validated READY; play INIT forces only
when armed; every configuration INIT, configuration/play DISCONNECT disarms (and clears installed
generations at configuration INIT). Effective values come from the server's
`common.combat.AnimationPolicy` (all forced true by its invariant). Non-Blockfield servers and
singleplayer keep the user's values. Server side the owner is `HeldWeaponPose.policy()`; the
client's ParCool ClientInfo is not a policy source (see U5 below).

## Evidence

Plain JUnit: server `AnimationManifestTest` (wire round trip, trailing/unordered/unknown/
noncanonical rejection, positional mismatches, digest separation, pinned-family coverage, clip
sensitivity, rotation rule), `AnimSyncResourcesTest` (activation installs only with both views,
twenty reloads keep only the pinned and active generations, release retires, FULL|RESET boundary,
server/shared rotation rule), `AnimationResourceGateTest` (admit, combat-override and code-mismatch
messages, stale/shape refusals), `LrDisplayFileParityTest`, `HeldWeaponPoseGraphTest` (lease
retirement); client `AnimationPolicyTest` (unarmed connection, refused configuration).

Transformed (production Knot, intermediary, full pack mod list):

- client `tacz.u9b-production-packs-manifest`: TaCZ's and LR's native reloads run over a real
  `MultiPackResourceManager` of the production gun/melee packs (TaCZ default, Elite x Quality Guns,
  LR JAR); the production resolver reproduces all 110 entries (92 TaCZ, 3 LR displays) of the
  manifest built by the shared server reading; all seven family JARs are the ADR4 pins.
- client `tacz.u9b-user-pack-precedence`: a user pack on top that changes `ak47_display`'s
  third-person fallback refuses exactly that key; a changed `rifle_default` animator file refuses
  every display using it; a changed LR `baseball_bat` section refuses it and `lr.assets`; a pack
  changing only HUD/texture is accepted.
- client `blockfield.u9b-ready-activation-fence`: a refused READY installs and arms nothing; the
  exact one installs before JOIN, arms only at play INIT, rejects records with other digests;
  a reload to a combat pack is fenced while the installed view keeps presenting; G+1 installs
  beside the retained G; DISCONNECT restores and the next server must arm again.
- server `tacz.u9b-server-generation-manifest`: the actual `HeldWeaponPose.reload` →
  `LrBodyPose.reload` → `AnimSyncResources.activate` over the same packs equals the shared
  reading entry by entry; same compat digest as the client run (`9bfa8c34…`).
- client `parcool.policy-first-client-info` (U9p) now also proves that an unarmed INIT forces
  nothing; `tacz.policy-tweaks-widget-lock` arms first.

## Deviations from ADR4

- **Configuration task instead of a login query.** ADR4 §6.3 names the login query. Behind the
  Blockfield Velocity proxy a backend login query never reaches the client:
  `blockfield-proxy` `LoginForwarding.onBackendLoginMessage` only replays its own cached
  proxy-login answers for `pack_version`/`pack_contract` (and answers TaCZ Tweaks itself).
  Fabric configuration payloads are relayed, run on every backend join/switch and still precede
  JOIN, so admission is unchanged: no combat-ready player is admitted before READY.
- **Native pins by JAR bytes, not versions** (fork metadata keeps upstream versions).
- **TaCZ client displays from POJOs**: TaCZ fills `ClientIndexManager.GUN_DISPLAY` only on the
  play-phase gun pack sync (and `GunDisplayInstance` construction loads models), so the resolver
  applies native `initBase`'s three third-person fields to the `ClientAssetsManager` POJO
  (`RemoteTaczAssets.retain(..., GunDisplay)`).

## Open items for other units

- **U5 (ParCool):** done by U7c ([handoff](anim-sync-u7c-parcool.md)): replace `compat.family.parcool` with the formula/configured-duration
  contribution (both peers must compute it) when the cohort is enabled; `ParCoolPoseInputs` on
  phase23 still reads `EnableLeanAnimationOfFastRun` from the client's ClientInfo (as-u5c already
  ignores it): read `HeldWeaponPose.policy().enableFastRunLean()` instead.
- **U6 (Create/SBW):** done by U8b (`common.combat.SbwLayouts`, `CreateHanging`, `compat.family.sbw/create`). Earlier: `SbwBodyPose.Layouts` and the Create phase adapter were server-only code;
  move their read-layout identity to common so both peers hash it (`compat.family.sbw/create`).
  Today their JAR bytes (`compat.native.*`) and `ObserverReducer.DIGEST` cover them.
- **U7:** done by U8b: a receipt whose generation is not
  `AnimationResourceGate.activated(player)` is `rejected:generation_not_activated`.
- **U10/U11:** the configuration task over a real connection and through Velocity, the client
  rotation winner from real entrypoint order (fixtures call the factory inits themselves), and
  F3+T with a live pack switch are not exercised offline.
- **Pin promotion (coordinator, owner-gated):** TaCZ Tweaks alpha.10 `9b8606ee…` is now part of the
  compared compatibility; promote it into the paired client/server pack pins with the ParCool fork
  pin once U5's fork branch is final (no fork pin promoted here). The workspace architecture map
  needs the new `blockfield:animation_manifest`/`animation_ready` configuration channels.
