# Transformed anim-sync oracle runner

Owner: U10. Consumers: U1–U9 fixtures. Contract: ADR4/ADR4.1 §§8–10.

## Execution and isolation

`:client:oracleTest` and `:server:oracleTest` launch separate, bounded JavaExec processes through Fabric Loader **0.19.3**, the production pack loader. Both run from `just test` and belong to Gradle `check`/`build`, hence CI's `just java-check`. No Minecraft main, mod main/client entrypoints, world, window, GL context or live stand starts. The T16b disconnect fixture uses one bounded localhost socket pair to exercise Fabric’s real `channelInactive` dispatch; no gameplay listener starts. Loader **preLaunch** entrypoints and mixin plugins do run. `DISPLAY`/`WAYLAND_DISPLAY` are removed and AWT is headless. Each process exits explicitly on success or failure. The gate runs the fixtures as sequential shards (`dev/oracle-shards.json`): each shard task has its own hard timeout sized from measured quiet time times four, and `:client:oracleTest`/`:server:oracleTest` aggregate their side's shards. Config/cache files are confined to `<side>/build/anim-sync-oracle/run/`.

The harness compiles in named namespace and `remapOracleJar` remaps it to **intermediary**. Minecraft, Blockfield and every pack jar then execute in production's intermediary namespace. Third-party jars, their mixins, refmaps, access wideners/classTweakers and JiJ declarations stay unchanged. Fabric selects/extracts nested mods normally. Blockfield's normal `remapJar` supplies the current production mixins. The original 0.17.2 project compile dependency stays unchanged.

The original failure was a real bootstrap mismatch: Loader 0.19.3 requires Sponge Mixin `0.17.3+mixin.0.8.7` (`IAdviceProvider`), while the WIP inherited `0.16.3+mixin.0.8.7`. `oracleBootstrap` pins the production loader's ASM **9.10.1** and Mixin libraries and checks every library SHA-256 against its bundled `fabric-installer.json`. Loader supplies MixinExtras **0.5.4**. Production discovery uses `fabric.addMods`, not classpath discovery with development mode disabled.

A named-runtime attempt subsequently proved unsuitable: Porting Lib's intrinsic `applyComponents` bridge collides with vanilla's named method and displaces owo's injection target. Executing in intermediary avoids that namespace artifact. There are **no derived third-party feed jars** in the final runner: no Loom metadata stripping and no YACL classTweaker removal. The earlier WIP cache copies are not inputs. Reflection strings in fixtures use `OracleReflection` and the pinned mappings, because bytecode remapping does not rewrite arbitrary strings.

## Commands and results

Run through the workspace heavy lock, with the required JDK and memory preflight. The targeted commands are:

```sh
./gradlew --no-daemon --max-workers=2 :client:oracleTest :server:oracleTest
```

Verified after merging `5b84abbc017186c6a6603328be12650bce565ee9` from phase23: `just format`, `just check`, `just test`, and `just java-check` all passed. JUnit: client 494 cases, zero failures/errors, two existing opt-in image-baker/dump skips; server 1534 cases plus two allocation cases, no failures/errors/skips. All 13 GameTests passed (12 original workshop cases plus the integrated explosion case). The transformed runner passed 11 client checks and two server checks, producing nine client fragments and two server fragments, all `pass` with `mixinsAppliedInJvm:true`. Loaded mod counts including nested/builtin mods: client 188, server 161. All four renderer mods loaded; their configs have successful application evidence. The sole unavailable pack artifact is Blockfield Music's HTTP-404 pin. Verification runtime: pinned Temurin 21.0.12+8, Linux amd64, both JOML option assertions false.

Local evidence is retained in `build/anim-sync-oracle/verification/` (four command logs, GameTest XML and a fragment digest index), with per-side fragments in the paths below. The full mixed-renderer/live/owner gates are **not** relabelled complete. Reports are `<side>/build/anim-sync-oracle/fragments/*.json`, with `artifacts.json` and `unavailable.json` beside them. Failed required transformations fail the process; an unavailable artifact is not equality evidence.

## Proof fixtures and boundaries

