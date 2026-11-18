#!/bin/sh
# Build the manually launched Android 4.4 control overlay. No APK is installed.
# Usage: sh build-ui.sh /absolute/output/adb-control.jar
set -eu
src=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
project=$(CDPATH= cd -- "$src/../../../../.." && pwd)
tools_dir="$project/.tools"
jdk_bin=${NEEV_JDK_BIN:-$tools_dir/java-build/jdk-17.0.21+9/Contents/Home/bin}
android_jar=${NEEV_ANDROID_JAR:-$tools_dir/android34/android.jar}
r8_jar=${NEEV_R8_JAR:-$tools_dir/java-build/r8.jar}
output=${1:?Usage: build-ui.sh OUTPUT_JAR}
[ "$#" -eq 1 ] && [ ! -d "$output" ]
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/classes"
"$jdk_bin/javac" --release 7 -Xlint:-options -encoding UTF-8 -classpath "$android_jar" \
    -d "$work/classes" "$src/AdbControl.java" "$src/WifiInfo.java" "$src/WifiQr.java" "$src/WifiPanelLayout.java" "$src"/vendor/*.java
"$jdk_bin/jar" cf "$work/classes.jar" -C "$work/classes" .
"$jdk_bin/java" -cp "$r8_jar" com.android.tools.r8.D8 --min-api 19 --lib "$android_jar" \
    --output "$work/adb-control.jar" "$work/classes.jar"
cp "$work/adb-control.jar" "$output"
printf 'Built %s\n' "$output"
