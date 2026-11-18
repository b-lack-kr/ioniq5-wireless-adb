#!/system/bin/sh
# The Nemesis launcher serializes run/recover and copies this entire kit to private storage.
export PATH=/system/bin:/system/xbin:/data/nemesis/bin
export LD_LIBRARY_PATH=/vendor/lib:/system/lib
umask 077
STATE=/data/nemesis/blackr-wireless-adb-session
LIVE=/dev/blackr-wireless-adb-live-$$
[ "$2" = "$STATE" ] && [ -d "$STATE" ] && [ ! -L "$STATE" ] && cd "$STATE" || exit 2
PROVIDER=/data/nemesis/apps/blackr.wireless.adb/runtime/provider.jar
BINARY=$STATE/neev-adbd
CHILD=0
PID=
BORN=
GUARD_PID=
GUARD_BORN=
AP_PID=
BOOT=$(cat /proc/sys/kernel/random/boot_id) || exit 2

detail() { echo "$1" > detail.tmp && mv detail.tmp detail; }
report() {
    echo "$1" > status.tmp && mv status.tmp status || return 1
    case "$1" in WAIT_DONGLE|DONGLE_TIMEOUT) ;; *) detail "$1";; esac
}
notice() {
    am broadcast -a com.hkmc.intent.action.TOAST -e com.hkmc.intent.extra.TOAST_MESSAGE "$1" --ez com.hkmc.intent.extra.TOAST_POPUPSHOWING true >/dev/null 2>&1 || :
}
uptime_s() {
    local up rest
    read -r up rest < /proc/uptime || return 1
    echo "${up%%.*}"
}
born() {
    local stat
    IFS= read -r stat < "/proc/$1/stat" || return 1
    # /proc/PID/stat's comm in parentheses may contain spaces. Trim it before indexing.
    stat=${stat##*) }
    set -- $stat
    [ "$#" -ge 20 ] && echo "${20}"
}
# Old toolbox builds differ in readlink availability; ls from this firmware prints " -> target".
points_to() {
    local dest listing
    dest=$(readlink "$1" 2>/dev/null) && { [ "$dest" = "$2" ]; return; }
    listing=$(ls -ld "$1" 2>/dev/null) || return 1
    case "$listing" in *" -> $2") return 0;; *) return 1;; esac
}
same_daemon() {
    [ -n "$PID" ] && [ -n "$BORN" ] && [ "$(born "$PID" 2>/dev/null)" = "$BORN" ] &&
        points_to "/proc/$PID/exe" "$BINARY"
}
# Also recognize our direct child while exec is in flight or procfs hides its exe.
# Recovery in a later process only uses the journaled birth + executable.
our_process() {
    same_daemon && return 0
    [ "$CHILD" = 1 ] && [ -n "$PID" ] || return 1
    local stat
    IFS= read -r stat < "/proc/$PID/stat" || return 1
    stat=${stat##*) }
    set -- $stat
    [ "$#" -ge 20 ] && [ "$1" != Z ] && [ "$2" = "$$" ] &&
        { [ -z "$BORN" ] || [ "${20}" = "$BORN" ]; }
}
stop_owned() {
    if our_process 2>/dev/null; then
        kill "$PID" 2>/dev/null || :
        local n=0
        while our_process 2>/dev/null && [ "$n" -lt 3 ]; do sleep 1; n=$((n+1)); done
        if our_process 2>/dev/null; then kill -9 "$PID" || return 1; sleep 1; fi
        our_process 2>/dev/null && return 1
    fi
    return 0
}
finish() {
    [ -z "$GUARD_PID" ] || stop_guard
    if [ -n "$AP_PID" ]; then
        kill "$AP_PID" 2>/dev/null || :
        wait "$AP_PID" 2>/dev/null || :
        AP_PID=
    fi
    if ! stop_owned; then report STOP_FAILED; exit 1; fi
    if [ "$CHILD" = 1 ] && [ -n "$PID" ]; then wait "$PID" 2>/dev/null || :; fi
    [ ! -L "$LIVE" ] && rm -f "$LIVE/commands" 2>/dev/null
    rmdir "$LIVE" 2>/dev/null || :
    report "$1" && echo 1 > done
}
recover() {
    local saved_boot
    if [ -f daemon.owner ]; then
        read -r saved_boot PID BORN < daemon.owner || return 1
        case "$PID" in ''|*[!0-9]*) return 1;; esac
        [ "$PID" -gt 1 ] || return 1
        [ "$saved_boot" = "$BOOT" ] || PID=
    fi
    finish RECOVERED
}
case "$1" in recover) recover; exit $?;; run) ;; *) exit 2;; esac