- `BlockfieldMixinOracleFixture`: calls the **merged** `LivingEntity.blockfield$clearInterpolation` on a constructor-free test entity, with counters planted at three. Transformed result is zero; the plain target has no such method. It also requires successful application of `blockfield-client.mixins.json`, and asserts that requiring a deliberately absent config throws.
- `ThirdPartyTransformOracleFixture`: invokes PlayerAnimator's **merged player accessor** and manipulates the returned native animation stack; invokes ParCool's **merged custom-ladder method** with vertical/horizontal end rods and checks opposite outcomes; verifies TaCZ's merged setup handler. These are transformed-target calls, not stand-alone library-presence checks.
- `VanillaSetupAnimOracleFixture`: executes real transformed `HumanoidModel.prepareMobModel/setupAnim`, compares repeat samples bitwise and requires attack-age sensitivity. It supplies canonical empty hand stacks and a scoped mock Minecraft singleton with a null model loader for Accessories' constructor query. No Minecraft constructor runs. The entity is a mocked nonplayer; this fixture does **not** claim armed remote-player or full renderer-overlap coverage.
- `ServerMixinOracleFixture`: calls real transformed `handleKeepAlive`, planting a 4000 ms first sample. Blockfield produces the full sample rather than vanilla's quarter smoothing. It requires successful application of `blockfield-server.mixins.json`.
- `ModelTargetAuditFixture`: inventories configs and class annotations for HumanoidModel, PlayerModel, LivingEntityRenderer and PlayerRenderer. This is class-transformation/read-layout evidence; it does not execute GL drawing or prove hook order in an actual mixed render.

Renderer mods are included when their unmodified production preLaunch/plugins and exercised target transformations load without GL. Loading those classes does not exercise their initialized GPU rendering. Iris on/off, EntityCulling on/off, shadow/main pass order, final 48 corners, native resource-loaded player layers, timing receive/task/render fixtures, numerical cross-runtime certification and owner A/B remain downstream family/U10 integration or U11 gates. VanillaBackport is included; it declares no direct mixin on these four classes, so its wider player/animation read layout still needs the family integration fixture. The present proofs do not close ADR4's full mixed-renderer acceptance gate.

## Adding a transformed fixture

1. Add a class under `client/src/oracleTest/java/.../combat/oracle/` or the corresponding server tree. Use `@BeforeAll` (static, once per fixture class) and ordinary zero-argument `@Test` methods. The runner is deliberately small; other JUnit lifecycle features are not supported.
2. Register the class in that side's session `FIXTURES` list, and add it to exactly one shard in `dev/oracle-shards.json` (`verifyOracleShards` fails the gate when a registered fixture is in no shard or in two). Classpath scanning cannot silently add tests. Client families and kebab-case IDs are checked by `AnimSyncFixtureRegistry`; server uses the same registry through shared oracle support.
3. Client: wrap the native call in `AnimSyncOracleRunner.run(family, id, body)`. Server: use `ServerTransformedOracleSession.runFixture(family, id, body)`. Return JSON values including raw scalar bits, branches and relevant state. Do not compare two ports and call that native evidence.
4. Call `OracleTransformAudit.requireConfig` for each config whose execution your fixture needs. The audit observes successful Mixin `postApply` class nodes and their `MixinMerged` annotations; merely finding a config resource is insufficient. An intentionally absent config must fail. Accessor-only configs may have no merged method annotation: assert their actual injected interface/member directly and report that boundary.
5. Use fresh native scratch and assert repeated sampling leaves retained state unchanged; add the ADR4 family-specific checkpoint/continuation/FPS/pass cases. Reflection on vanilla fields goes through `OracleReflection`. Keep production injection-target pins in `OracleInjectionTargetTest`; they complement transformed execution.

## U10 additions

- `MixedRendererOracleFixture` (client) and its server twin run the full production renderer
  against `AnimSyncEval.pose` over shared cases (`dev/oracleTest/.../MixedRendererCases`); see
  [U10](anim-sync-u10.md).
- `dev/anim_sync_report.py` aggregates every fragment, the cross-JVM differentials and the
  checked-in manifests into `build/anim-sync-oracle/equality-report.json`; `just test` runs it after
  both oracle tasks.
