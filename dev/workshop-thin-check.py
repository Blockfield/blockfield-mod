"""Thin Workshop layer gate (S233-U1): fail `just lint` when the wiring drifts.

The closure itself is enforced by javac, not by this script:
workshop/build.gradle compiles exactly workshop/thin.txt out of :common with
no sibling-project dependency, so any same-package, FQN or wildcard reference
outside thin.txt fails compilation. What javac cannot see — and what this gate
still checks — is the wiring: settings.gradle must include the workshop
module, every thin.txt entry must exist on disk (Gradle silently ignores
missing includes), and build.gradle must keep compiling exactly thin.txt with
no project() dependency (which would put non-thin classes back on the
compile classpath and mute the compiler check).
"""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
COMMON = ROOT / "common/src/main/java"
THIN = ROOT / "workshop/thin.txt"
BUILD = ROOT / "workshop/build.gradle"


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


def check_sources(files):
    for rel in files:
        if not (COMMON / rel).is_file():
            fail(f"thin source is missing: {rel}")


def check_build_wiring(text):
    if "include thin" not in text:
        fail("workshop/build.gradle no longer compiles exactly thin.txt")
    if "project(" in text:
        fail("workshop/build.gradle must not depend on a sibling project")


def main():
    files = thin_files()
    check_sources(files)
    check_build_wiring(BUILD.read_text())
    print(f"workshop-thin-check: {len(files)} thin sources, wiring ok")


if __name__ == "__main__":
    main()
