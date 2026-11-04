#!/usr/bin/env bash
# Генерация ванильных GUI-текстур в стиле лаунчера Blockfield.
# Результат кладётся в client/src/main/resources/assets/{minecraft,blockfield}/...
# и переопределяет ванильные текстуры без отдельного ресурс-пака.
#
# Требуется ImageMagick 7 (magick) и ванильный/фабричный jar 1.21.1 — из него берутся
# эталонные размеры, .mcmeta и сами PNG контейнеров (их только перекрашиваем, чтобы
# не сдвинуть слоты). Путь к jar можно передать через MC_JAR.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$HERE/src/main/resources/assets"
MC="$OUT/minecraft/textures/gui"
BF="$OUT/blockfield/textures/gui"
LOGO_SVG="${LOGO_SVG:-$HERE/../../blockfield-web/public/logo.svg}"
MC_JAR="${MC_JAR:-/tmp/bf-mc/versions/fabric-1.21.1-0.19.3/fabric-1.21.1-0.19.3.jar}"

[ -f "$MC_JAR" ] || {
    echo "нет jar 1.21.1: $MC_JAR (задай MC_JAR=...)" >&2
    exit 1
}
command -v magick >/dev/null || {
    echo "нужен ImageMagick 7 (magick)" >&2
    exit 1
}

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
VAN="$TMP/vanilla/assets/minecraft/textures/gui"
unzip -o -q "$MC_JAR" 'assets/minecraft/textures/gui/*' -d "$TMP/vanilla"

# --- палитра (BfTheme.java / theme.css лаунчера) ---
BG='#070604'
BG_EL='#0B0906'
CARD='#11100D'
POPOVER='#18130D'
BORDER='#2A2116'
BORDER_HOVER='#3A2C1D'
AMBER='#F5A524'
AMBER_BRIGHT='#FFB84D'
AMBER_DIM='#8A571C'
TEXT='#F3E7D0'
GRID='#151006'                     # AMBER 6% поверх BG, предпосчитано — тайлы 16x16 не дают смешивать слоями
FONT="${FONT:-Adwaita-Sans-Black}" # Adwaita Sans — форк Inter, дисплейный шрифт лаунчера

mkdir -p "$MC/sprites/widget" "$MC/sprites/hud" "$MC/container" "$MC/title/background" \
    "$BF" "$OUT/minecraft/texts"

# панель: рамка 1px + заливка
panel() { # out w h border fill
    magick -size "${2}x${3}" +antialias xc:"$4" -fill "$5" \
        -draw "rectangle 1,1 $(($2 - 2)),$(($3 - 2))" "$1"
}
# вкладка: рамки сверху/слева/справа, низа нет (nine-slice border bottom = 0 тянет центр)
tab() { # out fill side top
    magick -size 130x24 +antialias xc:"$2" \
        -fill "$3" -draw 'rectangle 0,0 0,23' -draw 'rectangle 129,0 129,23' \
        -fill "$4" -draw 'rectangle 0,0 129,0' "$1"
}

echo "== widget"
panel "$MC/sprites/widget/button.png" 200 20 "$BORDER" "$CARD"
panel "$MC/sprites/widget/button_highlighted.png" 200 20 "$AMBER" "$POPOVER"
panel "$MC/sprites/widget/button_disabled.png" 200 20 "$POPOVER" "$BG_EL"
panel "$MC/sprites/widget/text_field.png" 200 20 "$BORDER" "$BG"
panel "$MC/sprites/widget/text_field_highlighted.png" 200 20 "$AMBER" "$BG"
panel "$MC/sprites/widget/slider.png" 200 20 "$BORDER" "$BG_EL"
panel "$MC/sprites/widget/slider_highlighted.png" 200 20 "$BORDER_HOVER" "$BG_EL"
panel "$MC/sprites/widget/slider_handle.png" 8 20 "$AMBER_DIM" "$AMBER"
panel "$MC/sprites/widget/slider_handle_highlighted.png" 8 20 "$AMBER" "$AMBER_BRIGHT"
panel "$MC/sprites/widget/scroller.png" 6 32 "$AMBER_DIM" "$AMBER"
panel "$MC/sprites/widget/scroller_background.png" 6 32 "$POPOVER" "$BG"
tab "$MC/sprites/widget/tab.png" "$BG_EL" "$BORDER" "$BORDER"
tab "$MC/sprites/widget/tab_highlighted.png" "$CARD" "$BORDER_HOVER" "$BORDER_HOVER"
tab "$MC/sprites/widget/tab_selected.png" "$POPOVER" "$BORDER" "$AMBER"
tab "$MC/sprites/widget/tab_selected_highlighted.png" "$POPOVER" "$BORDER_HOVER" "$AMBER_BRIGHT"

