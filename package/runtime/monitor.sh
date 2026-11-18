#!/system/bin/sh
# Read-only, bounded dashboard output. A listening TCP socket alone is not a client.
export PATH=/system/bin:/system/xbin:/data/nemesis/bin
STATE=$1
MODE=$2
BOOT=$3
[ "$STATE" = /data/nemesis/blackr-wireless-adb-session ] && [ -d "$STATE" ] && [ ! -L "$STATE" ] || {
    echo CLIENT=STOPPED; exit 0;
}
numeric() { case "$1" in ''|*[!0-9]*) return 1;; esac; [ "${#1}" -le 20 ]; }
points_to() {
    local dest listing
    dest=$(readlink "$1" 2>/dev/null) && { [ "$dest" = "$2" ]; return; }
    listing=$(ls -ld "$1" 2>/dev/null) || return 1
    case "$listing" in *" -> $2") return 0;; *) return 1;; esac
}
same_daemon() {
    local line
    line=$(cat "/proc/$PID/stat" 2>/dev/null) || return 1
    line=${line##*) }; set -- $line
    [ "$#" -ge 20 ] && [ "$1" != Z ] && [ "${20}" = "$BORN" ] &&
        points_to "/proc/$PID/exe" "$STATE/neev-adbd"
}
listener_owned() {
    local sl addr peer st queue timer retr uid timeout inode rest fd
    while read -r sl addr peer st queue timer retr uid timeout inode rest; do
        [ "$addr" = 012BA8C0:15B3 ] && [ "$st" = 0A ] && numeric "$inode" || continue
        for fd in /proc/"$PID"/fd/*; do
            points_to "$fd" "socket:[$inode]" && return 0
        done
    done < /proc/net/tcp
    return 1
}
snapshot() {
    [ -f "$STATE/traffic.state" ] && [ ! -L "$STATE/traffic.state" ] || return 1
    # FD8 is one bounded line. Reject partial writes and unrecognized versions.
    read -r ver uptime seq auth rxp rxb txp txb shell_count sync_count ev extra < "$STATE/traffic.state" || return 1
    [ "$ver" = V1 ] && [ -z "$extra" ] || return 1
    case "$auth" in 0|1) ;; *) return 1;; esac
    for value in "$uptime" "$seq" "$rxp" "$rxb" "$txp" "$txb" "$shell_count" "$sync_count" "$ev"; do
        numeric "$value" || return 1
    done
}
client=STOPPED
telemetry=0
case "$MODE" in LISTENING|CONNECTED)
    client=UNKNOWN
    if [ -f "$STATE/daemon.owner" ] && read -r saved_boot PID BORN extra < "$STATE/daemon.owner" &&
        [ "$saved_boot" = "$BOOT" ] && [ -z "$extra" ] && numeric "$PID" && numeric "$BORN" &&
        [ "${#PID}" -le 9 ] && [ "$PID" -gt 1 ] && same_daemon && listener_owned &&
        points_to "/proc/$PID/fd/8" "$STATE/traffic.state" && snapshot && same_daemon; then
        telemetry=1
        if points_to "/proc/$PID/fd/9" "$STATE/client.state" &&
            read -r connected extra < "$STATE/client.state" && [ -z "$extra" ] &&
            [ "$connected" = "$auth" ] && same_daemon; then
            case "$connected" in 1) client=CONNECTED;; 0) client=WAITING;; esac
        fi
    fi
    ;;
    *) snapshot && telemetry=1;;
esac
echo "CLIENT=$client"
if [ "$telemetry" = 1 ]; then
    echo "RX_BYTES=$rxb"; echo "TX_BYTES=$txb"
    echo "RX_PACKETS=$rxp"; echo "TX_PACKETS=$txp"
    echo "SHELL_ACTIVE=$shell_count"; echo "SYNC_ACTIVE=$sync_count"
fi
echo LOG_BEGIN
if [ -f "$STATE/events.log" ] && [ ! -L "$STATE/events.log" ]; then
    listing=$(ls -ldn "$STATE/events.log" 2>/dev/null)
    set -- $listing
    bytes=$4
    if numeric "$bytes" && [ "${#bytes}" -le 6 ] && [ "$bytes" -le 131072 ]; then
        skip=$((bytes / 1024 - 8)); [ "$skip" -ge 0 ] || skip=0
        # KitKat toolbox has dd, but not tail. Drop partial/unrecognized records.
        dd if="$STATE/events.log" bs=1024 skip="$skip" count=9 2>/dev/null |
        while read -r ver sequence millis event a b extra; do
            [ "$ver" = E1 ] && [ -z "$extra" ] || continue
            case "$event" in ''|*[!A-Z0-9_]*) continue;; esac
            [ "${#event}" -le 32 ] || continue
            numeric "$sequence" && numeric "$millis" && numeric "$a" && numeric "$b" || continue
            # This Gen5W mksh has echo, but no printf builtin or executable.
            # All fields above are restricted to digits/fixed uppercase codes.
            echo "E1 $sequence $millis $event $a $b"
        done
    fi
fi
echo LOG_END
echo COMMANDS_BEGIN
if [ "$telemetry" = 1 ] && [ "$client" = CONNECTED ] && [ -f "$STATE/commands.path" ] && [ ! -L "$STATE/commands.path" ]; then
    read -r cmdfile extra < "$STATE/commands.path"
    case "$cmdfile" in /dev/blackr-wireless-adb-live-*/commands)
        if [ -z "$extra" ] && [ -f "$cmdfile" ] && [ ! -L "$cmdfile" ] && points_to "/proc/$PID/fd/6" "$cmdfile" && same_daemon; then
            cat "$cmdfile" 2>/dev/null
        fi;;
    esac
fi
echo COMMANDS_END
