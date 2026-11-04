#!/bin/sh
# Внутри контейнера bf-xvfb: виртуальный экран + клиент. Аргументы уходят клиенту
# (например --quickPlayMultiplayer host:port).
# Свой номер экрана на клиента: abstract-сокет @/tmp/.X11-unix/X<N> общий в host-сети, и второй Xvfb
# на том же номере не стартует -- клиент рисует в чужой экран и умирает вместе с первым контейнером.
display="${BF_RIG_DISPLAY:-99}"
Xvfb ":$display" -screen 0 "${BF_RES:-1280x720}x24" -nolisten tcp &
sleep 2
export DISPLAY=":$display"
run=
if [ -e /dev/dri/renderD128 ] && command -v vglrun >/dev/null; then
    # VirtualGL рисует GL клиента на GPU (EGL, без X-сервера на карте) и копирует кадр в Xvfb, так что xdotool,
    # import и скриншоты работают как с llvmpipe. faker 3.1 ищет EGL и GLX при старте процесса, а Java открывает
    # libGL позже через dlopen: без предзагрузки "undefined symbol: eglGetProcAddress / glXGetProcAddressARB".
    export LD_PRELOAD=/usr/lib64/libEGL.so.1:/usr/lib64/libGL.so.1
    run="vglrun -d egl"
    echo "rig: GPU через VirtualGL"
else
    # llvmpipe по умолчанию берёт поток на ядро: два клиента и сервер дрались за все 12.
    export LIBGL_ALWAYS_SOFTWARE=1 LP_NUM_THREADS="${LP_NUM_THREADS:-2}"
    echo "rig: программный рендер llvmpipe"
fi
opts="-Dbf.testbot.port=${BF_RIG_BOT_PORT:-47777}"
# JDWP для hotswap.sh client (сеть контейнера = host); второй клиент без отладчика, порт 5006 один.
[ "${BF_RIG_JDWP:-1}" = 1 ] && opts="$opts -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:5006"
# Без -Xmx JVM берёт четверть ОЗУ (~3.4 ГБ); с 1400m два клиента и сервер помещаются в 13 ГБ.
[ -n "${BF_CLIENT_XMX:-}" ] && opts="$opts -Xms256m -Xmx$BF_CLIENT_XMX -XX:+UseStringDeduplication -XX:ReservedCodeCacheSize=128m -XX:ParallelGCThreads=2 -XX:ConcGCThreads=1"
export JAVA_TOOL_OPTIONS="$opts"
# glibc заводит malloc-арену на поток (до 8 × ядер): у клиента с ~120 потоками это сотни МБ нативной памяти.
export MALLOC_ARENA_MAX="${MALLOC_ARENA_MAX:-2}"
# BF_AUDIO_WAV=/S/<файл>.wav -- звук клиента пишется в WAV (у контейнера нет звуковой карты): проверка музыки.
if [ -n "${BF_AUDIO_WAV:-}" ]; then
    printf '[general]\ndrivers = wave\n[wave]\nfile = %s\n' "$BF_AUDIO_WAV" >"$HOME/.alsoftrc"
fi
exec $run "/S/${BF_RIG_SCRIPT:-run-client.sh}" "$@"
