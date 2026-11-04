#!/usr/bin/env python3
"""Download the two pinned packwiz installers into the rig cache."""

import argparse
import hashlib
import json
import urllib.request
from pathlib import Path


def install(manifest, cache):
    specs = json.loads(manifest.read_text())
    downloads = {}
    for key, name in [
        ("bootstrap", "packwiz-installer-bootstrap.jar"),
        ("installer", "packwiz-installer.jar"),
    ]:
        spec = specs[key]
        path = cache / name
        data = path.read_bytes() if path.exists() else b""
        if hashlib.sha256(data).hexdigest() != spec["sha256"]:
            with urllib.request.urlopen(spec["url"], timeout=120) as response:
                data = response.read()
        if hashlib.sha256(data).hexdigest() != spec["sha256"]:
            raise ValueError(f"Installer checksum mismatch: {name}")
        downloads[name] = data
    cache.mkdir(parents=True, exist_ok=True)
    for name, data in downloads.items():
        temporary = cache / (name + ".tmp")
        temporary.write_bytes(data)
        temporary.replace(cache / name)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("manifest", type=Path)
    parser.add_argument("cache", type=Path)
    args = parser.parse_args()
    install(args.manifest, args.cache)
