#!/system/bin/sh
/system/bin/sh "$PWD/bin/control.sh" remove || exit 1
installed=$(pm path kr.blackr.wirelessadb 2>/dev/null)
case "$installed" in package:*) result=$(pm uninstall kr.blackr.wirelessadb 2>&1); case "$result" in *Success*) ;; *) echo "$result"; exit 1;; esac;; esac
exit 0