panel "$MC/sprites/widget/checkbox.png" 20 20 "$BORDER" "$BG_EL"
panel "$MC/sprites/widget/checkbox_highlighted.png" 20 20 "$AMBER" "$BG_EL"
# галочку берём из ванильного спрайта (белый по тёмному) и красим в янтарь
magick "$VAN/sprites/widget/checkbox_selected.png" -alpha off -colorspace gray -level '60%,100%' "$TMP/tick_mask.png"
magick -size 20x20 xc:"$AMBER" "$TMP/tick_mask.png" -alpha off -compose CopyOpacity -composite "$TMP/tick.png"
magick "$MC/sprites/widget/checkbox.png" "$TMP/tick.png" -compose over -composite "$MC/sprites/widget/checkbox_selected.png"
magick "$MC/sprites/widget/checkbox_highlighted.png" "$TMP/tick.png" -compose over -composite "$MC/sprites/widget/checkbox_selected_highlighted.png"

echo "== фоны меню и разделители"
# Сетка едва заметная (8%), иначе на фоне паузы поверх блюра она превращается в решётку.
bgtile() { # out rgba
    magick -size 16x16 +antialias xc:"$2" -fill "${GRID}14" \
        -draw 'rectangle 0,0 0,15' -draw 'rectangle 0,0 15,0' "$1"
}
plain() { magick -size 16x16 xc:"$2" "$1"; }
bgtile "$MC/menu_background.png" "${BG}E6" E6
bgtile "$MC/menu_list_background.png" "${BG}F2" F2
plain "$MC/inworld_menu_background.png" "${BG}B4"
plain "$MC/inworld_menu_list_background.png" "${BG}D9"
bgtile "$MC/tab_header_background.png" "${BG_EL}E6" E6
# 32x2: как в ванили — светлая линия и тень, только в нашей палитре
sep() { magick -size 32x2 +antialias xc:none -fill "$2" -draw 'rectangle 0,0 31,0' -fill "$3" -draw 'rectangle 0,1 31,1' "$1"; }
sep "$MC/header_separator.png" "$BORDER" "${BG}BF"
sep "$MC/inworld_header_separator.png" "$BORDER" "${BG}BF"
sep "$MC/footer_separator.png" "${BG}BF" "$BORDER"
sep "$MC/inworld_footer_separator.png" "${BG}BF" "$BORDER"

echo "== hud"
# 182x22 = рамка 1px + 9 ячеек по 20px; прозрачность как в ванили (0xBA)
args=(-size 182x22 +antialias "xc:${BG_EL}BA" -fill "${BORDER}E6"
    -draw 'rectangle 0,0 181,0' -draw 'rectangle 0,21 181,21'
    -draw 'rectangle 0,0 0,21' -draw 'rectangle 181,0 181,21'
    -fill "${BORDER}A0")
for k in 1 2 3 4 5 6 7 8; do args+=(-draw "rectangle $((1 + 20 * k)),1 $((1 + 20 * k)),20"); done
magick "${args[@]}" "$MC/sprites/hud/hotbar.png"
# 24x23: выделение — янтарная рамка вокруг ячейки (интерьер ячейки лежит на 2,2..21,21)
magick -size 24x23 +antialias xc:none \
    -fill "${AMBER}55" -draw 'rectangle 0,0 23,0' -draw 'rectangle 0,21 23,21' \
    -draw 'rectangle 0,0 0,21' -draw 'rectangle 23,0 23,21' \
    -fill "$AMBER" -draw 'rectangle 1,1 22,1' -draw 'rectangle 1,20 22,20' \
    -draw 'rectangle 1,1 1,20' -draw 'rectangle 22,1 22,20' \
    "$MC/sprites/hud/hotbar_selection.png"

echo "== перекраска ванильных PNG (контейнеры, offhand)"
# CLUT 1x256: серые ванильной панели -> наша палитра, промежуточные оттенки интерполируются
mkclut() { # out c0 c1 c2 c3  (0x00/0x37 -> c0, 0x8B -> c1, 0xC6 -> c2, 0xFF -> c3)
    magick -size 1x56 xc:"$2" \
        \( -size 1x84 gradient:"$2-$3" \) \
        \( -size 1x59 gradient:"$3-$4" \) \
        \( -size 1x57 gradient:"$4-$5" \) -append "$1"
}
mkclut "$TMP/clut_gui.png" "$BG" "$BG_EL" "$CARD" "$BORDER"
mkclut "$TMP/clut_hud.png" "$BG_EL" "$BG_EL" "$BORDER" "$BORDER_HOVER"
recolor() { # src dst clut
    magick "$1" -alpha extract "$TMP/a.png"
    magick "$1" -channel R -separate +channel -colorspace sRGB "$3" -clut \
        "$TMP/a.png" -alpha off -compose CopyOpacity -composite "$2"
}
for f in inventory generic_54 crafting_table; do
    recolor "$VAN/container/$f.png" "$MC/container/$f.png" "$TMP/clut_gui.png"
done
for f in hotbar_offhand_left hotbar_offhand_right; do
    recolor "$VAN/sprites/hud/$f.png" "$MC/sprites/hud/$f.png" "$TMP/clut_hud.png"
