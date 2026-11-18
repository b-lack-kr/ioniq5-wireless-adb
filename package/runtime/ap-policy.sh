#!/system/bin/sh
# Return success if CarPlay may still own or soon reclaim the shared adapter.
# An absent mod is safe. A disabled mod is safe only after the supervisor's
# published status confirms that its AP service is no longer active.
carplay_claims_radio() {
    local root="${1:-}"
    local mod="$root/data/hmgapi/mods/neev-usb-carplay"
    local status="$root/data/hmgapi/state/services.status"
    [ ! -e "$mod" ] && [ ! -L "$mod" ] && return 1
    [ -L "$mod" ] && return 0
    [ -e "$mod/.disabled" ] || return 0
    [ ! -L "$mod/.disabled" ] || return 0
    [ -f "$status" ] && [ ! -L "$status" ] && [ -r "$status" ] || return 0
    local id name state pid rest
    while read -r id name state pid rest; do
        [ "$id" = neev-usb-carplay ] && [ "$name" = usb-ap ] || continue
        case "$state" in running|waiting|'') return 0;; stopped|exited|failed) ;; *) return 0;; esac
    done < "$status"
    return 1
}
