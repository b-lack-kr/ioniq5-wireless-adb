#!/system/bin/sh
export PATH=/system/bin:/system/xbin:/data/nemesis/bin
export LD_LIBRARY_PATH=/vendor/lib:/system/lib
umask 077
[ "$(getprop ro.build.product)" = NEEV.KOR.0000.V039.001.260902 ] &&
    [ "$(getprop ro.board.platform)" = tcc803x ] &&
    [ "$(cat /proc/sys/kernel/osrelease)" = 3.18.24-tcc ] || exit 1
case "$(id)" in 'uid=0('*|'uid=0 '*|'uid=0') ;; *) exit 1;; esac
[ -s runtime/provider.jar ] || exit 1
chmod 755 runtime/neev-adbd || exit 1
for f in runtime/session.sh runtime/ap-policy.sh runtime/daemon.sh runtime/monitor.sh runtime/mac.pub runtime/adb-control.jar runtime/wifi-export.jar runtime/wifi-reader.jar app/adb-control.apk installation.id; do
    [ -s "$f" ] && [ ! -L "$f" ] || exit 1
done
# Retire only the previous ADB session; installation never starts a new one.
/system/bin/sh "$PWD/bin/control.sh" stop
