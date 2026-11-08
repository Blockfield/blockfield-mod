#!/bin/bash
# Fetch the canonical TestBot sources from blockfield-workspace at the pinned
# commit and unpack into upstream/. The commit id is the integrity check
# (fetched by SHA, verified with rev-parse); a tree checksum over the
# extracted files guards against local tampering. This keeps one source
# (server#319 U5): the old vendored copy is gone, only the pin and this
# script live here. Afterwards the jar is rebuilt when stale (see rig.sh).
set -eu
source "$(dirname "${BASH_SOURCE[0]}")/../paths.sh" # sets RIG to dev/rig

PIN="$RIG/testbot/testbot-pin.txt"
DEST="$RIG/testbot/upstream"

SHA="$(grep -v '^#' "$PIN" | sed -n '1p')"
SUM="$(grep -v '^#' "$PIN" | sed -n '2p')"
[ "${#SHA}" = 40 ] && [ "${#SUM}" = 64 ] || {
    echo "bad pin (want 40-hex SHA + 64-hex tree sha256): $PIN" >&2
    exit 1
}
case "$SHA$SUM" in *[!0-9a-f]*) echo "bad pin: $PIN" >&2 && exit 1 ;; esac
for tool in git tar sha256sum; do
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

# A codeload tarball is not a stable function of a commit, so fetch the
# pinned commit itself and check the id git resolves it to.
URL="https://github.com/Blockfield/blockfield-workspace"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
git init -q -b fetch "$TMP/repo"
git -C "$TMP/repo" fetch -q --depth 1 "$URL" "$SHA" || {
    echo "cannot fetch testbot $SHA from $URL" >&2
    exit 1
}
[ "$(git -C "$TMP/repo" rev-parse FETCH_HEAD)" = "$SHA" ] || {
    echo "testbot pin mismatch: FETCH_HEAD is not $SHA" >&2
    exit 1
}
git -C "$TMP/repo" archive FETCH_HEAD scripts/dev/testbot | tar -x -C "$TMP"
SRC="$TMP/scripts/dev/testbot"
[ -d "$SRC" ] || {
    echo "testbot tree missing at $SHA" >&2
    exit 1
}
got="$(cd "$SRC" && find . -type f -print0 | LC_ALL=C sort -z | xargs -0 sha256sum | sha256sum | cut -d' ' -f1)"
[ "$got" = "$SUM" ] || {
    echo "testbot tree checksum mismatch at $SHA" >&2
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
