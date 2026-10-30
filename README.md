# Magio TV → native MediaSession → Home Assistant

Show **what is actually playing on Magio TV** (channel, programme, position,
duration) in Home Assistant's TV card — the same way YouTube/MediaSession apps
do — without OCR, screenshots, or stream extraction.

Tested with **Magio TV 3.7.6** on a Sony BRAVIA KD-49XD7005 (Android 9,
armeabi-v7a) and an Android TV 12 arm64 emulator.

> **Status:** personal-use proof of concept. The patch is version-specific and
> must be rebased for each Magio release. Read [Legal](#legal) before using.

---

## How it works

```
Magio TV app (patched, re-signed)
 ├─ MainApplication.onCreate()
 │   ├─ WebView.setWebContentsDebuggingEnabled(true)   ← enables CDP
 │   └─ HermesSession.start(app)                        ← native MediaSession
 └─ HermesSession
     └─ BroadcastReceiver  ← "com.telekom.magiogo.HERMES_UPDATE"
         └─ android.media.session.MediaSession
             ├─ Metadata: title, artist (channel), duration
             └─ PlaybackState: position, playing

Host (this repo)
 └─ tools/magio_now_playing.py
     ├─ adb forward tcp:9222 → webview_devtools_remote_<pid>
     ├─ CDP Runtime.evaluate on the Magio WebView
     │   ├─ `.video-maxi-player` when the player screen is open
     │   └─ `.row-item.playing` when browsing EPG/home
     └─ adb shell am broadcast ... HERMES_UPDATE (ms)
```

Home Assistant's **Bravia/Sony** integration (`media_player.kd_49xd7005` in the
test setup) reads `dumpsys media_session` from the TV and shows the metadata.
HA's stock **Android TV** integration only exposes power/app/source, not media
metadata — use a MediaSession-reading integration for the card.

The watcher deliberately reads the app's **`.row-item.playing`** marker when the
EPG is open. The EPG's focused/hovered item is a different element
(`#detail-preview-title`, `:focus`) and must **not** be used; otherwise the card
follows the remote pointer instead of the actual playback.

---

## Requirements

- Magio TV **3.7.6** APK + its split APKs from your own device
- JDK 17 (`javac`, `keytool`)
- Android SDK build-tools (`d8`, `zipalign`, `apksigner`) and
  `platforms/android-XX/android.jar` (API 34 used here)
- [apktool](https://apktool.org/) 2.9.x jar
- `adb`
- Python 3.9+ with `websockets`

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

1. `apktool d -r` — keeps the original `resources.arsc` (rebuilding resources
   broke Media3 drawables in testing).
2. Inserts the WebView-debug + `HermesSession.start()` calls into
   `MainApplication.smali`.
3. Compiles `HermesSession.java` with `javac`, dexes it with `d8`, and adds it as
   `classes3.dex`.
4. Rebuilds with `apktool b -r`, zipaligns, signs the base and splits with the
   same key.

Output: `out/base.apk` + `out/magio_split_*.apk`.

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

## Run the watcher

```bash
python3 -m pip install -r tools/requirements.txt

python3 tools/magio_now_playing.py \
  --serial 192.168.1.151:5555 \
  --adb "$(command -v adb)"
```

It discovers the WebView socket, forwards it to `tcp:9222`, reads the app's own
state every 5 seconds, and broadcasts updates to the in-app MediaSession.

Keep it running (systemd, launchd, a small always-on host, or a cron-supervised
loop). Without the watcher the session keeps the last metadata but stops
advancing.

---

## Home Assistant

- Add the TV with the **Bravia/Sony** integration (or another integration that
  reads `dumpsys media_session`).
- The TV card should show `media_title`, `media_artist` (channel),
  `media_duration`, `media_position`.
- HA's stock **Android TV** integration is not enough for title/position.

---

## Patch details

`MainApplication.onCreate()` after `invoke-super`:

```smali
const/4 v0, 0x1
invoke-static {v0}, Landroid/webkit/WebView;->setWebContentsDebuggingEnabled(Z)V

move-object v1, p0
check-cast v1, Landroid/app/Application;
invoke-static {v1}, Lcom/telekom/tv/player/HermesSession;->start(Landroid/app/Application;)V
```

`HermesSession.java` creates a framework `MediaSession`, registers a dynamic
receiver for `com.telekom.magiogo.HERMES_UPDATE`, and updates metadata/state.
Extras: `title`, `channel` (string), `position`, `duration` (**long, ms**),
`playing` (boolean).

The app's player store (`localStorage['module-player']`) uses **milliseconds**
for `lastStreamContext.position`; the bridge extras are also milliseconds, but
the watcher's internal math is seconds. Keep the conversion in mind.

---

## Troubleshooting

| Symptom | Check |
|---|---|
| No `webview_devtools_remote` socket | Patch applied? `MainApplication.onCreate` calls `setWebContentsDebuggingEnabled(true)`. |
| CDP `HTTP 500` / connection refused | Stale `adb forward`; re-run watcher with `--port` or `adb forward --remove tcp:9222`. |
| Card shows the EPG-focused show | Use `.row-item.playing`, never `#detail-preview-title` on the EPG screen. |
| Archive timing absurd | `module-player.position` is ms; divide by 1000 before sending seconds, and send ms to the receiver. |
| No metadata in HA | The integration must read MediaSession; stock `androidtv` does not. |
| App crashes after patching | `classes3.dex` missing, or `HermesSession` inner classes not dexed (`d8` the whole class directory). |

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
