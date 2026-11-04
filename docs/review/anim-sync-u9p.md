# U9p animation-policy slice

Contract: ADR4.1 §6 (policy getters, locked GUI toggles, restore at disconnect) and §11 row U9.
Base: `fulla/phase23` `7aeec56d` (U9a getter overrides). Branch/worktree: `fulla/as-u9p`,
`WS/.dev/worktrees/as-u9p`. No ParCool fork change is needed; U9b's manifest/login gate is untouched.

## 1. One animator registry

`client.combat.AnimationPolicy.ANIMATORS` is now derived from U1's
`common.combat.AnimationPolicy.registered()` (the first 26 `AnimatorTag`s), mapped to class names
(`DIVE_HOST` → `DiveAnimationHostAnimator`, otherwise CamelCase + `Animator`). The old hand-written
list was alphabetical; native `AnimatorList` order has `HideInBlock` (12) before `Hang` (16).

- `AnimationPolicyTest.registryIsTheFirst26AnimatorTagsInOrder` checks the list index by index against the tags.
- `AnimationPolicyPinTest.parCoolRegistryStillHas26AnimatorsAndNoAlternate` now compares the
  pinned bf7 `AnimatorList.<clinit>` class literals **in native order** (previously sorted).

Both fail on the old list.

## 2. TaCZ Tweaks smoother-crawl widget lock