# A guard is our direct child, never an AP service. Verify birth and parent before
# sending a signal, then reap it before its output files can be reused.
guard_child_live() {
    [ -n "$GUARD_PID" ] || return 1
    local stat
    IFS= read -r stat < "/proc/$GUARD_PID/stat" || return 1
    stat=${stat##*) }
    set -- $stat
    [ "$#" -ge 20 ] && [ "$1" != Z ] && [ "$2" = "$$" ] &&
        { [ -z "$GUARD_BORN" ] || [ "${20}" = "$GUARD_BORN" ]; }
}
stop_guard() {
    [ -n "$GUARD_PID" ] || return 0
    if guard_child_live 2>/dev/null; then
        kill "$GUARD_PID" 2>/dev/null || :
        sleep 1
        if guard_child_live 2>/dev/null; then kill -9 "$GUARD_PID" 2>/dev/null || :; fi
    fi
    wait "$GUARD_PID" 2>/dev/null || :
    GUARD_PID=; GUARD_BORN=
}
guard() {
    local started now result
    started=$(uptime_s) || return 1
    CLASSPATH="$PROVIDER" /system/bin/app_process /system/bin kr.ioniq5.usbcp.SafeInterface "$@" > guard.out 2> guard.error &
    GUARD_PID=$!
    GUARD_BORN=$(born "$GUARD_PID" 2>/dev/null) || GUARD_BORN=
    while guard_child_live 2>/dev/null; do
        [ ! -f stop ] || { stop_guard; return 125; }
        now=$(uptime_s) || { stop_guard; return 1; }
        if [ $((now-started)) -ge 5 ]; then
            stop_guard
            return 124
        fi
        sleep 1
    done
    wait "$GUARD_PID" 2>/dev/null
    result=$?
    GUARD_PID=; GUARD_BORN=
    return "$result"
}
ap_addr() { ip -4 addr show dev "$1" 2>/dev/null | grep -Fq 'inet 192.168.43.1/24 '; }
select_ap() {
    local selected suffix result candidate found=0
    for candidate in /sys/class/net/rtwlan*; do
        [ ! -d "$candidate" ] || found=1
    done
    [ "$found" = 1 ] || { detail NO_DONGLE; return 1; }
    guard
    result=$?
    case "$result" in
        0) ;;
        124) detail GUARD_TIMEOUT; return 1;;
        *) detail GUARD_FAILED; return 1;;
    esac
    selected=$(cat guard.out) || { detail GUARD_FAILED; return 1; }
    set -- $selected
    [ "$#" = 3 ] || { detail INVALID_SELECTION; return 1; }
    IFACE=$1; INDEX=$2; TOKEN=$3
    suffix=${IFACE#rtwlan}
    [ "$suffix" != "$IFACE" ] && [ "${#suffix}" -le 3 ] || { detail INVALID_SELECTION; return 1; }
    case "$suffix" in ''|*[!0-9]*) detail INVALID_SELECTION; return 1;; esac
    case "$INDEX" in ''|0*|*[!0-9]*) detail INVALID_SELECTION; return 1;; esac
    case "$TOKEN" in ''|*[!0-9a-f]*) detail INVALID_SELECTION; return 1;; esac
    [ "${#TOKEN}" = 64 ] || { detail INVALID_SELECTION; return 1; }
    guard check "$IFACE" "$TOKEN"
    result=$?
    case "$result" in
        0) ;;
        124) detail GUARD_TIMEOUT; return 1;;
        *) detail IDENTITY_CHANGED; return 1;;
    esac
    ap_addr "$IFACE" || { detail WAIT_AP_ADDRESS; return 1; }
    return 0
}
startup_failure() {
    # Return a fixed diagnosis on the next STATUS visit without exporting raw vehicle logs.
    local line
    while IFS= read -r line; do
        case "$line" in
            *"CANNOT LINK EXECUTABLE"*|*"cannot locate symbol"*) echo DAEMON_LINKER_FAILED; return;;
            "Bind to WLAN:"*|"Bind to AP address:"*) echo DAEMON_BIND_FAILED; return;;
            "Invalid ADB public key"*) echo DAEMON_KEY_FAILED; return;;
            "Unable to listen as shell"*) echo DAEMON_SHELL_LISTEN_FAILED; return;;
            "Watch ADB worker:"*|"ADB worker unavailable"*) echo DAEMON_PARENT_FAILED; return;;
            "Dongle address unavailable"*) echo DAEMON_ADDRESS_UNAVAILABLE; return;;
            *"Permission denied"*|*"not executable"*|*"Exec format error"*) echo DAEMON_EXEC_FAILED; return;;
        esac
    done < daemon.log
    echo LISTENER_FAILED
}
listener_owned() {
    local sl addr peer st queue timer retr uid timeout inode rest fd
    # 192.168.43.1:5555, TCP_LISTEN. There may also be an unrelated socket.
    while read -r sl addr peer st queue timer retr uid timeout inode rest; do
        [ "$addr" = '012BA8C0:15B3' ] && [ "$st" = 0A ] && [ -n "$inode" ] || continue
        for fd in /proc/"$PID"/fd/*; do
            points_to "$fd" "socket:[$inode]" && return 0
        done
    done < /proc/net/tcp
    return 1
}
# HUP can accompany the physical USB-to-dongle swap.
trap '' HUP
trap 'finish STOPPED; exit 0' TERM INT
if [ ! -r "$PROVIDER" ] || [ ! -s mac.pub ] || [ ! -x "$BINARY" ] || ! . ./daemon.sh; then
    finish MISSING_COMPONENT; exit 1
fi
detail NO_DONGLE && report WAIT_DONGLE && echo 1 > READY || exit 1
start=$(uptime_s) || exit 1
# Reuse an already running AP first, whether it is CarPlay or another owner.
if select_ap; then
    echo EXISTING > ap-mode
else
    echo WAIT > ap-mode
fi
until select_ap; do
    if [ -z "$AP_PID" ] && [ ! -e /data/hmgapi/mods/neev-usb-carplay ] && [ ! -L /data/hmgapi/mods/neev-usb-carplay ]; then
        # Never take over an interface that already has an IPv4 address.
        busy=0
        for net in /sys/class/net/rtwlan*; do
            [ -d "$net" ] || continue
            if ip -4 addr show dev "${net##*/}" 2>/dev/null | grep -q 'inet '; then busy=1; fi
        done
        if [ "$busy" = 0 ]; then
            own=/data/nemesis/blackr-wireless-adb-ap
            if [ ! -L "$own" ] && [ ! -L "$own/config" ] && mkdir -p "$own/config" && chmod 700 "$own" "$own/config" &&
                CLASSPATH=/data/nemesis/apps/blackr.wireless.adb/runtime/wifi-reader.jar /system/bin/app_process /system/bin neev.adb.ApConfigMain; then
                echo OWN > ap-mode
                export ADB_OWNER_PID=$$
                ADB_OWNER_BORN=$(born $$) && export ADB_OWNER_BORN
                /system/bin/sh /data/nemesis/apps/blackr.wireless.adb/runtime/ap/service.sh > ap-worker.log 2>&1 &
                AP_PID=$!
            else detail AP_CONFIG_FAILED; fi
        else detail EXISTING_AP_CONFLICT; fi
    fi
    if [ -n "$AP_PID" ] && ! kill -0 "$AP_PID" 2>/dev/null; then
        wait "$AP_PID" 2>/dev/null || :
        AP_PID=
        detail AP_START_FAILED
        finish AP_START_FAILED; exit 1
    fi
    [ ! -f stop ] || { finish STOPPED; exit 0; }
    now=$(uptime_s) || { finish CLOCK_FAILED; exit 1; }
    [ $((now-start)) -lt 240 ] || { finish DONGLE_TIMEOUT; notice '무선 ADB 종료: 동글 핫스팟을 확인하세요'; exit 1; }
    sleep 2
