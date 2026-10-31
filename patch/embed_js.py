#!/usr/bin/env python3
"""Generate HermesJs.java from patch/js/hermes_state.js."""
import json
import pathlib
import sys


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__, file=sys.stderr)
        return 2
    src = pathlib.Path(sys.argv[1])
    dst = pathlib.Path(sys.argv[2])
    js = src.read_text(encoding="utf-8")
    dst.parent.mkdir(parents=True, exist_ok=True)
    dst.write_text(
        "package com.telekom.tv.player;\n\n"
        "public final class HermesJs {\n"
        "    private HermesJs() {\n"
        "    }\n\n"
        "    public static final String INJECT = " + json.dumps(js) + ";\n"
        "}\n",
        encoding="utf-8",
    )
    print(f"embedded {src} -> {dst} ({len(js)} chars)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
