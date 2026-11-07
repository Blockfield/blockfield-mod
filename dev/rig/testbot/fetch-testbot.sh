#!/bin/bash
# Fetch the canonical TestBot sources from blockfield-workspace at the pinned
# commit, verify the tarball checksum, and unpack into upstream/. This keeps
# one source (server#319 U5): the old vendored copy is gone, only the pin and
# this script live here. Build afterwards: (cd upstream && ./gradlew build).
set -eu
source "$(dirname "${BASH_SOURCE[0]}")/../paths.sh" # sets RIG to dev/rig

PIN="$RIG/testbot/testbot-pin.txt"
DEST="$RIG/testbot/upstream"
CACHE_DIR="${XDG_CACHE_HOME:-$HOME/.cache}/bf-testbot"

SHA="$(grep -v '^#' "$PIN" | sed -n '1p')"
SUM="$(grep -v '^#' "$PIN" | sed -n '2p')"
[ "${#SHA}" = 40 ] && [ "${#SUM}" = 64 ] || {
    echo "bad pin (want 40-hex SHA + 64-hex sha256): $PIN" >&2
    exit 1
}
case "$SHA$SUM" in *[!0-9a-f]*) echo "bad pin: $PIN" >&2 && exit 1 ;; esac
for tool in curl sha256sum tar; do
    command -v "$tool" >/dev/null || {
        echo "need $tool to fetch the testbot" >&2
        exit 1
    }
done

want="$(printf '%s\n%s' "$SHA" "$SUM")"
if [ -f "$DEST/.upstream-sha" ] && [ "$(cat "$DEST/.upstream-sha")" = "$want" ]; then
    echo "testbot $SHA already unpacked at $DEST"
    exit 0
fi

URL="https://github.com/Blockfield/blockfield-workspace/archive/$SHA.tar.gz"
TARBALL="$CACHE_DIR/$SHA.tar.gz"
mkdir -p "$CACHE_DIR"
if [ -f "$TARBALL" ] && ! echo "$SUM  $TARBALL" | sha256sum -c - >/dev/null 2>&1; then
    rm -f "$TARBALL"
fi
[ -f "$TARBALL" ] || curl -fsSL --retry 3 -o "$TARBALL" "$URL"
echo "$SUM  $TARBALL" | sha256sum -c -

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
tar -xzf "$TARBALL" -C "$TMP"
SRC="$TMP/blockfield-workspace-$SHA/scripts/dev/testbot"
[ -d "$SRC" ] || {
    echo "testbot tree missing in $TARBALL" >&2
    exit 1
}
rm -rf "$DEST"
mkdir -p "$DEST"
cp -r "$SRC"/. "$DEST"/
chmod +x "$DEST/gradlew"
printf '%s\n' "$SHA" "$SUM" >"$DEST/.upstream-sha"
trap - EXIT
rm -rf "$TMP"
echo "testbot $SHA ready at $DEST"
