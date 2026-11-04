# Team and class uniforms

Fifteen cosmetic outfits occupy the existing Accessories `uniform` slot:

| Class | RedTeam | BlueTeam | NONE (DM / range) |
|---|---|---|---|
| assault, mark | soviet_uniform | nato_uniform | dm_uniform |
| tech | red_engineer_uniform | blue_engineer_uniform | neutral_engineer_uniform |
| sniper | red_sniper_uniform | blue_sniper_uniform | neutral_sniper_uniform |
| snipers | red_airborne_uniform | blue_airborne_uniform | neutral_airborne_uniform |
| drone | red_drone_uniform | blue_drone_uniform | neutral_drone_uniform |

All IDs use the `blockfield:` namespace. Matching class configs are in the modpack's
`feat/team-class-uniforms` branch. Client JAR, server JAR and those configs must ship together;
the new registry entries must exist before the server loads the kits. No release tag or deployment
is part of this preparation.

Team language: dark olive / red versus light sand / cobalt, one versus two arm stripes, graphite / orange for FFA.
Every kit has its own design:

- Infantry / DM: high-cut helmet with NVG, strobe, counterweight and ear protection; plate carrier with magazines in
  open-top pouches and a flag patch (DM: emblem), tourniquet, belt grenade pouch; back panel with radio whip, IFAK and dump pouch.
- Engineer: helmet with goggles and headlamp; utility vest with tool pouches, belt wrench and hammer, drop-leg holster;
  toolbox pack with a hazard-striped lid and a cable coil.
- Sniper: ghillie hood a unit off the head with ~70 frayed jute strands in three tones and crown tabs, shemagh, light chest rig
  with emblem on the admin pouch, cartridge bandolier, rangefinder, ghillie cape and a drag bag with team bands.
- Airborne: hood with jump goggles; parachute harness with leg loops, belly reserve with ripcord, main container, wrist altimeter.
- Drone: helmet, FPV goggles and headset; chest tray controller with a painted video feed, forearm map tablet, relay pack with antennas and battery cells.

`UniformModel` builds native humanoid bones from cuboids; every cube is recorded by part path and material. Each kit has
its own texture at 12 texels per model unit (`textures/models/uniform/<role>_<side>.png`) and UV layout
(`uniforms/<role>_<side>.json`), both written by the test-side `UniformAtlasBaker`: it shelf-packs one MC box-UV island per
cube, paints materials and details (`Surfaces`: pixel camouflage, weave, stitching, MOLLE, laces, emblems, shemagh,
jute, cartridges, screens), ray-traces ambient occlusion against the kit's own bone and pads islands for mip levels.
Vanilla never mipmaps entity textures, so `UniformTexture` uploads a 4-level mip chain and renders with a mipmapped copy
of `armorCutoutNoCull`; without it the detail shimmers at range.

Run these commands from the mod root on Linux or Windows with native Just, Python and JDK 21:

```text
just uniform-bake
just gradle --no-daemon --max-workers=2 :client:test :client:remapJar
```

`uniform-bake` writes textures, layouts and item icons. PNG optimization is optional and unmanaged;
these recipes do not install or invoke oxipng.

Rebake after any geometry change: the model refuses to build a cube without its UV island. `preview.py` renders the baked
kits from four angles without starting Minecraft (`BF_SCALE`, `BF_VIEWS=front,3/4,side,back`); in-game frames remain the proof:

Preview requires ImageMagick 7's `magick` command on `PATH` and an explicit 64x64 player skin PNG.
The dump recipe creates its output directory; use an existing directory for the contact sheet output.

```text
just uniform-dump temp/quads
just uniform-preview temp/quads temp/sheet.png sniper_blue sniper_red --skin "path/to/player skin.png"
```

The existing SBW helmet, vest and leggings, plus leather boots, still supply all combat
statistics and armor plates. Only their third-person and SBM first-person rendering is suppressed while a visible
uniform is equipped. Vanilla outer skin layers are hidden to prevent skin clothing protruding.
Removing or hiding the uniform restores normal armor visuals. Vanilla capes are hidden whenever
any armor slot is occupied, or a uniform is visible; bare players keep their capes.

The testbot-only `-Dbf.testbot.cape=true` fixture gives offline test identities a visible cape,
so the hide/restore behavior can be checked without an account cape. Never distribute testbot.
