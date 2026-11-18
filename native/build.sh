#!/bin/sh
# Build the API 19 ARMv7 PIE, using the HMG-API 1.0.1 AOSP + KitKat compatibility files.
set -eu
src=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
hmg=${1:?usage: build.sh HMG-API-1.0.1-directory NDK-r25c-directory output}
ndk=${2:?}; output=${3:?}
case "$(uname -s)" in
    Darwin) host=darwin-x86_64 ;;
    Linux) host=linux-x86_64 ;;
    *) echo 'Unsupported NDK host' >&2; exit 1 ;;
esac
tc="$ndk/toolchains/llvm/prebuilt/$host/bin"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
cp -R "$hmg/third_party/aosp-4.4.4" "$tmp/aosp"
patch -s -d "$tmp/aosp" -p1 < "$src/session.patch"
# auth.c replaces the framework pairing handler with a pinned-key verifier.
rm "$tmp/aosp/adb/adb_auth_client.c"
"$tc/armv7a-linux-androideabi19-clang" -O2 -fPIE -pie -ffunction-sections -fdata-sections \
    "-ffile-prefix-map=$tmp/aosp=aosp-4.4.4" "-ffile-prefix-map=$src=neev-adb/installed/v1.0.2/native" \
    -Wl,--gc-sections,--hash-style=sysv,--build-id=none -Wno-implicit-function-declaration \
    -DHAVE_ANDROID_OS -DADB_HOST=0 -D_GNU_SOURCE -DHAVE_SYS_UIO_H -DHAVE_PTHREADS \
    -I"$hmg/third_party/hmgapi-compat" -I"$tmp/aosp/include" -I"$tmp/aosp/adb" -I"$src" \
    "$tmp/aosp"/adb/*.c "$tmp/aosp"/libcutils/*.c "$tmp/aosp"/libmincrypt/*.c \
    "$src/auth.c" "$src/session.c" "$src/events.c" "$src/client.c" -o "$tmp/neev-adbd" -llog
"$tc/llvm-strip" "$tmp/neev-adbd"
cp "$tmp/neev-adbd" "$output"
chmod 0755 "$output"