done

echo "== эмблема"
magick -background none "$LOGO_SVG" -resize 256x256 "$BF/logo.png"

echo "== главный экран"
# minecraft.png: холст 1024x256, но игра берёт верхние 1024x176 (blit 256x44 при texH=64)
magick -background none -fill "$TEXT" -font "$FONT" -pointsize 300 -kerning 16 \
    label:'BLOCKFIELD' -trim +repage -resize 828x104 "$TMP/word.png"
TW=$(magick identify -format %w "$TMP/word.png")
TH=$(magick identify -format %h "$TMP/word.png")
BLOCK=$((TH + 14 + 8))
TY=$(((176 - BLOCK) / 2))
magick -size 1024x256 xc:none \
    \( "$BF/logo.png" -resize 140x140 \) -geometry +14+18 -compose over -composite \
    "$TMP/word.png" -geometry "+180+$TY" -compose over -composite \
    -fill "$AMBER" +antialias -draw "rectangle 180,$((TY + TH + 14)) $((180 + TW - 1)),$((TY + TH + 21))" \
    "$MC/title/minecraft.png"
magick -size 512x64 xc:none "$MC/title/edition.png"

# Панорама главного меню: title/background/panorama_0..5 — грани кубмапа из эквиректангулярной
# картины (agy, 4096x2048), нарезка — ./equirect2cube.py <equirect.png> <dir> 1024. Здесь не генерится.

echo "== экран загрузки"
# Раскладка как у mojangstudios.png: верхняя половина текстуры = левая половина логотипа, нижняя = правая
# (ваниль рисует их рядом двумя blit'ами 2:1). Источник подменяет LoadingLogoTextureMixin.
# Блок собирается по содержимому и вписывается в 1600x300 внутри 2048x512 -- ваниль тянет текстуру
# целиком, поэтому без полей буквы упираются в край экрана.
magick -background none -fill "$TEXT" -font "$FONT" -pointsize 300 -kerning 16 \
    label:'BLOCKFIELD' -trim +repage -resize x200 "$TMP/lword.png"
LW=$(magick identify -format %w "$TMP/lword.png")
magick -size "$((260 + 56 + LW))x260" xc:none \
    \( "$BF/logo.png" -resize 260x260 \) -geometry +0+0 -compose over -composite \
    "$TMP/lword.png" -geometry "+$((260 + 56))+20" -compose over -composite \
    -fill "$AMBER" +antialias \
    -draw "rectangle $((260 + 56)),238 $((260 + 56 + LW - 1)),250" \
    "$TMP/lblock.png"
magick "$TMP/lblock.png" -resize '1600x300>' -background none -gravity center -extent 2048x512 "$TMP/strip.png"
magick "$TMP/strip.png" -crop 1024x512+0+0 +repage "$TMP/strip_l.png"
magick "$TMP/strip.png" -crop 1024x512+1024+0 +repage "$TMP/strip_r.png"
magick "$TMP/strip_l.png" "$TMP/strip_r.png" -append +repage "$BF/loading_logo.png"

echo "== сплэши"
cat >"$OUT/minecraft/texts/splashes.txt" <<'TXT'
Захвати точку!
Патроны не бесконечны.
Слушай рацию.
Держи строй!
Позиция важнее прицела.
Дым — твой друг.
Не беги по открытому.
Прикрой фланг!
Комната ждёт.
Один выстрел — один фраг.
Окоп копай заранее.
Тишина перед штурмом.
Респавн через три.
За Блокфилд!
TXT

# все PNG в 8-битный RGBA: NativeImage читает и палитру, и 16 бит, но одинаковый формат надёжнее
echo "== нормализация PNG в RGBA8"
find "$MC" "$BF/logo.png" "$BF/loading_logo.png" -name '*.png' -not -path '*/title/background/*' -print0 | while IFS= read -r -d '' p; do
    magick "$p" -depth 8 "PNG32:$p"
done

echo "== .mcmeta из ванили"
find "$MC" -name '*.png' | while read -r png; do
    rel="${png#$MC/}"
    if [ -f "$VAN/$rel.mcmeta" ]; then cp "$VAN/$rel.mcmeta" "$png.mcmeta"; fi
done

echo "== проверка размеров против ванили"
find "$MC" -name '*.png' | sort | while read -r png; do
    rel="${png#$MC/}"
    ours=$(magick identify -format '%wx%h' "$png")
    case "$rel" in title/background/*)
        echo "  --   $rel $ours (в jar лежит заглушка 1x1, реальная грань 1024x1024)"
        continue
        ;;
    esac
    if [ -f "$VAN/$rel" ]; then
        van=$(magick identify -format '%wx%h' "$VAN/$rel")
        if [ "$ours" = "$van" ]; then echo "  ok   $rel $ours"; else echo "  ПЛОХО $rel $ours != $van"; fi
    else
        echo "  --   $rel $ours (в ванильном jar нет эталона)"
    fi
done
echo "готово"
