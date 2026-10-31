#!/usr/bin/env bash
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT

javac -d "$OUT" \
  "$HERE/java/com/telekom/tv/player/NowPlayingState.java" \
  "$HERE/java/com/telekom/tv/player/NowPlayingCodec.java" \
  "$HERE/java/com/telekom/tv/player/NowPlayingStore.java" \
  "$HERE/../tests/java/com/telekom/tv/player/NowPlayingCodecTest.java" \
  "$HERE/../tests/java/com/telekom/tv/player/NowPlayingStoreTest.java"

java -cp "$OUT" com.telekom.tv.player.NowPlayingCodecTest
java -cp "$OUT" com.telekom.tv.player.NowPlayingStoreTest

if command -v node >/dev/null 2>&1; then
  node "$HERE/js/hermes_state.test.js"
fi
