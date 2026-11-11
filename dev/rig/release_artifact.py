#!/usr/bin/env python3
"""Block a release whose jars do not match the tag: the version baked into each
jar filename and PackContract.PROTOCOL baked into the bytecode must equal the
tagged source. Guards mod#46 (v1.59.2 tag/protocol confusion: the published
jar did match its tag, but nothing in the pipeline proved it). Historical
releases stay untouched; every new release proves itself before it publishes.
"""

import argparse
from pathlib import Path
import re
import struct
import sys
import zipfile

CLASS_PATH = "com/lunares/blockfield/network/PackContract.class"
PROTOCOL_RE = re.compile(r"public\s+static\s+final\s+int\s+PROTOCOL\s*=\s*(\d+)\s*;")
VERSION_RE = re.compile(r"-(\d+\.\d+\.\d+)\+mc")


class ReleaseMismatch(Exception):
    """The artifact does not match the tag; fail the release."""


def source_protocol(source_file):
    text = Path(source_file).read_text(encoding="utf-8")
    match = PROTOCOL_RE.search(text)
    if match is None:
        raise ReleaseMismatch(f"{source_file}: no PROTOCOL constant found")
    return int(match.group(1))


def jar_protocol(jar_path):
    """PROTOCOL baked into the jar: a static final int lives on as the
    field's ConstantValue attribute, the same value every caller inlined."""
    try:
        with zipfile.ZipFile(jar_path) as jar:
            try:
                data = jar.read(CLASS_PATH)
            except KeyError:
                raise ReleaseMismatch(f"{jar_path}: missing {CLASS_PATH}") from None
    except zipfile.BadZipFile as error:
        raise ReleaseMismatch(f"{jar_path}: not a jar: {error}") from error
    return class_protocol(data, str(jar_path))


def _pool(data, count):
    """Constant pool as index -> value; only Utf8 strings and Integers decode."""
    pool = [None]
    pos = 10
    while len(pool) < count:
        tag = data[pos]
        pos += 1
        if tag == 1:
            size = struct.unpack(">H", data[pos : pos + 2])[0]
            pool.append(data[pos + 2 : pos + 2 + size].decode("utf-8"))
            pos += 2 + size
        elif tag in (3, 4):
            pool.append(struct.unpack(">i", data[pos : pos + 4])[0])
            pos += 4
        elif tag in (5, 6):
            pool.extend((None, None))
            pos += 8
        elif tag in (7, 8, 16, 19, 20):
            pos += 2
            pool.append(None)
        elif tag in (9, 10, 11, 12, 17, 18):
            pos += 4
            pool.append(None)
        elif tag == 15:
            pos += 3
            pool.append(None)
        else:
            raise ReleaseMismatch(f"unknown constant-pool tag {tag}")
    return pool, pos


def class_protocol(data, label):
    if len(data) < 10 or data[:4] != b"\xca\xfe\xba\xbe":
        raise ReleaseMismatch(f"{label}: bad class magic")
    count = struct.unpack(">H", data[8:10])[0]
    pool, pos = _pool(data, count)
    pos += 6  # access, this, super
    interfaces = struct.unpack(">H", data[pos : pos + 2])[0]
    pos += 2 + 2 * interfaces
    fields = struct.unpack(">H", data[pos : pos + 2])[0]
    pos += 2
    for _ in range(fields):
        name = pool[struct.unpack(">H", data[pos + 2 : pos + 4])[0]]
        attrs = struct.unpack(">H", data[pos + 6 : pos + 8])[0]
        pos += 8
        for _ in range(attrs):
            attr = pool[struct.unpack(">H", data[pos : pos + 2])[0]]
            size = struct.unpack(">I", data[pos + 2 : pos + 6])[0]
            if name == "PROTOCOL" and attr == "ConstantValue":
                value = pool[struct.unpack(">H", data[pos + 6 : pos + 8])[0]]
                if not isinstance(value, int):
                    raise ReleaseMismatch(f"{label}: PROTOCOL is not an int")
                return value
            pos += 6 + size
    raise ReleaseMismatch(f"{label}: no baked PROTOCOL found")


def check_release(version, source_file, jars):
    tag = version[1:] if version.startswith("v") else version
    expected = source_protocol(source_file)
    if not jars:
        raise ReleaseMismatch("no jars to check")
    for jar in jars:
        match = VERSION_RE.search(Path(jar).name)
        if match is None or match.group(1) != tag:
            raise ReleaseMismatch(f"{jar}: filename version != release {version}")
        actual = jar_protocol(jar)
        if actual != expected:
            raise ReleaseMismatch(
                f"{jar}: baked PROTOCOL {actual} != source {expected}"
            )
    return expected


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True, help="release tag, e.g. v1.61.2")
    parser.add_argument("--source", required=True, help="PackContract.java path")
    parser.add_argument("jars", nargs="+", help="built jars to verify")
    args = parser.parse_args(argv)
    try:
        protocol = check_release(args.version, args.source, args.jars)
    except ReleaseMismatch as error:
        print(f"release_artifact: {error}", file=sys.stderr)
        return 1
    print(f"release_artifact: {len(args.jars)} jars speak protocol {protocol}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