The widget can be locked, so no fallback was needed. In pinned alpha.10, the YACL screen builds the
option from the property reference `Config$generateConfigScreen$1$4$3$7`
(→ `Visuals.getSmootherCrawlAnimation()`). It goes through the private `Config.option(OptionGroupBuilder,
KProperty0, Z, Function1)` (via `option$default`). That method's indy lambda `option$lambda$1` binds
`ConfigEntry.asBinding()` and then runs the option's own setup. The new
`client/mixin/tweaks/AnimationPolicyTweaksScreenMixin` injects at that lambda's TAIL. While
`lockedSmootherCrawl()` holds and the property is `smootherCrawlAnimation`, it calls YACL
`Option.Builder.available(false)`. YACL is not on the compile classpath, so that single call goes
through its public API interface reflectively and throws loudly if the API changes. The widget shows
the stored value; the screen is rebuilt on every open. Registered in `blockfield-client-tweaks.mixins.json`
(same plugin gate as U9a's getter hook).

Read closure, a whole-jar ASM scan of the pinned jar (`AnimationPolicyPinTest.taczTweaksScreenLockTargetAndReadClosure`,
digest-checked jar):

- `Visuals.smootherCrawlAnimation()Z` (hooked by U9a) is called only by the three renderer
  handlers `alwaysTranslate`, `modifyYTranslate` and `lerpZTranslate` in
  `PlayerRendererMixin`.
- `getSmootherCrawlAnimation` is called only by that getter and the screen property reference.
- `smootherCrawlAnimation$delegate` is used only by `Visuals.<clinit>` and the accessor. The entry is
  registered with plain `KotlinExtsKt.register`, not the synced path. The only other mention is a JSON-key
  string in `migration/V1Kt`.

The same test also pins the screen chain: one builder constructs `$4$3$7` and calls
`option$default(...KProperty0...)`, the KProperty0 `option` bootstraps exactly `option$lambda$1`, and
binding precedes `Function1.invoke`.

## 3. ParCool ClientInfo timing

Traced on the pinned bytecode: ParCool bf7 `9a0ec4e2…`, Fabric networking 4.3.1 (pack fabric-api 0.116.15),
porting_lib_entity 3.1.0-beta.90, and 1.21.1 `ClientPacketListener`.

1. Fabric `ClientPlayNetworkHandlerMixin.initAddon` runs at `ClientPacketListener.<init>` RETURN
   and calls `lateInit → invokeInitEvent`, which fires `ClientPlayConnectionEvents.INIT`.
2. `handleLogin` (`onGameJoin`) creates the level and the `LocalPlayer`, then calls `ClientLevel.addEntity(player)`
   (offset 317).
3. porting_lib `ClientLevelMixin.port_lib$addEntityEvent` (@HEAD `addEntity`) fires
   `EntityJoinLevelEvent`. ParCool `PlayerJoinHandler.onPlayerJoin` (LocalPlayer only) calls
   `ClientSetting.readFromLocalConfig()`, which reads every hooked `Booleans.get()`. It stores the
   snapshot and calls `PacketDistributor.sendToServer(new ClientInformationPayload(uuid, true, snapshot))`.
   That goes through `ClientPlayNetworking.send` with no `canSend` gate. The values are fixed at snapshot
   time; the Netty encode only serializes them.
4. Fabric `handleServerPlayReady` (@RETURN `onGameJoin`) then fires `JOIN`.

U9a installed on JOIN (step 4), so the first admitted ClientInfo carried the stored user values. The
fix is mod-side and needs no fork hook: `AnimationPolicy.register()` installs on play **INIT** (step 1).
DISCONNECT still restores. Later snapshots made while connected also see the forced values: respawn and
dimension re-adds, ParCool's settings screens, and the enable toggle. `trySyncLimitation` resends the
existing snapshot.

Evidence:

- `AnimationPolicyPinTest.policyInstallsBeforeParCoolJoinSnapshot` checks `register()` reads `INIT`
  and not `JOIN`, the snapshot/send order in `PlayerJoinHandler`, `readFromLocalConfig → Booleans.get`,
  `handleLogin → ClientLevel.addEntity`, and Fabric's two inject points.
- Transformed oracle `AnimationPolicyOracleFixture.firstParCoolClientInfoCarriesForcedValues`
  (`parcool.policy-first-client-info`) loads ParCool's real client spec over an in-memory
  NightConfig, with stored values `false` for the three forced toggles and for the personal
  `EnableCameraAnimationOfRolling`. It fires the real Fabric `INIT`/`DISCONNECT` invokers around the
  native `readFromLocalConfig` + `ClientInformationPayload.CODEC` encode/decode (the server's view):
  - before INIT: all three `false`;
  - first admitted after INIT: all three `true`, personal `false`, stored config still `false`;
  - after DISCONNECT: all three `false`.

  The raw payload hex is in the fragment. It requires `AnimationPolicyParCoolMixin` applied to `Booleans`.
- `AnimationPolicyOracleFixture.tweaksSmootherCrawlWidgetLocksWhileForced`
  (`tacz.policy-tweaks-widget-lock`) builds the option through the native private `option(KProperty0)`
  path, with the screen's own property reference and option lambda. While forcing:
  `available=false`, `pendingValue=false` (stored), getter `true`. After restore: `available=true`, getter
  `false`. It requires `AnimationPolicyTweaksScreenMixin` applied to `Config`.

Negative control, run once locally and not committed: with `register()` back on JOIN and the screen mixin
removed from its config, `:client:oracleTest` failed both fixtures. The messages were
`EnableAnimation ==> expected: <true> but was: <false>` and `widget locked ==> expected: <false> but was: <true>`.

## Left for U9b and others

- The policy still forces on **every** play connection, as in U9a. Scoping it to Blockfield
  servers and the READY/activation fence belong to U9b's login gate.
- Server side (U5/U9b): compliant clients now send the forced values in their first ClientInfo,
  sent during `handleLogin`. The server must still own the policy values and must not trust them,
  because a modified client can send anything. U5's decision to ignore the pre-JOIN snapshot stays valid.
- Both GUI locks are evaluated when the screen is built. A screen left open across a
  connect/disconnect keeps its build-time state until reopened, the same as U9a's ParCool screen lock.
- Not proven here: an actual handshake with a live server or stand. The oracle proves the ordering on
  the native event and codec paths only.
