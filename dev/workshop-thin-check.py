"""Thin Workshop layer gate (S233-U1): fail `just lint` when the closure drifts.

Reads workshop/thin.txt (paths relative to common/src/main/java) and asserts:
- every listed source exists,
- no listed source imports combat, client or server-game code,
- no listed source except NetworkHandler.java references a game-only packet
  (same-package names need no import, so the import scan cannot catch them).

NetworkHandler.register() names game-only packets in dead code that never
runs on the Workshop profile (only registerWorkshop() does); it stays exempt
from the reference scan, never from the import scan.
"""

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
COMMON = ROOT / "common/src/main/java"
THIN = ROOT / "workshop/thin.txt"
EXEMPT_REFERENCE = "com/lunares/blockfield/network/NetworkHandler.java"

ALLOWED_PREFIXES = (
    "com.lunares.blockfield.network.",
    "com.lunares.blockfield.workshop.",
    "com.lunares.blockfield.api.",
)
ALLOWED_CLASSES = {
    "com.lunares.blockfield.common.CaptureZone",
    "com.lunares.blockfield.common.DecorInteractions",
    "com.lunares.blockfield.common.WorkshopToolItems",
}

IMPORT = re.compile(r"^import\s+(?:static\s+)?([\w.]+)", re.MULTILINE)


def fail(message):
    raise SystemExit(f"workshop-thin-check: {message}")


def thin_files():
    settings = (ROOT / "settings.gradle").read_text().splitlines()
    if "include 'common', 'client', 'workshop'" not in settings:
        fail("settings.gradle does not include the 'workshop' module")
    if not THIN.is_file():
        fail("workshop/thin.txt is missing")
    files = [
        line.strip()
        for line in THIN.read_text().splitlines()
        if line.strip() and not line.strip().startswith("#")
    ]
    if not files:
        fail("workshop/thin.txt lists no sources")
    return files


def game_packets(thin_names):
    packets = COMMON / "com/lunares/blockfield/network/packet"
    return sorted(
        path.stem
        for path in packets.glob("*Packet.java")
        if path.name not in thin_names
    )


def check_imports(rel, text):
    for match in IMPORT.finditer(text):
        name = match.group(1)
        if not name.startswith("com.lunares.blockfield."):
            continue
        if name.startswith(ALLOWED_PREFIXES) or name in ALLOWED_CLASSES:
            continue
        fail(f"{rel} imports game-side {name}")


def check_references(rel, text, game_names):
    if rel == EXEMPT_REFERENCE:
        return
    body = "\n".join(
        line for line in text.splitlines() if not line.strip().startswith("import ")
    )
    hits = [name for name in game_names if re.search(rf"\b{name}\b", body)]
    if hits:
        fail(f"{rel} references game-only packets: {', '.join(hits)}")


def main():
    files = thin_files()
    thin_names = {Path(name).name for name in files}
    game_names = game_packets(thin_names)
    for rel in files:
        path = COMMON / rel
        if not path.is_file():
            fail(f"thin source is missing: {rel}")
        text = path.read_text()
        check_imports(rel, text)
        check_references(rel, text, game_names)
    print(f"workshop-thin-check: {len(files)} thin sources, no game-side refs")


if __name__ == "__main__":
    main()
