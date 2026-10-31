#!/usr/bin/env python3
"""Insert HermesSession.start() into Magio's MainApplication.smali.

This intentionally does NOT enable WebView debugging. V1 reads its own
WebView in-process; there is no CDP socket and no host watcher.

Usage: apply_patch.py <path/to/MainApplication.smali>
"""
import pathlib
import sys

MARKER = "invoke-super {p0}, Landroid/app/Application;->onCreate()V"

INJECT = """
    move-object v1, p0

    check-cast v1, Landroid/app/Application;

    invoke-static {v1}, Lcom/telekom/tv/player/HermesSession;->start(Landroid/app/Application;)V
"""


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__, file=sys.stderr)
        return 2
    path = pathlib.Path(sys.argv[1])
    text = path.read_text(encoding="utf-8")
    if "HermesSession" in text:
        print("already patched")
        return 0
    if MARKER not in text:
        print(f"marker not found in {path}", file=sys.stderr)
        return 1
    if ".locals 2" not in text:
        print(
            "expected .locals >= 2 in onCreate; adjust the register plan before patching",
            file=sys.stderr,
        )
        return 1
    text = text.replace(MARKER, MARKER + INJECT, 1)
    path.write_text(text, encoding="utf-8")
    print(f"patched {path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
