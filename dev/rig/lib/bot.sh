# shellcheck shell=bash
export BF_RIG_OWNER="$CLAUDE_CODE_SESSION_ID"
R="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
B() { (cd "$R" && ./rig.sh bot "$@"); }
B2() { (cd "$R" && BF_RIG_CLIENT=2 ./rig.sh bot "$@"); }
RC() { (cd "$R" && ./server.sh rcon "$1" 2>&1 | tail -n +2); }
hp() { RC "data get entity $1 Health" | grep -oE '[0-9.]+f$'; }
