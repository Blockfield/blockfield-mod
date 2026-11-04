# Blockfield in-game UI

<!-- impeccable:product-schema 1 -->

## Platform
Minecraft Java 1.21.1 / Fabric; native game rendering with GuiGraphics (not a web surface).

## Users and purpose
Players use the command tablet to choose modes, prepare for battle, view statistics and consult the tactical map. Class and deployment screens are adjacent parts of the same flow.

## Confirmed direction
The user selected The Division as the reference: graphite surfaces, orange accents, consistent icons and smooth animation. They requested implementation, inspection of every available screen on the local test rig, and alignment of health and stamina above the hotbar.

## Constraints from the existing implementation
Server packets own menu content and gameplay permissions. Preserve actions, disabled states, team colors, map coordinates and movement with the tablet open. Tablet contents fit without scrolling. Fonts must include Cyrillic. Desktop GUI scaling and small game windows are supported.

## Validation
Use the full-pack headless test rig, only the local server. Distinguish real gameplay screens from explicit UI fixtures for empty/error states. Do not publish or update the production modpack as part of this task.
