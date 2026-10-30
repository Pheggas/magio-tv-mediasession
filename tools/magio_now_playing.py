#!/usr/bin/env python3
"""Push Magio TV's now-playing state into its own MediaSession via CDP + adb.

The patched Magio app exposes its WebView over the Chrome DevTools protocol
(localhost, reachable through `adb forward`). This watcher reads the app's own
state, then broadcasts title/channel/position/duration to the patched
HermesSession receiver inside the app. Home Assistant's Bravia/Sony integration
(or any MediaSession reader) then shows it as the TV's media.

No OCR, no screenshots, no stream extraction.
"""
import argparse
import asyncio
import json
import re
import shlex
import subprocess
import time
import urllib.parse
import urllib.request
from datetime import datetime
from pathlib import Path

import websockets

PACKAGE = "com.telekom.magiogo"
ACTION = "com.telekom.magiogo.HERMES_UPDATE"

STATE_JS = r"""JSON.stringify((()=>{
  const q=s=>{const e=document.querySelector(s);return e?e.innerText.trim():null};
  const all=s=>Array.from(document.querySelectorAll(s)).map(e=>({t:e.innerText.trim(),s:e.getAttribute('style')}));
  const p=document.querySelector('.row-item.playing');
  let playing=null;
  if(p){
    const row=p.closest('.epg-row');
    const cid=row?row.getAttribute('data-channel-id'):null;
    const lbl=cid?document.getElementById('channel-title-label-'+cid):null;
    playing={title:(p.innerText||'').trim().split('\n')[0], channel:lbl?lbl.innerText.replace(/\.$/,''):null,
             channelId:cid, cls:p.className, contentId:p.id};
  }
  const pl=document.querySelector('.video-maxi-player');
  let player=null;
  if(pl){
    player={title:q('.video-maxi-player #detail-preview-title'), channel:q('.video-maxi-player .channel-name'),
            times:all('.seekbar-time'), progress:all('.seeker-progressbar .progress')};
  }
  return {hash:location.hash, playing, player, modulePlayer:localStorage.getItem('module-player')};
})())"""


def adb(args, serial, adb_bin):
    return subprocess.run([adb_bin, "-s", serial] + args, capture_output=True, text=True, timeout=20)


def forward(serial, adb_bin, port):
    pid = adb(["shell", "pidof " + PACKAGE], serial, adb_bin).stdout.strip()
    if not pid:
        raise RuntimeError("Magio TV is not running")
    sock = "webview_devtools_remote_" + pid
    adb(["forward", f"tcp:{port}", "localabstract:" + sock], serial, adb_bin)
    return sock


def hhmm(t):
    h, m = t.split(":")[:2]
    return (int(h) * 60 + int(m)) * 60000


def send(serial, adb_bin, title, channel, pos_s, dur_s):
    def q(s):
        return shlex.quote((s or "").replace('"', '\\"'))
    cmd = (f"am broadcast -p {PACKAGE} -a {ACTION} "
           f"--es title {q(title)} --es channel {q(channel)} "
           f"--el position {int(pos_s) * 1000} --el duration {int(dur_s) * 1000} --ez playing true")
    return adb(["shell", cmd], serial, adb_bin).stdout.strip().splitlines()[-1:]


async def read_state(port):
    pages = json.load(urllib.request.urlopen(f"http://127.0.0.1:{port}/json", timeout=5))
    page = pages[0]
    async with websockets.connect(page["webSocketDebuggerUrl"], max_size=20 * 1024 * 1024) as ws:
        await ws.send(json.dumps({"id": 1, "method": "Runtime.evaluate",
                                  "params": {"expression": STATE_JS, "returnByValue": True}}))
        while True:
            msg = json.loads(await ws.recv())
            if msg.get("id") == 1:
                return json.loads(msg["result"]["result"]["value"])


CACHE = {"title": None, "channel": None, "pos": 0, "dur": 0, "ts": time.time()}


def parse(s):
    hash_ = s.get("hash") or ""
    player = s.get("player")
    playing = s.get("playing")
    now = time.time()

    # Player page: use the actual player overlay. Live uses clock start/end;
    # archive uses the same clock labels but the progress bar is exact.
    if player and hash_.startswith("#/player"):
        title = player.get("title") or ""
        channel = player.get("channel") or ""
        times = [x["t"] for x in player.get("times", []) if re.match(r"^\d{1,2}:\d{2}$", x["t"])]
        pct = 0.0
        for p in player.get("progress", []):
            m = re.search(r"width:\s*([\d.]+)%", p.get("s") or "")
            if m:
                pct = float(m.group(1))
        dur = max(hhmm(times[1]) - hhmm(times[0]), 0) // 1000 if len(times) >= 2 else 0
        pos = int(dur * pct / 100.0)
        CACHE.update(title=title, channel=channel, pos=pos, dur=dur, ts=now)
        return title, channel, pos, dur

    # EPG/home: the `.playing` block is the real playback marker, not the
    # focused/hovered EPG item.
    if playing:
        title = playing.get("title") or ""
        channel = playing.get("channel") or ""
        if title == CACHE["title"] and channel == CACHE["channel"]:
            pos = CACHE["pos"] + int(now - CACHE["ts"])
            dur = CACHE["dur"]
        else:
            pos, dur = 0, 0
            try:
                mp = json.loads(s.get("modulePlayer") or "{}")
                ctx = mp.get("state", {}).get("video", {}).get("lastStreamContext", {})
                url = (ctx.get("streamURL") or {}).get("url") or ""
                qs = urllib.parse.parse_qs(urllib.parse.urlparse(url).query)
                if ctx.get("streamService") == "ARCHIVE" and "start" in qs and "end" in qs:
                    a = datetime.fromisoformat(qs["start"][0].replace("Z", "+00:00"))
                    b = datetime.fromisoformat(qs["end"][0].replace("Z", "+00:00"))
                    dur = max(int((b - a).total_seconds()), 0)
                    # module-player position is in milliseconds.
                    pos = int(ctx.get("position", 0) or 0) // 1000
            except Exception:
                pass
        CACHE.update(title=title, channel=channel, pos=pos, dur=dur, ts=now)
        return title, channel, pos, dur

    return None


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True, help="adb serial, e.g. 192.168.1.151:5555")
    ap.add_argument("--adb", default="adb", help="adb binary")
    ap.add_argument("--port", type=int, default=9222, help="local CDP port")
    ap.add_argument("--interval", type=float, default=5.0)
    args = ap.parse_args()

    sock = forward(args.serial, args.adb, args.port)
    print(f"forwarded tcp:{args.port} -> {sock}", flush=True)

    while True:
        try:
            state = await read_state(args.port)
            parsed = parse(state)
            if parsed:
                title, channel, pos, dur = parsed
                result = send(args.serial, args.adb, title, channel, pos, dur)
                print(f"{channel} | {title} | {pos//60}:{pos%60:02d} / {dur//60}:{dur%60:02d} {result}", flush=True)
        except Exception as exc:
            print("ERR", exc, flush=True)
        await asyncio.sleep(args.interval)


if __name__ == "__main__":
    asyncio.run(main())
