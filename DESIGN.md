---
name: Blockfield in-game UI
description: Native warm-brown command tablet with amber interaction accents, sharing the launcher and website palette.
colors:
  bg: "#070604"
  bg-elevated: "#0B0906"
  card: "#11100D"
  popover: "#18130D"
  border: "#3A2C1D"
  amber: "#F5A524"
  amber-bright: "#FFC861"
  text: "#F3E7D0"
  text-secondary: "#C7AE86"
  muted: "#A89373"
  muted-dark: "#8E7A5E"
  ok: "#82D66B"
  warn: "#F2C94C"
  danger: "#FF5C5C"
  blue-team: "#3480FA"
  scrim: "#070604E6"
typography:
  title:
    fontFamily: "IBM Plex Sans"
    fontSize: "22px"
    fontWeight: 400
  body:
    fontFamily: "IBM Plex Sans"
    fontSize: "11px"
    fontWeight: 400
rounded:
  square: "0px"
spacing:
  control-gap: "4px"
  compact-padding: "8px"
  card-gap: "12px"
  column-gutter: "24px"
components:
  button-primary:
    backgroundColor: "{colors.amber}"
    textColor: "{colors.bg}"
    rounded: "{rounded.square}"
    typography: "{typography.body}"
  button-primary-hover:
    backgroundColor: "{colors.amber-bright}"
    textColor: "{colors.bg}"
  button-secondary:
    backgroundColor: "{colors.card}"
    textColor: "{colors.text-secondary}"
    rounded: "{rounded.square}"
  button-disabled:
    backgroundColor: "{colors.bg-elevated}"
    textColor: "{colors.muted}"
    rounded: "{rounded.square}"
  card:
    backgroundColor: "{colors.card}"
    textColor: "{colors.text}"
    rounded: "{rounded.square}"
  navigation:
    backgroundColor: "{colors.bg-elevated}"
    textColor: "{colors.muted}"
    rounded: "{rounded.square}"
---

# Design System: Blockfield in-game UI

## Overview

**Creative North Star: "The Division command tablet"**

The command tablet keeps the Division-style layout in the Blockfield brand palette shared with the launcher and website: warm near-black brown panels, restrained amber controls, neutral line icons and readable Cyrillic. It is a native Minecraft Java/Fabric interface rendered with GuiGraphics; dimensions below are logical GUI or menu-canvas units, not physical screen pixels.

The system favors quick recognition during play: server-authored choices, compact option groups, visible unavailable states and short continuous motion. Gameplay team and danger colors remain meaningful. This records the implemented Blockfield surfaces, not third-party mod screens or a whole-surface accessibility certification.

**Key Characteristics:**
- Square warm-brown surfaces with fine borders.
- Amber selection and action feedback; semantic team colors.
- IBM Plex Sans Regular, local Lucide icons and Russian interface copy.
- Native scaling with shared input and clipping geometry.

Evidence: `BfTheme`, `BfIcon`, `UiMotion`, `TabletFrame`, `TabletCanvas`, `MenuScreen`, `MenuLayout`, `ClassSelectionScreen`, `SpawnSelectionScreen`, the UI font provider and HUD mixins under `client/src/main`. Visual references are `.impeccable/review/final/`, especially vote, class-ready, fixture-class-free, fixture-spawn-countdown, ffa-scoreboard and snipers-map pairs. Fixtures are test states, not gameplay captures. The finish disposition is scoped ship; no unrecorded random seed or web comp is claimed.

## Colors

Warm near-black brown forms a tonal stack beneath amber interaction cues; the values match the launcher `theme.css` and website `system.css` tokens. Frontmatter values are the reusable subset of `BfTheme`; Java ARGB has been transcribed to portable CSS RGBA order for the translucent scrim.

### Primary

- **Amber:** active tabs, selected borders, primary buttons, countdowns and interaction feedback.
- **Amber bright:** the enabled primary hover endpoint.

### Secondary

- **Danger / blue team:** red danger and red-team ownership, and blue-team ownership.
- **OK / warn:** positive status and warning feedback. These are semantic states, not additional brand accents.

### Neutral

- **Background / elevated background / card / popover:** successive warm brown surfaces; popover also supplies the soft-border tone.
- **Border:** fine structural outlines.
- **Text / secondary text / muted / dark muted:** primary content, supporting content, hints and lower-priority states.
- **Scrim:** translucent brown-black over the world behind class and deployment screens.

**The Semantic Color Rule.** Amber identifies interaction; team ownership and danger retain their semantic colors.

## Typography

**Display and body font:** locally bundled IBM Plex Sans Regular, licensed under OFL. The `blockfield:ui` provider falls back to `minecraft:default` for missing glyphs; this is not an operating-system font stack.

The hierarchy uses scale rather than a family or weight change. Body and labels share the provider's nominal size; menu titles use twice that size. Card labels start at 2× for large cards or 1.5× for compact cards, then fit available width. The timer uses 1.4×. These are native draw transforms, not a universal CSS type ramp or measured glyph heights. Line spacing is local to the component: shared tooltips advance ten logical units, menu information lines eleven.

**The One UI Face Rule.** Use the bundled UI font for Blockfield menus; preserve its Cyrillic coverage and atlas-specific linear sampling under fractional scaling.

The TTF provider oversamples at 3×. `UiFontFilterMixin` enables linear filtering only for `blockfield:ui/*`; vanilla and other font atlases retain their own behavior. Labels are Russian; official weapon and model identifiers retain their names. Useful section headings may be uppercase; there is no separate decorative eyebrow role.

## Layout

