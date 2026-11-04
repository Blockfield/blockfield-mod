#!/usr/bin/env python3
"""Select and replace versioned Blockfield jars; callers must first prove the rig is idle."""

import argparse
import json
from pathlib import Path
import shutil
import tempfile


def runtime_name(name, side):
    return (
        name.startswith(f"blockfield-{side}-")
        and name.endswith(".jar")
        and not name.endswith(("-sources.jar", "-dev.jar"))
    )


def candidates(directory, side):
    return sorted(
        path
        for path in directory.iterdir()
        if path.is_file() and runtime_name(path.name, side)
    )


def deployed(directory, side):
    jars = candidates(directory, side)
    if len(jars) != 1:
        raise ValueError(
            f"Expected one {side} runtime jar in {directory}, found: {jars}"
        )
    return jars[0]


def install(source, directory, side):
    if not runtime_name(source.name, side) or not source.is_file():
        raise ValueError(f"Not a {side} runtime jar: {source}")
    directory.mkdir(parents=True, exist_ok=True)
    destination = directory / source.name
    same_file = destination.exists() and source.samefile(destination)
    if not same_file:
        # Copy fully before replacing anything, including an existing jar with the same version.
        with tempfile.NamedTemporaryFile(
            dir=directory, prefix=".blockfield-install-", delete=False
        ) as file:
            temporary = Path(file.name)
        try:
            shutil.copyfile(source, temporary)
            temporary.replace(destination)
        finally:
            temporary.unlink(missing_ok=True)
    for old in candidates(directory, side):
        if old != destination:
            old.unlink()
    return destination


def prune(directory):
    """Delete jars packwiz does not track: a stale fork build next to its renamed successor loads instead of it."""
    manifest = json.loads((directory.parent / "packwiz.json").read_text())
    tracked = {
        entry.get("cachedLocation") for entry in manifest["cachedFiles"].values()
    }
    removed = []
    for jar in sorted(directory.glob("*.jar"), key=lambda path: path.name):
        # bftestbot is copied in by rig.sh start. A blockfield-* swap from rig.sh mod goes too: packwiz has
        # just put the pack's own build back next to it, and two builds of one mod must not load together.
        if f"{directory.name}/{jar.name}" in tracked or jar.name == "bftestbot.jar":
            continue
        jar.unlink()
        removed.append(jar.name)
    return removed


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=("install", "deployed", "prune"))
    parser.add_argument("side", choices=("client", "server"), nargs="?")
    parser.add_argument("directory", type=Path)
    parser.add_argument("source", nargs="?", type=Path)
    args = parser.parse_args()
    if args.action == "install" and args.source is None:
        parser.error("install requires a source jar")
    try:
        if args.action == "prune":
            print(
                "\n".join(f"removed stale {name}" for name in prune(args.directory))
                or "no stale jars"
            )
        else:
            print(
                install(args.source, args.directory, args.side)
                if args.action == "install"
                else deployed(args.directory, args.side)
            )
    except (ValueError, OSError) as error:
        parser.exit(1, f"{error}\n")
