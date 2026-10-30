#!/usr/bin/env bash
# Build a patched Magio TV APK (base + split APKs) with a native MediaSession.
#
# Requirements:
#   - JDK 17 (javac, keytool)
#   - Android SDK build-tools (d8, zipalign, apksigner) and platforms/android-XX/android.jar
#   - apktool 2.9.x jar
#
# Environment:
#   ANDROID_JAR   path to android.jar (e.g. $ANDROID_HOME/platforms/android-34/android.jar)
#   BUILD_TOOLS   path to build-tools (e.g. $ANDROID_HOME/build-tools/34.0.0)
#   APKTOOL_JAR   path to apktool.jar
#   KEYSTORE      path to a keystore (created if missing)
#   KS_PASS       keystore/key password (default: magio-patch)
#   KEY_ALIAS     key alias (default: magio)
#
# Usage:
#   ./build.sh base.apk out_dir split1.apk [split2.apk ...]
#
# The base and all splits must come from the same installed app and the same
# Magio version. The script re-signs the splits with the same key as the base.
set -euo pipefail

BASE_APK=${1:?usage: build.sh base.apk out_dir split1.apk [split2.apk ...]}
OUT=${2:?usage: build.sh base.apk out_dir split1.apk [split2.apk ...]}
shift 2
SPLITS=("$@")

: "${ANDROID_JAR:?set ANDROID_JAR to platforms/android-XX/android.jar}"
: "${BUILD_TOOLS:?set BUILD_TOOLS to the Android build-tools directory}"
: "${APKTOOL_JAR:?set APKTOOL_JAR to apktool.jar}"
KEYSTORE=${KEYSTORE:-$PWD/keystore.jks}
KS_PASS=${KS_PASS:-magio-patch}
KEY_ALIAS=${KEY_ALIAS:-magio}

HERE=$(cd "$(dirname "$0")" && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

mkdir -p "$OUT" "$WORK/classes" "$WORK/dex"

if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -keystore "$KEYSTORE" -alias "$KEY_ALIAS" \
    -storepass "$KS_PASS" -keypass "$KS_PASS" -keyalg RSA -keysize 2048 \
    -validity 10000 -dname "CN=Magio TV Now Playing Patch" >/dev/null
  echo "generated $KEYSTORE"
fi

echo "[1/6] decoding $BASE_APK"
java -jar "$APKTOOL_JAR" d -r -f "$BASE_APK" -o "$WORK/decoded" >/dev/null

echo "[2/6] patching MainApplication.smali"
python3 "$HERE/apply_patch.py" \
  "$WORK/decoded/smali_classes2/com/telekom/tv/player/MainApplication.smali"

echo "[3/6] compiling HermesSession"
javac -source 8 -target 8 -bootclasspath "$ANDROID_JAR" \
  -d "$WORK/classes" "$HERE/HermesSession.java"
CLASSES=$(find "$WORK/classes" -name '*.class' | tr '\n' ' ')
"$BUILD_TOOLS/d8" --lib "$ANDROID_JAR" --min-api 24 --output "$WORK/dex" $CLASSES

echo "[4/6] rebuilding resources (original resources.arsc preserved)"
java -jar "$APKTOOL_JAR" b -r "$WORK/decoded" -o "$WORK/base-unsigned.apk" >/dev/null

echo "[5/6] adding classes3.dex"
python3 "$HERE/add_dex.py" "$WORK/base-unsigned.apk" "$WORK/base-dex.apk" "$WORK/dex/classes.dex"

echo "[6/6] aligning and signing"
"$BUILD_TOOLS/zipalign" -f -p 4 "$WORK/base-dex.apk" "$WORK/base-aligned.apk"
"$BUILD_TOOLS/apksigner" sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" \
  --key-pass "pass:$KS_PASS" --out "$OUT/base.apk" "$WORK/base-aligned.apk"

for split in "${SPLITS[@]}"; do
  name=$(basename "$split")
  "$BUILD_TOOLS/apksigner" sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" \
    --key-pass "pass:$KS_PASS" --out "$OUT/$name" "$split"
  echo "signed split $OUT/$name"
done

echo
echo "Built:"
ls -lh "$OUT/base.apk" "$OUT"/*.apk
echo
echo "Install with:"
echo "  adb install-multiple -r $OUT/base.apk $OUT/<split>.apk ..."