The tablet uses a fixed logical outer margin (22), top tab height (26), and content gap (6). Its menu canvas starts at height 360 with a minimum width of 560, preserves aspect ratio and grows for overflow before scaling into the available rectangle. The growth request is capped at 900; aspect preservation can increase computed height further. Tablet content fits without scrolling; the standalone menu retains its own scroll fallback.

Menu content has a maximum width of 760 and at most two columns; columns require 300 each with the recorded column gutter. Choice cards use a regular height of 112 or compact height of 72 for larger groups. Large-card padding is 12; compact-card padding uses the recorded compact spacing. Vote segments share equal available widths. Whole sections remain together, and card rows balance remaining choices.

Class and deployment screens use separate reference canvases (650×400 and 800×480 respectively), scaled to the window. This is native GUI adaptation, not a desktop/mobile breakpoint layout. Mouse coordinates and scissor bounds must follow displayed transforms. Do not adopt fixture-specific map shapes or isolated coordinates as system spacing tokens.

## Elevation & Depth

Depth comes from brown tonal layers, a world scrim, fine outlines and small translucent accent rims. Shared panels and tooltips can carry an amber outer rim at 20% opacity; this is a one-unit perimeter highlight, not a hard offset shadow. Tactical corner ticks remain part of the map, timer and operator framing vocabulary. There is no shared drop-shadow scale.

**The Modal Separation Rule.** Blockfield modal screens suppress the gameplay HUD; the active custom TAB table renders after HUD callbacks.

`ModalHudMixin` suppresses `Gui.render` for the tablet, menu, class and spawn screens. `ScoreboardLayerMixin` renders the active custom table after that call at z=400, when no screen is open. Tooltips also use foreground depth. These are native render-order guarantees, not CSS stacking conventions.

## Shapes

Reusable surfaces have square corners and one-unit outlines. Selection adds an amber edge or underline; unavailable controls use quieter surfaces and text. There is no rounded-card or pill vocabulary. Line icons are ready-made Lucide assets from Iconify, stored as local SVG sources with ISC/MIT notices in `dev/ui/icons` and bundled as transparent 96×96 PNGs for runtime tinting. They are not font glyphs or runtime network resources.

## Components

### Buttons

Primary controls use amber fill and background-colored ink, brightening on hover. Shared secondary buttons use card fill, a structural border and secondary text, moving toward popover fill, amber border and brighter ink on hover. Disabled buttons use elevated fill, the soft border tone and muted ink. Menu buttons additionally support semantic destructive ink and icons. Menu keyboard focus shares its hover feedback; this does not establish keyboard focus support for every custom screen.

### Choice cards and class rows

Choice cards place a semantic icon above the label with supporting text at the bottom and an arrow at the upper right. Hover interpolates the fill and outline; selection keeps the accent border. Class rows combine a distinct class icon, availability or cost text, and kit variant buttons. Selected variants use the primary treatment. Full or blocked classes retain explanatory tooltips and unavailable states.

### Segmented voting

Each variant is a direct action in an equal-width row, with a semantic icon and count. Time, speed, friendly fire and random options use distinct ready-made symbols. Selection persists independently of hover.

### Navigation and motion

The tab rail uses the elevated surface; active tabs use card fill and amber ink with a two-unit underline. Tabs are 82 logical units wide. The underline moves continuously between tab positions while content reveals through a short fade. `UiMotion` uses a 170 ms cubic ease-out with continuous reversal. Opening and closing use `TabletAnim` at 130 ms quadratic ease-out and a 0.98→1 scale. Frame time, not game ticks, drives these transitions. The shared immediate `BfTheme.button` helper itself is not animated.

### Tactical map and deployment

Map surfaces retain their world aspect ratio, semantic team markers and muted terrain rendering. A low-opacity grid is a map fallback, not a replacement for available terrain. Spawn rows preserve unavailable reasons, selected state and countdown gating; deployment remains disabled while requirements are unmet. Class balance and credit explanation appear when offered kits include paid options. Spawn credit text requires both balance and deployment-cost fields. All-free class lists omit the credit explanation and balance.

### Tooltips and status

Tooltips use the popover surface, structural outline, optional accent rim and per-line semantic text colors; position is constrained to screen edges. Progress tracks use elevated fill and a bordered, clamped inner bar. Small square status markers use a soft halo. The custom player table belongs above gameplay HUD layers.

The sidecar contains self-contained HTML/CSS inspection previews translated from these native patterns. They illustrate colors and states only: browser font fallback, focus affordances and literal CSS dimensions are preview adaptations, not executable Minecraft components or proof of native pixel parity.

## Do's and Don'ts

### Do:

- Do reuse BfTheme, BfIcon and UiMotion for adjacent Blockfield surfaces.
- Do preserve disabled reasons, countdowns and server-authored permissions.
- Do show class balance and credit explanation only when offered classes include paid kits; deployment credits require the relevant server fields.
- Do verify both ordinary and small native GUI scales, including Cyrillic and animated hit targets.
- Do keep useful section labels, map grids and tactical corner marks where they carry structure.

### Don't:

- Don't replace team or danger colors with decorative amber.
- Don't use Unicode glyphs as substitute icons; reuse the licensed local Lucide assets.
- Don't add decorative grids outside map surfaces.
- Don't restore tick-stepped transitions or nearest sampling for the oversampled UI atlas.
- Don't infer a mobile CSS breakpoint system from the native canvas scaling.

Not canonized: isolated operator captions, decorative microcopy, fixture values, residual small-scale raster artifacts and one-off coordinates are not reusable rules. No decorative eyebrow style, glyph-icon convention or system display face is established by this document.
