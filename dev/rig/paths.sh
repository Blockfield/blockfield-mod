# shellcheck shell=bash
# Sourced by rig scripts; support the historical sibling path and dev/rig in the mod repository.
# MODROOT is the mod repo itself; ROOT is the directory holding the sibling
# checkouts (blockfield-client, blockfield-server) used by the pack subcommands.
RIG="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
if [ -d "$RIG/../../client/src" ]; then
    MODROOT="$(cd "$RIG/../.." && pwd -P)"
    ROOT="$(cd "$RIG/../../.." && pwd -P)"
else
    ROOT="$(cd "$RIG/.." && pwd -P)"
    if [ -d "$ROOT/blockfield-mod/client/src" ]; then
        MODROOT="$ROOT/blockfield-mod"
    else
        MODROOT="$ROOT/BlockField-Private-Project"
    fi
fi