- Fragments record the runtime matrix row (`OracleProvenance.runtime()`), and the audit records the
  merged handler call order of the six-box pipeline methods (`OracleTransformAudit.hookOrder()`).
- `-PoracleOnly=Name1,Name2` runs a subset of fixture classes while iterating; never for the gate.

## Fragment schema 2

Each fragment has `schemaVersion`, `unit`, `family`, `fixture`, `status` (`pass`/`fail`/`unavailable`), message, duration, generation time, runtime, classpath probes and measurements. Client fragments also contain the model-target/config registry. Both sides include loaded mods with versions and exact SHA-256 artifact identity (including nested children), the original input artifact inventory, explicit unavailable artifacts, and `appliedTransformations` with concrete target/mixin/config rows. Java's built-in mod fingerprints its JDK `release` file; runtime vendor/version/architecture are separate fields. `mixinsAppliedInJvm` is probed from successful transformation/member evidence, not a declaration that Knot was called. Main/client entrypoints are deliberately not invoked: fixtures must supply services they consume explicitly.

## Production pack audit

Snapshot: workspace client/server manifests, 2026-10-16. Every available executable is included unchanged, including its Loader-selected nested jars. No mixin config is removed or made optional. Client-only metadata is filtered by Fabric in SERVER. Blockfield uses the worktree’s remapped production jar instead of the released pack jar.

