"""Thin Workshop layer gate (S233-U1): fail `just lint` when the closure drifts.

Reads workshop/thin.txt (paths relative to common/src/main/java) and asserts:
- every com.lunares.blockfield import names a listed source (a static import
  names the class, so the last segment is the member and is dropped; a package
  wildcard names no class, so its package must hold at least one listed source;
  game-packet leakage through wildcards is caught by the reference scan below),
- no listed source references a game-only packet, except inside the body of
  NetworkHandler.register(): that dead code never runs on the Workshop profile
  (only registerWorkshop() does). registerWorkshop() and every other method are
  checked like any thin source.
"""

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
COMMON = ROOT / "common/src/main/java"
THIN = ROOT / "workshop/thin.txt"
EXEMPT_REFERENCE = "com/lunares/blockfield/network/NetworkHandler.java"

IMPORT = re.compile(r"^import\s+(static\s+)?([\w.]+?)(\.\*)?;", re.MULTILINE)


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


def check_imports(rel, text, thin):
    thin_set = set(thin)
    for match in IMPORT.finditer(text):
        static, name, wild = match.group(1), match.group(2), match.group(3)
        if not name.startswith("com.lunares.blockfield."):
            continue
        if wild and not static:
            package = name.replace(".", "/") + "/"
            if not any(entry.startswith(package) for entry in thin):
                fail(f"{rel} imports package {name} with no thin source")
            continue
        cls = name if wild else (name.rpartition(".")[0] if static else name)
        if cls.replace(".", "/") + ".java" not in thin_set:
            fail(f"{rel} imports game-side {match.group(0).strip()}")


def mask_non_code(text):
    """Blank comments and string/char literals (length-preserving)."""
    out = []
    i, end = 0, len(text)
    while i < end:
        if text.startswith("//", i):
            j = text.find("\n", i)
            j = end if j < 0 else j
            out.append(" " * (j - i))
            i = j
        elif text.startswith("/*", i):
            j = text.find("*/", i + 2)
            j = end if j < 0 else j + 2
            out.append(re.sub(r"[^\n]", " ", text[i:j]))
            i = j
        elif text.startswith('"""', i):
            j = text.find('"""', i + 3)
            j = end if j < 0 else j + 3
            out.append(re.sub(r"[^\n]", " ", text[i:j]))
            i = j
        elif text[i] in "\"'":
            quote, j = text[i], i + 1
            while j < end:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == quote:
                    j += 1
                    break
                if text[j] == "\n" and quote == "'":
                    break
                j += 1
            out.append(re.sub(r"[^\n]", " ", text[i:j]))
            i = j
        else:
            out.append(text[i])
            i += 1
    return "".join(out)


def checked_text(rel, text):
    """Scan body with only the dead register() body blanked (braces matched
    on comment/string-masked code so braces in comments cannot miscount)."""
    if rel != EXEMPT_REFERENCE:
        return text
    masked = mask_non_code(text)
    match = re.search(r"\bvoid\s+register\s*\(\s*\)\s*\{", masked)
    if match is None:
        return text  # fail closed: register() gone, check everything
    depth, start = 0, match.end() - 1
    stop = None
    for i in range(start, len(masked)):
        if masked[i] == "{":
            depth += 1
        elif masked[i] == "}":
            depth -= 1
            if depth == 0:
                stop = i + 1
                break
    if stop is None:
        return text
    return text[:start] + re.sub(r"[^\n]", " ", text[start:stop]) + text[stop:]


def check_references(rel, text, game_names):
    body = "\n".join(
        line
        for line in checked_text(rel, text).splitlines()
        if not line.strip().startswith("import ")
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
        check_imports(rel, text, files)
        check_references(rel, text, game_names)
    print(f"workshop-thin-check: {len(files)} thin sources, no game-side refs")


if __name__ == "__main__":
    main()
