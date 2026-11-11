# Shared in-game UI, match HUD and killfeed

Implementation brief for the draft PR. The behavior below is pending; this document alone does not complete the change.

## Deathmatch HUD

Move the existing frag count/limit, remaining time and leader from the lower-center text line to the upper-center match HUD. Reuse the established Blockfield panel, typography, spacing and colors. Remove the old lower line at its source so the data appears once.

Preserve FFA scoring and the existing values. Keep the HUD clear of the minimap, compass and killfeed. Fit or truncate long names without covering adjacent values. Follow existing HUD visibility and session-reset behavior.

## Killfeed

Display a recognizable weapon silhouette beside the participants in each killfeed entry, across modes. Reuse existing silhouette rendering and assets, preserving aspect ratio and contrast at feed size.

Resolve the silhouette from the recorded killing weapon or cause. Switching items after firing must not change the entry. Handle firearms, knife and explosives; use an honest neutral fallback for environment deaths or unknown IDs. Preserve room isolation, entry timing and clearing on match/session changes.

## Acceptance

- [ ] Deathmatch shows frags/limit, time and leader in the upper HUD, in the shared style, with no lower duplicate.
- [ ] Long names and large scores remain readable at GUI scales 2, 3 and 4 and at reduced window resolution.
- [ ] The HUD does not overlap neighboring widgets.
- [ ] Kills with two different firearms show the correct distinct silhouettes.
- [ ] A projectile kill after switching weapons still shows the killing weapon.
- [ ] Knife, explosive, suicide/environment and unknown-ID cases display correctly without errors.
- [ ] Room changes, reconnect and a new match clear old feed/HUD state; another room never receives the entry.
- [ ] Existing team-mode and campaign HUD values, timers, points and capture banners remain correct.
- [ ] Required repository checks pass; before/after screenshots and kill scenarios are recorded on an authorized isolated headless stand.

## Shared in-game UI components

Apply this task across the Blockfield in-game interface. Inventory HUDs, scoreboard, tablet tabs, hub and range screens, voting, class/kit and deployment selection, results, settings and workshop UI. Map each existing surface and state to shared components or an explicitly justified unique composition.

Build on BfTheme, BfIcon, UiMotion and existing layout/render helpers. Share panels, headings, text/number/icon treatment, controls, tooltips, table layout, score/timer/limit/leader widgets, status/progress/protection messages and killfeed entries. Centralize sizing, typography, spacing, colors, overflow and visibility behavior where they are shared. Keep mode rules and authoritative data with their current owners; use small semantic variants for FFA/team and tickets/frags.

Migrate existing callers and remove replaced copies. Rendering, hit testing, focus and clipping must use the same bounds and transforms. Preserve actions, permissions, animation and session cleanup. Use ordinary composition; avoid introducing another UI toolkit or a configurable UI engine.

Additional acceptance:

- [ ] Every in-game surface and its relevant states are covered by the component/caller inventory.
- [ ] Existing surfaces consume shared blocks; no unused component library or per-mode copies remain.
- [ ] Team Deathmatch, AWP and Snipers show score, time and kill limit once in the upper HUD.
- [ ] The scoreboard exposes all players with 15+ entries, fits narrow GUI widths and prevents long names from overlapping columns.
- [ ] Typography and deployment-protection presentation are consistent across modes.
- [ ] Screens and interactions retain their behavior at GUI scales 2, 3 and 4, including small windows and long text.
- [ ] Meaningful shared layout/state logic is checked, and every migrated surface is verified on the authorized isolated stand.
- [ ] Existing design documentation reflects the implementation.

Different game rules retain their distinct data and meaningful columns. Third-party/vanilla integration boundaries are recorded; launcher and website implementations are outside this in-game refactor.

The draft remains open for implementation and review. Publishing and deployment require the normal separate release authorization.