| Pack artifact | Side | Declared mixin configs | Oracle disposition |
| --- | --- | --- | --- |
| `CameraOverhaul-v2.1.1-fabric+mc[1.21-1.21.2].jar` | client | `cameraoverhaul.mixins.json` | included: production transformations |
| `CreativeCore_FABRIC_v2.13.39_mc1.21.1.jar` | client | `creativecore.mixins.json`, `creativecore.fabric.mixins.json` | included: production transformations |
| `Elite x Quality Guns Fabric v4.2.1 - 1.21.1-bf10.jar` | client/server | none | included: pack dependency/resource; no mixin config |
| `EnhancedVisuals_FABRIC_v1.8.30_mc1.21.1.jar` | client | `enhancedvisuals.mixins.json`, `enhancedvisuals.fabric.mixins.json` | included: production transformations |
| `FabricProxy-Lite-2.10.1.jar` | server | `core.mixins.json`, `hack.mixins.json` | included: production transformations |
| `ForgeConfigAPIPort-v21.1.6-1.21.1-Fabric.jar` | client/server | `forgeconfigapiport.common.mixins.json`, `forgeconfigapiport.fabric.mixins.json` | included: production transformations |
| `ImmediatelyFast-Fabric-1.6.13+1.21.1.jar` | client | `immediatelyfast-common.mixins.json`, `immediatelyfast-fabric.mixins.json` | included: production transformations |
| `Kiwi-1.21.1-Fabric-15.8.8.jar` | client/server | `kiwi.mixins.json` | included: production transformations |
| `Luckys-Cozyhome-Refurnished-1.1.20-bf1.jar` | client/server | none | included: pack dependency/resource; no mixin config |
| `ParCool-1.21.1-3.4.3.3-fabric-bf7.jar` | client/server | `parcool.mixins.json` | included: production transformations |
| `PassableFoliage-1.21.1-Fabric-9.2.1.jar` | client/server | `passablefoliage.mixins.json` | included: production transformations |
| `Ping-Wheel-1.12.2-fabric-1.21.1.jar` | client/server | `pingwheel.mixins.json` | included: production transformations |
| `Platform-fabric-1.21.1-1.3.3.jar` | client/server | `platform.mixins.json`, `platform-common.mixins.json` | included: production transformations |
| `TACZ-Refabricated-1.21.1-0.7.0-bf8.jar` | client/server | `tacz.mixins.json`, `tacz.fabric.mixins.json`, `tacz.compat.acceleratedrendering.mixins.json` | included: production transformations |
| `VanillaBackport-fabric-1.21.1-1.1.7.10-bf1.jar` | client/server | `vanillabackport.mixins.json` | included: production transformations |
| `accessories-fabric-1.1.0-beta.53+1.21.1.jar` | client/server | `accessories-fabric.mixins.json`, `accessories-common.mixins.json` | included: production transformations |
| `architectury-13.0.11-fabric.jar` | client/server | `architectury-common.mixins.json`, `architectury.mixins.json` | included: production transformations |
| `blockfield-client-1.50.0+mc1.21.1.jar` | client | current `client/src/main/resources/fabric.mod.json` | included: production transformations |
| `blockfield-decor-1.2.1+mc1.21.1.jar` | client/server | `blockfield-decor.mixins.json` | included: production transformations |
| `blockfield-music-6.jar` | client | none | unavailable: pinned URL returns HTTP 404; configs cannot be inspected |
| `blockfield-server-1.50.0+mc1.21.1.jar` | server | current `server/src/main/resources/fabric.mod.json` | included: production transformations |
| `cloth-config-15.0.140-fabric.jar` | client/server | none | included: pack dependency/resource; no mixin config |
| `copperagebackport-fabric-1.21.1-0.1.5.jar` | client/server | `copperagebackport.fabric.mixins.json` | included: production transformations |
| `create-fabric-6.0.0.0+mc1.21.1-bf4.jar` | client/server | `create.mixins.json` | included: production transformations |
| `crossstitch-0.1.6.jar` | server | `crossstitch.mixins.json` | included: production transformations |
| `dynamic-fps-3.11.4+minecraft-1.21.0-fabric.jar` | client | `dynamic_fps.mixins.json`, `dynamic_fps-common.mixins.json`, `dynamic_fps-textile.mixins.json` | included: production transformations |
| `entityculling-fabric-1.10.5-mc1.21.1.jar` | client | `entityculling.mixins.json` | included: production transformations |
| `fabric-api-0.116.15+1.21.1.jar` | client/server | none | included: pack dependency/resource; no mixin config |
| `fabric-language-kotlin-1.13.13+kotlin.2.4.10.jar` | client/server | none | included: pack dependency/resource; no mixin config |
| `ferritecore-7.0.3-fabric.jar` | client/server | `ferritecore.accessors.mixin.json`, `ferritecore.blockstatecache.mixin.json`, `ferritecore.dedupmultipart.mixin.json`, `ferritecore.fastmap.mixin.json`, `ferritecore.mrl.mixin.json`, `ferritecore.predicates.mixin.json`, `ferritecore.dedupbakedquad.mixin.json`, `ferritecore.threaddetec.mixin.json`, `ferritecore.modelsides.mixin.json`, `ferritecore.datacomponents.mixin.json`, `ferritecore.fabric.mixin.json` | included: production transformations |
| `fusion-1.3.14-fabric-mc1.21.jar` | client/server | `fusion.mixins.json` | included: production transformations |
| `geckolib-fabric-1.21.1-4.7.5.jar` | client/server | `geckolib.mixins.json` | included: production transformations |
| `iris-fabric-1.8.14-beta.1+mc1.21.1.jar` | client | `mixins.iris.json`, `mixins.iris.fabric.json`, `iris-batched-entity-rendering.mixins.json`, `mixins.iris.fantastic.json`, `mixins.iris.vertexformat.json`, `mixins.iris.bettermipmaps.json`, `mixins.iris.compat.sodium.json`, `mixins.iris.compat.dh.json`, `mixins.iris.fixes.maxfpscrash.json` | included: production transformations |
| `jeremyseqsdamageindicators-2.2.1-1.21.1-fabric.jar` | client | `jeremyseqsdamageindicators.mixins.json`, `jeremyseqsdamageindicators.client.mixins.json` | included: production transformations |
| `keybindfixplus-2.0.0.jar` | client/server | `keybindfixplus.mixins.json` | included: production transformations |
| `krypton-0.2.8.jar` | client/server | `krypton.mixins.json` | included: production transformations |
| `lexiconfig-fabric-1.4.21-novel.jar` | client | `lexiconfig.mixins.json`, `lexiconfig.fabric.mixins.json` | included: production transformations |
| `lithium-fabric-0.15.4+mc1.21.1.jar` | client/server | `lithium.mixins.json`, `lithium-fabric.mixins.json` | included: production transformations |
| `lrtactical-1.21.1-0.4.1-bf2.jar` | client/server | `lrtactical.mixins.json` | included: production transformations |
| `milk-lib-1.1.0.jar` | client/server | `milk-lib.mixins.json` | included: production transformations |
| `modernfix-fabric-5.25.1+mc1.21.1.jar` | client | `modernfix-fabric.mixins.json`, `modernfix-common.mixins.json` | included: production transformations |
| `owo-lib-0.12.15.4+1.21.jar` | client/server | `owo.mixins.json` | included: production transformations |
| `player-animation-lib-fabric-2.0.4+1.21.1.jar` | client/server | `playerAnimator-common.mixins.json` | included: production transformations |
| `porting_lib_accessors-3.1.0-beta.54+1.21.1.jar` | client/server | `porting_lib_accessors.mixins.json` | included: production transformations |
| `porting_lib_attributes-3.1.0-beta.90+1.21.1.jar` | client/server | `porting_lib_attributes.mixins.json` | included: production transformations |
| `porting_lib_base-3.1.0-beta.90+1.21.1.jar` | client/server | `porting_lib_base.mixins.json` | included: production transformations |
| `porting_lib_blocks-3.1.0-beta.90+1.21.1.jar` | client/server | `porting_lib_blocks.mixins.json` | included: production transformations |
| `porting_lib_brewing-3.1.0-beta.90+1.21.1.jar` | client/server | `porting_lib_brewing.mixins.json` | included: production transformations |
| `porting_lib_chunk_loading-3.1.0-beta.90+1.21.1.jar` | client/server | `porting_lib_chunk_loading.mixins.json` | included: production transformations |
| `porting_lib_client_events-3.1.0-beta.90+1.21.1.jar` | client/server | `porting_lib_client_events.mixins.json` | included: production transformations |
| `porting_lib_common-3.1.0-beta.90+1.21.1.jar` | client/server | `porting_lib_common.mixins.json` | included: production transformations |
| `porting_lib_config-3.1.0-beta.90+1.21.1.jar` | client/server | `porting_lib_config.mixins.json` | included: production transformations |
| `porting_lib_core-3.1.0-beta.90+1.21.1.jar` | client/server | none | included: pack dependency/resource; no mixin config |
| `porting_lib_data-3.1.0-beta.90+1.21.1.jar` | client/server | `porting_lib_data.mixins.json` | included: production transformations |
| `porting_lib_entity-3.1.0-beta.90+1.21.1.jar` | client/server | `porting_lib_entity.mixins.json` | included: production transformations |
| `porting_lib_fluids-3.1.0-beta.90+1.21.1.jar` | client/server | `porting_lib_fluids.mixins.json` | included: production transformations |
| `porting_lib_gametest-3.1.0-beta.90+1.21.1.jar` | client/server | `porting_lib_gametest.mixins.json` | included: production transformations |
| `porting_lib_gui_utils-3.1.0-beta.90+1.21.1.jar` | client/server | `porting_lib_gui_utils.mixins.json` | included: production transformations |
| `porting_lib_item_abilities-3.1.0-beta.90+1.21.1.jar` | client/server | `porting_lib_item_abilities.mixins.json` | included: production transformations |
| `porting_lib_items-3.1.0-beta.90+1.21.1.jar` | client/server | `porting_lib_items.mixins.json` | included: production transformations |
| `porting_lib_lazy_registration-3.1.0-beta.54+1.21.1.jar` | client/server | none | included: pack dependency/resource; no mixin config |
| `porting_lib_level_events-3.1.0-beta.90+1.21.1.jar` | client/server | `porting_lib_level_events.mixins.json` | included: production transformations |
| `porting_lib_loot-3.1.0-beta.90+1.21.1.jar` | client/server | `porting_lib_loot.mixins.json` | included: production transformations |
| `porting_lib_mixin_extensions-3.1.0-beta.90+1.21.1.jar` | client/server | `porting_lib_mixin_extensions.mixins.json` | included: production transformations |
| `porting_lib_model_loader-3.1.0-beta.90+1.21.1.jar` | client/server | `porting_lib_model_loader.mixins.json` | included: production transformations |
| `porting_lib_models-3.1.0-beta.90+1.21.1.jar` | client/server | `porting_lib_models.mixins.json` | included: production transformations |
| `porting_lib_obj_loader-3.1.0-beta.90+1.21.1.jar` | client/server | none | included: pack dependency/resource; no mixin config |
| `porting_lib_registry-3.1.0-beta.90+1.21.1.jar` | client/server | `porting_lib_registry.mixins.json` | included: production transformations |
| `porting_lib_render_types-3.1.0-beta.90+1.21.1.jar` | client/server | none | included: pack dependency/resource; no mixin config |
| `porting_lib_resources-3.1.0-beta.90+1.21.1.jar` | client/server | `porting_lib_resources.mixins.json` | included: production transformations |
| `porting_lib_tags-3.1.0-beta.90+1.21.1.jar` | client/server | `porting_lib_tags.mixins.json` | included: production transformations |
| `porting_lib_transfer-3.1.0-beta.90+1.21.1.jar` | client/server | `porting_lib_transfer.mixins.json` | included: production transformations |
| `raknetify-fabric-0.1.0+alpha.5.196-all.jar` | client | `raknetify-fabric.mixins.json` | included: production transformations |
| `rechiseled-1.2.5-fabric-mc1.21.jar` | client/server | `rechiseled.mixins.json` | included: production transformations |
| `simplebedrockmodel-fabric-2.5.1+mc1.21.1-bf4.jar` | client/server | `simplebedrockmodel.mixins.json`, `simplebedrockmodel.fabric.mixins.json` | included: production transformations |
| `skinrestorer-2.10.0+1.21-fabric.jar` | server | `skinrestorer.mixins.json`, `skinrestorer.fabric.mixins.json` | included: production transformations |
| `sodium-fabric-0.8.13+mc1.21.1.jar` | client | `sodium-common.mixins.json`, `sodium-fabric.mixins.json` | included: production transformations |
| `sound-physics-remastered-fabric-1.21.1-1.5.1.jar` | client | `sound_physics_remastered.mixins.json` | included: production transformations |
| `spark-1.10.109-fabric.jar` | server | `spark.mixins.json` | included: production transformations |
| `superbwarfare-0.8.9.1-mc1.21.1-bf24.jar` | client/server | `mixins.superbwarfare.json` | included: production transformations |
| `supermartijn642configlib-1.1.8-fabric-mc1.21.jar` | client/server | `supermartijn642configlib.mixins.json` | included: production transformations |
| `supermartijn642corelib-1.1.24-fabric-mc1.21.jar` | client/server | `supermartijn642corelib.mixins.json` | included: production transformations |
| `tacz-tweaks-3.0.0-alpha.10+1.21.1-fabric.jar` | client/server | `tacztweaks.mixins.json`, `tacztweaks-fabric.mixins.json` | included: production transformations |
| `voicechat-fabric-1.21.1-2.6.22.jar` | client/server | `voicechat.mixins.json` | included: production transformations |
| `worldedit-mod-7.3.8.jar` | client | `worldedit-fabric.mixins.json` | included: production transformations |
| `yet_another_config_lib_v3-3.8.2+1.21.1-fabric.jar` | client/server | `yacl.mixins.json`, `yacl-fabric.mixins.json` | included: production transformations |
| `yuushya-1.21.0-fabric-2.3.0-bf3.jar` | client/server | `yuushya.mixins.json`, `yuushya-common.mixins.json` | included: production transformations |

