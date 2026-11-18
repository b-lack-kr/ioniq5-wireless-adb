#!/system/bin/sh
export PATH=/system/bin:/system/xbin:/data/nemesis/bin
chmod 755 runtime/neev-adbd || exit 1
tmp=/data/nemesis/apps/blackr-wireless-adb-install.apk
[ ! -L "$tmp" ] || exit 1
cp app/adb-control.apk "$tmp" && chmod 644 "$tmp" || exit 1
prev=$(getprop sys.boot_completed)
restore() { [ -z "$prev" ] || setprop sys.boot_completed "$prev"; rm -f "$tmp"; }
trap restore EXIT
trap 'exit 1' HUP INT TERM
setprop sys.boot_completed 0 || exit 1
result=$(pm install -r "$tmp" 2>&1)
case "$result" in *Success*) ;; *) echo "$result"; exit 1;; esac
echo '무선 ADB 설치 완료.'
