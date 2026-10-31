# Magio TV → native MediaSession → Home Assistant

Show **what is actually playing on Magio TV** (channel, programme, position,
duration) in Home Assistant's TV card — the same way YouTube/MediaSession apps
do — without OCR, screenshots, stream extraction, a host watcher, or WebView
debugging.

Tested with **Magio TV 3.7.6** on a Sony BRAVIA KD-49XD7005 (Android 9,
armeabi-v7a).

> **Status:** personal-use proof of concept. The patch is version-specific and
> must be rebased for each Magio release. Read [Legal](#legal) before using.

---

## What this is

V1 is **observation only**:

- The patched Magio app publishes a standard Android `MediaSession`.
- Home Assistant reads it through an existing consumer — no Magio-specific HA
  integration, no custom add-on, no MQTT requirement.
- The app updates the session itself from its own WebView; there is no host
  process, no ADB forwarding, and no debugging socket.

V2 (channel list, EPG, channel switching, ad-skip) is a separate optional
control plane and is **not implemented here**. See [V2 boundary](#v2-boundary).

---

## How it works

```
Magio TV app (patched, re-signed)
 └─ MainApplication.onCreate()
     └─ HermesSession.start(app)
         ├─ android.media.session.MediaSession "MagioNowPlaying"
         │   ├─ Metadata: title, artist (channel), duration
         │   └─ PlaybackState: position, playing/paused
         └─ HermesWebViewSource
             ├─ ActivityLifecycleCallbacks → StbPlayerActivity
             ├─ HermesWebView → webContent WebView
             │   ├─ injects a guarded JS observer
             │   └─ HermesNative.postState(...)
             └─ NowPlayingStore → MediaSession
```

The JS observer reads the app's own player overlay and the `.row-item.playing`
marker. The EPG's focused/hovered item (`#detail-preview-title` outside the
player) is never used; otherwise the card would follow the remote pointer
instead of actual playback.

No host-side script is required. `HermesSession` creates the session when the
application starts, and the WebView bridge keeps it current.

---

## Home Assistant consumers

Pick one; they all read the same MediaSession.

| Setup | Consumer | What you install |
|---|---|---|
| Sony Bravia | `braviatv` | Nothing extra in HA; the patch is enough |
| MQTT user | `MediaSession2MQTT` | Its Android TV app + your MQTT broker; HA MQTT Discovery creates the entity |
| No Sony, no MQTT | `hacs-adb-media-session` | One HACS integration; polls ADB, read-only |

Notes:

- Sony's `braviatv` reads the TV's playing-content API. It showed the patched
  session on the tested KD-49XD7005, but Sony behaviour can vary by model.
- `MediaSession2MQTT` uses Android's notification-listener API and supports
  Home Assistant MQTT Discovery. It publishes title, duration, position and
  application ID; it does **not** publish artist/channel, so the channel name
  may be absent in that path.
- `hacs-adb-media-session` parses `dumpsys media_session` and exposes a
  read-only media player. It is the fallback when neither Sony nor MQTT fits.
- HA's stock **Android TV** integration exposes power/app/source, not media
  metadata. Do not use it for the TV card.

---

## Requirements

- Magio TV **3.7.6** APK + its split APKs from your own device
- JDK 17 (`javac`, `keytool`)
- Android SDK build-tools (`d8`, `zipalign`, `apksigner`) and
  `platforms/android-XX/android.jar` (API 34 used here)
- [apktool](https://apktool.org/) 2.9.x jar
- `adb`
- No Python runtime is required. The old host watcher has been removed.

No root. No Frida. The patched app is re-signed, so Android requires an
uninstall/reinstall and a fresh Magio login/device activation.

---

## Build

Pull the installed base and splits from your own TV:

```bash
adb shell pm path com.telekom.magiogo
# package:/data/app/.../base.apk
# package:/data/app/.../split_config.cs.apk
# ...

adb pull /data/app/.../base.apk magio_base.apk
adb pull /data/app/.../split_config.cs.apk magio_split_cs.apk
adb pull /data/app/.../split_config.sk.apk magio_split_sk.apk
adb pull /data/app/.../split_config.xhdpi.apk magio_split_xhdpi.apk
```

Build the patched base and re-signed splits:

```bash
export ANDROID_JAR=$ANDROID_HOME/platforms/android-34/android.jar
export BUILD_TOOLS=$ANDROID_HOME/build-tools/34.0.0
export APKTOOL_JAR=/path/to/apktool_2.9.3.jar
export KEYSTORE=$PWD/keystore.jks
export KS_PASS='choose-a-password'

./patch/build.sh magio_base.apk out \
  magio_split_cs.apk magio_split_sk.apk magio_split_xhdpi.apk
```

The build:

1. Checks `versionName == 3.7.6`; fails loudly on a different release.
2. `apktool d -r` — keeps the original `resources.arsc` (rebuilding resources
   broke Media3 drawables in testing).
3. Inserts only `HermesSession.start()` into `MainApplication.smali`. It does
   **not** call `setWebContentsDebuggingEnabled`.
4. Generates `HermesJs.java` from `patch/js/hermes_state.js`.
5. Compiles all `patch/java` sources with `javac`, dexes them with `d8`, and
   adds them as `classes3.dex`.
6. Rebuilds with `apktool b -r`, zipaligns, signs the base and splits with the
   same key.

Output: `out/base.apk` + `out/magio_split_*.apk`.

### Tests

```bash
./patch/test.sh
```

Expected:

```
OK
OK
```

The tests cover the wire format, malformed payloads, position/duration
clamping, store de-duplication, and pause position extrapolation.

---

## Install

```bash
adb uninstall com.telekom.magiogo          # signature changed
adb install-multiple -r out/base.apk out/magio_split_*.apk
adb shell am start -n com.telekom.magiogo/com.telekom.tv.player.StbPlayerActivity
```

Complete the on-screen browser activation (the TV shows a URL and a code).
The account's device limit may require unpairing an old device first.

---

## Validate

With Magio playing a live channel:

```bash
adb shell dumpsys media_session | sed -n '/MagioNowPlaying/,+12p'
```

Expected: one `MagioNowPlaying` session with a title, channel artist, and
`state=PlaybackState {state=3, ...}` while playing.

No debugging socket should exist:

```bash
adb shell cat /proc/net/unix | grep webview_devtools_remote || echo NO_DEBUG_SOCKET
```

Expected: `NO_DEBUG_SOCKET`.

No host watcher should be running:

```bash
pgrep -af 'magio_now_playing|magio_tv_watch|magio_tv_native' || echo NO_HOST_WATCHER
```

Expected: `NO_HOST_WATCHER`.

Scrolling the EPG must not change the session; the playing programme must stay
in `title=`. Playing an archive must show the archive duration/position.

---

## V2 boundary

V2 is deliberately separate. It will add:

- channel list and EPG;
- channel switching;
- later, ad-skip experiments.

That requires a Magio-specific control plane (local API/WebSocket or an
equivalent). V1 must not grow a server or an open port. The only seam V1
provides is:

- `HermesSession.store()` returning the synchronized `NowPlayingStore`;
- `NowPlayingStore.Listener` for state changes;
- a reserved `FEATURES=media+control` build profile.

See `patch/control/README.md`. Do not make V2 modify the V1 state model or
MediaSession publisher.

---

## Troubleshooting

| Symptom | Check |
|---|---|
| No `MagioNowPlaying` session | Is the APK patched? `MainApplication.onCreate` must call `HermesSession.start`. |
| Session exists but title is `Magio TV` | The WebView id is wrong or the JS observer has not installed. Check `webContent` in `StbPlayerActivity`. |
| Card shows the EPG-focused show | Use `.row-item.playing`; never use `#detail-preview-title` outside the player. |
| Archive duration/position wrong | `module-player` position is milliseconds; archive start/end come from the stream URL query. |
| No metadata in HA | The integration must read MediaSession; stock `androidtv` does not. |
| App crashes after patching | `classes3.dex` missing, or inner classes not dexed; `d8` the whole class directory. |

---

## Legal

This project is **not affiliated with, endorsed by, or supported by Slovak
Telekom / Magio**. It modifies a legally installed app for personal automation
and accessibility on a device you own, using your own subscription.

It does **not** decrypt, redistribute, or bypass DRM, and it does **not**
include any Telekom APK, decompiled Telekom source, stream URLs, tokens, or
keys. You must obtain the APK from your own device. Re-signing an app can
violate the provider's terms of service and may stop working after an update.
Use at your own risk; no warranty.

The code in this repository is MIT-licensed. See [LICENSE](LICENSE) and
[NOTICE.md](NOTICE.md).
