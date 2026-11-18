#!/system/bin/sh
export PATH=/system/bin:/system/xbin:/data/nemesis/bin
export LD_LIBRARY_PATH=/vendor/lib:/system/lib
export ANDROID_ROOT=/system ANDROID_DATA=/data
umask 077
ROOT=$(cd "${0%/*}/.." && pwd -P) || exit 1
case "$ROOT" in /data/nemesis/apps/blackr.wireless.adb|/data/nemesis/packages/blackr.wireless.adb) ;; *) exit 1;; esac
am start -n kr.blackr.wirelessadb/neev.adb.AdbControl >/dev/null 2>&1 || exit 1
