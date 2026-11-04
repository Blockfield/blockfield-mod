# Workshop write boundary acceptance — 2026-09-24

Full modpack, one local workshop JVM, three test clients, builder without OP.
Offline test identities and local web owner fixture exercise ACL; this is not Drasl/proxy authentication acceptance.

Before the fix, `//pos1 100000,130,100000`, `//pos2 100000,130,100000`,
`//set create:andesite_casing` wrote outside fields. RCON `execute if block` confirmed it.

After the fix:
- Create casing at 3749,250,-772: set succeeds, undo restores air, redo restores casing.
- Set at 100001,130,100000 leaves air; setbiome desert changes zero blocks.
- Copy/paste armor stand with entities: 1 → 2; undo 2 → 1; redo 1 → 2.
- Stack entity east from boundary x=4239 leaves one entity; no copy past the construction margin.
- `/butcher` is unavailable to the builder.
- Web role edit → controller inbox → live player: removing fields changes creative to adventure;
  set/undo/redo denied. Restoring fields restores creative.
- Pending candidate and malformed candidates.json both switch the builder to adventure;
  direct RCON setblock is also refused. Restoring valid state restores editing.

Server tests and remapJar passed. The chunk write guard covers block state changes;
it does not by itself freeze ticking entities or block-entity data for a consistent snapshot.
Candidate consistency, release delivery and complete workshop acceptance remain separate work.

Runtime evidence: `/var/tmp/bf-jobs/closure-20260924/evidence/workshop-*.json`.
