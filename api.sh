#!/usr/bin/env bash
# Проверка существования API без запуска gradle: javap по classpath уже скачанных зависимостей.
#   ./api.sh net.minecraft.world.item.ItemStack          -- все сигнатуры класса
#   ./api.sh net.minecraft.world.item.ItemStack hoverName -- только строки с 'hoverName'
#   ./api.sh -f RecipeRemainder                          -- найти класс(ы), где встречается имя
#
# Классы Forge/NeoForge из classpath выброшены намеренно: их в сборке нет.
set -euo pipefail
cd "$(dirname "$0")"
JAVAP=$(ls -d "$HOME"/.local/share/mise/installs/java/temurin-21*/bin/javap | head -1)
CACHE=.api-classpath
if [ ! -s "$CACHE" ]; then
    {
        ls .gradle/loom-cache/minecraftMaven/net/minecraft/*/*/*.jar 2>/dev/null | grep -v sources || true
        ls */.gradle/loom-cache/minecraftMaven/net/minecraft/*/*/*.jar 2>/dev/null | grep -v sources || true
        ls "$HOME"/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged*/*/*.jar 2>/dev/null | grep -v sources || true
        find "$HOME/.gradle/caches/modules-2" -name '*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' 2>/dev/null
        find "$HOME/.gradle/caches/fabric-loom" -name '*.jar' ! -name '*-sources.jar' -path '*remapped*' 2>/dev/null
    } | sort -u | grep -v -e neoforge -e forge -e fancymodloader -e '/fmlloader/' >"$CACHE"
fi
CP=$(tr '\n' ':' <"$CACHE")
if [ "${1:-}" = "-f" ]; then
    while read -r j; do
        unzip -l "$j" 2>/dev/null | grep -i "$2" | sed "s|^|$(basename "$j"): |"
    done <"$CACHE"
    exit 0
fi
CLASS=$1
shift
if [ $# -gt 0 ]; then
    "$JAVAP" -cp "$CP" "$CLASS" | grep -i "$1"
else
    "$JAVAP" -cp "$CP" "$CLASS"
fi
