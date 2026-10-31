#!/usr/bin/env bash
# Build a V1 (media-only) patched Magio APK.
#
# FEATURES=media              V1: MediaSession only, no server (default)
# FEATURES=media+control      reserved for V2; not implemented in this plan
set -euo pipefail

BASE_APK=${1:?usage: build.sh base.apk out_dir split1.apk [split2.apk ...]}
OUT=${2:?usage: build.sh base.apk out_dir split1.apk [split2.apk ...]}
shift 2
SPLITS=("$@")
FEATURES=${FEATURES:-media}

: "${ANDROID_JAR:?set ANDROID_JAR to platforms/android-XX/android.jar}"
: "${BUILD_TOOLS:?set BUILD_TOOLS to the Android build-tools directory}"
: "${APKTOOL_JAR:?set APKTOOL_JAR to apktool.jar}"
KEYSTORE=${KEYSTORE:-$PWD/keystore.jks}
KS_PASS=${KS_PASS:-magio-patch}
KEY_ALIAS=${KEY_ALIAS:-magio}

HERE=$(cd "$(dirname "$0")" && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

mkdir -p "$OUT" "$WORK/classes" "$WORK/dex" \
  "$WORK/generated/com/telekom/tv/player"

if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -keystore "$KEYSTORE" -alias "$KEY_ALIAS" \
    -storepass "$KS_PASS" -keypass "$KS_PASS" -keyalg RSA -keysize 2048 \
    -validity 10000 -dname "CN=Magio TV Now Playing Patch" >/dev/null
  echo "generated $KEYSTORE"
fi

echo "[1/8] checking version"
VERSION=$("$BUILD_TOOLS/aapt" dump badging "$BASE_APK" \
  | grep -o "versionName='[^']*'" | head -1 | cut -d"'" -f2)
if [ "$VERSION" != "3.7.6" ]; then
  echo "unsupported Magio version: $VERSION (expected 3.7.6)" >&2
  exit 1
fi

echo "[2/8] decoding $BASE_APK"
java -jar "$APKTOOL_JAR" d -r -f "$BASE_APK" -o "$WORK/decoded" >/dev/null

echo "[3/8] patching MainApplication.smali"
python3 "$HERE/apply_patch.py" \
  "$WORK/decoded/smali_classes2/com/telekom/tv/player/MainApplication.smali"

echo "[4/8] embedding JavaScript"
python3 "$HERE/embed_js.py" \
  "$HERE/js/hermes_state.js" \
  "$WORK/generated/com/telekom/tv/player/HermesJs.java"

echo "[5/8] compiling"
SOURCES=$(find "$HERE/java" -name '*.java' -print)
SOURCES="$SOURCES $WORK/generated/com/telekom/tv/player/HermesJs.java"
if [ "$FEATURES" = "media+control" ]; then
  if [ ! -d "$HERE/control/java" ]; then
    echo "FEATURES=media+control is reserved for V2 and not implemented yet" >&2
    exit 1
  fi
  SOURCES="$SOURCES $(find "$HERE/control/java" -name '*.java' -print)"
  python3 "$HERE/apply_control_patch.py" \
    "$WORK/decoded/smali_classes2/com/telekom/tv/player/MainApplication.smali"
fi
# shellcheck disable=SC2086
javac -source 8 -target 8 -bootclasspath "$ANDROID_JAR" \
  -d "$WORK/classes" $SOURCES
CLASSES=$(find "$WORK/classes" -name '*.class' | tr '\n' ' ')
# shellcheck disable=SC2086
"$BUILD_TOOLS/d8" --lib "$ANDROID_JAR" --min-api 24 \
  --output "$WORK/dex" $CLASSES

echo "[6/8] rebuilding resources"
java -jar "$APKTOOL_JAR" b -r "$WORK/decoded" -o "$WORK/base-unsigned.apk" >/dev/null

echo "[7/8] adding classes3.dex"
python3 "$HERE/add_dex.py" "$WORK/base-unsigned.apk" \
  "$WORK/base-dex.apk" "$WORK/dex/classes.dex"

echo "[8/8] aligning and signing"
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
