#!/system/bin/sh
# Installed, manual-only controller. No AP/USB settings or init services are changed.
export PATH=/system/bin:/system/xbin:/data/nemesis/bin
export LD_LIBRARY_PATH=/vendor/lib:/system/lib
export ANDROID_ROOT=/system ANDROID_DATA=/data
umask 077
ROOT=$(cd "${0%/*}/.." && pwd -P) || exit 1
case "$ROOT" in
    /data/nemesis/apps/blackr.wireless.adb|/data/nemesis/packages/blackr.wireless.adb|/data/local/tmp/nemesis/stage/blackr.wireless.adb) ;;
    *) echo 'ADB 패키지 경로를 확인하지 못했습니다.'; exit 1;;
esac
STATE=/data/nemesis/blackr-wireless-adb-session
PUBLIC=/data/local/tmp/blackr-wireless-adb-public
BOOT=$(cat /proc/sys/kernel/random/boot_id) || exit 1
installation=$(cat "$ROOT/installation.id") || exit 1
[ -n "$installation" ] || exit 1
action=$1
case "$action" in start|stop|status|remove) ;; *) exit 2;; esac
if [ -L "$STATE" ] || { [ -e "$STATE" ] && [ ! -f "$STATE/launcher.owned" ]; }; then
    echo 'STATE=UNRECOGNIZED_STATE'; echo '기존 ADB 작업 폴더를 확인하지 못했습니다.'; exit 1
fi
birth() {
    local line
    line=$(cat "/proc/$1/stat" 2>/dev/null) || return 1
    line=${line##*) }; set -- $line
    [ "$#" -ge 20 ] && [ "$1" != Z ] && echo "${20}"
}
worker_alive() {
    [ "$(cat "$STATE/launcher.boot" 2>/dev/null)" = "$BOOT" ] || return 1
    read -r pid saved_birth < "$STATE/launcher.pid" || return 1
    case "$pid" in ''|*[!0-9]*) return 1;; esac
    [ "$pid" -gt 1 ] && [ -n "$saved_birth" ] && [ "$(birth "$pid")" = "$saved_birth" ]
}
show_status() {
    local s d
    if worker_alive 2>/dev/null; then
        s=$(cat "$STATE/status" 2>/dev/null)
        [ -n "$s" ] || s=STARTING
    elif [ "$(cat "$STATE/launcher.boot" 2>/dev/null)" = "$BOOT" ] && [ -f "$STATE/done" ]; then
        s=$(cat "$STATE/status" 2>/dev/null)
    elif [ "$(cat "$STATE/launcher.boot" 2>/dev/null)" = "$BOOT" ] && [ -f "$STATE/launcher.started" ]; then
        s=WORKER_INTERRUPTED
    else s=STOPPED; fi
    case "$s" in ''|*[!A-Z0-9_]*) s=UNKNOWN;; esac
    echo "STATE=$s"
    d=$(cat "$STATE/detail" 2>/dev/null)
    case "$d" in ''|*[!A-Z0-9_]*) ;; *) echo "DETAIL=$d";; esac
    case "$s" in
        LISTENING) echo 'ADB 실행 중: 192.168.43.1:5555';;
        WAIT_DONGLE) echo '동글과 Wi-Fi 핫스팟 확인 중입니다.';;
        STOPPED|RECOVERED|DONGLE_REMOVED) echo 'ADB가 꺼져 있습니다. 시작을 누르면 켜집니다.';;
        *) echo 'ADB 상태 코드를 확인하세요. 실패 기록은 다음 USB 연결 때 수집됩니다.';;
    esac
    /system/bin/sh "$ROOT/runtime/monitor.sh" "$STATE" "$s" "$BOOT"
}
if [ "$action" = status ]; then show_status; exit 0; fi
case "$(id)" in 'uid=0('*|'uid=0 '*|'uid=0') ;; *) echo 'STATE=ROOT_REQUIRED'; exit 1;; esac
lock=/dev/blackr-wireless-adb-lock
if ! mkdir "$lock" 2>/dev/null; then echo 'STATE=BUSY'; echo '다른 ADB 작업이 진행 중입니다.'; exit 1; fi
trap 'rmdir "$lock"' EXIT
trap 'exit 1' HUP INT TERM
stop_current() {
    if worker_alive 2>/dev/null; then
        : > "$STATE/stop" || return 1
        n=0
        while worker_alive 2>/dev/null && [ "$n" -lt 20 ]; do sleep 1; n=$((n+1)); done
        worker_alive 2>/dev/null && return 1
    fi
    if [ -f "$STATE/launcher.started" ] && [ ! -f "$STATE/done" ]; then
        /system/bin/sh "$STATE/session.sh" recover "$STATE" || return 1
    fi
}
if [ "$action" = stop ] || [ "$action" = remove ]; then
    stop_current || { echo 'STATE=STOP_PENDING'; echo 'ADB 종료를 기다리고 있습니다. 잠시 후 중지를 다시 누르세요.'; exit 1; }
    if [ -d "$STATE" ]; then echo STOPPED > "$STATE/status"; echo STOPPED > "$STATE/detail"; echo 1 > "$STATE/done"; fi
    if [ "$action" = remove ]; then
        [ ! -d "$STATE" ] || rm -r "$STATE" || exit 1
        if [ -d "$PUBLIC" ] && [ ! -L "$PUBLIC" ] && [ "$(cat "$PUBLIC/installation.id")" = "$installation" ]; then
            rm -f "$PUBLIC/installation.id" && rmdir "$PUBLIC" || exit 1
        fi
    fi
    show_status
    echo 'ADB를 껐습니다. CarPlay 연결은 그대로 유지합니다.'; exit 0