### Mixins touching the four model/render targets

This list comes from the pinned class annotations and configs, not class-name guesses. The runtime audit repeats it for the actual transformed classpath. Registration is separate from successful application; `appliedTransformations` records only successful `postApply` callbacks.

| Target | Config | Mixin |
| --- | --- | --- |
| `LivingEntityRenderer` | `accessories-fabric.mixins.json` | `io.wispforest.accessories.fabric.mixin.client.LivingEntityRendererMixin` |
| `LivingEntityRenderer` | `accessories-common.mixins.json` | `io.wispforest.accessories.mixin.client.LivingEntityRendererAccessor` |
| `PlayerRenderer` | `accessories-common.mixins.json` | `io.wispforest.accessories.mixin.client.PlayerRendererMixin` |
| `HumanoidModel` | `create.mixins.json` | `com.simibubi.create.foundation.mixin.client.HumanoidModelMixin` |
| `PlayerRenderer` | `create.mixins.json` | `com.simibubi.create.foundation.mixin.client.PlayerRendererMixin` |
| `LivingEntityRenderer` | `create.mixins.json` | `com.simibubi.create.foundation.mixin.fabric.LivingEntityRendererAccessor` |
| `PlayerModel` | `lrtactical.mixins.json` | `me.xjqsh.lrtactical.mixin.client.PlayerModelMixin` |
| `LivingEntityRenderer` | `modernfix-common.mixins.json` | `org.embeddedt.modernfix.common.mixin.safety.LivingEntityRendererMixin` |
| `LivingEntityRenderer` | `parcool.mixins.json` | `com.alrex.parcool.mixin.client.LivingRendererMixin` |
| `PlayerModel` | `parcool.mixins.json` | `com.alrex.parcool.mixin.client.PlayerModelMixin` |
| `PlayerRenderer` | `parcool.mixins.json` | `com.alrex.parcool.mixin.client.PlayerRendererMixin` |
| `HumanoidModel` | `playerAnimator-common.mixins.json` | `dev.kosmx.playerAnim.mixin.BipedEntityModelMixin` |
| `LivingEntityRenderer` | `playerAnimator-common.mixins.json` | `dev.kosmx.playerAnim.mixin.LivingEntityRenderRedirect_bendOnly` |
| `PlayerModel` | `playerAnimator-common.mixins.json` | `dev.kosmx.playerAnim.mixin.PlayerModelAccessor` |
| `PlayerModel` | `playerAnimator-common.mixins.json` | `dev.kosmx.playerAnim.mixin.PlayerModelMixin` |
| `PlayerRenderer` | `playerAnimator-common.mixins.json` | `dev.kosmx.playerAnim.mixin.PlayerRendererMixin` |
| `LivingEntityRenderer` | `playerAnimator-common.mixins.json` | `dev.kosmx.playerAnim.mixin.firstPerson.LivingEntityRendererMixin` |
| `HumanoidModel` | `porting_lib_accessors.mixins.json` | `io.github.fabricators_of_create.porting_lib.mixin.accessors.client.accessor.HumanoidModelAccessor` |
| `LivingEntityRenderer` | `porting_lib_base.mixins.json` | `io.github.fabricators_of_create.porting_lib.mixin.client.LivingEntityRendererMixin` |
| `PlayerRenderer` | `porting_lib_base.mixins.json` | `io.github.fabricators_of_create.porting_lib.mixin.client.PlayerRendererMixin` |
| `PlayerRenderer` | `porting_lib_client_events.mixins.json` | `io.github.fabricators_of_create.porting_lib.client_events.mixin.client.PlayerRendererMixin` |
| `PlayerRenderer` | `voicechat.mixins.json` | `de.maxhenkel.voicechat.mixin.PlayerRendererMixin` |
| `PlayerModel` | `simplebedrockmodel.mixins.json` | `com.github.mcmodderanchor.simplebedrockmodel.v1.mixin.client.PlayerModelMixin` |
| `PlayerRenderer` | `simplebedrockmodel.fabric.mixins.json` | `cn.sh1rocu.simplebedrockmodel.mixin.client.PlayerRendererMixin` |
| `HumanoidModel` | `mixins.superbwarfare.json` | `com.atsuishio.superbwarfare.mixins.HumanoidModelMixin` |
| `LivingEntityRenderer` | `mixins.superbwarfare.json` | `com.atsuishio.superbwarfare.mixins.LivingEntityRendererMixin` |
| `PlayerRenderer` | `mixins.superbwarfare.json` | `com.atsuishio.superbwarfare.mixins.PlayerRendererMixin` |
| `HumanoidModel` | `tacz.mixins.json` | `com.tacz.guns.mixin.client.HumanoidModelMixin` |
| `PlayerModel` | `tacz.mixins.json` | `com.tacz.guns.mixin.client.PlayerModelMixin` |
| `PlayerRenderer` | `tacztweaks.mixins.json` | `me.muksc.tacztweaks.mixin.feature.audio_and_visuals.visuals.smoother_crawl_animation.PlayerRendererMixin` |
