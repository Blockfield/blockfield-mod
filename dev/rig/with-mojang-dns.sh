#!/usr/bin/env bash
# Запускает любую команду с живой нодой CDN Mojang в /etc/hosts.
# Две ноды anycast-пула не принимают TCP из этой сети, а DNS их продолжает выдавать
# (подробности в FABRIC-MIGRATION.md). Подмена живёт только внутри процесса, систему не трогает.
#   ./with-mojang-dns.sh ./gradlew build
set -euo pipefail
IP=${MOJANG_IP:-150.171.109.194}
HOSTS=$(mktemp)
trap 'rm -f "$HOSTS"' EXIT
cat /etc/hosts >"$HOSTS"
echo "$IP piston-meta.mojang.com piston-data.mojang.com launchermeta.mojang.com libraries.minecraft.net resources.download.minecraft.net" >>"$HOSTS"
exec bwrap --dev-bind / / --bind "$HOSTS" /etc/hosts -- "$@"