done
[ ! -f stop ] || { finish STOPPED; exit 0; }
export NEEV_ADB_WORKER_PID=$$
CMD_FILE=/dev/null
if mkdir "$LIVE" 2>/dev/null && [ ! -L "$LIVE" ]; then
    CMD_FILE="$LIVE/commands"
    echo "$CMD_FILE" > commands.path
fi
exec_daemon "$BINARY" "$IFACE" "$STATE/mac.pub" 6> "$CMD_FILE" 7> events.log 8> traffic.state 9> client.state > daemon.log 2>&1 &
PID=$!
CHILD=1
# An init-independent worker owns the child; on restart verify boot, PID birth, and executable.
BORN=$(born "$PID" 2>/dev/null) || { finish DAEMON_START_FAILED; exit 1; }
echo "$BOOT $PID $BORN" > owner.tmp && mv owner.tmp daemon.owner || { finish JOURNAL_FAILED; exit 1; }
n=0
until same_daemon && listener_owned; do
    [ ! -f stop ] || { finish STOPPED; exit 0; }
    if [ "$n" -ge 8 ] || ! kill -0 "$PID" 2>/dev/null; then
        finish "$(startup_failure)"; notice '무선 ADB 시작 실패: 상태 기록을 확인하세요'; exit 1
    fi
    sleep 1; n=$((n+1))
done
report LISTENING
notice '무선 ADB 준비됨: Mac에서 연결하세요'
while :; do
    [ ! -f stop ] || { finish STOPPED; break; }
    same_daemon || { finish DAEMON_EXITED; break; }
    if ! read -r live < "/sys/class/net/$IFACE/ifindex" || [ "$live" != "$INDEX" ] || ! ap_addr "$IFACE"; then
        finish DONGLE_REMOVED; break
    fi
    sleep 2
done
