# Deathmatch HUD and killfeed

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

The draft remains open for implementation and review. Publishing and deployment require the normal separate release authorization.