fi
[ "$(getprop ro.build.product)" = NEEV.KOR.0000.V039.001.260902 ] &&
    [ "$(getprop ro.board.platform)" = tcc803x ] &&
    [ "$(cat /proc/sys/kernel/osrelease)" = 3.18.24-tcc ] &&
    [ "$(getprop sys.boot_completed)" = 1 ] || { echo 'STATE=WRONG_VEHICLE_OR_BOOTING'; exit 1; }
if worker_alive 2>/dev/null; then show_status; exit 0; fi
stop_current || { echo 'STATE=PREVIOUS_SESSION_RECOVERY_FAILED'; exit 1; }
[ -r /data/nemesis/apps/blackr.wireless.adb/runtime/provider.jar ] || { echo 'STATE=ADB_PROVIDER_MISSING'; exit 1; }
if [ -L "$PUBLIC" ] || { [ -e "$PUBLIC" ] && [ "$(cat "$PUBLIC/installation.id")" != "$installation" ]; }; then
    echo 'STATE=IDENTITY_CONFLICT'; exit 1
fi
mkdir -p "$PUBLIC" && chmod 755 "$PUBLIC" &&
    cp "$ROOT/installation.id" "$PUBLIC/installation.id" && chmod 644 "$PUBLIC/installation.id" || exit 1
if [ -d "$STATE" ]; then
    # Preserve the most recent failed run, without accumulating unbounded log files.
    LAST="$ROOT/last-session"
    [ ! -L "$LAST" ] || exit 1
    mkdir -p "$LAST" && chmod 700 "$LAST" || exit 1
    for f in status detail worker.log daemon.log guard.error events.log traffic.state client.state; do
        rm -f "$LAST/$f" || exit 1
        [ ! -f "$STATE/$f" ] || cp "$STATE/$f" "$LAST/$f" || exit 1
    done
    rm -r "$STATE" || exit 1
fi
mkdir "$STATE" && chmod 700 "$STATE" && : > "$STATE/launcher.owned" || exit 1
md5_file() { local line; line=$(md5 "$1") || return 1; echo "${line%% *}"; }
for name in session.sh daemon.sh neev-adbd mac.pub; do
    src="$ROOT/runtime/$name"
    [ -f "$src" ] && [ ! -L "$src" ] &&
        sum=$(md5_file "$src") && cp "$src" "$STATE/$name" && chmod 600 "$STATE/$name" &&
        [ "$(md5_file "$STATE/$name")" = "$sum" ] || { echo 'STATE=STAGING_FAILED'; exit 1; }
done
chmod 700 "$STATE/neev-adbd" || exit 1
echo "$BOOT" > "$STATE/launcher.boot"
: > "$STATE/launcher.started"
(
    trap '' HUP
    cd "$STATE" || exit 1
    n=0
    while [ ! -s launcher.pid ]; do [ "$n" -lt 5 ] || exit 1; sleep 1; n=$((n+1)); done
    exec /system/bin/sh "$STATE/session.sh" run "$STATE"
) </dev/null >"$STATE/worker.log" 2>&1 &
pid=$!
started=$(birth "$pid") && echo "$pid $started" > "$STATE/launcher.tmp" &&
    mv "$STATE/launcher.tmp" "$STATE/launcher.pid" || { : > "$STATE/stop"; echo 'STATE=CANNOT_RECORD_WORKER'; exit 1; }
n=0
while worker_alive 2>/dev/null && [ "$n" -lt 15 ]; do
    s=$(cat "$STATE/status" 2>/dev/null)
    [ "$s" != LISTENING ] || break
    [ ! -f "$STATE/done" ] || break
    sleep 1; n=$((n+1))
done
show_status
