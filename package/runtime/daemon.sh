#!/system/bin/sh
# The caller backgrounds this function; its PID becomes the foreground daemon.
exec_daemon() {
    [ "$#" -eq 3 ] && [ -x "$1" ] && [ -r "$3" ] || return 1
    exec "$1" "$2" "$3"
}
