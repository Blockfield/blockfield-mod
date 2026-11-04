# Creative ammunition box / rig regression (releases#11)

Issue: https://github.com/Blockfield/blockfield-releases/issues/11

## Confirmed source mismatch and remaining uncertainty

Both existing rig filters admitted only `tacz:*ammo*`. Superb Warfare registers a separate `superbwarfare:creative_ammo_box`, so that item was deterministically rejected. TACZ creative variants use a TACZ ammo-box item and were already admitted by the old filter. The actual registry ID/components in the reported live lobby chest have NOT been captured; do not infer them from the translated display name. Confirm this first during acceptance. If it is a TACZ box, investigate the reported auto-pickup path further rather than declaring this SBW fix sufficient.

## Implementation

- One common storage predicate now serves automatic absorption, manual writes and the client/server menu. It retains TACZ behavior and admits only the exact SBW creative box, not an arbitrary SBW namespace or items named like ammo.
- Storage retains the complete ItemStack data and existing Amount encoding; the box is not converted into consumable cartridges.
- A shared optional SBW mixin extends `InventoryTool.hasItem` only when the query is for that creative box and the owner is a Player. It reads only an actual Blockfield rig in the equipped Accessories rig slot, not an open chest, inventory spare rig, raw list or recursively nested container.
- SBW `GunData.countBackupAmmo(Entity)` and `consumeBackupAmmo(Entity, int)` both use `hasCreativeAmmoBox(Entity)`, which delegates to this presence check. Vehicle passenger checks retain upstream `usePassengerCreativeAmmoBox` handling. Ordinary list/item-handler paths remain unchanged.
- Client and server use the same read-only bridge. Accessories is compile-only/optional; @Pseudo skips the SBW target when absent. No infinite ammo is cached or materialized. Removing/clearing the equipped box removes the new presence result immediately.
- Existing battle preparation/deployment rig clearing is unchanged. This does not make SBW boxes supply TACZ weapons.

Source contracts inspected: `Blockfield/superbwarfare-fabric` ModItems.kt, CreativeAmmoBoxItem.kt, InventoryTool.kt (blob 6b8a08e62c648ee56a8d685aa559d3146bc9a387) and GunData.kt (blob 328007e3760b44e7fe9481dae1bb9ae3918aa747).

## Automated verification

Run `just gradle --no-daemon --max-workers=2 :server:test --tests '*CreativeAmmoRigTest'` then `just build`.

Eight unit tests exercise shared filters, menu slots/shift-click, real rig absorption/manual writes, component round-trip, full-rig remainder, legacy/invalid/duplicate-slot data, read-only lookup and actual rig clearing with mocked Accessories boundaries. Fake registry items test storage, not real gun behavior. The ordinary minimal rig smoke test does not include SBW, so it cannot establish the new SBW mixin injection or reload behavior.

## Required full-modpack live acceptance

1. Record the exact item ID and components of the reported lobby chest box. Test both that item and `superbwarfare:creative_ammo_box`; retain a TACZ creative/all-type box as a separate control.
2. In adventure/survival with a worn rig, take the box from the chest and verify automatic absorption. Also test drag and shift-click in both directions, full rig, a reopened menu and reconnect. Account for the existing 100-tick pause after manual rig interaction.
3. With all loose ammunition and boxes removed from main inventory/offhand, reload and fire a compatible SBW gun from the equipped box. Check both client prediction/HUD and server ammo state repeatedly. No box count/components may be consumed or duplicated.
4. Remove the box or clear the rig. Confirm infinite supply stops; ordinary finite inventory ammo remains functional. A box in an open chest or another player's rig must not count.
5. Confirm no startup/mixin errors with the actual pinned SBW jar. Exercise the vehicle passenger configuration both enabled and disabled. Launch without SBW and without Accessories as optional-dependency checks.
6. Enter a real match and verify the original lobby box is removed from both inventory and rig, with no infinite supply remaining. Compare TACZ finite and creative reload behavior against the base version.

Status: exact lobby-item capture and full-modpack gun/client acceptance have not been executed by the authoring agent. Keep the PR draft and the issue open until those checks pass. Both side-specific Blockfield jars need rebuilding for this change; this PR does not publish or deploy them.
